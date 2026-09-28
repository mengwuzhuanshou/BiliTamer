package com.bilibili.ijk.media;

import java.util.List;

/** 与 classes14 MultiFlvSegment 同形状的替身。 */
public final class MultiFlvSegment {
    private final String url;
    private final List<String> backupUrlList;
    private final long durationMs;
    private final long size;

    public MultiFlvSegment(String url, List<String> backupUrlList, long durationMs, long size) {
        this.url = url;
        this.backupUrlList = backupUrlList;
        this.durationMs = durationMs;
        this.size = size;
    }

    public final String getUrl() {
        return url;
    }

    public final List<String> getBackupUrlList() {
        return backupUrlList;
    }

    public final long getDurationMs() {
        return durationMs;
    }

    public final long getSize() {
        return size;
    }
}
