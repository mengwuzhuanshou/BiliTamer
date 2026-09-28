package com.bilibili.ijk.media;

/** 宿主 DrmInfo 的替身：AccelHooks 只按「非 null 即 DRM，跳过」处理。 */
public final class DrmInfo {
    private final String kid;

    public DrmInfo(String kid) {
        this.kid = kid;
    }

    public String getKid() {
        return kid;
    }
}
