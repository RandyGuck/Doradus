/*
 * Copyright (C) 2014 Dell, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dell.doradus.service.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.dell.doradus.common.ApplicationDefinition;
import com.dell.doradus.common.CommonDefs;
import com.dell.doradus.common.ContentType;
import com.dell.doradus.common.UNode;
import com.dell.doradus.common.Utils;
import com.dell.doradus.core.DoradusServer;
import com.dell.doradus.service.Service;
import com.dell.doradus.service.StorageService;
import com.dell.doradus.service.db.DBService;
import com.dell.doradus.service.db.DBTransaction;
import com.dell.doradus.service.db.DColumn;
import com.dell.doradus.service.db.DRow;
import com.dell.doradus.service.rest.RESTCallback;
import com.dell.doradus.service.rest.RESTService;
import com.dell.doradus.service.taskmanager.TaskManagerService;

/**
 * Provides common schema services for the Doradus server. The SchemaService parses new
 * and modified application schemas, add them to the Applications table, and notify the
 * appropriate storage service of the change.
 */
public class SchemaService extends Service {
    // Application ColumnFamily name:
    public static final String APPS_STORE_NAME = "Applications";
    
    // Application definition row column names:
    private static final String COLNAME_APP_SCHEMA = "_application";
    private static final String COLNAME_APP_SCHEMA_FORMAT = "_format";
    private static final String COLNAME_APP_SCHEMA_VERSION = "_version";

    // Singleton instance:
    private static final SchemaService INSTANCE = new SchemaService();
    
    // Current format version with which we store schema definitions:
    private static final int CURRENT_SCHEMA_LEVEL = 2;

    // REST commands supported by the SchemaService:
    private static final List<Class<? extends RESTCallback>> CMD_CLASSES = Arrays.asList(
        ListApplicationsCmd.class,
        ListApplicationCmd.class,
        DefineApplicationCmd.class,
        ModifyApplicationCmd.class,
        DeleteApplicationCmd.class
    );
    
    //----- Service methods
    
    /**
     * Get the singleton instance of the StorageService. The object may or may not have
     * been initialized yet.
     * 
     * @return  The singleton instance of the StorageService.
     */ 
    public static SchemaService instance() {
        return INSTANCE;
    }

    // Called once before startService. 
    @Override
    public void initService() {
        RESTService.instance().registerCommands(CMD_CLASSES);
    }

    // Wait for the DB service to be up and check application schemas.
    @Override
    public void startService() {
        DBService.instance().waitForFullService();
        DBService.instance().createStoreIfAbsent(APPS_STORE_NAME, false);
    }

    // Currently, we have nothing special to do to "stop".
    @Override
    public void stopService() {
    }

    //----- Public SchemaService methods

    /**
     * Create the application with the given name. If the given application
     * already exists, the request is treated as an application update. If the
     * update is successfully validated, its schema is stored in the database,
     * and the appropriate storage service is notified to implement required
     * physical database changes, if any.
     * 
     * @param appDef
     *            {@link ApplicationDefinition} of application to create or
     *            update.
     */
    public void defineApplication(ApplicationDefinition appDef) {
        checkServiceState();
        ApplicationDefinition currAppDef = findExistingApplication(appDef);
        StorageService storageService = verifyStorageServiceOption(currAppDef, appDef);
        storageService.validateSchema(appDef);
        initializeApplication(currAppDef, appDef);
    }

    /**
     * Return the {@link ApplicationDefinition} for all applications.
     * 
     * @return          A collection of application definitions. 
     */
    public Collection<ApplicationDefinition> getAllApplications() {
        checkServiceState();
        return findAllApplications();
    }
    
    /**
     * Return the {@link ApplicationDefinition} for the application. Null is returned if
     * no application is found with the given name.
     * 
     * @return The {@link ApplicationDefinition} for the given application or null if no
     *         no such application is defined.
     */
    public ApplicationDefinition getApplication(String appName) {
        checkServiceState();
        return getApplicationDefinition(appName);
    }

    /**
     * Examine the given application's StorageService option and return the corresponding
     * {@link StorageService}. An error is thrown if the storage service is unknown or has
     * not been initialized.
     * 
     * @param   appDef  {@link ApplicationDefinition} of an application.
     * @return          The application's assigned {@link StorageService}.
     */
    public StorageService getStorageService(ApplicationDefinition appDef) {
        checkServiceState();
        String ssName = getStorageServiceOption(appDef);
        StorageService storageService = DoradusServer.instance().findStorageService(ssName);
        Utils.require(storageService != null, "StorageService is unknown or hasn't been initialized: " + ssName);
        return storageService;
    }

    /**
     * Get the given application's StorageService option. If none is found, assign and
     * return the default. Unlike {@link #getStorageService(ApplicationDefinition)}, this
     * method will not throw an exception if the storage service is unknown or has not
     * been initialized.
     * 
     * @param   appDef  {@link ApplicationDefinition} of an application.
     * @return          The application's declared or assigned StorageService option.
     */
    public String getStorageServiceOption(ApplicationDefinition appDef) {
        String ssName = appDef.getOption(CommonDefs.OPT_STORAGE_SERVICE);
        if (Utils.isEmpty(ssName)) {
            ssName = DoradusServer.instance().getDefaultStorageService();
            appDef.setOption(CommonDefs.OPT_STORAGE_SERVICE, ssName);
        }
        return ssName;
    }

    /**
     * Delete the given application, including all of its data. If the given application
     * doesn't exist, the call is a no-op.
     * 
     * @param appName   Name of application to delete.
     */
    public void deleteApplication(String appName) {
        checkServiceState();
        ApplicationDefinition appDef = getApplication(appName);
        if (appDef == null) {
            return; 
        }
        deleteApplication(appDef);
    }
    
    /**
     * Delete the application with the given definition, including all of its data. If the
     * given application doesn't exist, the call is a no-op.
     * 
     * @param appDef    {@link ApplicationDefinition} of application to delete.
     */
    public void deleteApplication(ApplicationDefinition appDef) {
        checkServiceState();
        
        // Delete storage service-specific data first.
        m_logger.info("Deleting application: {}", appDef.getAppName());
        StorageService storageService = getStorageService(appDef);
        storageService.deleteApplication(appDef);
        TaskManagerService.instance().deleteApplicationTasks(appDef);
        deleteAppProperties(appDef);
    }
    
    //----- Private methods

    // Singleton construction only
    private SchemaService() {}

    // Delete the given application's schema row from the Applications CF.
    private void deleteAppProperties(ApplicationDefinition appDef) {
        DBTransaction dbTran = DBService.instance().startTransaction();
        dbTran.deleteRow(SchemaService.APPS_STORE_NAME, appDef.getAppName());
        DBService.instance().commit(dbTran);
    }
    
    // Initialize storage and store the given schema for the given new or updated application.
    private void initializeApplication(ApplicationDefinition currAppDef, ApplicationDefinition appDef) {
        getStorageService(appDef).initializeApplication(currAppDef, appDef);
        storeApplicationSchema(appDef);
    }

    // Store the application row with schema, version, and format.
    private void storeApplicationSchema(ApplicationDefinition appDef) {
        String appName = appDef.getAppName();
        DBTransaction dbTran = DBService.instance().startTransaction();
        dbTran.addColumn(SchemaService.APPS_STORE_NAME, appName, COLNAME_APP_SCHEMA, appDef.toDoc().toJSON());
        dbTran.addColumn(SchemaService.APPS_STORE_NAME, appName, COLNAME_APP_SCHEMA_FORMAT, ContentType.APPLICATION_JSON.toString());
        dbTran.addColumn(SchemaService.APPS_STORE_NAME, appName, COLNAME_APP_SCHEMA_VERSION, Integer.toString(CURRENT_SCHEMA_LEVEL));
        DBService.instance().commit(dbTran);
    }
    
    // See if the same application name exists and return it's definition. 
    private ApplicationDefinition findExistingApplication(ApplicationDefinition appDef) {
        ApplicationDefinition currAppDef = getApplication(appDef.getAppName());
        if (currAppDef == null) {
            m_logger.info("Defining application: {}", appDef.getAppName());
        } else {
            m_logger.info("Updating application: {}", appDef.getAppName());
        }
        return currAppDef;
    }
    
    // Verify the given application's StorageService option and, if this is a schema
    // change, ensure it hasn't changed.  Return the application's StorageService object.
    private StorageService verifyStorageServiceOption(ApplicationDefinition currAppDef, ApplicationDefinition appDef) {
        // Verify or assign StorageService
        String ssName = getStorageServiceOption(appDef);
        StorageService storageService = getStorageService(appDef);
        Utils.require(storageService != null, "StorageService is unknown or hasn't been initialized: %s", ssName);
        
        // Currently, StorageService can't be changed.
        if (currAppDef != null) {
            String currSSName = getStorageServiceOption(currAppDef);
            Utils.require(currSSName.equals(ssName), "'StorageService' cannot be changed for application: %s", appDef.getAppName());
        }
        return storageService;
    }

    private Map<String, String> getColumnMap(Iterator<DColumn> colIter) {
        Map<String, String> colMap = new HashMap<>();
        while (colIter.hasNext()) {
            DColumn col = colIter.next();
            colMap.put(col.getName(), col.getValue());
        }
        return colMap;
    }
    
    // Parse the application schema from the given application row.
    private ApplicationDefinition loadAppRow(Map<String, String> colMap) {
        ApplicationDefinition appDef = new ApplicationDefinition();
        String appSchema = colMap.get(COLNAME_APP_SCHEMA);
        if (appSchema == null) {
            return null;    // Not a real application definition row
        }
        String format = colMap.get(COLNAME_APP_SCHEMA_FORMAT);
        ContentType contentType = Utils.isEmpty(format) ? ContentType.TEXT_XML : new ContentType(format);
        String versionStr = colMap.get(COLNAME_APP_SCHEMA_VERSION);
        int schemaVersion = Utils.isEmpty(versionStr) ? CURRENT_SCHEMA_LEVEL : Integer.parseInt(versionStr);
        if (schemaVersion > CURRENT_SCHEMA_LEVEL) {
            m_logger.warn("Skipping schema with advanced version: {}", schemaVersion);
            return null;
        }
        try {
            appDef.parse(UNode.parse(appSchema, contentType));
        } catch (Exception e) {
            m_logger.warn("Error parsing schema for application '" + appDef.getAppName() + "'; skipped", e);
            return null;
        }
        return appDef;
    }

    // Get the given application's application.
    private ApplicationDefinition getApplicationDefinition(String appName) {
        Iterator<DColumn> colIter =
            DBService.instance().getAllColumns(SchemaService.APPS_STORE_NAME, appName).iterator();
        if (!colIter.hasNext()) {
            return null;
        }
        return loadAppRow(getColumnMap(colIter));
    }

    // Get all application definitions.
    private Collection<ApplicationDefinition> findAllApplications() {
        List<ApplicationDefinition> result = new ArrayList<>();
        Iterator<DRow> rowIter =
            DBService.instance().getAllRows(SchemaService.APPS_STORE_NAME).iterator();
        while (rowIter.hasNext()) {
            DRow row = rowIter.next();
            ApplicationDefinition appDef = loadAppRow(getColumnMap(row.getAllColumns(1024).iterator()));
            if (appDef != null) {
                result.add(appDef);
            }
        }
        return result;
    }

}   // class SchemaService
