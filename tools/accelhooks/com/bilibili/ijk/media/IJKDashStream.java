package com.bilibili.ijk.media;

import java.util.List;

/** 与 classes14 IJKDashStream 同形状的替身。 */
public final class IJKDashStream {
    private final int mediaType;
    private final int codecId;
    private final int qn;
    private final int bandWidth;
    private final String url;
    private final List<String> backupUrlList;

    public IJKDashStream(int mediaType, int codecId, int qn, int bandWidth, String url,
                         List<String> backupUrlList) {
        this.mediaType = mediaType;
        this.codecId = codecId;
        this.qn = qn;
        this.bandWidth = bandWidth;
        this.url = url;
        this.backupUrlList = backupUrlList;
    }

    public final int getMediaType() {
        return mediaType;
    }

    public final int getCodecId() {
        return codecId;
    }

    public final int getQn() {
        return qn;
    }

    public final int getBandWidth() {
        return bandWidth;
    }

    public final String getUrl() {
        return url;
    }

    public final List<String> getBackupUrlList() {
        return backupUrlList;
    }
}
