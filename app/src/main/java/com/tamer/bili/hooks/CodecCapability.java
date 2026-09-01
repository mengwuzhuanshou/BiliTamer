package com.tamer.bili.hooks;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 设备硬解能力探测：供「自动顺位」过滤设备无法<b>硬解</b>的视频编码。
 *
 * 背景（v1.6.1 黑屏修复）：分发用户反馈播放随机黑屏、只有声音——模块在自动顺位下
 * 无条件请求 AV1/HEVC 流，设备无对应硬解时播放器软解/解码失败，音频轨正常走，
 * 画面黑。根因是只做了「服务端有什么」的顺位，没做「设备能解什么」的过滤。
 *
 * 机制：反射遍历 {@code MediaCodecList(REGULAR_CODECS)} 的解码器，找目标 mime
 * 的硬件加速解码器。整体走反射而非直接引用 android.media：其一，编译桩不含
 * MediaCodecList；其二，{@code MediaCodecInfo.isHardwareAccelerated()} API 29
 * 才转公，直调低版本会 NoSuchMethodError——反射失败再回退名字启发
 * （omx.google. / c2.android. / c2.google. / 含 .sw. 一律视为软解）。
 *
 * 策略：探测异常按「支持」处理（fail-open，保持旧行为，不让探测本身弄坏能播的
 * 设备）；名字都拿不到的解码器保守按软解。结果进程内缓存一次。
 */
public final class CodecCapability {

    /** HEVC 媒体类型（MediaFormat.MIMETYPE_VIDEO_HEVC）。 */
    public static final String MIME_HEVC = "video/hevc";
    /** AV1 媒体类型（MediaFormat.MIMETYPE_VIDEO_AV1，字符串值跨版本稳定）。 */
    public static final String MIME_AV1 = "video/av01";

    /** MediaCodecList.REGULAR_CODECS 常量值（API 21 起存在，值稳定）。 */
    private static final int REGULAR_CODECS = 1;

    private static volatile Boolean sHwHevc;
    private static volatile Boolean sHwAv1;

    private CodecCapability() {
    }

    /** 设备是否存在 HEVC 硬件解码器。 */
    public static boolean hwHevc() {
        return hwOf(MIME_HEVC);
    }

    /** 设备是否存在 AV1 硬件解码器。 */
    public static boolean hwAv1() {
        return hwOf(MIME_AV1);
    }

    private static boolean hwOf(String mime) {
        boolean hevc = MIME_HEVC.equals(mime);
        Boolean cached = hevc ? sHwHevc : sHwAv1;
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean ok = probe(mime);
        if (hevc) {
            sHwHevc = Boolean.valueOf(ok);
        } else {
            sHwAv1 = Boolean.valueOf(ok);
        }
        return ok;
    }

    private static boolean probe(String mime) {
        try {
            Class<?> cls = Class.forName("android.media.MediaCodecList");
            Object list = cls.getConstructor(int.class)
                    .newInstance(Integer.valueOf(REGULAR_CODECS));
            Object[] infos = (Object[]) cls.getMethod("getCodecInfos").invoke(list);
            if (infos != null) {
                for (Object info : infos) {
                    if (info == null || !isDecoder(info) || !supports(info, mime)) {
                        continue;
                    }
                    if (isHwAccelerated(info)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            return true; // fail-open：探测不了就不过滤，保持旧行为
        }
        return false;
    }

    private static boolean isDecoder(Object info) {
        try {
            Object r = info.getClass().getMethod("isEncoder").invoke(info);
            return r instanceof Boolean && !((Boolean) r).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean supports(Object info, String mime) {
        try {
            String[] types = (String[]) info.getClass()
                    .getMethod("getSupportedTypes").invoke(info);
            if (types == null) {
                return false;
            }
            for (String t : types) {
                if (mime.equalsIgnoreCase(t)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** API 29+ 公开的 isHardwareAccelerated()；低版本/反射失败回退名字启发。 */
    private static boolean isHwAccelerated(Object info) {
        try {
            Object r = info.getClass().getMethod("isHardwareAccelerated").invoke(info);
            if (r instanceof Boolean) {
                return ((Boolean) r).booleanValue();
            }
        } catch (Throwable ignored) {
        }
        String name = null;
        try {
            Object n = info.getClass().getMethod("getName").invoke(info);
            if (n instanceof String) {
                name = (String) n;
            }
        } catch (Throwable ignored) {
        }
        return !isSoftwareName(name);
    }

    /**
     * 名字启发：C2 软解统一叫 c2.android.*（AV1 软解 c2.android.av1.decoder /
     * dav1d、HEVC 软解 c2.android.hevc.decoder 都命中），老 OMX 软解是
     * omx.google.*；Codec2 厂商软解按约定带 .sw. 段。名字拿不到保守按软解。
     */
    private static boolean isSoftwareName(String name) {
        if (name == null) {
            return true;
        }
        String n = name.toLowerCase(Locale.US);
        return n.startsWith("omx.google.")
                || n.startsWith("c2.android.")
                || n.startsWith("c2.google.")
                || n.contains(".sw.");
    }
}
