package com.bilibili.ijk.media;

/** 替身：真身内部只包一个 native 指针；这里存 asset 供断言。 */
public final class AndroidNativeMediaAsset {
    public final MediaAsset asset;

    public AndroidNativeMediaAsset(MediaAsset asset) {
        this.asset = asset;
    }
}
