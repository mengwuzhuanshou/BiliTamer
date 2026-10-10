# BiliTamer — 哔哩哔哩国际版增强模块 / Enhancement module for the international Bilibili app

> ## ⚠️ AI-generated module / 本模块由 AI 生成
> 本项目由大语言模型（AI）在人类指导下生成，包括全部 Hook 代码、设置界面、构建流水线与文档。
> 代码未经人工长期审计，请自行评估风险后使用；欢迎人工审查与 PR。
>
> This project was generated and iterated by a large language model (AI) under
> human direction, including all hook code, the settings UI, the build pipeline
> and these docs. The code has not been long-term audited by humans — evaluate
> the risk yourself; human review and PRs are welcome.

> 与哔哩哔哩公司无任何关联；B 站相关商标与版权归原厂所有。仅供学习与研究 Android Hook 技术。
> Not affiliated with Bilibili Inc.; trademarks and copyrights belong to their owners.
> For learning and research on Android hooking techniques only.

面向**国际版哔哩哔哩** `com.bilibili.app.in`（实测适配 **6.3.0 / 6.4.0 / 6.5.0 / 6.6.0**）的 LSPosed 模块。
An LSPosed module for the **international Bilibili app** (`com.bilibili.app.in`, tested against **6.3.0 / 6.4.0 / 6.5.0 / 6.6.0**).

---

## 功能一览 / Features

| 功能 Feature | 说明 Description | 默认 Default |
| --- | --- | --- |
| 评论/主页 IP 属地 IP location | 把请求身份改写为国内版客户端，服务端返回 location 字段，评论区「IP属地：」与主页 IP 标签随之显示 / Rewrite request identity to the domestic client so the server returns the location field (comment-area "IP location" and profile IP tag) | 开 on |
| 身份声明范围 Identity scope | 评论区限定：仅评论/字幕请求声明国内版身份，其余请求保持国际版；或全局（旧行为）/ Scoped: declare the domestic identity for comment & subtitle requests only; or global (legacy behavior) | 评论区限定 scoped |
| 解码顺位 Decoder preference | AV1 > HEVC > H264 自动顺位，或锁定某一种（含**锁定 H264** 兜底档），或**关闭（不干预）**；自动顺位按设备硬解能力过滤请求位（先清后设，宿主已置的位也会被移除），不能硬解的编码不下发（只过滤请求，不替换解码）/ AV1 > HEVC > H264 auto preference, or lock one (incl. **lock H264** fallback) or **off (untouched)**; auto mode explicitly clears + re-sets request bits by hw-decode capability (bits the host already set are also removed) so undecodable codecs aren't delivered (filter only, no substitution) | 自动 auto |
| 音质顺位 Audio preference | 杜比全景声 > Hi-Res > AAC 自动顺位，或锁定，或**关闭（不干预）** / Dolby > Hi-Res > AAC auto preference, or lock, or **off (untouched)** | 自动 auto |
| HDR 画质顺位 HDR preference | HDR Vivid > HDR > SDR 自动顺位，或锁定/强制关闭，或**关闭（不干预）** / HDR Vivid > HDR > SDR auto preference, or lock/force-off, or **off (untouched)** | 自动 auto |
| 听视频听完暂停 Pause after video | 听视频（全屏音频播放器）播完当前视频即暂停，不自动连播（零监听实现）/ Pause when the current video ends in the listen-mode fullscreen audio player instead of auto-advancing (zero-listener implementation) | 关 off |
| 隐藏互动提示 Hide interaction hints | 一键三连动画/文案、投票面板、UP 关注引导气泡 / Hide triple-action animation, vote panel and follow-bubble hints | 关 off |
| 首页不自动刷新 No home auto-refresh | 从后台/其它页面切回首页时不自动重载推荐流；下拉/点 tab/首次进入不受影响 / Skip the automatic feed reload when returning to the home page; manual refresh unaffected | 关 off |
| 首页顶栏消息入口 Top-bar message entry | 顶栏搜索栏右侧加消息图标，未读红点带数字 / A message icon on the top bar with an unread numeric badge | 开 on（6.4.0） |
| 首页头像→我的 Avatar as Mine entry | 顶栏头像点击投一发宿主自己的「按路由切页」动作，打开完整「我的」页（与底栏这一格画不画出来无关）/ Avatar tap dispatches the host's own route action to open the full Mine page — works whether or not the bottom bar draws that slot | 开 on（6.4.0） |
| 首页直播板块 Live channel unlock | 国际版顶栏默认没有「直播」栏：服务端按身份裁剪下发的 tab 列表。解锁即把 `bilibili://live/home` 追加进顶栏页列表（标题与 pager 同源，不动其它 tab）/ The server trims the live channel out of the tab list for intl identities; this appends it back into the top-bar page list (titles and pager share one list) | 开 on |
| 底栏删 tab Bottom-bar tabs | 移除「消息」tab、隐藏「我的」tab / Remove Message tab, hide Mine tab | 开 on（6.4.0） |
| 首页推荐分区屏蔽 Feed partition blocker | 按推荐卡分区标签（tname）整卡屏蔽；词表批量输入/检索/逐词移除，无上限 / Block feed cards by partition tag; bulk-edit/search/remove word list, no limit | 空词表不生效 |
| 首页只展示 UGC UGC-only feed | 移除官方合集/活动/直播等非用户上传卡（判据 `cardGoto=av`）；整批都不匹配时不过滤并告警，不会清空首页 / Drop non-UGC feed cards; a batch with no match is left untouched instead of blanking the feed | 关 off |
| 干净的视频卡片 Clean video cards | 去掉卡片上的「竖屏」「1万点赞」这类角标文字与推荐理由，UP 入口沿用宿主自己的名字行（实测点它本来就进空间页）；锚点按服务端协议名定位，宿主改名不至于静默失效 / Strip the "portrait" and "10k likes" style badges and recommendation reasons from feed cards; the UP entry stays the host's own name line, which already opens the author space. Anchors resolve by protocol name so a host rename can't fail silently | 关 off |
| 禁止竖屏播放器 No portrait player | 首页竖屏卡的跳转路由 `bilibili://story/<id>` 改写为 `bilibili://video/<id>`，落进传统横屏播放器；只保留 id、丢掉卡片自带的预载参数段（带着它会让横屏播放页在启动时崩，见 PITFALLS #40）/ Rewrite story routes on feed cards to the landscape player's own minimal route (id only — the card's preload query is dropped, because keeping it crashes the target page; see PITFALLS #40) | 关 off |
| 关闭大卡片 No large cards | 按 `card_type` 移除占满整屏宽度的卡（轮播 `banner_v*`、大封面 `large_cover_v*`、内联播放 `inline_av_*`），双列小卡与直播/广告卡不受影响 / Drop full-width feed cards (carousel, large-cover, inline-play) by `card_type`; two-column small cards, live and ad cards are untouched | 关 off |
| 干掉云视听小电视 No activity overlay | 清空弹幕回包 `DmViewReply` 里的 `activity_meta` 活动浮层素材（视频内下发的活动挂件）/ Clear the `activity_meta` activity-overlay material from the dm reply | 关 off |
| 配置同步 Config sync | 设置保存经启动投递+host-conf 代次协议生效，不依赖 root / Settings delivered at launch with a host-conf generation protocol — no root needed | — |

所有开关独立可逆；总开关关闭后模块完全休眠。
Every switch is independently reversible; the master switch disables the whole module.

## 环境要求 / Requirements

* 已 root 的 Android 设备：Magisk 或 KernelSU + Zygisk + LSPosed / rooted device with Zygisk + LSPosed;
* 国际版哔哩哔哩 6.3.0 / 6.4.0 / 6.5.0 / 6.6.0（com.bilibili.app.in）/ international Bilibili 6.3.0 / 6.4.0 / 6.5.0 / 6.6.0.

## 使用方法 / Installation

1. 安装 APK，LSPosed 中启用模块，作用域勾选「哔哩哔哩国际版」/ install the APK, enable the module and select the Bilibili scope;
2. 强制停止哔哩哔哩后重新打开 / force-stop Bilibili and reopen;
3. 设置入口：LSPosed 模块详情页，或桌面「B站国际版增强」图标 / open settings from the LSPosed module page or the launcher icon;
4. 保存设置会自动拉起 B 站并即时生效（配置经启动投递，无需 root）；若 B 站已在运行，保存后会自动投递到前台实例 / saving settings auto-launches/re-delivers the config to Bilibili without root.

### 实现要点 / How the identity rewrite works

* 评论/字幕走 KMP moss gRPC：拦截图库「moss-common-headers」拦截器取 service/method，
  proceed 前打 ThreadLocal 标记；6.5.0 起主改写点为真名类 grpc 上下文的二进制头写入口
  `kntr.base.moss.ignet.impl.grpc.c.f`（参数替换），把
  `x-bili-metadata-bin`/`x-bili-device-bin` 里 mobiApp 字节从 `android_i` 改为 `android`
  （protobuf 变长长度前缀同步重建）。描述符族随构建整族换名（6.4.0 `Zq1.*` / 6.5.0 `kr1.*`
  / 6.6.0 `xr1.*`），按「名字提示 + 形状兜底」双路定位。6.3.0/6.4.0 时代的提供者级兜底
  （`up1.a.a()`、`kr1.a.a()`、`mq0.a/oq0.a`）已从代码移除：dex 实证它们在 6.5.0 就已不存在
  或被 R8 复用成无关类，留着只会产生重试与误导性 ERROR
  / Comment & subtitle RPCs are scoped via the moss-common-headers interceptor: before
  `chain.proceed()` the service/method is read and a ThreadLocal marker set. Since 6.5.0
  the main rewrite point is the stable, real-named binary-header write entry
  `kntr.base.moss.ignet.impl.grpc.c.f` (argument replacement) which rewrites the mobiApp
  protobuf bytes in `x-bili-metadata-bin`/`x-bili-device-bin` (`android_i` → `android`,
  rebuilding the varint length prefix). The descriptor family renames as a whole per build
  (6.4.0 `Zq1.*` / 6.5.0 `kr1.*` / 6.6.0 `xr1.*`) and is resolved by name hints plus shape
  validation; the legacy provider-level fallbacks were dropped once dex showed the classes
  were gone or reused by R8;
* 空间页走 REST：6.4.0 身份在 URL 参数里（`mobi_app=android_i`），hook 空间页 API 专属
  拦截器的 `addCommonParam` 改写之——天然按页面定域。6.3.0 锚点为 okretro 公共参数注入点
  `XA0.a` / Profile pages go through REST: on 6.4.0 the identity is a URL parameter
  (`mobi_app=android_i`), rewritten via the space-API-specific interceptor's
  `addCommonParam` — scoped to the space page by construction. The 6.3.0 anchor is the
  okretro common-param injection point `XA0.a`;
* 只重写 `android_i`→`android`，不触碰 android_hd；心跳/播放等其它服务保持国际版身份
  （日志可验证：每条改写行伴随同线程 armed 行）/ Heartbeats and other services keep the
  international identity — every rewrite line is paired with a same-thread "armed" line in the log;
* 机制、坑与校准方法详见 [PITFALLS.md](PITFALLS.md) / See PITFALLS.md for the full mechanism notes.

## 为什么用 libxposed API / Why libxposed

经典 `IXposedHookLoadPackage` 在 B 站主进程可能以 webview 名义触发（classLoader 错误），
或被厂商进程管理冻结导致不触发；libxposed 的 `onPackageReady` 直接给出正确 classLoader，
一次注入成功。打包声明用 `META-INF/xposed/java_init.list` + `module.prop` + `scope.list`，
manifest 不写 classic xposedmodule metadata。
The classic API is unreliable on the Bilibili main process (webview-provider triggers, vendor
process freezing). libxposed's `onPackageReady` delivers the right classLoader in one shot.

## 从源码构建 / Build from source

    python tools/build_module.py

* 无 Gradle / Android SDK 依赖：javac(--release 8, 编译桩) → dalvik-dx → 手写 AXML → zip(arsc 对齐) → apksig v1+v2+v3 签名，约 30 秒
  / No Gradle or Android SDK: javac(--release 8 with compile stubs) → dalvik-dx → hand-written AXML → aligned zip → apksig v1+v2+v3, ~30 s;
* 构建引擎完整内置于 `tools/engine/`（含 AOSP dx 与 apksig 两个 Apache-2.0 jar，见其 NOTICE.md），
  libxposed API jar 在 `tools/libxposed/`（io.github.libxposed:api 102.0.0，Apache-2.0，见其 PROVENANCE.md）
  / The engine is fully vendored in tools/engine/ (AOSP dx + apksig jars, Apache-2.0 — see NOTICE.md);
  the libxposed API jar is in tools/libxposed/ (io.github.libxposed:api 102.0.0, Apache-2.0 — see PROVENANCE.md);
* 需要 JDK（`BILITAMER_JDK` 或 `JAVA_HOME`，或 PATH 上有 javac/java）
  / Requires a JDK (BILITAMER_JDK or JAVA_HOME, or javac/java on PATH);
* 签名密钥经 gitignored 的 `tools/signing.local` 提供（KS_PATH=/KS_PASS=/KS_ALIAS=），仓库不含任何密钥；
  自行生成：`keytool -genkeypair -v -keystore my.jks -alias mymod -keyalg RSA -keysize 2048 -validity 10000`
  / The keystore is supplied via gitignored tools/signing.local — the repo contains no keys;
* 产物：`dist/BiliTamer-v<版本>.apk` / output at dist/BiliTamer-v<ver>.apk.

## 项目结构 / Layout

    BiliTamer/
    ├── module_conf.py                  # 构建配置（包名/版本/入口/图标）/ build config
    ├── apk/                            # 随仓库提交的发布 APK（CI 取此打包）/ committed release APK for CI
    ├── tools/
    │   ├── build_module.py             # 一键构建入口 / one-shot build entry
    │   ├── engine/                     # 自包含构建引擎（builder/axml/arsc/SignApk/dx/apksig/编译桩）/ vendored engine
    │   ├── libxposed/                  # libxposed API jar（Apache-2.0）+ PROVENANCE.md
    │   ├── signing.local               # 签名密钥（gitignored）/ keystore pointer (gitignored)
    │   └── icon/ic_launcher.png
    ├── app/src/main/
    │   ├── assets/xposed_init          # classic 入口占位（libxposed 模式下构建时不打包）/ classic placeholder
    │   └── java/com/tamer/bili/
    │       ├── MainHook.java           # libxposed 入口 / entry (XposedModule.onPackageReady)
    │       ├── BiliConfig.java         # 配置加载 / config loading
    │       ├── hooks/
    │       │   ├── HookApi.java            # hook 统一封装 / libxposed wrapper
    │       │   ├── IpLocationHooks.java    # 评论/主页 IP 属地 / scoped IP location
    │       │   ├── PlayerCodecHooks.java   # 解码/音质/HDR 顺位 / codec & audio & HDR preference
    │       │   ├── ListenPauseHooks.java   # 听视频听完暂停 / pause after video
    │       │   ├── InteractHintHooks.java  # 隐藏互动提示 / hide interaction hints
    │       │   ├── FeedCleanHooks.java     # 首页推荐流四项 / the four home-feed rewrites
    │       │   ├── DmActivityMetaHooks.java # 干掉云视听小电视 / clear dm activity_meta
    │       │   ├── FeedTagHooks.java       # 首页推荐分区屏蔽 / feed partition blocker
    │       │   └── HomeNoAutoRefreshHooks.java # 首页不自动刷新 / no home auto-refresh
    │       └── ui/SettingsActivity.java    # 纯代码设置界面 / code-only settings UI
    └── PITFALLS.md                     # 实现笔记与坑 / implementation notes & pitfalls

## 排查 / Troubleshooting

* LSPosed 日志过滤 `BiliTamer`：安装成功有 `hooks installed` 与各 hook `ok` 行
  / filter `BiliTamer` in LSPosed logs; look for `hooks installed` and per-hook `ok` lines;
* 详细日志开关打开后可见 `kmp header value rewritten` / `rest params rewritten` 等改写细节
  / enable verbose logging for rewrite details;
* 功能不生效：确认开关已开、作用域勾选、强停重开；升级 B 站后混淆锚点（`up1.a`/`XA0.a` 等）可能
  漂移导致静默失效，以日志为准 / after app upgrades the obfuscated anchors may drift silently —
  trust the logs, and see PITFALLS.md for calibration.

## 已知限制 / Known limitations

* 仅适配实测版本 6.3.0 / 6.4.0 / 6.5.0 / 6.6.0；其它版本需自行校准混淆锚点 / tested against
  these versions only;
* 国际版评论区目前没有广告；横幅等广告仅在使用全局身份声明（v1.2 旧行为）时出现，
  默认的评论区限定模式无此副作用 / The international comment area currently has no ads;
  banner ads only appear when the legacy global identity declaration is used — the default
  scoped mode has no such side effect;
* 首页四项（只展示 UGC／干净卡片／禁止竖屏／关闭大卡片）挂在推荐流的协议解析出口上：只影响**之后
  发出的流请求**，屏幕上已经渲染好的那批卡要等下拉刷新/切页/重进首页才会被改写 /
  The four home-feed switches act at the protocol parse exit, so they only affect
  subsequent feed requests — cards already rendered change after a pull-to-refresh,
  a tab switch or a relaunch;
* 「关闭大卡片」按 `card_type` 子串判跨列卡，值域只在 6.6.0 上取过现场样本；服务端换卡型命名后
  这一项会退化成「什么都不删」（读不到 `card_type` 的卡按未知保留，不会误删）/ The large-card
  removal matches `card_type` substrings sampled on 6.6.0 only; if the server renames card types
  it degrades to removing nothing (cards without a readable `card_type` are kept, not guessed
  away);
* 「干掉云视听小电视」只证到**清空**：挂点命中、`activity_meta` 条目被清掉都有实机日志，但浮层
  从屏幕上消失需要在活动期拿同一稿件肉眼 A/B，本版未做，不声称已证 / The activity-overlay switch
  is verified down to *clearing* the field (hook hit and cleared entries are in the log); the
  visual disappearance was not A/B'd on the same video during an active campaign, so it is
  not claimed;
* 「禁止竖屏播放器」丢掉卡片自带的预载参数段（这是不崩的前提），因此竖屏卡在横屏播放页
  起播时不走那条预载、分 P 固定从第一 P 解析 / Dropping the preload query (required to keep
  the target page from crashing) means those videos start without that preload hint and
  resolve from part 1;
* 「禁止竖屏播放器」实测只在 6.6.0 上验证过生效：它改的是卡片自己的 `uri` 协议字段，
  宿主换代到不流经该解析出口的那代卡片模型时，这一项会**静默不生效**（日志会给
  parse-entry not found 告警）。先前为 6.4.0/6.5.0 配过一条 `BasicIndexItem.getUri` 兜底，
  因无法在不降级宿主的前提下取到实机读数、且同形的「保留 query」改写在 6.6.0 已证明会把
  播放页点崩，已整条撤掉 / The no-portrait rewrite is verified on 6.6.0 only; on hosts whose
  cards don't carry a `uri` protocol field it stays inert (the parse-entry warning says so).
  The former 6.4.0/6.5.0 fallback hook on `BasicIndexItem.getUri` was removed: unverifiable
  without downgrading the host, and the same shape of rewrite crashed the player page;
* IP 属地依赖服务端策略，属风控敏感功能，是否显示由服务端决定 / the IP-location display is
  server-controlled and risk-control sensitive;
* 主页 IP 标签依赖账号与服务端返回，个别页面可能无该字段 / the profile IP tag depends on the server response.

## 鸣谢 / Acknowledgments

| 项目 / Project | 贡献 / Contribution | 链接 / Link |
| --- | --- | --- |
| **BiliFix** (com.xjw.bilifix.in) | 身份声明思路与 libxposed 打包范式的启蒙参考（本模块与其无代码派生关系）/ the inspiration for the identity-declaration approach and libxposed packaging (no code derived from it) | https://github.com/xiaojiuwo233/BiliFix |
| **libxposed/api** | 现代 Xposed API / the modern Xposed API | https://github.com/libxposed/api |
| **MBGA** (top.trangle.mbga) | 首页三项功能（只展示 UGC／干净卡片／禁止竖屏）与「干掉云视听小电视」的语义来源：本版按 6.6.0 的新接缝与新城载体重写，未复用其代码；「关闭大卡片」是本版自己加的（判据 `card_type`）/ the origin of the three home-feed features and the activity-overlay switch's semantics — reimplemented against this host's own seams and card models, no code reused; the large-card switch is this module's own addition (matched by `card_type`) | https://github.com/cledwynl/mbga |
| **AOSP dx / apksig** | 构建链组件（Apache-2.0）/ build-chain components | https://android.googlesource.com |

## 许可证 / License

MIT © mengwuzhuanshou，详见 LICENSE / MIT © mengwuzhuanshou. See LICENSE.
