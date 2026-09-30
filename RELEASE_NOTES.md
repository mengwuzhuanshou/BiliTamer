# BiliTamer Release notes

## v1.7.13

> 本版包含未单独发布的 v1.7.12 全部变更；1.7.11 及以下直接安装本版即可。
> This release supersedes the never-published v1.7.12 — install directly over 1.7.11 or below.

* **国际版 6.6.0（9130300）适配 / Adapted to host 6.6.0**: 宿主升到 6.6.0 后混淆锚点整族换包，
  本轮每个落点都按「dex 里按形状+角色反查 → 装机一行日志验收」双证重新定位：
  `fnval` 计算 `kJ1.a` → `aK1.a`（真机 `fnval int 17364 -> 84948`）、听模式完成监听器
  `CE1.f` → `lK1.j`、首页分区屏蔽的 feed 解析入口 `pegasus.request.g` → `request.h`、
  直播后台播放门 `HX.c$b/$c.q1()` → `JX.b$b/$c.m1()`、gRPC 身份描述符族 `kr1.a..n` →
  `xr1.a..n`。旧名在 6.6.0 大多已被 R8 复用成无关类，所以每条都必须过形状/角色校验，
  只把新名字加进候选表是不够的。同时移除三条在 6.5.0 就已经断死的 IP 兜底链
  （`ip1.h` 线程标记、`mq0.a/oq0.a` 身份提供者、`kr1.a/up1.a` 头提供者 —— dex 实证类不在
  或形状不符），它们只会贡献重试次数和误导性 ERROR；已消亡的 main2 底栏与 mini-player
  biz 层探针也降为 debug，ERROR 只留给真故障。
  / Every obfuscated anchor moved as a family on 6.6.0; each seam was re-located statically by
  shape and role, then confirmed by one log line on device. Three identity-rewrite fallback
  chains that had already died on 6.5.0 were removed rather than kept as noise.
* **修复：多 CDN 加速的两处「按计划在走、事实没在走」 / Fixed: two plan-vs-reality bugs in the
  multi-CDN accelerator**: ① 只镜像了半份文件的节点会把**中段**子块按它自己的短总长截短交付，
  旧代码把截短当合法的尾部 EOF，然后跳到下一个**计划**子块的起点，响应体里留下空洞 —— 真机
  表现为播到中段花屏、往后拖一下进度条才恢复；② 起播之后某个子块三轮候选全败时旧代码直接
  向上抛异常，而代理的兜底只认「响应尚未开口」，等于把视频流当场砍尾（画面定格、进度条与
  音频照走）。两处现在都改成「交付到事实边界，再从断点单连接续传到计划末尾」（等价官方单
  连接行为，不是装饰性兜底），并给 sink 写失败补上取消记账（挂断不计节点账）。另外续传通道
  在全部候选都被退避挡掉时退回地址全集 —— 兜底通道没有第二选择，退避对它只是排程提示。
  桌面回归先复现了这两处错（修复前红、修复后绿），967 项断言全过。
  / A partially mirrored CDN truncated a middle piece and the merge loop skipped the hole that
  left (mid-video artifacts); a permanently failed piece threw after the response had already
  started, which cut the stream off. Both now deliver to the real boundary and resume on one
  connection.
* **修复：首页「不自动刷新」在 6.6.0 上拦错了类型 / Fixed: no-home-auto-refresh vetoed the
  wrong flush type on 6.6.0**: 旧名单只拦 `AUTO_BACK_FROM_BACKGROUND` /
  `AUTO_BACK_FROM_OTHER_PAGE`，而 6.6.0 上真正会发出来的自动刷新是 `FLUSH_ON_BACK_PRESS`
  （停在首页 tab 按返回键时整屏换推荐流），于是钩子「装了、也调了、但永远判成不该拦」。
  名单现在按结构匹配（不认方法名）覆盖 `FLUSH_ON_BACK_PRESS` + 三个 `AUTO_BACK_*`，
  并保留「ViewModel 已有内容才拦」的空状态放行，避免页面重建后首屏空白。
  真机 A/B（国际版 6.6.0，同一触发动作在开关两侧各跑一次）：
  关闭时 14:57:48 按 BACK → `type=FLUSH_ON_BACK_PRESS` → 首页 5 条标题全换（0 重合）；
  开启时 15:05:19 同样动作 → `auto refresh blocked, type=FLUSH_ON_BACK_PRESS` →
  5/5 标题与基线完全一致；手动下拉 `type=PULL_DOWN` 不受影响（照常刷新）。
  / The old list only matched `AUTO_BACK_FROM_*`, but on 6.6.0 the refresh that actually
  fires is `FLUSH_ON_BACK_PRESS` (back-press while sitting on the home tab replaces the
  whole feed), so the hook was installed yet never matched. Verified on device with an
  A/B on the same gesture: off → feed fully replaced; on → blocked and 5/5 titles
  unchanged; manual pull-to-refresh still works.
* **取证订正（写进代码注释，避免下次照抄）/ Corrected evidence, recorded in the source**:
  本轮一度记下「`AutoRefreshComponent#w(Z)`（`AUTO_BACK_FROM_*` 的读取方）在 6.5.0/6.6.0
  都没有调用点」，那是**扫描口径错误**造成的假结论：dex 的 `invoke-virtual` 记的是**声明类**，
  而 `w(Z)` 覆写自父类 `com.bilibili.pegasus.b`，按声明类重查后这条链是活的
  （`BasePegasusFragment#am(Z)` ← `Zl(I,I)` ← `onResume/onPause/onFragmentShow/onFragmentHide`），
  首页每次可见性变化都会过它。真机本轮没触发 `AUTO_BACK_*` 的真正原因也查清了：三个分支的
  时间阈值与页面白名单全部来自服务端下发的 Pegasus 配置，当前设备/账号上这些值为 0，`w` 在
  调用加载桥之前就返回 —— 所以「关掉功能做对照也不刷」不能当作钩子起效的证据，必须找同一动作
  在开关两侧都能观察到差异的触发点。名单仍维持显式四条而不用宿主自带的 `isUserRequest()`：
  它把 `TAB_DOUBLE_CLICK`、`BOTTOM_REFRESH_BUTTON_CLICK` 也判成「非用户请求」，照它拦会把
  双击 tab 和底部刷新按钮一并弄坏。
  / An earlier "no call site for `AutoRefreshComponent#w`" reading was a scan artifact: dex
  `invoke-virtual` records the *declaring* class, and `w` overrides `com.bilibili.pegasus.b#w`,
  so the chain is in fact live from the fragment lifecycle. The reason it stayed quiet on
  device is that all `AUTO_BACK_*` thresholds come from server-delivered config and are 0 on
  this account — which is why "nothing changed with the feature off" proves nothing.

## v1.7.11

> 本版包含未单独发布的 v1.7.10 全部变更；1.7.9 及以下用户直接安装本版。
> This release supersedes the never-published v1.7.10 — install directly over 1.7.9 or below.

* **修复：听完自动暂停在 6.5.0 听视频上不生效 / Fixed: pause-after-listen now works on
  6.5.0 listen mode**: 旧实现挂在播放器的完成回调上吞事件——6.5.0 的听视频框架**根本不用
  这个回调决定切集**（真机取证：完成事件后 138ms 框架独立发起下一集加载，循环/顺序模式
  路径相同）。本版改为两层：完成事件到达时把播放器停在片尾前 0.8s 并暂停（用户可见的
  「听完暂停」），随后 6 秒守卫窗内拦截播放器实例上的一切加载/起播调用。真机验证：完成后
  138ms 框架试图起播下一集，被守卫精确拦下——**下一集不会自动播放**；注：框架自己的列表
  指针仍会前进（UI 显示切到下一集），守卫拦的是播放而非列表状态，这是当前语义。普通视频
  行为不受影响（播完原生即暂停）。零监听、零轮询。
  / The old hook swallowed the player's completion callback — but 6.5.0's listen mode
  decides episode switching independently (device evidence: the framework fired the
  next-episode load 138 ms after the completion event). Now: on completion the player is
  parked 0.8 s before the end and paused, and a 6-second guard window blocks every
  load/start call on the player instance, so the next episode never auto-plays. Note: the
  framework's own playlist pointer still advances (the UI jumps to the next episode) — the
  guard blocks playback, not the list state. Verified on device; normal (non-listen)
  playback is untouched. No listeners, no polling.
* **修复：首页「不自动刷新」在 6.5.0 失效 / Fixed: no-home-auto-refresh ineffective on 6.5.0**:
  6.5.0 把 feed 状态对象改成了嵌套结构（列表在 state.a.a 两层深），「已有内容」判定只扫
  直接字段，永远判成空状态 → 每次自动刷新都被放行。现改为深度受限的递归搜索，并加探针
  （fired / allowed-empty / blocked 三态首触日志）。真机验证：切后台返回时
  `auto refresh blocked, type=AUTO_BACK_FROM_OTHER_PAGE`。/ 6.5.0 nested the feed state
  object, so the has-content guard always saw "empty" and let every auto-refresh through.
  The guard now searches nested fields, with first-fire probes for tri-state diagnosis;
  verified on device (background-return refreshes are blocked).
* **新增：直播后台播放入口（6.5.0）/ New: live background-play entry on 6.5.0**:
  国际版 6.5.0 直播间的播放器设置面板里，「后台播放」（应用退至后台，可继续播放）一项被
  房间 specialType 判定跳过而不创建。本版把该判定强制放行——设置面板恢复显示「后台播放」
  开关，打开后退出到后台直播声音继续。真机验证：开关出现、后台播放正常（MediaSession
  PLAYING 持续）。注意这与「仅播声音」（观看中切纯音频）是两个特性，后者仍受限于播放器
  元数据检查，未在本版处理。/ On 6.5.0 the live-room settings panel skipped creating the
  "Background play" entry behind a room specialType check. The check is now forced open —
  the entry is back, and toggling it keeps live audio playing after leaving the app.
  Verified on device (MediaSession stays PLAYING in background). Note this is distinct
  from audio-only switching while watching, which remains gated and is not addressed here.
* **修复：头像 →「我的」打开完整页面（6.5.0）/ Fixed: avatar → full Mine page on 6.5.0**:
  6.5.0 上底栏 tab 选中动作类从 `FC1.c` 漂移为 `jD1.b/jD1.c`（`HomeFrameViewModel.w0` 参数
  接口同组换名，方法名未漂移）、`tab_host` 资源 id 从 0x7f0938b4 漂到 0x7f0938d3——真实
  派发、合成点击、tab 服务三级入口全部失守，头像降级为深链，打开的「我的」页面不完整。
  现在动作类按候选列表（jD1.c/FC1.c）+ 形状校验解析，全失败时把 `w0` 参数接口名打进日志
  （下次漂移一行日志定位）；合成点击的 `tab_host` 查找失败时按资源名运行时解析兜底。
  实机验证：头像点击走真实派发，页面含离线缓存/历史/收藏/创作中心等完整功能区。
  / On 6.5.0 the bottom-bar tab-select action class drifted (FC1.c → jD1.c) and the
  tab_host resource id moved, so all three entry paths failed and the avatar fell back to
  the incomplete deep-link shell. The action class is now resolved from a candidate list
  with shape validation (falling back to logging the dispatch interface for the next
  drift), and the tab-host lookup falls back to resolving by resource name.
* **移除：分享面板「分享到 QQ」/ Removed: Share-to-QQ entry**: QQ 侧对重签名包的
  「非官方应用 25201」校验已覆盖全部宿主版本（6.3.0 也失效），该 hook 无存在意义，连同
  设置项一并删除。/ QQ now rejects repackaged builds on every supported host version, so
  the injection hook and its settings entry are removed.
* **IP 属地改写点加活体计数 / Liveness counters on the identity-rewrite point**: 排查
  「评论区属地失效」时发现 once-per-process 探针会掩盖长会话中的钩子失效。改写点现在带
  滚动计数（每 200 次写打一条 alive，每 200 次实际改打一条心跳），与 moss RPC 计数对照
  即可分层定位：钩子死 / 服务端行为变 / 传输路径变。/ The rewrite hook now logs rolling
  counters so a mid-session hook death is distinguishable from a server-side change —
  the previous once-per-process probe could mask exactly that.
* 构建 / Build: versionCode 23。

## v1.7.9

* **播放器挂断不再被记到 CDN 节点账上 / Player hang-ups are no longer charged to CDN nodes**:
  播放器在饿缓冲时会从同一起点并发开多条连接、每条只取一块就断开（对代理的写由此以
  Broken pipe 结束）。代理原先把这种「已交付若干字节后写失败」当成节点的部分失败记账，
  而封禁表把部分失败按 0 字节空响应统计——两次就把自己正在用的镜像封掉；节点被封、
  其余节点退避、取数更慢、播放器更饿、并发更多：限流风暴是自己造的。现在对 sink 的写
  失败被单独识别：立即取消在飞子块、完全不记节点账；真正的 CDN 中途掉线仍照常退避，
  且按封禁表契约带上真实字节数（拿到过字节的失败只退避、不封禁——该契约此前被调用方
  丢字节数架空了）。/ The player opens parallel connections from the same offset when
  starved and closes each after one chunk, surfacing as Broken pipe on our writes. Those
  hang-ups were booked as partial node failures and — via a lost byte-count — counted as
  empty responses, so two of them banned a mirror we were actively using: bans starved the
  remaining nodes, which slowed fetches, which starved the player further. Sink-write
  failures are now recognized as client-side disconnects (cancel in-flight work, zero
  accounting), while genuine mid-stream CDN deaths keep their backoff and honor the
  ban-list contract (bytes-received failures back off but never ban).
* **真机量化（冷缓存 + 中段续播）/ Measured on device (cold cache + mid-file resume)**:
  修复前一次首播走网 36.7 MB，其中仅 18.7 MB 是独一份内容——49% 流量浪费在重复下载与
  作废的在飞子块上，伴随镜像被封、6 次无可用节点、可感知的起播卡顿。修复后同一场景：
  4 条连接、0 封禁、0 卡缓冲，走网字节与落盘缓存逐字节相等（浪费 ≈ 0），采样 wlan 消耗
  从 ~34 MB 降到 16.4 MB。/ Before the fix one cold session spent 36.7 MB on the wire for
  18.7 MB of unique content (49% waste) with mirror bans and visible start-up stalls; after
  it, the same scenario connects 4 times, bans nothing, never stalls, and every wire byte
  lands in the shared cache (waste ≈ 0).
* 构建 / Build: versionCode 21。

## v1.7.8

* **播放侧读放大清零 + 账面诚实 / Read amplification gone, ledger made honest**:
  v1.7.4–v1.7.8 这一串都在修同一件事——前台播放被代理接管后，「一条连接只吃几十秒的料，
  我们却替它垫付整窗流量」。最终形态是**按需扩窗**：首窗只投 2 块（`FIRST_WINDOW=2`，
  512 KiB 刚好喂满播放器一次取数），消费过半才翻倍、上限 `min(pieces, windowBytes/chunk,
  2×windowSlots)`；对端挂断时**只留已经下完的子块，绝不等在飞的**（v1.7.5 那版等 4 秒的写法
  跑在 `token.cancel` 之前，死连接占满 8 个并发许可与 12 个流位，冷播 37/47 卡在缓冲，已回退）。
  真机（intl 6.5.0，清空缓存后同一入口首播）：0 次 BUFFERING、37 次 PLAYING、
  `stream failed` 0、`banned` 0，两条被播的流账面 `out == net` ⇒ v1.7.4 的 **8× 放大**（交付 7 MB /
  走网 60 MB）压平。/ The prefetch window now grows on demand instead of by byte budget, and an
  abandoned stream keeps only the pieces that already finished. On device, cold playback shows
  zero buffering stalls and `out == net`, versus the 8× traffic amplification measured in v1.7.4.
* **`disk=0` 是账面假象，不是缓存失效 / `disk=0` was a reporting artifact**:
  `AccelProxy` 的 `disk` 原先取 `serveCached` 的返回值，而播放器挂断会让它抛 IOException，
  那次赋值被整个跳过 ⇒ 所有「从盘上给、对端中途走人」的流一律记成 `disk=0 net=全部`。
  现在逐块累计、按 `min(disk, out)` 截断。真机重放同一条视频（重装模块、新进程）：
  `req=0--1 out=2752512 disk=2752512 net=0`，半命中
  `req=14297108--1 out=7913244 disk=4741080 net=3172164`，切换点正好落在清单覆盖末尾
  ⇒ **跨会话块缓存命中成立**。桌面自测 `testDisconnectDuringCacheServe` 钉住这条：
  旧代码复现出与真机逐字同形的 `out=524288 disk=0 net=524288`。/ `disk` is now accumulated as
  bytes leave the cache, so an aborted stream still reports its hit; the replay proves
  cross-session reuse works (the switch to network lands exactly at the indexed coverage edge).
* **并发流共享缓存句柄 / Shared cache handles**: `BlockCache.acquire` 按数据文件身份在进程内
  共享实例并引用计数（v1.7.6）。覆盖清单原先只在连接关闭时刷盘，一次播放并发的多条流互相
  看不见对方已落到数据文件里的字节，同一段被反复重下。/ Concurrent streams now share one
  refcounted handle per file identity, so bytes a sibling already landed on disk are visible
  immediately instead of only after that connection closes.

## v1.7.3

* **适配 6.5.0：评论区/主页 IP 属地修复 / 6.5.0 support: comment & profile IP location
  fixed**: 宿主 App 自动升级 6.5.0 后身份链混淆锚点整组漂移，评论区 IP 属地失效
  （6.3.0 的 `up1.a` 在 6.5.0 已被无关类占用，旧 hook 挂在不相关类上静默失效）。
  本版把身份改写主路径上移到真名类 `kntr.base.moss.ignet.impl.grpc.c.f`（二进制身份头
  写入存储的唯一入口，6.3.0–6.5.0 均未漂移），对 `x-bili-metadata-bin` /
  `x-bili-device-bin` 做参数替换改写；6.3.0/6.4.0 的提供者层 hook 保留为兜底并新增
  严格形状校验（无参非抽象方法 + (String, byte[]) 构造器返回形状），杜绝撞名挂错。
  实机 6.5.0 验证：评论区属地（省份标签）恢复显示，非评论请求零改写。/ After the host
  app auto-updated to 6.5.0, the obfuscated identity-chain anchors drifted again (the
  6.3.0 `up1.a` name is now held by an unrelated class, so the old hook attached to the
  wrong class and silently died). The main rewrite path now hooks the stable,
  real-named `kntr.base.moss.ignet.impl.grpc.c.f` — the single entry through which
  binary identity headers enter the request context — and swaps the
  `x-bili-metadata-bin` / `x-bili-device-bin` bytes via argument replacement. The
  6.3.0/6.4.0 provider-level hooks remain as fallbacks, now with strict shape
  validation (non-abstract no-arg method + (String, byte[]) constructor on the return
  type) so name collisions can never attach a dead hook. Verified on a 6.5.0 device:
  province tags are back in the comment section; non-comment requests stay untouched.
* **说明 / Note**: 6.5.0 评论区主服务已迁移至 `bilibili.main.community.reply.v2`，
  服务名前缀判定天然覆盖。/ The 6.5.0 comment section mainly calls
  `bilibili.main.community.reply.v2`; the service-name prefix check covers it.
* **6.5.0 全功能盘点与适配 / 6.5.0 full audit & adaptation**: 解码/音质/HDR 的 fnval
  计算类漂移（`FG1.b`→`GI1.e`→`kJ1.a`，三类同构），新候选加入后 int/long 双钩实测触发；
  听视频播完暂停的完成监听器（`RI1.l`→`CE1.f`，R8 横向合并成 (Object,int) 合成类但签名
  未变）加入候选；底栏渲染隐藏「我的」为 6.3.0/6.4.0 专属：6.5.0 容器已 lambda 化且
  存在不可靠的首帧竞态，新版上不安装该 hook（「我的」tab 保持 App 默认显示，开关其余
  行为不受影响）。6.3.0/6.4.0 的全部旧锚点保留为候选，单 APK 继续跨三版本。
  / The fnval calculator drifted (`FG1.b`→`GI1.e`→`kJ1.a`, structurally identical);
  the new candidate hooks int/long variants, verified firing on device. The
  listen-mode completion listener gained `CE1.f` as 6.5.0 candidate (R8 horizontally
  merged the old listeners into an (Object,int) synthetic class, signatures
  unchanged). The experimental bottom-bar "hide Mine" stays 6.3.0/6.4.0-only: on
  6.5.0 the container is a compose lambda with an unreliable first-frame race, so the
  hook is deliberately not installed there (the Mine tab simply stays visible as in
  the stock app). All 6.3.0/6.4.0 anchors remain in the candidate lists — one APK
  across three versions.
* 构建 / Build: versionCode 15。

## v1.7.2

* **修复：顶栏入口随服务器新增分区栏错位 / Fixed: top-bar entries misaligning after
  the server added a section bar**: 6.4.0 顶栏由服务器下发了新的分区栏（如「推荐/动画」
  一行，App 版本未更新即生效）。v1.7.0 的「我的」入口与消息图标是用「追加到容器末尾 +
  负 topMargin」叠进顶栏的，该写法假定顶栏容器下只有单一内容行；服务器插入分区栏后
  入口被挤到分区栏一行，与顶栏头像脱节。本版改为「内容行用 FrameLayout 包裹、入口与
  内容行同层叠放」：入口恒与内容行对齐，服务器再往下插行也不影响。实机验证：入口回到
  顶栏行，消息图标点开消息页、头像点开完整「我的」页均正常。/ The 6.4.0 top bar received
  a server-delivered section bar (e.g. a Recommended/Anime row) without an app update.
  The v1.7.0 "Mine" entry and message icon were overlaid via "append to container end +
  negative top margin", which assumed the top bar held a single content row; the
  server-inserted section bar pushed the entries into the wrong row. Entries are now
  anchored inside a FrameLayout wrapper around the content row, so they stay aligned
  with it no matter what rows the server adds below. Verified on device: entries back on
  the top bar row; the message icon opens the IM page and the avatar opens the full Mine
  page.
* **包含 v1.7.1 全部变更 / Supersedes v1.7.1**: v1.7.1 未单独发布，本版包含其全部变更
  （黑屏过滤真正生效、锁定 H264、解码/音质/HDR 关闭档）。1.7.0 及以下用户直接安装本版。
  / v1.7.1 was not published separately; this release contains all of its changes
  (effective black-screen filter, lock H.264, off/untouched modes). Install directly if
  you are on 1.7.0 or earlier.
* 构建 / Build: versionCode 14。

## v1.7.1

* **修复：黑屏（有声无画面）过滤真正生效 / Fixed: HW-decode filter now actually takes
  effect**: v1.6.1 引入的「按硬解能力过滤」此前只在服务端请求位上 OR 加位、从不清位，
  而宿主 App 自身会按自家（乐观的）能力检测预先置好 AV1/HEVC 请求位——模块的过滤
  对宿主已置的位形同虚设，设备硬解运行时失败的流照样下发，黑屏依旧。本版起 fnval 位
  改写改为「先清后设」：自动顺位下设备没有硬解的编码位会被从请求中移除，服务端不再
  下发对应流。这是黑屏反馈的核心修复。/ The hardware-decode filter added in v1.6.1
  previously only OR-ed format bits into the request without ever clearing the bits the
  host app had already set from its own (optimistic) capability detection, so streams
  the device fails to hardware-decode at runtime were still delivered and black screens
  persisted. fnval bit handling now explicitly clears and re-sets bits: in auto mode,
  codecs the device cannot hardware-decode are removed from the request so the server
  stops delivering them. This is the core black-screen fix.
* **新增：锁定 H264 / New: lock to H.264**: 视频解码新增「锁定 H264」档位——只请求
  H.264，清掉 AV1/HEVC/H266 请求位。兼容性最好的兜底档：解码异常、黑屏、卡顿的设备
  可显式切到此档。/ New "Lock H.264" codec mode — requests H.264 only (AV1/HEVC/H266
  bits cleared). Most-compatible fallback for devices that glitch on modern codecs.
* **新增：解码/音质/HDR 均可完全关闭 / New: decode, audio and HDR can each be fully
  off**: 三组新增「关闭（不干预）」档——模块完全不触碰对应的请求位与选择逻辑，纯
  App 原行为，便于逐项排查是模块哪一路改动引发的问题。/ New "Off (no intervention)"
  option for each of codec, audio quality and HDR: the module leaves the corresponding
  request bits and selection logic completely untouched (pure app behaviour), useful
  for isolating which module change causes an issue.
* **音质/HDR 布局归位 / Audio & HDR moved under Player section**（设置页原误置于首页
  布局段）。
* 构建 / Build: versionCode 13。

## v1.7.0

* **首页布局对齐国内版 / CN-style home layout (6.4.0)**:
  - 顶栏搜索栏右侧加消息入口（代码自绘信封图标，不依赖目标资源），未读时显示红点+数字
    角标（与消息页角标同源，99+ 封顶）/ A message entry on the top bar (self-drawn icon,
    no target resources); red-dot numeric badge fed by the same source as the in-app IM
    badge (cap 99+).
  - 顶栏左侧头像变为「我的」入口：经真实 tab 派发打开完整「我的」页（不再是深链的
    不完整壳页面）/ Tapping the top-left avatar now opens the full "Mine" tab page via the
    app's own tab-select action (not the incomplete deep-link shell).
  - 底栏移除「消息」tab（数据级，页面一并收敛）；「我的」tab 默认隐藏（渲染级：数据保留，
    顶栏头像入口仍可打开完整页）/ Bottom bar: the Message tab is removed at data level;
    the Mine tab is hidden at render level by default (data kept so the avatar entry keeps
    opening the full page).
* **首页推荐分区屏蔽 / Feed partition (tag) blocker**: 按推荐卡的分区标签（tname）整卡屏蔽，
  词表管理支持批量输入（中英文逗号/分号/顿号/换行均可）、搜索定位、逐词移除，**无词数上限**。
  匹配为包含关系（加「游戏」会连带「主机游戏」）。/ Block feed cards whose partition tag
  (tname) contains any blocked word. Word list editor: bulk input (commas/semicolons/
  newlines), search-locate, per-word removal, no word-count limit. Matching is substring.
* **配置通道最小权限化 / Minimal-permission config channel**: 设置保存不再依赖 root——保存时
  携带配置+代次拉起目标应用，模块在宿主进程截获并写入宿主自有副本（host-conf），重启后按
  conf_gen 代次协议选用最新配置（陈旧副本永不反盖）。root 仅作开发兜底；LSPosed 2.2.0
  已弃用的 XSharedPreferences 通道不再使用。/ Saving settings no longer needs root:
  the app delivers the config (with a generation stamp) by launching the target, the module
  intercepts it in-process and persists to the host's own copy; on every start the newest
  generation wins. Root stays a dev-only fallback; the deprecated XSharedPreferences
  channel is not used.
* **分享到 QQ（6.4.0 门控）/ Share-to-QQ gating (6.4.0)**: 6.4.0 上 QQ 客户端校验调用方签名
  （错误码 25201），重签名包无法原生卡片分享——分享面板不再注入 QQ 渠道以免误触报错；
  6.3.0 保持原生注入。/ On 6.4.0 QQ verifies the caller signature (error 25201), so native
  card share is impossible for a repackaged build — the QQ entry is not injected into the
  share panel; 6.3.0 keeps the native entry.
* 构建 / Build: versionCode 12。

## v1.6.1

* **修复 / Fixed**: 分发用户反馈的「播放随机黑屏、只有声音」：模块此前在解码**自动顺位**下
  无条件向服务端请求 AV1/HEVC 流（fnval 位强制 OR），不校验设备自身的硬解能力——没有
  HEVC/AV1 硬解的设备上播放器只能软解或解码失败，音频轨正常播放而画面黑。「随机」是因为
  不同视频服务端下发的编码不同。现自动顺位按 `MediaCodecList` 硬解能力过滤**请求位**：
  设备没有硬件解码器的编码不再写入 fnval，服务端即不下发对应流；**只过滤请求、不替换
  解码**——自动顺位的选择仍完全交给原逻辑，由其在服务端实际下发的流集合上自行回退
  （AVC 恒在），锁定 HEVC/AV1 行为不变。探测异常时按支持处理（fail-open，保持旧行为），
  结果进程内缓存。
  / Fixed the reported random black-screen-with-audio playback: in auto mode the module
  unconditionally requested AV1/HEVC streams via fnval without checking the device's own
  hardware decode capability, so devices without an HEVC/AV1 hardware decoder software-
  decode (or fail) while audio keeps playing. Auto mode now filters the requested format
  bits by MediaCodecList hardware-decoder availability: codecs the device cannot
  hardware-decode are never requested, so the server stops delivering them. Filter only,
  no substitution — the app's own preference logic still chooses freely among the
  delivered streams (AVC is always the baseline); locked modes are untouched. Probing
  failures fail open; results are cached per process.
* **新开关 / New**: 设置页「按硬解能力自动过滤 HEVC/AV1」（出厂默认开）。锁定 HEVC/AV1
  是用户显式选择，不被过滤，仅在设备无对应硬解时打一条警告日志 / New default-on switch
  "HW-decode auto filter" in settings. Locked HEVC/AV1 remain explicit user overrides
  (never filtered); a warning is logged once when the locked codec has no hw decoder.
* 构建 / Build: versionCode 11。

## v1.6.0

* **适配 / Adaptation**: 适配哔哩哔哩国际版 **6.4.0**（versionCode 不变，仍为 10）。6.4.0
  大规模混淆漂移（moss 身份链、播放器核心、okretro 参数点全部换名），全部旧锚点保留为
  6.3.0 候选，新锚点以候选列表形式并存 / Adapted to Bilibili international **6.4.0**. A
  large-scale obfuscation drift (moss identity chain, player core, okretro param injection)
  was remapped; every 6.3.0 anchor is kept as a candidate and the new anchors coexist in the
  same candidate lists.
* **修复 / Fixed**: 听视频「播完暂停」在 6.4.0 的全屏音频播放器上重新生效：6.4.0 听模式
  完成事件已不在 mini-player biz 层（旧钩子保留但触发不了），改挂播放器核心完成回调——
  播完后回退 0.8 秒并暂停、吞掉自动连播转发（completed 状态下直接 pause 是无操作，
  必须先 seek）/ Pause-after-video works again on 6.4.0's fullscreen audio player: the
  completion event left the mini-player biz layer, so the player-core completion callback is
  hooked instead — seek back 0.8 s, pause, and swallow the auto-next forwarding (pause()
  alone is a no-op in the completed state).
* **修复 / Fixed**: 空间页 IP 属地在 6.4.0 重新生效：6.4.0 空间请求的身份在 REST URL 参数
  （mobi_app=android_i）里而非 moss/proto 头，hook 空间页专属拦截器的 addCommonParam 改写
  之——天然按页面定域 / Profile-page IP location works again on 6.4.0: the space request
  identity now travels as a REST URL parameter (mobi_app=android_i) instead of a moss proto
  header; the space-specific interceptor's addCommonParam rewrites it, which is scoped to the
  space page by construction.
* **杂项 / Misc**: 解码/音质/HDR 顺位、首页不自动刷新、隐藏互动提示、分享到 QQ 均在 6.4.0
  复测通过 / codec/audio/HDR preference, no-home-auto-refresh, interaction-hint hiding and
  Share-to-QQ re-verified on 6.4.0.
* 构建 / Build: versionCode 10。

## v1.5.0

* **撤回 / Withdrawn**: 倍速解锁功能应要求撤回，不随本版本发布。该功能已完成开发与
  实机验证（菜单注入 + 内核放行 + 时钟探针），技术结论存档于 PITFALLS #15：**native
  层存在 3.0x 硬钳制**（`ffp_set_playback_rate` 从内部配置系统读上限覆盖请求值，
  `FFP_PROP_FLOAT_MAX_SPEED` 为只读遥测），>3 档位为观感 placebo，突破需 Zygisk
  原生 hook。下次若重启该功能，按 PITFALLS #14/#15 与 git 历史直接重建，无需重新逆向。
  / The speed-unlock feature is withdrawn before release at the user's request. It was
  fully developed and device-verified; findings are archived in PITFALLS #15: the native
  layer clamps playback rate at 3.0x regardless of the requested value, entries above 3x
  are placebo, and breaking it would require a Zygisk native hook.
* **移除 / Removed**: AI 字幕源功能（含其绑定的 dm.v1 评论区限定分支）——实验性价值
  有限且与 IP 属地共享的身份链路已由 scoped 模式覆盖 / The AI-subtitle feature is
  removed (including its dm.v1 scoped-identity branch).
* 构建 / Build: versionCode 9。

## v1.4.0

* **新功能 / New**: 分享面板补回「分享到 QQ」入口（默认开）：向服务端下发的渠道列表注入
  share_channel="QQ" 条目（与微信同排），点击复用 B 站自带 QQ 互联链路
  （tauth + share_config.json 的 qq.appId），弹出 QQ 分享面板选好友/群——国内版同款效果。
  实机验证：QQ 项与微信同行显示，点击拉起 com.tencent.mobileqq 的
  QPublicTransFragmentActivity（QQ 分享确认页）。未安装 QQ 时面板自动隐藏该渠道。
  / New Share-to-QQ entry in the share panel (on by default): a share_channel="QQ" item is
  injected into the server-driven channel list (same row as WeChat), and tapping it reuses
  the app's native QQ OpenSDK flow — verified on device (QQ's share confirmation page opens).
  The entry hides automatically when QQ is not installed.

## v1.3.2

* **新功能 / New**: IP 属地「评论区限定」模式（默认）：仅评论与 AI 字幕 gRPC 请求声明国内版身份，
  心跳/播放/首页等其它请求保持国际版身份 / Scoped IP-location mode (now default): only comment
  & AI-subtitle gRPC requests declare the domestic identity; heartbeats, playback and all other
  services keep the international identity.
* **修复 / Fixed**: 评论区 IP 属地在「全局」模式下也曾不显示——改写时机必须落在
  moss-common-headers 拦截器 proceed 之前 / Comment-area IP location now actually displayed:
  the rewrite must happen before the moss-common-headers interceptor proceeds.
* **修复 / Fixed**: 听视频「播完暂停」锚点改为签名匹配，功能正式生效 / Listen-pause anchor
  switched to signature matching; the feature now works.
* **杂项 / Misc**: 详细日志关闭时保留每类改写的首条探针日志 / first-probe logging keeps
  diagnostics visible when verbose logging is off.
* 评论区限定模式（默认）下评论区无广告副作用；横幅等广告仅出现在全局声明（v1.2 旧行为）下
  / the scoped mode (default) introduces no ads into the comment area; banner ads only appear
  under the legacy global declaration.

## v1.3.1 / v1.3.0

* IP 属地架构改造与作用域模式实验（详见 PITFALLS.md #6/#7）/ IP-location rework and scoped-mode
  experiments (see PITFALLS.md).

## v1.2.0

* 探针日志模式；听视频/隐藏互动提示/首页不自动刷新等功能落地 / probe logging; listen-pause,
  interaction-hint hiding and no-home-auto-refresh features.