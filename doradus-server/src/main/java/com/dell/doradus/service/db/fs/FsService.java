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

package com.dell.doradus.service.db.fs;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dell.doradus.service.db.DBService;
import com.dell.doradus.service.db.DBTransaction;
import com.dell.doradus.service.db.DColumn;

public class FsService extends DBService {
    private String ROOT; 
    protected final Logger m_logger = LoggerFactory.getLogger(getClass());
    
    private Map<String, FsStore> m_stores = new HashMap<>();
    
    private final Object m_sync = new Object();
    
    public FsService() {
        ROOT = getParamString("db-path");
        if(ROOT == null) throw new RuntimeException("FsService: db-path not defined");
        m_logger.info("Using FS API");
        File root = new File(ROOT);
        if(!root.exists()) root.mkdirs();
        m_logger.info("Root file path: {}", ROOT);
    }

    @Override public void stopService() {
        for(FsStore store: m_stores.values()) store.close();
    }
    
    @Override public void createNamespace() {
        synchronized (m_sync) {
            File namespaceDir = new File(ROOT);
            if(!namespaceDir.exists())namespaceDir.mkdir();
        }
    }

    @Override public void dropNamespace() {
        synchronized(m_sync) {
            File namespaceDir = new File(ROOT);
            FileUtils.deleteDirectory(namespaceDir);
        }
    }
    
    @Override public void createStoreIfAbsent(String storeName, boolean bBinaryValues) {
        synchronized(m_sync) {
            File storeDir = new File(ROOT + "/" + storeName);
            if(!storeDir.exists()) storeDir.mkdir();
        }
    }
    
    @Override public void deleteStoreIfPresent(String storeName) {
        synchronized(m_sync) {
        	closeStore(storeName);
            File storeDir = new File(ROOT + "/" + storeName);
            if(storeDir.exists()) FileUtils.deleteDirectory(storeDir);
        }
    }
    
    @Override public void commit(DBTransaction dbTran) {
        synchronized(m_sync) {
        	Set<String> stores = new HashSet<String>();
            Map<String, Map<String, List<DColumn>>> columnUpdates = dbTran.getColumnUpdatesMap();
            Map<String, Map<String, List<String>>> columnDeletes = dbTran.getColumnDeletesMap();
            Map<String, List<String>> rowDeletes = dbTran.getRowDeletesMap();
            stores.addAll(columnUpdates.keySet());
            stores.addAll(columnDeletes.keySet());
            stores.addAll(rowDeletes.keySet());
            
            for(String storeName: stores) {
                FsStore store = getStore(storeName);
                store.addMutations(columnUpdates.get(storeName), columnDeletes.get(storeName), rowDeletes.get(storeName));
            }
        }
    }
    
    @Override public List<DColumn> getColumns(String storeName, String rowKey, String startColumn, String endColumn, int count) {
        synchronized(m_sync) {
            FsStore store = getStore(storeName);
            return store.getColumns(rowKey, startColumn, endColumn, count);
        }
    }

    @Override
    public List<DColumn> getColumns(String storeName, String rowKey, Collection<String> columnNames) {
        synchronized(m_sync) {
            FsStore store = getStore(storeName);
            return store.getColumns(rowKey, columnNames);
        }
    }

    @Override public List<String> getRows(String storeName, String continuationToken, int count) {
        synchronized (m_sync) {
            FsStore store = getStore(storeName);
            return store.getRows(continuationToken, count);
        }
    }


    private FsStore getStore(String storeName) {
        String path = ROOT + "/" + storeName;
        FsStore store = m_stores.get(path);
        if(store == null) {
            File rootDir = new File(ROOT);
            store = new FsStore(rootDir, storeName);
            m_stores.put(path, store);
        }
        return store;
    }
    
    private void closeStore(String storeName) {
        String path = ROOT + "/" + storeName;
        FsStore store = m_stores.get(path);
        if(store == null) return;
        store.close();
        m_stores.remove(path);
    }
    
}
