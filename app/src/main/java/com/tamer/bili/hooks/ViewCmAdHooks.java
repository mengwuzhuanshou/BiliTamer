package com.tamer.bili.hooks;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 干掉「播放器下方广告」——这是身份改写带进来的国内版行为。
 *
 * 6.7.1 真机证到这块界面有三条腿：{@code ViewReply.cm_.cm_under_player}（下挂广告卡本体）、
 * 同一条 {@code CM} 里的 {@code sourceContentItem*}（实机那张商品卡走的是这条，见
 * {@link #installDetailAdLegs}），以及简介区楼层广告 RPC {@code FloorAdSearch}。同一开关下三条都拦。
 * 另有简介区「视频提及」的游戏推广卡，它不在 {@code CM} 里，是
 * {@code Module.videoMentions}，同开关下第四组钩子（{@link #installVideoMentionLegs}）。
 *
 * 因果链（6.7.1 静态）：IP 属地/稿件可见性那套把 {@code mobi_app} 从 {@code android_i} 报成
 * 国内版（REST 侧 {@code android}），服务端就当国内版发回包，而「播放器下方广告」这个槽位只在
 * 国内版的排期里。也就是说这块广告是**本模块引入的副作用**，所以对应开关出厂默认开（与其余
 * 「删宿主自己界面」的开关默认关相反）。moss 二进制头后来换成 {@code android_hd}（PITFALLS §55：
 * 那一档下「视频提及」游戏卡不下发），但下挂卡/楼层广告两腿不能依赖取值，钩子照旧留着。
 * 这条因果装机已证到（荣耀，宿主 6.7.1，同一条稿件「热门枕头从夯到拉排行榜，
 * 带你极速避…」/ 简介区显示 2026年9月22日 14:22，2026-10-28 三格对照，每格都是杀进程冷启动后重进，
 * 每格用 400px 小步长滚 8 屏逐屏 dump——步长大于模块高度会把这一段整段跳过去，造成假阴性）：
 *  ① 身份改写 ON + 本开关 OFF ⇒ 「视频提及」段与星途天城游戏卡在场（前两屏
 *    {@code 视频提及}=1、{@code ivGameIcon}=1、{@code 星途天城}=2）。这一格是「不拦就看得见」
 *    的正样本，缺它其余两格都不算数；同一条稿件的这一格里 {@code underplayer_container} 在 8 屏
 *    里都是 0，所以它只给「视频提及」腿作证，下挂卡的正样本另见 {@link #installDetailAdLegs}。
 *  ② 身份改写 ON + 本开关 ON ⇒ 8/8 屏三项计数全 0，同一次冷启动的 pid 指纹
 *    {@code 7ff3-1a180c7fbbf-1-e3/e4} 打出 {@code blocked source_content_item list (items=1)}
 *    ＋ {@code dropped 1 game mention card(s)} ＋ {@code reporting the whole section absent}；
 *  ③ 身份改写 OFF + 本开关 OFF（完全不干预，既不改身份也不拦广告）⇒ 同一条稿件 8/8 屏同样三项全 0，
 *    详情页照常渲染。第三格是关键：它排除了「这条稿件本来没广告」的另一种解释，
 *    说明卡的存在由 {@code mobi_app} 改写触发，而不是稿件自带的推广位。
 *
 * 数据面（6.7.1 dex 直读）：稿件详情回包
 * {@code com.bapis.bilibili.app.viewunite.v1.ViewReply} 只有一个广告字段 {@code cm_}
 * （类型 {@code viewunite.v1.CM}），{@code CM} 里各槽位分别是
 * {@code cmUnderPlayer_}(播放器下方) / {@code cmHalfPanel_}(半屏面板) /
 * {@code adsControl_} / {@code padRelateCm_} / {@code overseasAdInfo_}(国际版自己的广告) /
 * {@code sourceContent*}；{@code PagesReply}、{@code ViewBase} 都没有广告字段。
 * 读这个槽位的全 apk 只有三处（{@code dexcall callers}）：{@code theseus/ugc/ad/DetailAdService}
 * （下挂广告卡本体）、{@code PausedPageService}（暂停页）、{@code UgcAiRelateAsyncRepository}。
 * 本钩子动 {@code cmUnderPlayer_}、{@code padRelateCm_}、{@code cmHalfPanel_} 和两个
 * {@code sourceContent*} 列表（即 {@code DetailAdService} 往播放器下方槽位塞素材的全部读点）
 * ＋楼层广告那条腿；{@code CM} 的其余槽位（含国际版自有广告 {@code overseasAdInfo}）一律不动。
 *
 * 抑制手法＝**改返回值**，不是清字段：{@code hasCmUnderPlayer()} 直接回 {@code false}、
 * {@code getCmUnderPlayer()} 直接回 {@code Any.getDefaultInstance()}。三个读方法都是纯读，
 * 渲染侧（上面那三处）拿到的就是「没这个槽位」，跟服务端真没下发时长得一样。
 * 为什么不用 {@code clearCmUnderPlayer()}：① protobuf-lite 的消息一旦 {@code makeImmutable}
 * 就改不动（抛 {@code UnsupportedOperationException}），能不能清取决于回包怎么被构造，
 * 这条路是运气；② 字段没设时 {@code getCm()} 返回全局共享的 {@code DEFAULT_INSTANCE}，
 * 对着它清等于改全局单例。改返回值这两条都不沾，也不需要 {@code setAccessible}。
 *
 * 锚点为什么落在「读时」而不是「回包时」（6.7.1 装机五轮试出来的，别再走回头路）：
 *  ① 先按 {@code KViewMoss$view$$inlined$suspendCall$1} 这类回调类猜——Kotlin 每个调用点各自内联，
 *    带默认参数的 {@code view(...)} 走的是 {@code …$suspendCall$default$1}，是另一个类；
 *  ② 再把三个候选类的 {@code onNext}/{@code onNextForAck} 全挂上（日志 12 条 ready），
 *    稿件页照常渲染，两条首触日志一条没有；
 *  ③ 于是改挂全 apk 唯一的 moss 回包分发口 {@code Is1.m#onNext(byte[])}（现场读真回调类，
 *    闸门用反序列化策略的 {@code SerialDescriptor.serialName}）——钩子确实进活
 *    （{@code ad: moss dispatcher fired}），但三次冷启动开新稿件，它只见到
 *    {@code KDownloadReply / KPlayConfReply / KGetTotalUnreadReply / KUserPreferenceReply}
 *    四条小 RPC，**一条 ViewReply 都没有**：这版的 viewunite 详情回包不走 moss 那条 KMP 回调链。
 *  ④ 改挂读方法（v1.9.3）。锚点本身成立：{@code ViewReply.getCm()} 在 06:07:25 打过
 *    {@code probe read#1 via reply.getCm cm_under_player=absent}，而同一时刻前后有 8 条
 *    {@code View} RPC 出去——所以这个读**不是每条回包一次**，而是「广告路径真去查这条回包时」
 *    才一次，正是我们要拦的那一下。
 *  ⑤ 但这版真机给的样本是负的：23:14:34.612（荣耀）详情页 Activity 起来，381 ms 后打出
 *    {@code ad: view reply reached the under-player ad path (cm=absent)}，而截图那张播放器下方的
 *    商品卡（STN工作室 BV1g3H56mEiH，「券后价¥254.15」）照旧在——它不走 {@code cm_under_player} 槽位。
 *    当时静态追到的候选是简介区楼层广告（⑥推翻了这个归因）：{@code MerchandiseService#r} 构造 {@code FloorAdSearchReq}，经
 *    {@code ViewMoss.executeFloorAdSearch}（{@code ViewuniteMossKtxKt} 的
 *    {@code floorAdSearch(req, handler)}）发出，回包 {@code FloorAdSearchReply{item_, tab_}} 里的
 *    {@code FloorAdSearchItem{sourceContent_: Any}} 交给
 *    {@code com.bilibili.ad.adview.videodetail.merchandise.AdMerchandiseView} 渲染；这三枚 getter
 *    的全 apk 消费者只有 {@code MerchandisePanelComponent$bindContent$result$1}（其余命中是
 *    proto 生成的 Builder 转发）。同一开关下给这条腿补三枚同手法的读抑制
 *    （{@link #installFloorAdHooks}）。
 *    两件没证到的事别读成证到了：⑴ 「这条 RPC 与稿件详情共用 moss 服务名 {@code View}、于是被
 *    {@code isViewService} 按包名前缀一并武装成国内身份」只有静态依据——小米同一条 BV 上宿主
 *    根本没发这个请求（{@code verbose_log} 下改写行只有 {@code svc=View method=View}、
 *    {@code Player method=PlayViewUnite}、{@code PlayURL method=PlayConf}），没有可对照的调用；
 *    ⑵ 卡是否真由这条腿渲染，判据只能回荣耀那条稿件取：小米（同 6.7.1、同出厂默认）既没有 {@code cm}
 *    也没有楼层请求，同一稿件两台表现相反，说明这一层按现场（账号/画像）下发。
 *    {@code cm_under_player} 那条腿仍留着：它至今只有一次负样本、没有正样本，别据此判它有没有用。
 *  ⑥ ⑤的归因错了，错在「没查渲染侧还有没有别的入口」。荣耀 BV1aeiH 之外那条枕头视频
 *    （2026-10-27 21:21 现场，pid 1472 与装钩进程同一个）UI 树是
 *    {@code underplayer_container → ad_tint_frame → title/tv_download_status}，即
 *    {@code ad/adview/videodetail/upper/a#k} 这个 under-player 槽位；而楼层广告三枚钩子在该进程
 *    零读数、{@code hasCmUnderPlayer} 也已强制 false，卡照旧在。反汇编
 *    {@code DetailAdService$1$2#invokeSuspend} 才看清：同一个方法里 {@code hasCmUnderPlayer}
 *    （unit 920）之前还有一条 {@code padRelateCm} 腿（unit 169 起），它也往同一个槽位塞样式
 *    （{@code Dd/*}、{@code Ed/*}，判据是 {@code Card.getCardType()}/{@code getCardStyle()}），
 *    而且根本不看 {@code hasCmUnderPlayer}。教训：抑制「存在性」判假只覆盖了它自己那条分支，
 *    「卡还在」不能反推「钩子没生效」，先把同一个渲染槽位的全部写入方数出来再归因。
 *    补齐见 {@link #installDetailAdLegs}。
 *
 * 观测口径：① {@code getCm()} 只留一条「广告路径读到了回包」的首触日志（带 CM 是不是
 * DEFAULT_INSTANCE，用来分「这条回包压根没有 cm」和「有 cm 但没这个槽位」）；
 * ② 真拦下一次（{@code hasCmUnderPlayer} 原值为真）留一条首触日志并带上广告体指纹
 * （{@code Any.typeUrl}）——这是判「拦掉的到底是不是那块的素材」唯一能拿到的现场依据；
 * ③ 原值为假也留一条首触：广告按稿件内容下发，不是每条稿件都有，少了这行，「这批本来就没有」
 * 和「钩子没跑」在屏幕上长得一模一样（DmActivityMetaHooks 的装机教训）；
 * ④ 楼层广告那条腿同口径：真拦下（{@code getItemList()} 原值非空）留一条带条数的首触，
 *    原值为空也留一条首触；这组不给探针抽行——它只在开详情页时跑几次，不值得再加分支。
 * {@code probe_enabled} 下再按读数抽行（命中必打，其余每 25 次一条，预算 12）：一次性首触
 * 分不清「钩子只跑了一次」和「每条稿件都没这个槽位」，而热路径不能刷行。
 */
public final class ViewCmAdHooks {

    private static final String REPLY_CLZ =
            "com.bapis.bilibili.app.viewunite.v1.ViewReply";
    private static final String CM_CLZ =
            "com.bapis.bilibili.app.viewunite.v1.CM";
    /** 广告体类型（装机日志里读到的真实签名：{@code public Any getCmUnderPlayer()}）。 */
    private static final String ANY_CLZ = "com.google.protobuf.Any";
    /** {@code CM.padRelateCm_} 的类型——屏幕上那张商品卡的真正来源（见 {@link #installDetailAdLegs}）。 */
    private static final String PAD_RELATE_CLZ =
            "com.bapis.bilibili.app.viewunite.v1.PadRelateCM";

    /** 「视频提及」那一族（见 {@link #installVideoMentionLegs}）：简介区里游戏推广卡的数据面。 */
    private static final String MODULE_CLZ =
            "com.bapis.bilibili.app.viewunite.common.Module";
    private static final String MENTIONS_CLZ =
            "com.bapis.bilibili.app.viewunite.common.VideoMentions";
    private static final String MENTION_CLZ =
            "com.bapis.bilibili.app.viewunite.common.Mention";
    private static final String MENTION_TYPE_CLZ =
            "com.bapis.bilibili.app.viewunite.common.MentionType";

    /** 楼层广告回包三兄弟（6.7.1 dex 直读，签名逐条核过：
     *  {@code List getItemList()} / {@code FloorAdSearchTab getTab()} /
     *  {@code Any getSourceContent()}，全部 {@code public} 无参）。 */
    private static final String FLOOR_REPLY_CLZ =
            "com.bapis.bilibili.app.viewunite.v1.FloorAdSearchReply";
    private static final String FLOOR_ITEM_CLZ =
            "com.bapis.bilibili.app.viewunite.v1.FloorAdSearchItem";
    private static final String FLOOR_TAB_CLZ =
            "com.bapis.bilibili.app.viewunite.v1.FloorAdSearchTab";

    /** viewunite 系类懒加载：安装期没就绪就延后重试（与 IpLocationHooks / DmActivityMeta 同法）。 */
    private static final long RETRY_DELAY_MS = 3000L;
    private static final int RETRY_MAX = 5;

    private final HookApi api;
    private final ClassLoader cl;

    private final AtomicBoolean sawReplyLogged = new AtomicBoolean(false);
    private final AtomicBoolean blockedLogged = new AtomicBoolean(false);
    private final AtomicBoolean emptyLogged = new AtomicBoolean(false);
    private final AtomicBoolean floorBlockedLogged = new AtomicBoolean(false);
    private final AtomicBoolean floorEmptyLogged = new AtomicBoolean(false);
    /** 每条腿各留一次首触：共用一枚 CAS 会让第二条腿拦到了也没记录（IpLocationHooks 同法）。 */
    private final java.util.Set<String> loggedLegs =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** 读数计数（探针行用），以及探针自己的预算。 */
    private final AtomicInteger readCount = new AtomicInteger();
    private final AtomicInteger probeBudget = new AtomicInteger(12);

    public ViewCmAdHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        installRetry(0);
    }

    private void installRetry(final int attempt) {
        try {
            installNow();
            return;
        } catch (ClassNotFoundException e) {
            if (attempt >= RETRY_MAX) {
                api.error("ad: viewunite reply classes still absent after " + RETRY_MAX
                        + " retries -> switch is a no-op on this host build", e);
                return;
            }
            api.debug("ad: viewunite reply classes not loaded yet (attempt " + (attempt + 1) + ")");
        } catch (Throwable t) {
            api.error("ad: hook group unavailable: view cm under-player ad", t);
            return;
        }
        try {
            api.postDelayed(new Runnable() {
                @Override public void run() {
                    installRetry(attempt + 1);
                }
            }, RETRY_DELAY_MS);
        } catch (Throwable t) {
            api.warn("ad: retry scheduling failed: " + t);
        }
    }

    private void installNow() throws Throwable {
        final Class<?> cmCls = api.load(cl, CM_CLZ);
        final Method hasUnder = api.publicMethod(cmCls, "hasCmUnderPlayer");
        final Method getUnder = api.publicMethod(cmCls, "getCmUnderPlayer");
        final Object emptyAny = api.invoke(
                api.publicMethod(api.load(cl, ANY_CLZ), "getDefaultInstance"), null);
        final Object cmDefault = api.invoke(
                api.publicMethod(cmCls, "getDefaultInstance"), null);

        // ① 观测锚点：广告路径有没有真去查这条回包。零干预，只留一条首触。
        Class<?> reply = api.load(cl, REPLY_CLZ);
        Method getCm = api.publicMethod(reply, "getCm");
        api.deoptimize(getCm);
        api.addHook("ad: under-player observation (reply.getCm)", getCm,
                new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    if (api.isPlayerNoUnderAdEnabled() && sawReplyLogged.compareAndSet(false, true)) {
                        Object cm = chain.proceed();
                        api.info("ad: view reply reached the under-player ad path (cm="
                                + (cm == cmDefault ? "absent" : "present") + ")");
                        return cm;
                    }
                } catch (Throwable t) {
                    api.error("ad: under-player observation failed", t);
                }
                return chain.proceed();
            }
        });

        // ② 抑制锚点 A：存在性判假——三处渲染侧都是 if (hasX()) 再取值，这一层就够。
        api.deoptimize(hasUnder);
        api.addHook("ad: under-player presence", hasUnder, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                boolean real = Boolean.TRUE.equals(chain.proceed());
                if (!api.isPlayerNoUnderAdEnabled()) {
                    return real;
                }
                note(real, "hasCmUnderPlayer");
                return Boolean.FALSE;
            }
        });

        // ③ 抑制锚点 B：兜一层「不看存在性、直接取值」的读法，顺带留广告体指纹。
        api.deoptimize(getUnder);
        api.addHook("ad: under-player payload", getUnder, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object real = chain.proceed();
                if (!api.isPlayerNoUnderAdEnabled()) {
                    return real;
                }
                if (real != emptyAny) {
                    api.info("ad: blocked cm_under_player (payload=" + typeUrl(real) + ")");
                    return emptyAny;
                }
                return real;
            }
        });

        // ④ DetailAdService 的其余广告腿（屏幕上那张卡的真身，见方法注释）。
        installDetailAdLegs(cmCls, emptyAny);

        // ⑤ 楼层广告那条腿：FloorAdSearch（见 installFloorAdHooks 的原因说明）。
        installFloorAdHooks(emptyAny);

        // ⑥ 简介区「视频提及」里的游戏推广卡（见 installVideoMentionLegs 的原因说明）。
        installVideoMentionLegs();
    }

    /**
     * 简介区「视频提及」里的游戏推广卡。荣耀 2026-10-28 13:2x 现场（「枕头推荐2026」详情页，
     * 开关已是默认的开）：{@code ComposeView}[0,1714][1216,2071] 内
     * {@code ivGameIcon/tvGameTitle/tvHotTag/tvGameSubTitle/downloadContainer/tvDownload}
     * ＝「星途天城 / 专属礼包 / 下载」，而 {@code underplayer_container}、
     * {@code ad_tag_2}、{@code ad_desc} 全为 0——它不走 {@code CM} 任何广告槽位，
     * 所以上面五组钩子一条都拦不到它。
     *
     * 数据腿（6.7.1 dex 直读）：{@code Module.videoMentions -> VideoMentions.mentions[]}，
     * 每个 {@code Mention}＝{@code title + m_type(MentionType) + cards[]}，{@code cards} 是
     * oneof（base/game/toy/course/charge_ugv/charge_manju/lottery/ogv/streamer）。
     * {@code Mention#getGame()} 的业务读点只有 {@code GameVideoMentionedComponent.<init>}
     * （@unit 189/200/211/222），建卡工厂是 {@code biligame.videocard.j#a(Mention,…)}/
     * {@code viewunite…videomentioned.seed.s#f(Mention)}；{@code VideoMentionsReply.getMentions()}
     * 那条独立 RPC 全 apk 只有 Builder 转发，业务侧不走它。
     *
     * 只摘 {@code MENTION_TYPE_GAME}：同一枚举里的 {@code COURSE/CHARGE_UGC/CHARGE_MANJU/TOY/
     * LOTTERY/OGV/STREAMER} 是 UP 自己的课程、充电、手办链接，属正文，不算广告，不动。
     *
     * 两枚钩子必须配套，缺一条就露馅：{@code theseus/ugc/intro/j#a} 的渲染条件是
     * {@code if (module.hasVideoMentions())}，随后
     * {@code n.a(videomentioned.m.a(vm), vm.getMentionsList())}——除了存在性以外不判任何条件。
     * 只过滤列表会让这段剩一个光杆标题，所以 {@code getMentionsList()} 摘掉 game 条目，
     * {@code hasVideoMentions()} 再在「摘完就空」时回 false，让整段不进页面。
     */
    private void installVideoMentionLegs() {
        try {
            final Class<?> moduleCls = api.load(cl, MODULE_CLZ);
            final Class<?> mentionsCls = api.load(cl, MENTIONS_CLZ);
            final Class<?> mentionCls = api.load(cl, MENTION_CLZ);
            final Class<?> typeCls = api.load(cl, MENTION_TYPE_CLZ);
            final Method getList = api.publicMethod(mentionsCls, "getMentionsList");
            final Method hasMentions = api.publicMethod(moduleCls, "hasVideoMentions");
            final Method getMentions = api.publicMethod(moduleCls, "getVideoMentions");
            final Method mType = api.publicMethod(mentionCls, "getMTypeValue");
            final Object gameCase = api.declaredField(typeCls, "MENTION_TYPE_GAME").get(null);
            final int gameNum = ((Integer) api.invoke(
                    api.publicMethod(typeCls, "getNumber"), gameCase)).intValue();

            api.deoptimize(getList);
            api.addHook("ad: video mention list", getList, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    java.util.List<?> real = (java.util.List<?>) chain.proceed();
                    if (!api.isPlayerNoUnderAdEnabled() || real.isEmpty()) {
                        return real;
                    }
                    int dropped = 0;
                    java.util.List<Object> kept = new java.util.ArrayList<Object>(real.size());
                    for (Object m : real) {
                        if (isGameMention(m, mType, gameNum)) {
                            dropped++;
                        } else {
                            kept.add(m);
                        }
                    }
                    if (dropped == 0) {
                        return real;
                    }
                    logLegOnce("mention", "ad: dropped " + dropped
                            + " game mention card(s) from 视频提及 (" + kept.size()
                            + " non-game left)");
                    return kept;
                }
            });

            api.deoptimize(hasMentions);
            api.addHook("ad: video mention presence", hasMentions,
                    new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    boolean real = Boolean.TRUE.equals(chain.proceed());
                    if (!real || !api.isPlayerNoUnderAdEnabled()) {
                        return real;
                    }
                    java.util.List<?> kept;
                    try {
                        kept = (java.util.List<?>) api.invoke(getList,
                                api.invoke(getMentions, chain.getThisObject()));
                    } catch (Throwable t) {
                        return real;   // 读不动就维持原状：宁可留广告，不能把正文弄没
                    }
                    if (!kept.isEmpty()) {
                        return real;
                    }
                    logLegOnce("mention-empty", "ad: 视频提及 carried only game cards"
                            + " -> reporting the whole section absent");
                    return Boolean.FALSE;
                }
            });
        } catch (Throwable t) {
            api.error("ad: hook group unavailable: video mention legs", t);
        }
    }

    /** {@code Mention.m_type == MENTION_TYPE_GAME}？反射读不动时按「不是游戏卡」处理（保正文）。 */
    private boolean isGameMention(Object mention, Method mType, int gameNum) {
        try {
            Object v = api.invoke(mType, mention);
            return v instanceof Integer && ((Integer) v).intValue() == gameNum;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@code DetailAdService} 读 {@code CM} 的其余四条腿（6.7.1 反汇编逐条钉过）。
     *
     * 带货的是 {@code CM.getSourceContentItemList()}（{@code 0485/04cf/04fa -> AdMossHelper.m}），
     * 同 aid 配对钉死（aid 117487303662221，2026-10-28）：开关 OFF 时瑞幸商品卡落在
     * {@code ad/adview/videodetail/upper/a} 的 {@code underplayer_container}[0,818][1216,1063]，
     * 开关 ON 时同一次进入整块容器都不加，同 pid 只有一行
     * {@code blocked source_content_item list (items=1)}——即「列表回空 ⇒ 整卡不加」，
     * 与 {@code das.txt} 里 0571/0577/0582 三处 {@code isEmpty()} 直接跳到 return、
     * 不触 {@code AdUpperScene.UNITE_DETAIL} 的反汇编一致。
     *
     * 两条曾经怀疑过的腿在本机都是空跑，钩子留着只为覆盖别的机型/分支：
     * {@code hasCmUnderPlayer} 每次回 false（{@code cm=absent}），
     * {@code getPadRelateCm} 在本进程压根没被调用过（{@code pad}/{@code pad-empty} 两行
     * 一条都没有，而同进程的 {@code source_content_item} 钩子跑了）——上一版据此把它写成
     * 「真腿」，是错的。楼层广告同理，卡还在时 {@code hasCmUnderPlayer} 已被强制 false。
     *
     * 半屏面板（{@code hasCmHalfPanel}/{@code getCmHalfPanel}）与两个 {@code sourceContent*}
     * 列表同属这个方法；后两者的额外消费者只有 {@code PausedPageService}（暂停页广告）和
     * {@code UGCEndPageRelatedRecommendService}（播完页），都是广告位，不存在误伤正文的路径。
     * 全 apk 读方法之外的命中是 proto 生成的 {@code CM$b} 转发，不是业务代码。
     */
    private void installDetailAdLegs(Class<?> cmCls, final Object emptyAny) {
        final Class<?> padCls;
        final Object padDefault;
        try {
            padCls = api.load(cl, PAD_RELATE_CLZ);
            padDefault = api.invoke(
                    api.publicMethod(padCls, "getDefaultInstance"), null);
        } catch (Throwable t) {
            api.info("ad: PadRelateCM unavailable on this host"
                    + " -> relate-ad leg left alone (" + t.getClass().getSimpleName() + ")");
            return;
        }
        try {
            final Method getPad = api.publicMethod(cmCls, "getPadRelateCm");
            final Method hasHalf = api.publicMethod(cmCls, "hasCmHalfPanel");
            final Method getHalf = api.publicMethod(cmCls, "getCmHalfPanel");
            final Method getAnyList = api.publicMethod(cmCls, "getSourceContentList");
            final Method getItemList = api.publicMethod(cmCls, "getSourceContentItemList");

            api.deoptimize(getPad);
            api.addHook("ad: relate-ad payload", getPad, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object real = chain.proceed();
                    if (!api.isPlayerNoUnderAdEnabled()) {
                        return real;
                    }
                    if (real != padDefault) {
                        logLegOnce("pad", "ad: blocked pad_relate_cm (the merchandise card"
                                + " under the player) -> reporting it absent");
                        return padDefault;
                    }
                    // 空样本也要留一条：否则「这条腿本来就空」和「钩子压根没跑」在日志里
                    // 长得一样，卡还在就又是一轮无据可查的猜测（本文件锚点史③的教训）。
                    logLegOnce("pad-empty", "ad: padRelateCm hook is live but this reply carried"
                            + " no padRelateCm -> if the card is still there it comes from a leg"
                            + " we don't read yet");
                    return real;
                }
            });

            api.deoptimize(hasHalf);
            api.addHook("ad: half-panel presence", hasHalf, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    boolean real = Boolean.TRUE.equals(chain.proceed());
                    if (api.isPlayerNoUnderAdEnabled()) {
                        if (real) {
                            logLegOnce("half", "ad: cm_half_panel is present on this host"
                                    + " -> reporting it absent");
                        }
                        return Boolean.FALSE;
                    }
                    return real;
                }
            });

            api.deoptimize(getHalf);
            api.addHook("ad: half-panel payload", getHalf, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object real = chain.proceed();
                    if (api.isPlayerNoUnderAdEnabled() && real != emptyAny) {
                        logLegOnce("half-any", "ad: blocked cm_half_panel (payload="
                                + typeUrl(real) + ")");
                        return emptyAny;
                    }
                    return real;
                }
            });

            suppressList(getAnyList, "source_content");
            suppressList(getItemList, "source_content_item");
        } catch (Throwable t) {
            api.error("ad: hook group unavailable: detail ad legs", t);
        }
    }

    /** {@code getSourceContent*List()} 回空表：三个消费者都只遍历，拿到的就是「没这批广告位」。 */
    private void suppressList(Method m, final String slot) throws Throwable {
        api.deoptimize(m);
        api.addHook("ad: " + slot + " list", m, new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object real = chain.proceed();
                if (!api.isPlayerNoUnderAdEnabled()) {
                    return real;
                }
                int n = sizeOf(real);
                if (n > 0) {
                    logLegOnce("list:" + slot, "ad: blocked " + slot + " list (items=" + n
                            + ") -> reporting it empty");
                    return java.util.Collections.emptyList();
                }
                return real;
            }
        });
    }

    /** 每条腿一次一行的首触记录（同一文案模板，不另开观测面）。 */
    private void logLegOnce(String leg, String msg) {
        if (!loggedLegs.add(leg)) {
            return;
        }
        api.info(msg);
    }

    /**
     * 简介区「商品」楼层广告：{@code FloorAdSearch} 是一条独立 RPC（请求由
     * {@code MerchandiseService#r} 构造，走 {@code ViewMoss.executeFloorAdSearch}，
     * 与稿件详情共用同一个 moss 服务名 {@code View}——这正是它被身份改写一并武装成国内版的原因），
     * 回包不经过 {@code ViewReply.cm_}，上面三枚钩子对它零作用。
     *
     * 拦法仍是改返回值：{@code getItemList()} 回空表（渲染侧
     * {@code MerchandisePanelComponent$bindContent$result$1} 只读这三个 getter，拿到的就是「没这一层」），
     * {@code getSourceContent()} 回 {@code Any} 默认实例兜住已取出的条目，{@code getTab()} 回默认实例
     * 免得只剩一条空 tab 条。
     *
     * 整段单独吞异常：旧宿主没这几个类时只声明放弃，绝不能把 {@code ClassNotFoundException} 抛回
     * {@code installRetry}——那会让上面三枚 cm 钩子重复注册一遍。
     *
     * 装机侧目前只证到「注册」：23:52:46.750（小米 6.7.1、{@code confSrc=defaults}）三条
     * {@code hook installed: ad: floor …} 打出的签名与 dex 直读逐字一致，读数仍为零——那条稿件在这台
     * 不发这个请求。「卡消失」这个判据还没拿到，只能在真出卡的现场取。
     */
    private void installFloorAdHooks(final Object emptyAny) {
        final Class<?> floorReply;
        final Class<?> floorItem;
        final Class<?> floorTab;
        try {
            floorReply = api.load(cl, FLOOR_REPLY_CLZ);
            floorItem = api.load(cl, FLOOR_ITEM_CLZ);
            floorTab = api.load(cl, FLOOR_TAB_CLZ);
        } catch (ClassNotFoundException e) {
            api.info("ad: FloorAdSearch classes absent on this host"
                    + " -> merchandise floor left alone");
            return;
        } catch (Throwable t) {
            api.error("ad: hook group unavailable: floor ad", t);
            return;
        }
        try {
            final Object emptyTab = api.invoke(
                    api.publicMethod(floorTab, "getDefaultInstance"), null);
            final Method getList = api.publicMethod(floorReply, "getItemList");
            final Method getTab = api.publicMethod(floorReply, "getTab");
            final Method getSrc = api.publicMethod(floorItem, "getSourceContent");

            api.deoptimize(getList);
            api.addHook("ad: floor list", getList, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object real = chain.proceed();
                    if (!api.isPlayerNoUnderAdEnabled()) {
                        return real;
                    }
                    int n = sizeOf(real);
                    if (n > 0) {
                        if (floorBlockedLogged.compareAndSet(false, true)) {
                            api.info("ad: blocked FloorAdSearch floor (items=" + n + ")");
                        }
                        return java.util.Collections.emptyList();
                    }
                    if (floorEmptyLogged.compareAndSet(false, true)) {
                        api.info("ad: floor hook is live but this reply carried no items"
                                + " -> nothing to block on this batch (ads are scheduled per"
                                + " video, so a clean sample proves nothing)");
                    }
                    return real;
                }
            });

            api.deoptimize(getSrc);
            api.addHook("ad: floor payload", getSrc, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object real = chain.proceed();
                    if (api.isPlayerNoUnderAdEnabled() && real != emptyAny) {
                        api.info("ad: blocked floor ad payload (type=" + typeUrl(real) + ")");
                        return emptyAny;
                    }
                    return real;
                }
            });

            api.deoptimize(getTab);
            api.addHook("ad: floor tab", getTab, new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object real = chain.proceed();
                    if (api.isPlayerNoUnderAdEnabled() && real != emptyTab) {
                        return emptyTab;
                    }
                    return real;
                }
            });
        } catch (Throwable t) {
            api.error("ad: hook group unavailable: floor ad install", t);
        }
    }

    /** {@code getItemList()} 的条数，只为区分「空表」与「真拦下一批」；不遍历、不缓存。 */
    private static int sizeOf(Object list) {
        return list instanceof java.util.List ? ((java.util.List<?>) list).size() : -1;
    }

    /** 存在性读数：真拦下一次留一条，没素材也留一条首触，{@code probe_enabled} 下再抽行。 */
    private void note(boolean real, String src) {
        int n = readCount.incrementAndGet();
        if (api.isProbeEnabled() && (real || n % 25 == 1) && probeBudget.getAndDecrement() > 0) {
            api.info("ad: probe read#" + n + " via " + src
                    + " cm_under_player=" + (real ? "SET" : "absent"));
        }
        if (real) {
            if (blockedLogged.compareAndSet(false, true)) {
                api.info("ad: cm_under_player is present on this host -> reporting it absent"
                        + " (the under-player ad card has no payload to render)");
            }
        } else if (emptyLogged.compareAndSet(false, true)) {
            api.info("ad: under-player hook is live but this reply had no cm_under_player"
                    + " -> nothing to block on this batch (ads are scheduled per video, so a"
                    + " clean sample proves nothing; check a video that does show one)");
        }
    }

    /** 广告体指纹（{@code Any.typeUrl}）：静态侧只能证到字段名，这是判「拦掉的是不是那块的素材」
     *  唯一能拿到的现场依据。{@code Any} 自己的方法没挂钩子，不会再进来一次。 */
    private String typeUrl(Object any) {
        try {
            return String.valueOf(api.invoke(
                    api.publicMethod(any.getClass(), "getTypeUrl"), any));
        } catch (Throwable t) {
            return "<unreadable:" + t.getClass().getSimpleName() + ">";
        }
    }
}
