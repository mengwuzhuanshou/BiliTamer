package com.tamer.bili.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/**
 * 首页推荐流清洗（v1.8.0）：把老版 play 国际版模块 MBGA（top.trangle.mbga，
 * github.com/cledwynl/mbga）的三项功能搬到 BiliTamer。
 *
 * MBGA 原版三条都挂在 {@code com.bilibili.pegasus.api.BaseTMApiParser} 的
 * {@code (JSONArray)->ArrayList} 解析方法 AFTER 上，操作 {@code modelv2} 卡片模型
 * （{@code BasicIndexItem.cardGoto} / {@code SmallCoverV2Item.rcmdReason…}）。
 * 6.6.0（9130300）dex 实证这套接缝与模型都已换代，故按「语义等价」重新定位：
 *
 *  ① 只展示 UGC（{@code feed_only_ugc}）：与 {@link FeedTagHooks} 同一个协议解析出口
 *     （6.6.0 = {@code com.bilibili.pegasus.request.h.a(okhttp3.E)->GeneralResponse}，
 *     由 {@code PegasusGsonParser.g} 委托；形状定位见 {@link FeedItems}）拿卡片列表，
 *     {@code cardGoto != "av"} 的整卡移除。列表元素两代不同：6.4.0 是
 *     {@code BasicIndexItem} 后代（public 字段 {@code cardGoto}），6.6.0 换成了 holder
 *     类族（{@code LXD0/a;} 等实现 {@code LWD0/a;}，只有 {@code getCardGoto()}，内部字段
 *     单字母）——所以属性读取统一走 {@link FeedItems#readProp}「getter 优先、字段兜底」。
 *  ② 干净的视频卡片（{@code feed_clean_card}）：按协议名清掉卡片角标——
 *     {@code rcmd_reason_style}/{@code left_bottom_rcmd_reason_style}（推荐理由，
 *     如「1万点赞」）与 {@code goto_icon}（类型就叫 {@code StoryCardIcon}，即用户名旁边
 *     的「竖屏」标）。**两类键的清法不一样，这是实机踩出来的**：图标键整对象置 null
 *     （宿主按对象有无决定那个图标 ViewStub 渲不渲染），理由键只能保留对象、清掉里面的
 *     {@code text} 与颜色/图标字段——把它们也整对象置 null 会让宿主连「推荐理由 + UP 名」
 *     那一整行都不渲染，标题下面变成一条空白（{@link #REASON_KEYS} 里记了 A/B 数）。
 *     把这条空白判成「数据层收不掉、得去钩渲染层」是我的计数口径错了：空容器在功能关闭轮更多，
 *     而当时屏幕渲染的是缓存批、日志打的是网络批，两批卡根本不相交（PITFALLS #48）。
 *     MBGA 还有半边「{@code desc_button} 为空时造一个 UP 名按钮」，这里**没有搬**，
 *     因为它在这代宿主是死写：数据类确实带 {@code desc_button} 字段（{@code XD0.u}
 *     的 {@code getDescButton()} 存在，写回也成功、日志一度打 {@code filled=4}），
 *     但 6.6.0 卡片布局里根本没有它的槽位——uiautomator 抓这张卡的底行只有
 *     {@code bottom_layout}/{@code real_desc}/{@code desc_v3}/{@code more}，
 *     一个 desc_button 节点都没有，屏上也确实什么都没多出来；dex 侧
 *     {@code getDescButton} 全仓只有 2 个调用点、都在 {@code onClick} 里，没有任何
 *     bind 路径把它摆上屏。而宿主自己的名字行（{@code desc_v3}）点击本来就进
 *     {@code LocalAuthorSpaceActivity}，UP 入口早已存在，补一个不可见的按钮纯属自欺
 *     （PITFALLS #42）。
 *     载体是 6.6.0 的活卡模型（gson 直解、协议名齐全、字段名被 R8 改成单字母），
 *     所以这一条**只能按 {@code @SerializedName} 定位并写回**
 *     （{@link FeedItems#fieldByJsonName}）。现场探针还定了两件事：不同 {@code card_type}
 *     用的是**不同的数据类**（{@code YD0.c}/{@code XD0.w}/{@code XD0.u}），UP 名/uid 不在
 *     {@code up} 而在 {@code args}（{@code up_name}/{@code up_id}），而 {@code desc}
 *     是名字行不是角标（直播卡 {@code desc} 就是主播名）——所以它不在清理之列。
 *     走过的弯路值得留着：先前只 dexdump 了 holder 类族 {@code LXD0/a;}，它的协议名集合里
 *     确实没有 {@code rcmd_reason}/{@code desc_button}，据此一度判定「这条数据面在新宿主
 *     已不存在、②只能空操作」；是现场 uiautomator 里那两张卡（角标 TextView 的
 *     resource-id 都叫 {@code desc}）把它推翻的——holder 只是渲染层包装，数据在它
 *     下层的活模型上。「类查得到字段」和「数据流经它」都要证，
 *     反过来「某个类没有该字段」也不能推出「数据不存在」（PITFALLS #39）。
 *  ③ 禁止竖屏播放器（{@code feed_no_portrait}）：把 {@code bilibili://story/<id>}
 *     改写成 {@code bilibili://video/<id>}，点竖屏卡即落进传统横屏播放器。只有一条实现：
 *     **数据层**改写卡片自己的 {@code uri} 协议字段（{@link #applyStoryRewrite}，与①②
 *     同一个解析出口，刷新/加载更多/预载提交三条路径一次覆盖）。原先配一条
 *     {@code BasicIndexItem.getUri()} AFTER 老宿主兜底，本轮已整条撤掉，理由写在
 *     {@link #applyStoryRewrite} 末尾。**代价明写**：这一项只在卡片带 {@code uri} 协议字段、
 *     且命中 {@link FeedItems} 那个解析出口的宿主上生效（实测 6.6.0）；老宿主上会静默不生效。
 *     这里的教训比结论值钱：原先只凭 dex 里「{@code CardClickProcessor} 会 new
 *     {@code BasicIndexItem} 并调 {@code getUri}」就认定挂父类一处即可覆盖 6.6.0，
 *     实测装钩后点竖屏卡、存活日志一条未出——那 40 个调用点确实存在，但都不在首页
 *     这条路径上。「谁调用它」是必要条件不是充分条件，要证的是**这条用户路径**会不会
 *     走到它（{@code dexcall callers} 给的是全集，不是这一条链）。
 *     第二课更贵：数据层改写**只换 scheme 前缀、原样保留 query** 时，落点确实换成了
 *     横屏播放页，但播放页在 {@code onCreate} 抛 Dagger 作用域递归把宿主直接崩掉
 *     （两张不同的竖屏卡各复现一次）。分离变量后：同一个 aid 走宿主自己的路由外壳，
 *     无论带不带那条近 4000 字符的预载 query 都正常；只有「卡片点击的上下文 + 带 query」
 *     才崩。所以这里写的是**目标页自己的最小路由**（只留 id，query 整段丢弃，
 *     见 {@link #idSegment}）；具体是哪个 extra 在打架并未反解出来，属于用干预证明的
 *     修法而不是用读代码证明的机制（PITFALLS #40）。
 *     验收口径也因此比①②更严：
 *     落点=横屏播放页 + 进程存活 + 崩溃缓冲 0 条，三条同查。
 *
 *  ④ 关闭大卡片（{@code feed_no_large_card}）：移除占满整屏宽度的卡（轮播 {@code banner_v8}、
 *     单列大封面 {@code large_cover_*}、内联播放 {@code inline_av}/{@code ad_inline_av}）。
 *     判据用 {@code card_type} 而不是①的 {@code card_goto}——现场实测两者重叠
 *     （{@code large_cover_v9} 的 goto 是 {@code inline_av_v2}，①本来就删得掉），这条留着
 *     是为了「只去大卡、别动直播/广告卡」这个①给不了的诉求，也为了两条判据互相备份；
 *     取证与代价见 {@link #applyNoLargeCard}。
 *
 * 四项都会改变用户能看到/点到什么，出厂默认关。①另有安全阀：一整批都不匹配 UGC
 * 判据时不过滤并告警（宿主改口径时最坏退化成「不生效」，不会首页空白）；
 * 单卡判据读不到按「未知」保留。
 */
public final class FeedCleanHooks {

    private static final String STORY_PREFIX = "bilibili://story/";
    private static final String VIDEO_PREFIX = "bilibili://video/";
    /** 现场探针一批最多看几张卡（一次性日志的上界）。 */
    private static final int PROBE_CARDS = 8;

    private final HookApi api;
    private final ClassLoader cl;

    /** ①的「整批都不像 UGC」只告警一次，避免每次刷新刷日志。 */
    private final AtomicBoolean ugcEmptyWarned = new AtomicBoolean(false);
    /** 直方图/锚点探针/降级告警/效果日志各只打一次（一次性诊断，非轮询）。 */
    private final AtomicBoolean gotoLogged = new AtomicBoolean(false);
    private final AtomicBoolean anchorProbed = new AtomicBoolean(false);
    private final AtomicBoolean cleanLogged = new AtomicBoolean(false);
    private final AtomicBoolean missWarned = new AtomicBoolean(false);
    private final AtomicBoolean storyLogged = new AtomicBoolean(false);
    /** 每批一条「这批改写了多少条 story 路由」，也只印一次。 */
    private final AtomicBoolean rewriteLogged = new AtomicBoolean(false);
    /** 第一条 story 卡的写回成败（一次性；静默失败和没进来必须能区分）。 */
    private final AtomicBoolean storyDiag = new AtomicBoolean(false);
    /** ④「移除后这批还剩哪些 card_type」：最多印 3 批，用来给漏网的跨列卡定名。 */
    private final java.util.concurrent.atomic.AtomicInteger largeHistBudget =
            new java.util.concurrent.atomic.AtomicInteger(3);

    public FeedCleanHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        installGroup("feed card filter (ugc only + clean card + story route + no large card)",
                new ThrowingAction() {
            @Override public void run() throws Throwable {
                installParseFilter();
            }
        });
        api.info("FeedCleanHooks installed");
    }

    private interface ThrowingAction {
        void run() throws Throwable;
    }

    private void installGroup(String name, ThrowingAction a) {
        try {
            a.run();
            api.info("feedclean: hook group ready: " + name);
        } catch (Throwable t) {
            api.error("feedclean: hook group unavailable: " + name, t);
        }
    }

    // ===== ① + ②：解析出口 AFTER 原地改写卡片列表 =====

    private void installParseFilter() throws Throwable {
        FeedItems.ParseEntry entry = FeedItems.findParseEntry(api, cl);
        if (entry == null) {
            api.error("feedclean: parse entry not found, ugc/clean-card disabled", null);
            return;
        }
        api.deoptimize(entry.method);
        api.addHook("feedclean: " + entry.owner.getName() + "." + entry.method.getName(),
                entry.method, new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        try {
                            boolean ugc = api.isFeedOnlyUgcEnabled();
                            boolean clean = api.isFeedCleanCardEnabled();
                            boolean noPortrait = api.isFeedNoPortraitEnabled();
                            boolean noLarge = api.isFeedNoLargeCardEnabled();
                            // 探针与开关无关地跑一次：关闭对照轮同样需要这份现场值域
                            if (ugc || clean || noPortrait || noLarge || !anchorProbed.get()) {
                                List<?> items = FeedItems.findItems(api, result);
                                if (items != null && !items.isEmpty()) {
                                    if (!anchorProbed.get()) {
                                        probeBatch(items);
                                    }
                                    if (noPortrait) {
                                        applyStoryRewrite(items);
                                    }
                                    if (noLarge) {
                                        applyNoLargeCard(items);
                                    }
                                    if (ugc) {
                                        applyOnlyUgc(items);
                                    }
                                    if (clean) {
                                        applyCleanCard(items);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            api.error("feedclean: card filter failed", t);
                        }
                        return result;
                    }
                });
    }

    /** ①：只留 cardGoto="av" 的卡（读不到判据的卡按未知保留）。 */
    private void applyOnlyUgc(List<?> items) {
        HashMap<String, Integer> hist = new HashMap<String, Integer>();
        int unknown = 0;
        int kept = 0;
        for (int i = 0; i < items.size(); i++) {
            String g = cardGotoOf(items.get(i));
            if (g == null) {
                unknown++;
                kept++; // 未知保留：失败模式退化成「少过滤」，而不是把首页清空
            } else {
                Integer c = hist.get(g);
                hist.put(g, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
                if ("av".equals(g)) {
                    kept++;
                }
            }
        }
        if (gotoLogged.compareAndSet(false, true)) {
            api.info("feedugc: cardGoto histogram " + hist + " unknown=" + unknown);
        }
        if (kept == 0 && !items.isEmpty()) {
            if (ugcEmptyWarned.compareAndSet(false, true)) {
                api.warn("feedugc: no card matched cardGoto=av in a batch of " + items.size()
                        + " (histogram " + hist + ") -> filter skipped; calibrate the UGC goto"
                        + " value from the histogram above");
            }
            return;
        }
        ArrayList<String> removedTypes = new ArrayList<String>();
        int removed = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            String g = cardGotoOf(items.get(i));
            if (g != null && !"av".equals(g)) {
                items.remove(i);
                removed++;
                if (removedTypes.size() < 6 && !removedTypes.contains(g)) {
                    removedTypes.add(g);
                }
            }
        }
        if (removed > 0) {
            api.info("feedugc: removed " + removed + " non-ugc card(s), goto in " + removedTypes);
        }
    }

    /** cardGoto：先下钻到数据对象，再按 getter→同名字段→协议名三路读（readProp 已含 card_goto）。 */
    private String cardGotoOf(Object item) {
        Object v = FeedItems.readProp(FeedItems.cardOf(item), "cardGoto");
        return v instanceof String ? (String) v : null;
    }

    /**
     * ④：移除占满整屏宽度的卡（轮播大图、单列大封面卡、内联播放卡）。
     *
     * 判据是 {@code card_type} 子串，不是卡片宽度，也不复用①的 {@code card_goto=="av"}——
     * 但**两者现场实测是重叠的**，这一点推翻了我最初的静态推断，值得留字：
     * 我原先按老一代 pegasus 的常识判定「{@code large_cover_v9} 是标准 UGC、
     * {@code card_goto} 就是 {@code av}，所以①挡不住它、必须另开一条判据」；装机后
     * ④的移除日志给出的是 {@code [large_cover_v9/inline_av_v2]}——这代宿主给这种卡打的
     * goto 是 {@code inline_av_v2}，**①本来就把它删了**（①开着连滑 5 屏、约 40 张卡，
     * uiautomator 里全是 521 宽的双列卡，一个跨列卡都没有）。
     *
     * 那这条开关为什么还留：①的口径是「只要 UGC」，会顺手删掉直播卡、广告卡和跨列内容卡，
     * 而「我不想首页有大卡，但直播/广告卡无所谓」是另一个诉求，①给不了；④只删跨列卡，
     * 双列卡一张不动。另外①的判据整个押在 {@code card_goto} 的取值上（服务端换口径就漂），
     * ④押在类型名上，两条互相备份。
     *
     * 三个子串是宿主 dex 里核过的跨列类型名（classes17 的类型常量表 + classes10/11）：
     * {@code banner_v*}（轮播，现场 {@code id/banner} 宽 1054/1080）、
     * {@code large_cover}（{@code large_cover_v7~v9}、{@code large_cover_single_v7~v13}、
     * {@code vertical_large_cover_v7/v9/v11}、{@code channel_detail_large_cover}）、
     * {@code inline_av}（{@code inline_av}、{@code ad_inline_av}）。双列卡类型名
     * {@code small_cover_v2}/{@code small_cover_v9}/{@code cm_v2}/{@code ogv_small_cover}
     * 一个都不含，不会误伤（现场直方图印证：移除后剩 {@code {small_cover_v2=6, cm_v2=1,
     * small_cover_v9=1}}）。
     *
     * **{@code cm_v2}（横幅广告卡）故意不纳入**：它现场也是整屏宽的（实测一张 1054x327，
     * 节点带 {@code disallow_slide}/{@code corner_hint}/{@code tag_img}），但它同时也会
     * 以双列小卡出现，按类型名删就等于替用户决定「广告一律删」——那归①管
     * （{@code cm_v2} 的 goto 是 {@code ad_av}/{@code ad_web_s}，非 av，①开着时实测会删）。
     *
     * 读不到 {@code card_type} 的卡按未知保留（与①同口径：失败模式退化成「少过滤」，
     * 不是把首页清空）。移除日志与「移除后本批还剩哪些类型」的直方图（前 3 批）都打：
     * 只印一次的话，用户往后滚动时「还在生效吗」在日志里就没有答案了，而漏网的跨列卡
     * 也永远定不了名。
     */
    private static final String[] LARGE_CARD_KEYS = {"banner_v", "large_cover", "inline_av"};

    private void applyNoLargeCard(List<?> items) {
        ArrayList<String> removedTypes = new ArrayList<String>();
        int removed = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            Object item = items.get(i);
            String type = cardTypeOf(FeedItems.cardOf(item));
            if (type == null || !isLargeCardType(type)) {
                continue;
            }
            items.remove(i);
            removed++;
            if (removedTypes.size() < 6 && !removedTypes.contains(type)) {
                removedTypes.add(type + "/" + cardGotoOf(item));
            }
        }
        if (removed > 0) {
            api.info("feedbig: removed " + removed + " large card(s) [" + removedTypes
                    + "] from a batch of " + items.size());
        }
        if (largeHistBudget.getAndDecrement() > 0) {
            // 移除**之后**的直方图：屏幕上量得到的跨列卡（宽度只有现场知道）如果还留在这张
            // 表里，它叫什么类型名就一目了然，判据据此补而不是靠猜。
            HashMap<String, Integer> hist = new HashMap<String, Integer>();
            for (int i = 0; i < items.size(); i++) {
                String t = cardTypeOf(FeedItems.cardOf(items.get(i)));
                String k = t == null ? "?" : t;
                Integer c = hist.get(k);
                hist.put(k, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
            }
            api.info("feedbig: card_type histogram left in this batch " + hist);
        }
    }

    /** card_type 读取（与探针同一路：getter 优先、字段兜底）。 */
    private String cardTypeOf(Object card) {
        Object v = FeedItems.readProp(card, "cardType");
        return v instanceof String ? (String) v : null;
    }

    private static boolean isLargeCardType(String type) {
        for (int i = 0; i < LARGE_CARD_KEYS.length; i++) {
            if (type.contains(LARGE_CARD_KEYS[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * ②：按**协议名**清掉卡片角标。
     *
     * 键表是现场打出来的（不是静态推的）。探针在 6.6.0 上看到三种卡型用的是**三个不同的
     * 数据类**（{@code YD0.c}=banner_v8、{@code XD0.w}=small_cover_v9/live、
     * {@code XD0.u}=small_cover_v2/av），每个类的 {@code @SerializedName} 集合都不同，
     * 与 MBGA 那代 {@code modelv2} 的对应关系按**类型名**而不是字段名认：
     *  - {@code rcmd_reason_style} / {@code left_bottom_rcmd_reason_style}（类型 {@code j}）
     *    = MBGA 的 {@code rcmdReason}，即「1万点赞」这类推荐理由；
     *  - {@code goto_icon}（类型就叫 {@code StoryCardIcon}）= MBGA 的
     *    {@code storyCardIcon}，即用户名旁边那个「竖屏」标；协议名换了、类型名没换，
     *    所以按 {@code story_card_icon} 这个键名去找是找不到的。
     *
     * **{@code desc} 不在清理之列**，这是现场推翻掉的一个想当然：它的值就是 real_desc 那行
     * 的名字（直播卡 {@code desc="小莫寝不足"} = 主播名），清了是删名字不是删角标。
     */
    /**
     * 图标键：宿主按**对象有无**决定那个「竖屏」图标 ViewStub 渲不渲染
     * （{@code ME0/x#onBind} 与 {@code kE0/e#j} 都是 {@code if (storyCardIcon == null)
     * viewStub.setVisibility(GONE)}），所以整对象置 null 正是想要的。
     */
    private static final String[] BADGE_KEYS = {"goto_icon", "story_card_icon"};

    /**
     * 推荐理由键：**不能整对象置 null**。现场 A/B 实测（同一台设备、同一入口、只翻这个开关）：
     * 四个键全置 null 时 39 张卡里 8 张的 {@code bottom_layout} 子节点数直接变成 0——
     * 也就是「推荐理由 + UP 名」这一整行不渲染了，用户看到的就是标题下面一条空白；
     * 关掉这一条只留图标键时 36 张卡 0 空白。宿主是用这个对象决定那一整行（含 UP 名）
     * 渲不渲染的，所以只能**保留对象、清掉它的内容字段**。
     */
    private static final String[] REASON_KEYS = {
            "rcmd_reason_style", "left_bottom_rcmd_reason_style",
    };

    /**
     * 推荐理由对象里要清的内容字段（{@code bE0.j} 的 {@code @SerializedName}，jadx 直读）。
     * 不含 {@code text_len}/{@code bg_style} 这类 int 字段——反射给 int 写 null 必然失败，
     * 而颜色与图标 URL 清空后它们已经没有可画的东西。
     */
    private static final String[] REASON_CONTENT_KEYS = {
            "icon_url", "icon_night_url", "icon_bg_url",
            "bg_color", "bg_color_night", "border_color", "border_color_night",
    };

    /** 一次性探针要看的键集（图标 + 理由，与清理口径无关）。 */
    private static final String[] PROBE_KEYS = {
            "rcmd_reason_style", "left_bottom_rcmd_reason_style", "goto_icon", "story_card_icon",
    };

    /**
     * 这张卡有没有 UP 名要显示。6.6.0 的名字在 {@code args.up_name}（不在 {@code up} 里，
     * 见 {@link #probeBatch}）。形状读不到时按「有名」处理：宁可留一条清空后的行，
     * 也不要误把有名字的行收掉。
     */
    private boolean hasUpName(Object card) {
        Object args = FeedItems.readJson(card, "args");
        if (args == null) {
            return true;
        }
        Object up = FeedItems.readProp(args, "upName");
        if (up == null) {
            return true;
        }
        return !(up instanceof String) || ((String) up).length() > 0;
    }

    private void applyCleanCard(List<?> items) {
        int stripped = 0;
        int rowKept = 0;
        int rowDropped = 0;
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (item == null) {
                continue;
            }
            Object card = FeedItems.cardOf(item);
            boolean touched = false;
            for (String key : BADGE_KEYS) {
                if (FeedItems.readJson(card, key) != null) {
                    touched |= FeedItems.writeJson(card, key, null);
                }
            }
            for (String key : REASON_KEYS) {
                Object tag = FeedItems.readJson(card, key);
                if (tag == null) {
                    continue;
                }
                if (hasUpName(card)) {
                    // 这行有 UP 名要留：保留对象、只清内容，否则宿主整行不渲染（见 #REASON_KEYS）
                    if (FeedItems.writeJson(tag, "text", "")) {
                        for (String paint : REASON_CONTENT_KEYS) {
                            FeedItems.writeJson(tag, paint, null);
                        }
                        touched = true;
                        rowKept++;
                    }
                } else {
                    // 这行除了标签没别的内容：整对象置 null 让宿主把行收掉，别留一条空白
                    if (FeedItems.writeJson(card, key, null)) {
                        touched = true;
                        rowDropped++;
                    }
                }
            }
            if (touched) {
                stripped++;
            }
        }
        if (stripped == 0) {
            if (missWarned.compareAndSet(false, true)) {
                api.warn("feedclean: clean-card found no badge key on this host build (see the"
                        + " anchor probe line above for the card's actual @SerializedName set)"
                        + " -> reporting a no-op instead of pretending");
            }
            return;
        }
        if (cleanLogged.compareAndSet(false, true)) {
            api.info("feedclean: clean-card applied, badge cleared on " + stripped
                    + " card(s) (batch " + items.size() + "); reason row kept with name=" + rowKept
                    + ", row dropped for no up_name=" + rowDropped);
        }
    }

    /**
     * 现场值域探针（整批只跑一次，与开关无关）：打出前 {@link #PROBE_CARDS} 张卡的
     * {@code card_type/card_goto/up有无/desc值}，外加第一张卡的完整
     * {@code @SerializedName} 清单。
     *
     * 为什么必须有：①的 UGC 判据（{@code card_goto=="av"}）和②要清哪几个键都是静态
     * 推出来的，真正的值域只有宿主自己知道。没有这几行日志，「屏幕没变」既可能是判据
     * 漂了、也可能是这批卡本来就没角标，两者无法区分；关闭对照轮同样需要这份数据。
     */
    private void probeBatch(List<?> items) {
        if (!anchorProbed.compareAndSet(false, true)) {
            return;
        }
        int n = Math.min(items.size(), PROBE_CARDS);
        String lastCls = null;
        for (int i = 0; i < n; i++) {
            Object item = items.get(i);
            if (item == null) {
                continue;
            }
            Object card = FeedItems.cardOf(item);
            String cls = card.getClass().getName();
            if (!cls.equals(lastCls)) {
                // 每种卡型各打一份键表：现场证明不同 card_type 用的是不同的数据类
                lastCls = cls;
                StringBuilder present = new StringBuilder();
                for (String key : PROBE_KEYS) {
                    if (FeedItems.hasJson(card, key)) {
                        if (present.length() > 0) {
                            present.append('+');
                        }
                        present.append(key);
                    }
                }
                api.info("feedclean: card class " + cls + " badge keys[" + present + "] jsonmap "
                        + FeedItems.jsonFieldMap(card));
            }
            Object args = FeedItems.readJson(card, "args");
            api.info("feedclean: card[" + i + "] cls=" + cls
                    + " type=" + FeedItems.readProp(card, "cardType")
                    + " goto=" + cardGotoOf(item)
                    + " title=" + brief(FeedItems.readProp(card, "title"))
                    + " argsUp=" + FeedItems.readProp(args, "upName")
                    + "/" + FeedItems.readProp(args, "upId")
                    + " name(desc)=" + FeedItems.readJson(card, "desc")
                    + " uri=" + brief(FeedItems.readJson(card, "uri"))
                    + " badges" + badgeValues(card));
        }
    }

    /**
     * 探针值截短：卡片 {@code uri} 里带着整条 playurl 预载（实测单值数千字节），日志行有长度
     * 上限，超了就把行尾**截掉**——截掉的正是排在后面的 {@code badges} 段，等于探针白跑一轮。
     * {@code title} 用同一个函数截，因为它要和界面 dump 对得上，全文也没人读。
     */
    private static String brief(Object v) {
        if (v == null) {
            return "null";
        }
        String s = String.valueOf(v);
        return s.length() <= 48 ? s : s.substring(0, 48) + "...(" + s.length() + ")";
    }

    /**
     * 逐个角标键的**现值**（一次性诊断）：字符串直接印，复杂对象只印类名，null 印 {@code -}。
     *
     * 为什么要印值：②要清哪几个键是静态推的，「键存在」不够——{@code desc} 这一代既可能是
     * 「竖屏」文案，也可能是直播卡的主播名（现场实证：{@code goto=live} 的卡
     * {@code desc=奶斯菟-Lop}）。只有把值打出来，才能区分「这个键在这一代就是角标」和
     * 「同名键被别的卡型拿去放别的数据」。
     */
    private String badgeValues(Object card) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < PROBE_KEYS.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            Object v = FeedItems.readJson(card, PROBE_KEYS[i]);
            sb.append(PROBE_KEYS[i]).append('=');
            if (v == null) {
                sb.append('-');
            } else if (v instanceof String) {
                sb.append('"').append(v).append('"');
            } else {
                sb.append("obj(").append(v.getClass().getSimpleName())
                        .append(":text=").append(FeedItems.readJson(v, "text")).append(')');
            }
        }
        return sb.append('}').toString();
    }

    /**
     * ③ 的主实现：在解析出口把卡片自带的 {@code uri} 里的 {@code bilibili://story/<id>}
     * 改写成 {@code bilibili://video/<id>}，点竖屏卡即落进传统横屏播放器。
     *
     * 为什么写在数据层而不是点卡路径上：先前配过一条 {@code BasicIndexItem.getUri()} AFTER
     * 的老宿主兜底，它在 6.6.0 上**运行时实测从未被调用**（一次点卡之后存活日志一条没出，
     * 尽管 dex 里它有 40 个调用点——「有人调」不等于「首页这条路径调」，PITFALLS #40 的第一
     * 课），而老宿主那一代始终**拿不到实机读数**：验它要把用户的宿主降级、清掉他的登录与
     * 本地数据。一个「不可验 + 上一代已证明会把播放页点崩」的改写挂法留在包里，装的就是未知
     * 风险，所以整条撤了。代价明写：③ 只在卡片带 {@code uri} 协议字段、且命中
     * {@link FeedItems} 那个解析出口的宿主上生效（实测 6.6.0）；老宿主上会静默不生效，
     * 由 {@link #installParseFilter} 的「parse entry not found / no badge key」告警暴露。
     * 写在数据层还有额外好处：①②③共用同一个出口，刷新/加载更多/预载提交三条路径一次覆盖；
     * 首页卡已是 {@code XD0.u/YD0.c/XD0.w} 这套数据类，路由本来就从卡片的 {@code uri}
     * 协议字段直接取。
     */
    private void applyStoryRewrite(List<?> items) {
        int rewritten = 0;
        for (int i = 0; i < items.size(); i++) {
            Object card = FeedItems.cardOf(items.get(i));
            Object v = FeedItems.readJson(card, "uri");
            if (!(v instanceof String)) {
                continue;
            }
            String uri = (String) v;
            if (!uri.startsWith(STORY_PREFIX)) {
                continue;
            }
            // 只取 id 段，把 query 整段丢掉：竖屏卡带来的 player_preload/player_height
            // 是 story 播放器那套形状，带着它改写会让横屏播放页
            // （UnitedBizDetailsActivity）在 onCreate 里炸 Dagger 作用域递归、宿主直接闪退；
            // 只留 bilibili://video/<id> 的最小路由实测能干净打开同一个 aid。
            String fixed = VIDEO_PREFIX + idSegment(uri);
            Field f = FeedItems.fieldByJsonName(card, "uri");
            boolean ok = false;
            String why = "field not found";
            if (f != null) {
                try {
                    f.set(card, fixed);
                    ok = true;
                    why = "written";
                } catch (Throwable t) {
                    why = String.valueOf(t);
                }
            }
            // 第一条 story 卡无条件印一次成败：上一轮这条路径既没印成功也没印异常，
            // 「静默失败」和「根本没进来」这两种情况在日志里长得一模一样。
            if (storyDiag.compareAndSet(false, true)) {
                api.info("feedportrait: first story card cls=" + card.getClass().getName()
                        + " write=" + ok + " why=" + why + " final="
                        + java.lang.reflect.Modifier.isFinal(f == null ? 0 : f.getModifiers()));
            }
            if (ok) {
                rewritten++;
                if (storyLogged.compareAndSet(false, true)) {
                    api.info("feedportrait: story uri rewritten on the data layer to the legacy"
                            + " player route, e.g. " + fixed);
                }
            }
        }
        if (rewritten > 0 && rewriteLogged.compareAndSet(false, true)) {
            api.info("feedportrait: " + rewritten + " story route(s) rewritten in this batch ("
                    + items.size() + " cards)");
        }
    }

    /** story uri 的 id 段：{@code bilibili://story/<id>?...} 里只留 {@code <id>}。 */
    private static String idSegment(String uri) {
        String rest = uri.substring(STORY_PREFIX.length());
        int q = rest.indexOf('?');
        return q < 0 ? rest : rest.substring(0, q);
    }
}
