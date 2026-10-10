package com.tamer.bili.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.tamer.bili.BiliConfig;

/** 模块设置界面（纯代码 UI，无资源依赖）。v1.1：远程通道为主，root 仅开发兜底。 */
public class SettingsActivity extends Activity {

    private LinearLayout root;
    private TextView statusView;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float den = getResources().getDisplayMetrics().density;
        final int dp = Math.max(1, Math.round(den));

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setBackgroundColor(Color.parseColor("#FAFAFA"));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp * 20, dp * 24, dp * 20, dp * 40);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("B站国际版增强 (BiliTamer)");
        title.setTextColor(Color.parseColor("#FB7299"));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        addSpace(dp * 4);

        TextView sub = new TextView(this);
        sub.setText("目标应用：com.bilibili.app.in（国际版哔哩哔哩 6.3.0 / 6.4.0）\n"
                + "IP 属地(出厂默认开)·解码/音质顺位 等——不配置也开箱即用。\n"
                + "下方开关为个性化定制项：修改需要 root 同步一次配置。");
        sub.setTextColor(Color.parseColor("#666666"));
        sub.setTextSize(13);
        root.addView(sub);
        addSpace(dp * 16);

        addSwitch(BiliConfig.KEY_MASTER, "模块总开关", "Master switch",
                "关闭后模块完全休眠，等同未启用");

        section("IP 属地", "IP location");
        addSwitch(BiliConfig.KEY_IP_LOCATION, "评论区 + 主页显示 IP 属地", "Show IP location",
                "把请求身份改写为国内版客户端，服务端即返回评论与主页的 IP 属地字段。\n"
                + "注意：可能让评论接口返回国内版行为，建议先小范围验证");
        addRadio(BiliConfig.KEY_IP_SCOPE, "身份声明范围", "Identity scope",
                new String[]{"评论区限定（推荐：仅评论/字幕请求改身份，其余保持国际版）",
                             "全局（旧行为：所有请求都声明国内版身份）"},
                new int[]{BiliConfig.IP_SCOPE_COMMENT, BiliConfig.IP_SCOPE_GLOBAL},
                "评论区限定可避免国内版身份带来的全局副作用（如评论区外广告）；\n"
                + "主页 IP 属地走独立 REST 通道，两种模式下均生效。改后需强制停止 B 站重开。");

        section("播放器", "Player");
        addRadio(BiliConfig.KEY_CODEC, "视频解码格式", "Video codec",
                new String[]{"顺位 Auto (AV1>HEVC>H264)", "锁定 HEVC (H.265)", "锁定 AV1",
                             "锁定 H264 (H.264)", "关闭（不干预解码）"},
                new int[]{0, 1, 2, 3, 4},
                "顺位：优先 AV1，服务端没有 AV1 就 HEVC，都没有才用 H264；\n"
                + "锁定：只请求指定编码（服务端没有时回退默认流）；\n"
                + "锁 H264 兼容性最好（黑屏/解码失败可试此项）；\n"
                + "关闭=模块完全不碰解码请求，纯 App 原行为");
        addSwitch(BiliConfig.KEY_CODEC_HW_FILTER, "按硬解能力自动过滤 HEVC/AV1", "HW-decode auto filter",
                "自动顺位下，设备没有硬件解码器的编码不再向服务端请求，\n"
                + "规避软解失败导致的随机黑屏（有声无画面）。\n"
                + "锁定 HEVC/AV1 不受此开关影响；改后需强停 B 站重开");
        addRadio(BiliConfig.KEY_AUDIO_QUALITY, "音质选项", "Audio quality",
                new String[]{"顺位 Auto (杜比>无损>AAC)", "锁定 AAC", "锁定杜比全景声",
                             "锁定 Hi-Res 无损", "关闭（不干预音质）"},
                new int[]{0, 1, 2, 3, 4},
                "顺位：优先杜比全景声，没有就 Hi-Res 无损，再没有才 AAC；\n"
                + "锁定：只用指定音质（不可用时回退 AAC）；\n"
                + "关闭=模块完全不碰音质请求与音轨选择；\n"
                + "杜比/无损需大会员账号与服务端支持");
        addRadio(BiliConfig.KEY_HDR, "HDR 画质", "HDR",
                new String[]{"顺位 Auto (HDR Vivid>HDR>SDR)", "锁定 HDR", "锁定 HDR Vivid",
                             "强制关闭 HDR", "关闭（不干预）"},
                new int[]{0, 1, 2, 3, 4},
                "顺位：设备支持 HDR 时自动开启（优先 HDR Vivid）；\n"
                + "锁定/强制关闭：强制指定 HDR 模式；\n"
                + "关闭=模块完全不碰 HDR 请求位，纯 App 原行为；\n"
                + "需屏幕支持 HDR 才有实际效果");

        section("下载加速（实验）", "Download acceleration (beta)");
        addSwitch(BiliConfig.KEY_ACCEL, "多 CDN 并发加速播放/离线下载", "Multi-CDN accel (beta)",
                "把在线播放与离线下载的媒体地址改接本机 127.0.0.1 回环代理，\n"
                + "背后多节点多区间并发取数、按序回吐；离线取到的字节前台播放直接复用。\n"
                + "实验特性：默认关。缓存目录 files/bili_tamer_accel（配额默认 2GB，LRU 清理）；\n"
                + "DRM/免流改写地址不接管。改动需强制停止 B 站重开（含下载进程）");

        section("首页布局（国内版风格）", "Home layout (CN-style)");
        addSwitch(BiliConfig.KEY_HOME_TOPBAR_MSG_ICON, "顶栏搜索栏右侧加「消息」图标", "Topbar message icon",
                "对齐国内版布局：搜索框右侧留白处加消息图标，点击直达消息页。\n"
                + "国际版搜索区右侧本就留白，不影响搜索框点击。仅适配 6.4.0");
        addSwitch(BiliConfig.KEY_HOME_TOPBAR_MSG_BADGE, "顶栏「消息」图标显示未读角标", "Topbar message badge",
                "消息图标右上角显示未读红点带数字（与消息 tab 角标同源），无未读时\n"
                + "隐藏。需开启「顶栏消息图标」；改后需强停 B 站重开");
        addSwitch(BiliConfig.KEY_HOME_AVATAR_MINE_ENTRY, "顶栏头像作为「我的」入口", "Avatar as Mine entry",
                "国际版顶栏头像原本无点击行为，开启后点击头像直达「我的」页面。仅适配 6.4.0");
        addSwitch(BiliConfig.KEY_HOME_TABBAR_RM_MSG, "底栏移除「消息」tab（实验）", "Remove message tab (beta)",
                "消息入口已上移到顶栏时移除底栏对应 tab。实验性：部分版本底栏\n"
                + "为 Compose 直出，可能不生效；改后需强停 B 站重开");
        addSwitch(BiliConfig.KEY_HOME_TABBAR_RM_MINE, "底栏移除「我的」tab（实验）", "Remove mine tab (beta)",
                "「我的」入口已上移到顶栏头像时从底栏隐藏该 tab（数据保留，\n"
                + "头像点击仍可打开完整「我的」页）。仅适配 6.4.0；改后需强停");

        section("听视频", "Listen mode");
        addSwitch(BiliConfig.KEY_LISTEN_PAUSE_AFTER_END, "听完此视频自动暂停", "Pause when current video ends",
                "听视频/迷你播放器播完当前视频后暂停，不自动切到下一集。\n"
                + "零监听实现：仅拦截播放完成动作入口，无额外耗电");

        section("隐藏互动提示", "Hide in-video prompts");
        addSwitch(BiliConfig.KEY_HIDE_TRIPLE, "隐藏一键三连", "Hide triple-like",
                "隐藏点赞/投币/收藏连击动画与提示文案");
        addSwitch(BiliConfig.KEY_HIDE_VOTE, "隐藏投票", "Hide votes",
                "隐藏互动弹幕投票面板（打分弹幕/投票）");
        addSwitch(BiliConfig.KEY_HIDE_UP_PROMPT, "隐藏 UP 提示", "Hide UP prompts",
                "隐藏关注引导气泡等 UP 提示");
        addSwitch(BiliConfig.KEY_PLAYER_NO_ACTIVITY_META, "干掉云视听小电视", "No activity overlay",
                "清掉弹幕回包里的 activity_meta 活动浮层（跑动的「云视听小电视」小电视形象）。\n"
                + "只在活动期服务端真下发时才有东西可清；日志会明确写出两种情况（清了几条 /\n"
                + "钩子进来了但这批本来就是空的），不会静默假生效。改后需强停 B 站重开");

        section("首页", "Home");
        addSwitch(BiliConfig.KEY_NO_AUTO_REFRESH, "首页不自动刷新", "No auto refresh on home",
                "从后台/其它页面切回首页时不自动重新加载推荐流（省流量、省电）；\n"
                + "手动下拉刷新、点击 tab、首次进入不受影响");
        addSwitch(BiliConfig.KEY_HOME_LIVE_TAB, "首页顶栏补回「直播」板块", "Unlock home live tab",
                "国际版首页顶栏的直播频道被服务端按身份裁掉了，板块本体随包就在\n"
                + "（路由 bilibili://live/home 与直播首页 fragment 都在 apk 里）。打开后在顶栏标题\n"
                + "与页面共用的收口处补回这一格（追加在末尾，既有页下标不动）；关闭即恢复原样。\n"
                + "改后需强停 B 站重开 / appended at the shared funnel that feeds both the titles "
                + "and the pager pages, so existing tab indices stay untouched");

        section("首页推荐流清洗", "Feed stream cleanup");
        addSwitch(BiliConfig.KEY_FEED_ONLY_UGC, "首页只展示用户上传作品（UGC）", "Show only UGC on home",
                "移除推荐流里的官方合集、活动、直播等非用户上传卡（判据 cardGoto=av）。\n"
                + "安全阀：若一整批卡都不匹配该判据（宿主改了口径），本批不过滤并在日志告警，\n"
                + "不会把首页清空。四项都只影响之后发出的流请求：屏幕上已经渲染好的卡片，\n"
                + "要下拉刷新/切页/重进首页才会变。改后需强停 B 站重开");
        addSwitch(BiliConfig.KEY_FEED_CLEAN_CARD, "干净的视频卡片", "Clean video cards",
                "卡片只留标题与 UP：去掉「推荐理由」角标和「竖屏」角标。\n"
                + "角标这些字段在 6.6.0 已被混淆成单字母，所以按服务端协议名定位、\n"
                + "不认 Java 字段名，宿主改名不会让功能静默失效；UP 入口沿用宿主自己的\n"
                + "名字行（实测点它本来就能进空间页），不再另补按钮。改后需强停 B 站重开");
        addSwitch(BiliConfig.KEY_FEED_NO_PORTRAIT, "禁止竖屏播放器", "No portrait player",
                "首页点竖屏视频时改用传统横屏播放器，不再进竖屏全屏（单击暂停）的新播放器。\n"
                + "只影响卡片跳转路由（story→video），播放页内的竖屏入口不变。改写只保留视频 id、\n"
                + "丢掉卡片自带的预载参数（实测带着它会让横屏播放页启动即崩），代价是这类卡起播\n"
                + "不走那条预载、多 P 稿件从第一 P 解析。改后需强停 B 站重开");
        addSwitch(BiliConfig.KEY_FEED_NO_LARGE_CARD, "关闭大卡片", "No large cards",
                "移除占满整屏宽度的卡（顶部轮播大图、单列大封面卡、内联播放卡），只留双列小卡。\n"
                + "判据是卡片类型名（banner_v / large_cover / inline_av），跟「只展示 UGC」互不\n"
                + "依赖：实测 6.6.0 上开着只展示 UGC 时大卡确实全没了（轮播卡 goto=banner、\n"
                + "大封面卡 goto=inline_av_v2，都不是 av 所以被顺手删掉），但那个开关同时还会\n"
                + "删直播卡和广告卡；只想去掉大卡、其它照旧，就开这一个。横幅广告卡不在本开关\n"
                + "判据内（它也有双列形态，按类型删会连双列广告一起删掉）。改后需强停 B 站重开");

        section("首页推荐分区屏蔽", "Feed partition blocker");
        LinearLayout tagRow = new LinearLayout(this);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);
        tagRow.setPadding(0, dp * 10, 0, dp * 10);
        LinearLayout tagCol = new LinearLayout(this);
        tagCol.setOrientation(LinearLayout.VERTICAL);
        TextView tagT1 = new TextView(this);
        int tagN = 0;
        for (String w : getSp().getString(BiliConfig.KEY_FEED_BLOCK_TNAMES, "").split(",")) {
            if (w.trim().length() > 0) tagN++;
        }
        tagT1.setText("屏蔽分区词管理（当前 " + tagN + " 个）");
        tagT1.setTextColor(Color.parseColor("#222222"));
        tagT1.setTextSize(16);
        tagCol.addView(tagT1);
        TextView tagT2 = new TextView(this);
        tagT2.setText("Manage blocked feed partitions");
        tagT2.setTextColor(Color.parseColor("#AAAAAA"));
        tagT2.setTextSize(11);
        tagCol.addView(tagT2);
        TextView tagT3 = new TextView(this);
        tagT3.setText("推荐卡的分区(tname)包含任一词即整卡移除。支持批量输入\n"
                + "（逗号/换行分隔）、检索定位、逐词移除；词数无上限；改后需强停 B 站重开");
        tagT3.setTextColor(Color.parseColor("#888888"));
        tagT3.setTextSize(12);
        tagCol.addView(tagT3);
        tagRow.addView(tagCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button tagBtn = new android.widget.Button(this);
        tagBtn.setText("管理");
        tagBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(SettingsActivity.this,
                        FeedTagSettingsActivity.class));
            }
        });
        tagRow.addView(tagBtn, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(tagRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        section("调试", "Debug");
        addSwitch(BiliConfig.KEY_DEBUG_ALIVE, "写存活标记", "Alive marker",
                "在 B 站 files 目录写 bili_tamer_alive.txt，便于无 logcat 时确认已加载");
        addSwitch(BiliConfig.KEY_VERBOSE, "详细日志", "Verbose log",
                "打印身份改写等详细日志（Tag: BiliTamer）");

        addSpace(dp * 20);
        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#0A7D32"));
        statusView.setTextSize(12);
        root.addView(statusView);
        addSpace(dp * 4);
        TextView foot = new TextView(this);
        foot.setText("\u26a0 本模块由 AI 生成，请自行评估风险。/ AI-generated; use at your own discretion.\n\n"
                + "首次使用：1) 在 LSPosed 启用本模块（作用域：哔哩哔哩国际版）；\n"
                + "2) 强制停止并重开 B 站即以出厂默认生效，无需任何配置或 root。\n"
                + "定制开关：root 设备会自动同步配置（dev 场景）；本地 conf 含 dev_override=true 时优先生效。");
        foot.setTextColor(Color.parseColor("#999999"));
        foot.setTextSize(12);
        root.addView(foot);

        persistAll(true);
        // 桥晚到兜底：冷启动注入稍慢时补推一次（幂等）
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                persistAll(true);
            }
        }, 1500);
    }

    private void section(String text, String textEn) {
        float den = getResources().getDisplayMetrics().density;
        int dp = Math.max(1, Math.round(den));
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#FB7299"));
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp * 8, 0, 0);
        root.addView(tv);
        TextView tve = new TextView(this);
        tve.setText(textEn);
        tve.setTextColor(Color.parseColor("#F2A9BC"));
        tve.setTextSize(11);
        tve.setPadding(0, 0, 0, dp * 8);
        root.addView(tve);
    }

    private void addSwitch(final String key, String title, String titleEn, String desc) {
        float den = getResources().getDisplayMetrics().density;
        final int dp = Math.max(1, Math.round(den));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp * 10, 0, dp * 10);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(Color.parseColor("#222222"));
        t1.setTextSize(16);
        textCol.addView(t1);

        TextView ten = new TextView(this);
        ten.setText(titleEn);
        ten.setTextColor(Color.parseColor("#AAAAAA"));
        ten.setTextSize(11);
        textCol.addView(ten);

        TextView t2 = new TextView(this);
        t2.setText(desc);
        t2.setTextColor(Color.parseColor("#888888"));
        t2.setTextSize(12);
        textCol.addView(t2);

        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textCol, textLp);

        Switch sw = new Switch(this);
        boolean def = BiliConfig.defaultValueOf(key);
        sw.setChecked(getSp().getBoolean(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                getSp().edit().putBoolean(key, isChecked).apply();
                persistAll(false);
            }
        });
        row.addView(sw, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addRadio(final String key, String title, String titleEn,
                          final String[] labels, final int[] values, String desc) {
        float den = getResources().getDisplayMetrics().density;
        final int dp = Math.max(1, Math.round(den));

        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(Color.parseColor("#222222"));
        t1.setTextSize(16);
        t1.setPadding(0, dp * 10, 0, 0);
        root.addView(t1);

        TextView ten = new TextView(this);
        ten.setText(titleEn);
        ten.setTextColor(Color.parseColor("#AAAAAA"));
        ten.setTextSize(11);
        root.addView(ten);

        final RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        final int def = BiliConfig.defaultIntOf(key);
        final int cur = getSp().getInt(key, def);
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTextColor(Color.parseColor("#333333"));
            rb.setTextSize(14);
            rb.setId(i + 1000);
            rb.setChecked(cur == values[i]);
            group.addView(rb, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup rg, int checkedId) {
                int idx = checkedId - 1000;
                if (idx < 0 || idx >= values.length) return;
                getSp().edit().putInt(key, values[idx]).apply();
                persistAll(false);
            }
        });
        root.addView(group);

        TextView t2 = new TextView(this);
        t2.setText(desc);
        t2.setTextColor(Color.parseColor("#888888"));
        t2.setTextSize(12);
        t2.setPadding(0, 0, 0, dp * 6);
        root.addView(t2);
    }

    private void addSpace(int px) {
        View v = new View(this);
        root.addView(v, new ViewGroup.LayoutParams(1, px));
    }

    private SharedPreferences getSp() {
        return getSharedPreferences(BiliConfig.PREFS_NAME, MODE_PRIVATE);
    }

    private void persistAll(boolean auto) {
        long gen = ConfSync.saveAll(this);
        String st;
        if (gen > 0) {
            // 无 root 主链路：仅用户手动改动（auto=false）才拉起 B 站投递配置；
            // auto（打开设置页/兜底补推）只落盘，避免打开设置页就切走前台。
            if (!auto) {
                ConfSync.launchTargetWithConf(this);
            }
            st = "状态：✅ 已保存（gen=" + gen + "）"
                    + (auto ? "" : "，正在拉起 B 站同步配置")
                    + "\nconf 探针见 B 站启动日志首行 confSrc=";
        } else {
            st = "状态：❌ 保存失败\nconf 探针见 B 站启动日志首行 confSrc=";
        }
        statusView.setText(st.toString());
        android.util.Log.i("BiliTamer", "persistAll: gen=" + gen + " auto=" + auto);
        if (!auto) {
            Toast.makeText(SettingsActivity.this,
                    gen > 0 ? "已保存，正在拉起 B 站同步配置" : "保存失败",
                    Toast.LENGTH_SHORT).show();
        }
    }
}
