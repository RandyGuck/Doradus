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

package com.dell.doradus.service.db;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.dell.doradus.common.Utils;
import com.dell.doradus.core.ServerParams;
import com.dell.doradus.service.Service;
import com.dell.doradus.service.schema.SchemaService;
import com.dell.doradus.service.taskmanager.TaskManagerService;

/**
 * Provides methods that access a persistence instance (i.e., database). Each subclass
 * implements a connection to a specific kind of database.
 */
public abstract class DBService extends Service {
    private static final DBService INSTANCE;
    static {
        String dbServiceName = ServerParams.instance().getModuleParamString("DBService", "dbservice");
        if (Utils.isEmpty(dbServiceName)) {
            throw new RuntimeException("'DBService.dbservice' parameter is not defined.");
        }
        try {
            // Find and call the constructor DBService().
            @SuppressWarnings("unchecked")
            Class<DBService> serviceClass = (Class<DBService>) Class.forName(dbServiceName);
            Constructor<DBService> constructor = serviceClass.getConstructor();
            INSTANCE = constructor.newInstance();
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Cannot load specified 'dbservice': " + dbServiceName, e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Could not load dbservice class '" + dbServiceName + "'", e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Required constructor missing for dbservice class: " + dbServiceName, e);
        } catch (SecurityException | InstantiationException | IllegalAccessException e) {
            throw new RuntimeException("Could not invoke constructor for dbservice class: " + dbServiceName, e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Could not invoke constructor for dbservice class: " + dbServiceName, e);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize DBService: " + dbServiceName, e);
        }
    }
    
    private final int db_connect_retry_wait_millis;
    
    /**
     * Create a new DBService object. The constructor does little since the actual DB
     * connection, if needed, is not made until {@link #initService()} is called.
     */
    protected DBService() {
        m_startDelayMillis = 1000;
        db_connect_retry_wait_millis =
            ServerParams.instance().getModuleParamInt("DBService", "db_connect_retry_wait_millis", 5000);
    }
    
    /**
     * Get the singleton {@link DBService} instance.
     * 
     * @return  The default DBService object.
     */
    public static DBService instance() {
        return INSTANCE;
    }

    //----- Public Service methods

    /**
     * This method is "final" in DBService and unneeded in subclasses.
     */
    protected final void initService() {}
    
    /**
     * This method is "final" in DBService and unneeded in subclasses.
     */
    protected final void startService() {
        m_logger.info("Starting DBService");

        boolean bDBOpened = false;
        while (!bDBOpened) {
            try {
                INSTANCE.start();
                INSTANCE.createNamespace();
                bDBOpened = true;
            } catch (DBNotAvailableException e) {
                // Fall through to retry.
            } catch (Throwable e) {
                throw new RuntimeException("Cannot start DBService", e);
            }
            if (!bDBOpened) {
                m_logger.info("Database is not reachable. Waiting to retry");
                try {
                    Thread.sleep(db_connect_retry_wait_millis);
                } catch (InterruptedException ex2) {
                    // ignore
                }
            }
        }
    }
    
    /**
     * DBService subclasses should implement this method, disconnecting from the DB.
     */
    protected abstract void stopService();
    
    //----- Public DBService methods: Namespace management
    
    /**
     * Create the namespace needed for this DBService, if any. It is up to the concrete
     * DBService class to decide what, if anything, should be done to prepare the new
     * namespace.
     */
    public abstract void createNamespace();
    
    /**
     * Delete the namespace for this DBService, if any, including its applications and
     * data.
     */
    public abstract void dropNamespace();
    
    //----- Public DBService methods: Store management
    
    /**
     * Return true if the given store name is a system table, aka metadata table. System
     * tables store column values as strings. All other tables use binary column values.
     * 
     * @param   storeName   Store name to test.
     * @return              True if the store name is a system table.
     */
    public static boolean isSystemTable(String storeName) {
        return storeName.equals(SchemaService.APPS_STORE_NAME) ||
               storeName.equals(TaskManagerService.TASKS_STORE_NAME);
    }
    
    /**
     * Create a new store. Columns hold string or binary values as requested. If the
     * given store already exists, this is a no-op.
     * 
     * @param storeName     Name of new store to create.
     * @param bBinaryValues True to ensure that new store holds binary values. False means
     *                      columns hold string values.
     */
    public abstract void createStoreIfAbsent(String storeName, boolean bBinaryValues);
    
    /**
     * Delete the store with the given name. If the store does not exist, this is a no-op.
     * 
     * @param storeName Name of store to delete.
     */
    public abstract void deleteStoreIfPresent(String storeName);
    
    //----- Public DBService methods: Updates
    
    /**
     * Create a new {@link DBTransaction} object that holds updates that will be committed
     * together. The transactions can be committed by calling {@link #commit(DBTransaction)}.
     * 
     * @return  New {@link DBTransaction}.
     */
    public DBTransaction startTransaction() {
        return new DBTransaction();
    }
    
    /**
     * Commit the updates in the given {@link DBTransaction}. An exception is thrown if
     * the updates cannot be committed after all retries. Regardless of whether the
     * updates are successful or not, the updates are cleared from the transaction object
     * before returning.
     * 
     * @param dbTran    {@link DBTransaction} containing updates to commit.
     */
    public abstract void commit(DBTransaction dbTran);
    
    //----- Public DBService methods: Queries

    
    /**
     * Get columns for the row with the given key in the given store, sorted by the column's name.
     * Columns range is defined by an interval [startCol, endCol). If there are more
     * than 'count' columns then only 'count' columns should be returned.
     * startColumn is included, endColumn is not included.
     * if startColumn is null the results should start from the first column in the row
     * if endcolumn is null then the results should end at the last column in the row
     * Empty array or null can be returned if no row
     * is found with the given key or no columns in the given interval found.
     * 
     * @param storeName Name of store to query.
     * @param rowKey    Key of row to fetch.
     * @param startCol  First name in the column names interval, inclusive, or null.
     * @param endCol    Upper bound on the column names interval, exclusive, or null.
     * @return          List of {@link DColumn}s or null. List should be sorted by the column's name
     */
    public abstract List<DColumn> getColumns(String storeName, String rowKey,
                                             String startColumn, String endColumn, int count);
    
    /**
     * Get columns by the specified column names.
     * Empty array or null can be returned if no row is found
     * with the given key or no columns with the given column names are found.
     * 
     * @param storeName   Name of store to query.
     * @param rowKey      Key of row to fetch.
     * @param columnNames List of column names to return.
     * @return            List of {@link DColumn}s or null.
     */
    public abstract List<DColumn> getColumns(String storeName, String rowKey, Collection<String> columnNames);
    
    /**
     * Use this method to get all the rows in the database.
     * First call should set continuationToken to null,
     * next calls should set it to the last row of the previous invocation,
     * so the chunk should start from the row next after continuationToken
     * Empty array or null can be returned if no rows in the given store are found.
     * 
     * @param storeName         Name of store to query.
     * @param continuationToken Last row of the previous invokation, or null.
     * @param count             Maximum number of rows returned.
     * @return                  List of row names or null.
     */
    public abstract List<String> getRows(String storeName, String continuationToken, int count);
    
    /**
     * Get all columns for the row with the given key in the given store. Columns are
     * returned as an Iterator of {@link DColumn}s. If no row is found with the given key,
     * the iterator's hasNext() will be false.
     * 
     * @param storeName Name of store to query.
     * @param rowKey    Key of row to fetch.
     * @return          Iterator of {@link DColumn}s. If there is no such row, hasNext()
     *                  will be false.
     */
    public Iterable<DColumn> getAllColumns(String storeName, String rowKey) {
        return getColumnSlice(storeName, rowKey, null, null);
    }

    /**
     * Get columns for the row with the given key in the given store. Columns range
     * is defined by an interval [startCol, endCol]. Columns are returned
     * as an Iterator of {@link DColumn}s. Empty iterator is returned if no row
     * is found with the given key or no columns in the given interval found.
     * 
     * @param storeName Name of store to query.
     * @param rowKey    Key of row to fetch.
     * @param startCol	First name in the column names interval.
     * @param endCol	Last name in the column names interval.
     * @return          Iterator of {@link DColumn}s. If there is no such row, the
     *                  iterator's hasNext() will be false.
     */
    public Iterable<DColumn> getColumnSlice(String storeName, String rowKey, String startCol, String endCol) {
        DRow row = new DRow(storeName, rowKey);
        return row.getColumns(startCol, endCol, 1024);
    }

    /**
     * Get a single column for a single row in the given store. If the given row or column
     * is not found, null is returned. Otherwise, a {@link DColumn} containing the column
     * name and value is returned.
     * 
     * @param storeName Name of store to query.
     * @param rowKey    Key of row to read.
     * @param colName   Name of column to fetch.
     * @return          {@link DColumn} containing the column name and value or null if
     *                  the row or column was not found.
     */
    public DColumn getColumn(String storeName, String rowKey, String colName) {
        List<String> colNames = new ArrayList<String>(1);
        colNames.add(colName);
        List<DColumn> columns = getColumns(storeName, rowKey, colNames);
        if(columns.size() == 0) return null;
        else return columns.get(0);
    }

    public DColumn getLastColumn(String storeName, String rowKey, String startCol, String endCol) {
        DColumn lastCol = null;
        DRow row = new DRow(storeName, rowKey);
        for(DColumn c: row.getColumns(startCol, endCol, 1024)) {
            lastCol = c;
        }
        return lastCol;
    }
    
    /**
     * Get {@link DRow} object for the given row key.
     * the object will be returned even if the row has no columns or does not exist
     * 
     * @param storeName Name of physical store to query.
     * @return          {@link DRow} object. May be empty but not null.
     */
    public Iterable<DRow> getAllRows(String storeName) {
        return new SequenceIterable<DRow>(new RowSequence(storeName, 65536));
    }
    
    /**
     * Get iterator for rows with a specific set of keys. Results are returned as an
     * Iterator of {@link DRow} objects. If any given row is not found, no entry
     * with its key will be returned.
     * 
     * @param storeName Name of store to query.
     * @param rowKeys   Collection of row keys to read.
     * @return          Iterator of {@link DRow} objects. May be empty but not null.
     */
    public DRow getRow(String storeName, String rowKey) {
        return new DRow(storeName, rowKey);
    }
    
    /**
     * Get iterator for rows with a specific set of keys. Results are returned as an
     * Iterator of {@link DRow} objects. If any given row is not found, no entry
     * with its key will be returned.
     * 
     * @param storeName Name of store to query.
     * @param rowKeys   Collection of row keys to read.
     * @return          Iterator of {@link DRow} objects. May be empty but not null.
     */
    public Iterable<DRow> getRows(String storeName, Collection<String> rowKeys) {
        List<DRow> rows = new ArrayList<>(rowKeys.size());
        for(String rowKey: rowKeys) {
            rows.add(new DRow(storeName, rowKey));
        }
        return rows;
    }

}
