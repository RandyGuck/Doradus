package com.dell.doradus.logservice;

public class ChunkIterable implements Iterable<ChunkInfo> {
    private String m_store;
    private String m_partition;
    
    public ChunkIterable(String store, String partition) {
        m_store = store;
        m_partition = partition;
    }

    @Override public ChunkIterator iterator() {
        return new ChunkIterator(m_store, m_partition);
    }
    
}
