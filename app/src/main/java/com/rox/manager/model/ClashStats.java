package com.rox.manager.model;

/**
 * Domain model for Clash connection statistics.
 * Returned by {@link com.rox.manager.service.ClashApiService#getStats}.
 */
public final class ClashStats {
    private final long connectionCount;
    private final long downloadTotal;
    private final long uploadTotal;

    public ClashStats(long connectionCount, long downloadTotal, long uploadTotal) {
        this.connectionCount = connectionCount;
        this.downloadTotal = downloadTotal;
        this.uploadTotal = uploadTotal;
    }

    public long getConnectionCount() { return connectionCount; }
    public long getDownloadTotal() { return downloadTotal; }
    public long getUploadTotal() { return uploadTotal; }
}
