package com.bilibili.ijk.media;

import java.util.List;
import java.util.Map;

/** 与 classes14 真身同形状的替身（public ctor + public final getters）。 */
public interface MediaAsset {

    final class Normal implements MediaAsset {
        private final String url;
        private final List<String> backupUrlList;
        private final MediaAssetScheme scheme;
        private final Map<String, String> option;
        private final DrmInfo drm;

        public Normal(String url, List<String> backupUrlList, MediaAssetScheme scheme,
                      Map<String, String> option, DrmInfo drm) {
            this.url = url;
            this.backupUrlList = backupUrlList;
            this.scheme = scheme;
            this.option = option;
            this.drm = drm;
        }

        public final String getUrl() {
            return url;
        }

        public final List<String> getBackupUrlList() {
            return backupUrlList;
        }

        public final MediaAssetScheme getScheme() {
            return scheme;
        }

        public final Map<String, String> getOption() {
            return option;
        }

        public final DrmInfo getDrm() {
            return drm;
        }
    }

    final class Dash implements MediaAsset {
        private final int curAudioQn;
        private final int curVideoQn;
        private final List<IJKDashStream> videoStreamList;
        private final List<IJKDashStream> audioStreamList;
        private final MediaAssetScheme scheme;
        private final Map<String, String> option;
        private final DrmInfo drm;

        public Dash(int curAudioQn, int curVideoQn, List<IJKDashStream> videoStreamList,
                    List<IJKDashStream> audioStreamList, MediaAssetScheme scheme,
                    Map<String, String> option, DrmInfo drm) {
            this.curAudioQn = curAudioQn;
            this.curVideoQn = curVideoQn;
            this.videoStreamList = videoStreamList;
            this.audioStreamList = audioStreamList;
            this.scheme = scheme;
            this.option = option;
            this.drm = drm;
        }

        public final int getCurAudioQn() {
            return curAudioQn;
        }

        public final int getCurVideoQn() {
            return curVideoQn;
        }

        public final List<IJKDashStream> getVideoStreamList() {
            return videoStreamList;
        }

        public final List<IJKDashStream> getAudioStreamList() {
            return audioStreamList;
        }

        public final MediaAssetScheme getScheme() {
            return scheme;
        }

        public final Map<String, String> getOption() {
            return option;
        }

        public final DrmInfo getDrm() {
            return drm;
        }
    }

    final class MultiFlv implements MediaAsset {
        private final List<MultiFlvSegment> segmentList;
        private final MediaAssetScheme scheme;
        private final Map<String, String> option;

        public MultiFlv(List<MultiFlvSegment> segmentList, MediaAssetScheme scheme,
                        Map<String, String> option) {
            this.segmentList = segmentList;
            this.scheme = scheme;
            this.option = option;
        }

        public final List<MultiFlvSegment> getSegmentList() {
            return segmentList;
        }

        public final MediaAssetScheme getScheme() {
            return scheme;
        }

        public final Map<String, String> getOption() {
            return option;
        }
    }
}
