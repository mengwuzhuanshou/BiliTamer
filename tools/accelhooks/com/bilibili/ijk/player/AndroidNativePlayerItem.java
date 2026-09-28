package com.bilibili.ijk.player;

import java.util.List;

/** 替身：记录 addDashStream 实际收到的两个流清单。 */
public final class AndroidNativePlayerItem {
    public List<?> videoSeen;
    public List<?> audioSeen;
    public int calls;

    public void addDashStream(List videoStreamList, List audioStreamList) {
        calls++;
        this.videoSeen = videoStreamList;
        this.audioSeen = audioStreamList;
    }
}
