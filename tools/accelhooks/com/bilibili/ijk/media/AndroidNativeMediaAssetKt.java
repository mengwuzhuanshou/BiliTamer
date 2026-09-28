package com.bilibili.ijk.media;

/** 6.5.0 名的静态口；AccelHooks 按「static + 1×MediaAsset → *AndroidNativeMediaAsset」形状匹配。 */
public final class AndroidNativeMediaAssetKt {
    public static AndroidNativeMediaAsset createNativeAsset(MediaAsset mediaAsset) {
        return new AndroidNativeMediaAsset(mediaAsset);
    }
}
