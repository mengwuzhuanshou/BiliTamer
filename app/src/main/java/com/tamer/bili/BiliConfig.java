package com.tamer.bili;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * BiliTamer 开关配置：与设置界面(SettingsActivity)共用同一份 SharedPreferences。
 * Hook 侧（libxposed）按 conf 文件读取：最高优先级为宿主自己 files 目录下的
 * bili_tamer_host.conf（由启动 Intent 投递后 Hook 落盘），其后再找 root 写的
 * bili_tamer.conf 与 /data/local/tmp 兜底副本；所有路径按当前进程的用户区拼，
 * 见 {@link #loadForHook(String)}。
 */
public final class BiliConfig {
    public static final String MODULE_PKG = "com.tamer.bili";
    public static final String PREFS_NAME = "bili_tamer_config";
    public static final String TARGET_PKG = "com.bilibili.app.in";
    /** 国内版宿主：仅侦查/探针通路验证用（媒体栈与国际版同源），功能 hook 不承诺适配。 */
    public static final String DOMESTIC_PKG = "tv.danmaku.bili";
    public static final String WEB_PROCESS = "com.bilibili.app.in:web";
    /** 离线下载进程（OkDown/VideoDownloadService 所在，manifest android:process=":download" 实测）。 */
    public static final String DOWNLOAD_PROCESS = "com.bilibili.app.in:download";

    public static boolean isBiliHost(String pkg) {
        return TARGET_PKG.equals(pkg) || DOMESTIC_PKG.equals(pkg);
    }
    public static final String CONF_NAME = "bili_tamer.conf";
    /**
     * 投递回执的广播 action（Hook 进程 → 模块设置页）。显式 setPackage 到模块，
     * 只带 gen/nonce/来源读数，不含任何用户数据；伪造它只能骗过设置页的诊断文案，
     * 不影响宿主行为，故不设自定义权限（权限要求会把这条通道变成又一次跨应用查询）。
     */
    public static final String ACTION_CONF_ACK = "com.tamer.bili.CONF_ACK";
    /** 实际生效的配置来源（日志排查用） */
    public static volatile String sConfSource = "defaults";
    /** 当前生效配置的代次（文件通道与 Intent 通道共用这一把尺子）。 */
    public static volatile long sConfGen = 0L;
    /** 最近一次读到的本地 conf 全路径（仅日志诊断）。 */
    private static volatile String sLastConfPath = "";

    // ===== 总开关 =====
    public static final String KEY_MASTER = "master_enabled";

    // ===== IP 属地（评论区 + 用户主页）=====
    public static final String KEY_IP_LOCATION = "ip_location_enabled";
    /** 身份声明范围：0=全局（旧行为，所有请求都改写身份）；1=评论区限定（默认，仅评论/字幕 RPC 改写）。 */
    public static final String KEY_IP_SCOPE = "ip_scope_mode";
    public static final int IP_SCOPE_GLOBAL = 0;
    public static final int IP_SCOPE_COMMENT = 1;

    // ===== 播放器解码：0=自动(硬解过滤) 1=锁定 HEVC 2=锁定 AV1 3=锁定 H264 4=关闭(不干预) =====
    public static final String KEY_CODEC = "codec_mode";

    // ===== 播放器硬解过滤：自动顺位下按设备硬解能力过滤 HEVC/AV1（默认开）=====
    // v1.7.1 起过滤为「显式清位」：设备无硬解的编码位从 fnval 移除，服务端不下发。
    public static final String KEY_CODEC_HW_FILTER = "codec_hw_filter";

    // ===== 首页 UI 布局（国内版风格）：顶栏消息图标 / 头像=我的入口 / 底栏删 tab =====
    public static final String KEY_HOME_TOPBAR_MSG_ICON = "home_topbar_message_icon";
    public static final String KEY_HOME_AVATAR_MINE_ENTRY = "home_avatar_mine_entry";
    public static final String KEY_HOME_TABBAR_RM_MSG = "home_tabbar_remove_message";
    public static final String KEY_HOME_TABBAR_RM_MINE = "home_tabbar_remove_mine";
    public static final String KEY_HOME_TOPBAR_MSG_BADGE = "home_topbar_message_badge";

    // ===== 音质：0=自动 1=AAC 2=杜比全景声 3=Hi-Res 无损 4=关闭(不干预) =====
    public static final String KEY_AUDIO_QUALITY = "audio_quality";

    // ===== HDR：0=自动 1=锁定 HDR 2=锁定 HDR Vivid 3=强制关闭 4=关闭(不干预) =====
    public static final String KEY_HDR = "hdr_mode";

    // ===== 下载加速（实验）：回环代理 + 多 CDN 多 Range 并发 + 共享块缓存 =====
    // 默认必须关（探针/实验特性，未过真机全链路验证前不得随 release 出厂生效）。
    public static final String KEY_ACCEL = "accel_enabled";
    /** 单流并发子块数（1..64，越界回默认 8）。 */
    public static final String KEY_ACCEL_CONCURRENCY = "accel_concurrency";
    /** 块缓存目录配额（MB，64..65536，越界回默认 2048）。 */
    public static final String KEY_ACCEL_CACHE_MB = "accel_cache_mb";
    /** 节点池：0=主国内地 1=海外 2=自定义（对齐 CdnResolver.MODE_*）。 */
    public static final String KEY_ACCEL_MODE = "accel_mode";
    /** 自定义节点列表（逗号分隔 host；仅 mode=2 生效）。 */
    public static final String KEY_ACCEL_CUSTOM_HOSTS = "accel_custom_hosts";

    // ===== 听视频（迷你播放器）：听完当前视频自动暂停 =====
    public static final String KEY_LISTEN_PAUSE_AFTER_END = "listen_pause_after_end";

    // ===== 隐藏视频内互动提示 =====
    public static final String KEY_HIDE_TRIPLE = "hide_triple";       // 一键三连
    public static final String KEY_HIDE_VOTE = "hide_vote";           // 投票/互动弹幕
    public static final String KEY_HIDE_UP_PROMPT = "hide_up_prompt"; // UP 提示（关注引导等）

    // ===== 首页不自动刷新 =====
    public static final String KEY_NO_AUTO_REFRESH = "no_auto_refresh";
    public static final String KEY_LIVE_BG_UNLOCK = "live_bg_unlock";          // 直播后台播放入口解锁
    public static final String KEY_HOME_LIVE_TAB = "home_live_tab_unlock";      // 首页直播板块入口解锁

    // ===== 首页推荐分区屏蔽：tname 词表（逗号分隔存储；唯一字符串键）=====
    public static final String KEY_FEED_BLOCK_TNAMES = "feed_blocked_tnames";

    // ===== 首页推荐流清洗（对齐 MBGA 三项 + 大卡片，均默认关）=====
    /** 只展示 UGC：cardGoto 非 "av" 的卡（官方合集/活动/直播等）整卡移除。 */
    public static final String KEY_FEED_ONLY_UGC = "feed_only_ugc";
    /** 干净卡片：去推荐理由角标与竖屏角标，descButton 缺失时补「UP 名 → 空间页」。 */
    public static final String KEY_FEED_CLEAN_CARD = "feed_clean_card";
    /** 禁止竖屏播放器：卡片路由 bilibili://story/<id> 改写为 bilibili://video/<id>。 */
    public static final String KEY_FEED_NO_PORTRAIT = "feed_no_portrait";
    /** 关闭大卡片：移除占满整屏宽度的卡（轮播/大封面/内联播放），双列小卡不受影响。 */
    public static final String KEY_FEED_NO_LARGE_CARD = "feed_no_large_card";

    // ===== 播放器 =====
    /** 干掉云视听小电视：清空弹幕回包（DmViewReply）里的 activity_meta 活动浮层。 */
    public static final String KEY_PLAYER_NO_ACTIVITY_META = "player_no_activity_meta";
    /** 干掉详情页的广告位：播放器下方商品卡（{@code ViewReply.cm_} 的 {@code sourceContentItem*}）、
     *  简介区楼层广告，以及简介区「视频提及」里的游戏推广卡（{@code Module.videoMentions}，只摘
     *  {@code MENTION_TYPE_GAME}，UP 自己的课程/充电/手办链接不动）。
     *  出厂默认**开**：这些槽位只在国内版排期里，是身份改写让服务端把客户端当国内版才下发的，
     *  属于本模块引入的回归，不是宿主原生长相（与其余「删界面」类开关默认相反）。
     *  三格对照装机已证（同一条稿件，改身份才下发；荣耀 2026-10-28，细节见 {@link com.tamer.bili.hooks.ViewCmAdHooks} 类头）。 */
    public static final String KEY_PLAYER_NO_UNDER_AD = "player_no_under_ad";

    // ===== 调试 =====
    public static final String KEY_DEBUG_ALIVE = "debug_alive_marker";
    public static final String KEY_VERBOSE = "verbose_log";
    /** 侦查探针（仅日志零干预）：播放器 setDataSource / okhttp / 下载栈类加载观测。 */
    public static final String KEY_PROBE = "probe_enabled";
    /** 开发兜底开关：只被 v1.0 式本地 conf 文件识别；置 true 时该文件覆盖远程配置。 */
    public static final String KEY_DEV_OVERRIDE = "dev_override";


    public static final String[] ALL_KEYS = {
        KEY_MASTER,
        KEY_IP_LOCATION,
        KEY_IP_SCOPE,
        KEY_CODEC,
        KEY_CODEC_HW_FILTER,
        KEY_AUDIO_QUALITY,
        KEY_HDR,
        KEY_ACCEL,
        KEY_ACCEL_CONCURRENCY,
        KEY_ACCEL_CACHE_MB,
        KEY_ACCEL_MODE,
        KEY_ACCEL_CUSTOM_HOSTS,
        KEY_HOME_TOPBAR_MSG_ICON,
        KEY_HOME_TOPBAR_MSG_BADGE,
        KEY_HOME_AVATAR_MINE_ENTRY,
        KEY_HOME_TABBAR_RM_MSG,
        KEY_HOME_TABBAR_RM_MINE,
        KEY_FEED_BLOCK_TNAMES,
        KEY_FEED_ONLY_UGC,
        KEY_FEED_CLEAN_CARD,
        KEY_FEED_NO_PORTRAIT,
        KEY_FEED_NO_LARGE_CARD,
        KEY_PLAYER_NO_ACTIVITY_META,
        KEY_PLAYER_NO_UNDER_AD,
        KEY_LISTEN_PAUSE_AFTER_END,
        KEY_HIDE_TRIPLE,
        KEY_HIDE_VOTE,
        KEY_HIDE_UP_PROMPT,
        KEY_NO_AUTO_REFRESH,
        KEY_LIVE_BG_UNLOCK,
        KEY_HOME_LIVE_TAB,
        KEY_DEBUG_ALIVE,
        KEY_VERBOSE,
        KEY_PROBE,
    };

    /** 默认值表：与 SettingsActivity 保持一致 */
    public static boolean defaultValueOf(String key) {
        if (KEY_MASTER.equals(key)) return true;
        if (KEY_IP_LOCATION.equals(key)) return true;   // v1.1 起出厂默认开
        if (KEY_CODEC_HW_FILTER.equals(key)) return true; // v1.6.1 起出厂默认开（黑屏修复）
        if (KEY_HOME_TOPBAR_MSG_ICON.equals(key)) return true;   // v1.7.0 首页布局（6.4.0）
        if (KEY_HOME_TOPBAR_MSG_BADGE.equals(key)) return true;  // v1.7.0 顶栏消息未读角标
        if (KEY_HOME_AVATAR_MINE_ENTRY.equals(key)) return true; // v1.7.0 首页布局（6.4.0）
        if (KEY_HOME_TABBAR_RM_MSG.equals(key)) return true;     // v1.7.0 底栏删消息 tab（顶栏消息入口替代）
        if (KEY_HOME_TABBAR_RM_MINE.equals(key)) return true;    // v1.7.0 底栏隐藏「我的」tab（数据保留，顶栏头像经真实派发打开完整页）
        if (KEY_HIDE_TRIPLE.equals(key)) return false;
        if (KEY_HIDE_VOTE.equals(key)) return false;
        if (KEY_HIDE_UP_PROMPT.equals(key)) return false;
        // 首页推荐流清洗四项：会减少卡片数量/改写卡片内容，一律出厂默认关，由用户按需开启
        if (KEY_FEED_ONLY_UGC.equals(key)) return false;
        if (KEY_FEED_CLEAN_CARD.equals(key)) return false;
        if (KEY_FEED_NO_PORTRAIT.equals(key)) return false;
        if (KEY_FEED_NO_LARGE_CARD.equals(key)) return false;
        if (KEY_PLAYER_NO_ACTIVITY_META.equals(key)) return false;
        if (KEY_PLAYER_NO_UNDER_AD.equals(key)) return true;
        if (KEY_DEBUG_ALIVE.equals(key)) return false;
        if (KEY_VERBOSE.equals(key)) return false;
        if (KEY_PROBE.equals(key)) return false;
        if (KEY_NO_AUTO_REFRESH.equals(key)) return false;
        if (KEY_LIVE_BG_UNLOCK.equals(key)) return true;      // 直播后台播放入口解锁，出厂默认开
        if (KEY_HOME_LIVE_TAB.equals(key)) return true;       // 首页直播板块入口解锁，出厂默认开
        if (KEY_ACCEL.equals(key)) return false; // 实验特性出厂默认关（硬约束）
        if (KEY_LISTEN_PAUSE_AFTER_END.equals(key)) return false;
        return false;
    }

    public static int defaultIntOf(String key) {
        if (KEY_CODEC.equals(key)) return 0;
        if (KEY_AUDIO_QUALITY.equals(key)) return 0;
        if (KEY_HDR.equals(key)) return 0;
        if (KEY_IP_SCOPE.equals(key)) return IP_SCOPE_COMMENT; // v1.3 起默认评论区限定
        if (KEY_ACCEL_CONCURRENCY.equals(key)) return 8;
        if (KEY_ACCEL_CACHE_MB.equals(key)) return 2048;
        return 0;
    }

    private final Map<String, Object> map;

    private BiliConfig(Map<String, Object> map) {
        this.map = map;
    }

    /**
     * Hook 侧加载（LineTamer v1.6.1 同款代次协议，最小权限）：
     * 读序 host-conf → module 副本 → B 站 files → /data/local/tmp，
     * 每来源带 conf_gen 代次：gen 大者胜、同代次先到先得；全无代次（旧格式）
     * 沿用静态顺序，兼容旧副本。/data/local/tmp 世界可写，不算可信通道：先要求
     * {@link #CONF_KEY} 防伪键匹配（缺键或不等直接当垃圾），过了这一关也只在
     * 没有任何可信副本压得住它（可信件缺失或代次更低）时才采纳，否则一律让位。
     * XSharedPreferences 通道不采用（LSPosed 2.2.0
     * 标记废弃、2.3.0 移除）。LAST_CONF_SRC 带 confSrc= 便于诊断
     * （设置页开关可能根本到不了目标进程——HMT/QQTamer §13 教训）。
     *
     * 路径按**当前进程所在用户区**拼（uid/100000 即 Android 用户号），宿主包名由
     * 调用方传入：写死 user 0 + 写死国际版包名时，宿主跑在应用分身 / 安全文件夹 /
     * 工作资料（user 10、999…）会让读写两边同时错位，root 兜底写的也还是 user 0
     * 那份——表现就是「改了不生效、给了 root 也没用」。旧字面路径继续留作候选，
     * 兼容既有副本。
     */
    public static BiliConfig loadForHook(String hostPkg) {
        String u = "/data/user/" + (android.os.Process.myUid() / 100000);
        java.util.List<String[]> cand = new java.util.ArrayList<String[]>();
        // ⓪ 宿主自有副本：设置页经启动 Intent 投递后由 Hook 写入宿主 files
        cand.add(new String[]{u + "/" + hostPkg + "/files/" + HOST_CONF_NAME, "host-conf"});
        cand.add(new String[]{"/data/user/0/" + hostPkg + "/files/" + HOST_CONF_NAME, "host-conf"});
        cand.add(new String[]{"/data/data/" + hostPkg + "/files/" + HOST_CONF_NAME, "host-conf"});
        // ①② 模块自有两份（设置页随保存实时重写，永远最新；跨 uid 读不到属正常）
        cand.add(new String[]{u + "/" + MODULE_PKG + "/files/" + CONF_NAME, "module-files-conf"});
        cand.add(new String[]{u + "/" + MODULE_PKG + "/shared_prefs/" + CONF_NAME, "module-prefs-conf"});
        // ③ 宿主 files（root 写，仅开发兜底）
        cand.add(new String[]{u + "/" + hostPkg + "/files/" + CONF_NAME, "files-conf"});
        cand.add(new String[]{"/data/data/" + hostPkg + "/files/" + CONF_NAME, "files-conf"});
        // ④ /data/local/tmp：root/shell 都能写、世界可读，不是可信通道，单独降档处理
        cand.add(new String[]{"/data/local/tmp/" + CONF_NAME, "tmp-conf", "untrusted"});
        ParsedConf best = null;
        String bestName = null;
        ParsedConf tmp = null;
        for (int i = 0; i < cand.size(); i++) {
            ParsedConf p = parseConfPath(cand.get(i)[0]);
            if (p == null) {
                continue;
            }
            boolean trusted = cand.get(i).length < 3;
            if (trusted) {
                if (best == null || (p.gen > 0 && p.gen > best.gen)) {
                    best = p;
                    bestName = cand.get(i)[1];
                }
            } else if (!CONF_KEY.equals(p.key)) {
                // 世界可写的那一档：防伪键不匹配（含没这一行）就当垃圾，连「压不住时才用」
                // 的资格都不给。可信通道（模块/宿主 files、启动 Intent）不查这把键，
                // 免得既有副本因为没这行而集体失效。
                continue;
            } else if (tmp == null || (p.gen > 0 && p.gen > tmp.gen)) {
                tmp = p;
            }
        }
        // tmp 只在「没有任何可信副本压得住它」时才采纳：投递链路通则用不着它，
        // 三星那种跨应用启动被拦、只剩 root/shell 写 tmp 的场景照样能救活。
        // 同代次算可信通道赢（真机可能出现同一份文件被复制到两处的情况）。
        if (tmp != null && (best == null || tmp.gen > best.gen)) {
            best = tmp;
            bestName = "tmp-conf";
        }
        if (best != null) {
            sConfSource = bestName + " gen=" + best.gen;
            sConfGen = best.gen;
            sLastConfPath = best.path;
            return new BiliConfig(best.map);
        }
        sConfSource = "defaults";
        sConfGen = 0L;
        return new BiliConfig(new HashMap<String, Object>());
    }

    public static final String HOST_CONF_NAME = "bili_tamer_host.conf";
    public static final String KEY_CONF_GEN = "conf_gen";
    /** conf 里的防伪键行键名（不是功能开关，故不进 {@link #ALL_KEYS}）。 */
    public static final String KEY_CONF_KEY = "conf_key";
    /**
     * conf 防伪键：编译进模块 APK，写侧（{@code ConfSync.kvConfText}）随 conf 落盘，
     * 读侧只对 {@code /data/local/tmp/} 那一份要求匹配。
     *
     * 为什么需要它：/data/local/tmp 世界可写，任何 root/shell 都能塞进一份高代次 conf，
     * 光靠「可信通道压得住才让位」挡不住「可信件缺失时的高代次伪造」。加这把键之后，
     * 伪造者必须先知道这个常量。
     * 强度上限要诚实：它编译在**公开 APK** 里，反解我们的发布件就能拿到，所以它挡住的是
     * 「随手写个 tmp 文件抢配置」，挡不住「专门冲着我们模块来的人」。真要 per-install
     * 私钥得让 Hook 侧拿得到模块私有 SP，那需要另开一条投递腿，当前不做。
     */
    public static final String CONF_KEY = "bilitamer.k1.9d3f7c4a2e";

    public boolean get(String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        return def;
    }

    /** 字符串键读取（当前仅 feed_blocked_tnames；缺失/类型不符返回空串）。 */
    public String getString(String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : "";
    }

    public int getInt(String key, int def) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }

    /** 单个 conf 来源解析结果 */
    private static final class ParsedConf {
        final Map<String, Object> map;
        final long gen;
        final String path;
        final String key;
        ParsedConf(Map<String, Object> map, long gen, String path, String key) {
            this.map = map;
            this.gen = gen;
            this.path = path;
            this.key = key;
        }
    }

    /** 解析单个 conf 副本：kv 行 + conf_gen 代次行（BOM 去除；非已知类型键跳过）。 */
    private static ParsedConf parseConfPath(String path) {
        try {
            File f = new File(path);
            if (!f.isFile() || !f.canRead()) return null;
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            return parseConfText(sb.toString(), path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** conf 全文解析：返回 kv（布尔严格 true/false；int/字符串键原样）+ 代次。 */
    private static ParsedConf parseConfText(String text, String path) {
        try {
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1); // BOM 去除（HMT §13 教训）
            }
            Map<String, Object> m = new HashMap<String, Object>();
            long gen = 0L;
            String confKey = null;
            for (String line : text.split("\\r?\\n")) {
                int i = line.indexOf('=');
                if (i <= 0) {
                    continue;
                }
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                if (KEY_CONF_GEN.equals(k)) {
                    try { gen = Long.parseLong(v); } catch (Throwable ignored2) {}
                    continue;
                }
                if (KEY_CONF_KEY.equals(k)) {
                    confKey = v; // 防伪键：不进功能表，只交给读侧的 untrusted 档校验
                    continue;
                }
                if (KEY_DEV_OVERRIDE.equals(k)) {
                    continue; // 新协议下 remote/host 副本即权威，不再按 dev_override 分叉
                }
                boolean known = false;
                for (String key : ALL_KEYS) {
                    if (key.equals(k)) { known = true; break; }
                }
                if (!known) {
                    continue; // 未知键跳过（严格解析：假阳性教训）
                }
                if (KEY_CODEC.equals(k) || KEY_AUDIO_QUALITY.equals(k)
                        || KEY_HDR.equals(k) || KEY_IP_SCOPE.equals(k)
                        || KEY_ACCEL_CONCURRENCY.equals(k) || KEY_ACCEL_CACHE_MB.equals(k)
                        || KEY_ACCEL_MODE.equals(k)) {
                    try { m.put(k, Integer.valueOf(v)); } catch (Throwable ignored2) {}
                } else if (KEY_FEED_BLOCK_TNAMES.equals(k) || KEY_ACCEL_CUSTOM_HOSTS.equals(k)) {
                    m.put(k, v);
                } else if ("true".equals(v) || "false".equals(v)) {
                    m.put(k, Boolean.valueOf("true".equals(v)));
                }
            }
            if (m.isEmpty()) {
                return null;
            }
            return new ParsedConf(m, gen, path, confKey);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Hook 运行时热替换配置（启动 Intent 投递 / 主链路刷新用）：整表替换并
     * 记录代次与来源。conf 文本规范化由调用方 writeHostConf 完成。
     */
    public long overrideGen = 0L;

    public static BiliConfig fromConfText(String conf, long gen) {
        ParsedConf p = parseConfText(conf, "intent");
        if (p == null) {
            return null;
        }
        BiliConfig c = new BiliConfig(p.map);
        c.overrideGen = p.gen > 0 ? p.gen : gen;
        // 纯工厂：不碰 sConfSource/sConfGen。这两个静态是「当前生效来源」的读数，
        // 调用方拿它做代次比较，若在比较前就写成传入代次，第一次投递会把自己
        // 判成 stale 丢掉（1.9.8 装机日志抓到的回归）。采纳时才由调用方盖章。
        return c;
    }

    /** 规范化 conf 全文（按解析结果重建行，传输形态不落盘）。 */
    public String toNormalizedText(long gen) {
        StringBuilder sb = new StringBuilder();
        for (String key : ALL_KEYS) {
            Object v = map.get(key);
            if (v == null) {
                continue;
            }
            sb.append(key).append('=').append(v).append('\n');
        }
        sb.append(KEY_CONF_GEN).append('=').append(gen).append('\n');
        return sb.toString();
    }
}
