# 实现笔记与坑 / Implementation notes & pitfalls

本文档面向想维护、移植本模块，或给国际版 B 站写类似模块的开发者。
每条先讲机制与约束，再给可行做法；不依赖本仓库的提交历史即可理解。

This document is for anyone maintaining, porting, or writing a similar module
for the international Bilibili app. Each section states the mechanism and the
constraint first, then the working approach.

---

## 1. 注入入口要用 libxposed API，经典 API 在主进程不可靠

国际版 B 站主进程上，经典 `IXposedHookLoadPackage` 有两类失效方式：

* webview provider 先于应用加载，`handleLoadPackage` 以 `com.google.android.webview`
  名义触发，拿到的 classLoader 不是 B 站应用的；
* 主进程可能被厂商进程管理预加载/冻结，`handleLoadPackage` 干脆不触发。

**做法**：改用 libxposed API（`extends XposedModule`），在
`onPackageReady(PackageReadyParam)` 里取 `param.getClassLoader()`，一次注入成功。

## 2. libxposed 模块的打包声明与 classic 不同

* 入口声明在 `META-INF/xposed/java_init.list`（每行一个入口类全限定名）；
* `META-INF/xposed/module.prop`：`minApiVersion=101` / `targetApiVersion=102` / `staticScope=true`；
* `META-INF/xposed/scope.list`：目标包名（可选）；
* manifest **不要**写 classic 的 `xposedmodule` / `xposedminversion` metadata；
* 编译期必须用官方 libxposed API jar 作 classpath，**不要自写 stub**——
  LSPosed 运行时会把 libxposed 类改名，自写 stub 的继承链对不上会
  `ClassNotFoundException`。

## 3. jadx 显示的类名不等于 dex 里的真实类名

jadx 的反混淆/别名机制会改写部分混淆类名，直接照抄会 hook 到不存在的类。
实测过的差异：

| jadx 显示 | dex 真实名 | 身份 |
| --- | --- | --- |
| `p061ip1.h` | `ip1.h` | KMP moss 传输发送入口 |
| `p488mq0.a` | `mq0.a` | 旧 moss 身份头提供者 |
| `p304hl.d` / `p101bq0.b` | （不存在） | okretro 参数注入的假名 |
| —（jadx 未高亮） | `up1.a` | KMP 身份头提供者（真锚点） |
| —（jadx 未高亮） | `XA0.a` | okretro URL 公共参数注入点 |
| `oq0\\C0999a.java`（文件名） | `oq0.a` | 6.4.0 okhttp 身份提供者（jadx 碰撞改名） |
| `gI1\\e.java`（文件名，目录小写） | `GI1.e` | 6.4.0 fnval 改写点（**目录大小写≠真实包名**，以源码头 `package` 为准） |

**校准方法**：不信 jadx 的类名，解析 dex 二进制建索引核对——
string_ids（header 偏移 56/60）、type_ids（64/68）、method_ids（88/92）、
class_defs（96/100）；按 descriptor 建索引确认类定义在哪个 dex。
字符串命中不等于类定义。

## 4. KMP 身份头改写是 protobuf 字节手术，长度前缀要重建

`x-bili-metadata-bin` / `x-bili-device-bin` 的 value 由 kotlinx protobuf 序列化，
mobiApp 字段以 `tag + length(varint) + bytes` 存储。把 `android_i`（9 字节）改成
`android`（7 字节）时必须重建数组并更新 length 前缀，否则 protobuf 解析损坏，
服务端可能拒收或静默忽略。只有同长度替换才能原位覆盖。

## 5. REST 请求的身份在 URL 查询参数里，不在 header

主页 `/x/v2/space`、评论 REST `/x/v2/reply` 的 `mobi_app`/`build`/`channel` 由
okretro 在 `addCommonParamToUrl` 注入 URL 查询参数（并参与签名）。改身份要 hook
参数注入点（`XA0.a.addCommonParam` 改写 Map），只改 User-Agent 或 metadata header
没有用。主页用 `mobi_app=android`（普通版）即可，不要用 android_hd。

**6.4.0 更新**：okretro 注入点整体换名（`XA0.a` 不再是 URL 注入锚点），空间页请求的
公共参数由页面专属拦截器（`com.bilibili.app.comm.list.common.api.e`，继承自 okretro
基类）的 `addCommonParam(Map)` 组装——hook 这个子类的 AFTER 改 `mobi_app` 即可，
天然只作用于空间页请求；签名在参数组装之后计算，改写来得及生效（实测）。

## 6. KMP 一元 RPC 的头来源是 header.b 拦截器，不是语义上“像”的类

6.3.0 的 KMP moss gRPC 栈里，一元调用（评论 MainList 等）的公共头由拦截器
`kntr.base.moss.ignet.impl.header.b`（name="moss-common-headers"，priority 0，
GRPC protocol）写入：它的 `b(chain, cont)` **同步**遍历 `jp1.d` 头提供者
（`up1.a.a()` 就在这被调，同线程同栈），把身份头写进本次调用的上下文（grpc.c）。

两个语义上很像但**不可用**的锚点：

* `header.j`（MossCommonHeadersProvider）：只被 stream tunnel 建隧道引用，
  一元 RPC 不经过，hook 上零触发；
* `Aq0.a.intercept`（okhttp 拦截器）：REST/gRPC 实际都不走它。

**教训**：hook 点必须先用运行时证据（探针日志）验证真的被调用，
“语义上应该被调用”不等于真的被调用。

## 7. 作用域化改写：ThreadLocal 是否可用取决于入口对，时机必须在 proceed 前

想让只有评论/字幕请求声明国内版身份（其余保持国际版），需要在改写点知道
“当前是什么服务”。可行性由入口对决定：

* `ip1.h.a`（传输层发送入口）与 `up1.a.a()`（身份头生成）**跨线程**（协程/executor
  调度），ThreadLocal 传标记会丢——不可用；
* `header.b`（拦截器）与 `up1.a.a()` **同线程同栈**——可用。

时序要点：header.b 是链上第一个拦截器，`chain.proceed()` 返回时整条链
（包括真正发请求）都已执行完，此时再改头存储为时已晚（服务端不认）。
**正确做法**：在 `chain.proceed()` 之前，从 chain 拿到 grpc.c 上下文
（继承 MossInterceptor.e，字段 b=jp1.g）提取 service/method，判定是目标服务后
设置 ThreadLocal 标记；`up1.a.a()` 的 hook 见标记才改写字节——改写发生在
提供者被调用之前/期间，必然赶得上请求。

评论区相关服务（6.3.0 实测）：`bilibili.main.community.reply.v1`（MainList 等）
与 `reply.v2`（SubjectDescription）。日志自证：每条改写行必伴随同线程的 armed 行。
（v1.5 起模块不再包含 AI 字幕功能，dm.v1 作用域分支已随功能一并移除。）

## 8. hook 方法名锚可能不存在，按签名匹配更稳

jadx 里看到的调用点方法名（如 `z(xG1.InterfaceC36904m)`）在 dex 的 method_ids 里
可能根本不存在（jadx 生成的显示名）。可靠做法：

* 按签名特征匹配：遍历目标类 `getDeclaredMethods()`，按参数个数 + 参数类型名
  （如参数类型含 `xG1`）筛出目标方法；
* 匹配失败时把候选方法列表打进日志，便于现场校准。

## 9. 模块配置：目标进程直读文件 + 默认值单一来源

libxposed 的 `onPackageReady` 在目标进程执行，直接读
`/data/data/<目标包>/files/<conf>` 比依赖 `XSharedPreferences` 可靠。
配套约束：

* conf 解析要处理 BOM；日志带 confSrc=（defaults/local file）来源字段，否则
  “配置没下发”会被误判成“hook 失效”；
* 开关默认值必须单一来源（一张 defaultValueOf 表），getter 与日志全部经它取数——
  各处硬编码兜底值会让“改了默认值”静默不生效；
* 本地 conf 需显式 `dev_override=true` 才覆盖出厂默认，防止设备上残留的旧版本
  配置文件劫持新版本的默认值；
* 想做“免 root 定制”：LSPosed 对 self-hook 另有门槛（作用域声明自身包名后，
  升级安装新增的作用域条目不会自动合并进框架数据库，守护进程也不给模块自身
  进程注入）；正路是 libxposed service-api（经 binder 直写），不是 self-hook；
* **模块私有目录里的 conf 副本对宿主进程永远不可读**：宿主以目标 App 的 uid 运行，
  而 `/data/user/0/<模块包>` 是 0700，SELinux 还按 App 分配不同类别（MLS 约束），
  两道都拦。所以「模块 files / 模块 shared_prefs」两条读路径在 enforcing ROM 上是死代码，
  真正可用的只有两条：① 宿主自己的 files（由钩子在收到带 extras 的启动 Intent 时写入，
  这是无 root 主链路）；② root 写的 world-readable 兜底副本（仅开发用）。
  日志里 `confSrc=defaults` 就是这个信号：设备装完模块后没用过设置页 → 宿主没有自己的
  conf → 全部回退出厂默认值。此时「功能没生效」既不是 hook 失效也不是配置写错，
  先查 confSrc 再动代码。

## 10. verbose 关闭时的“首条探针”日志模式

详细日志关闭时，改写逻辑全程零输出，无法区分“hook 没触发”和“改写成功但静默”。
模式：每类改写点配一个 `AtomicBoolean` + 统一 `logRewrite(once, msg)`——

```java
private void logRewrite(AtomicBoolean once, String msg) {
    if (!api.isVerboseLoggingEnabled() && !once.compareAndSet(false, true)) return;
    api.info("[probe] ip: " + msg);
}
```

verbose 开 → 走完整详志；关 → 每类改写的第一条必打一行，进程生命周期内去重，
不刷屏也不至于全盲。探针只挂在 else 分支，不改变详志行为。

## 11. 大 APK 逆向纪律

* 几十个 dex 找锚点：解析 dex 二进制建类/方法索引秒查，比反复
  `jadx --single-class` 快得多；
* 大 APK 的 jadx 全量反编译放后台跑，输出当资料库 grep；
* 复杂分析脚本落盘成文件执行，不要在命令行里拼转义；
* 设备验证循环：install -r → force-stop → 启动 → 等 12~15 s → grep 日志；
  注入偶发丢失时再杀再启一次即可复现。

## 12. B 站在前台时 UI 自动化不可靠，验证以日志为准

B 站在前台时 `uiautomator dump` 可能抓到后台应用的内容或报
`could not get idle state`，偶发成功也不可信。驱动 UI 用：

* deep link 直达：`am start -a android.intent.action.VIEW -d "bilibili://video/<BV>"`；
* 固定坐标 `input tap/swipe` 盲操作；

验证结果优先看模块日志与网络行为，截图/dump 仅作辅助。
`logcat --pid=<pid>` 过滤可避开老缓冲行的干扰。

## 13. 分享面板渠道：服务端渠道表 + 客户端白名单双闸（功能已于 v1.7.10 移除，本节为技术存档）

> **状态**：QQ 互联对重签名包的「非官方应用 25201」校验最初只在 6.4.0+ 宿主出现，
> 后来 6.3.0 也被拒（服务端策略收紧，与宿主版本无关）——注入入口失去了意义，
> v1.7.10 起该 hook 与设置项整体删除。本节保留机制结论，重启功能时直接取用。

国际版分享面板的渠道列表由服务端 `ShareChannels`（`above_channels`/`below_channels`）
下发；客户端另有一道白名单 `Gt0.f.a`（含 QQ/QZONE/WEIXIN/SINA/COPY/GENERIC 及
LINE/FACEBOOK 等国际社媒）。渠道项的图标与文案在应用内**硬编码**
（`p411kl.j.d("QQ")` → 图标 res + 名称 res），点击统一进 `ShareTargetTask.f(channelId)`
→ 分享引擎（BShare/com.bilibili.socialize）→ tauth QQ 互联 SDK
（`assets/share_config.json` 的 `qq.appId` + `QQAssistActivity` 回调）。

结论：QQ 分享的完整原生链路（弹 QQ 分享面板选好友/群）客户端**本来就有**，
缺的只是服务端渠道条目——向 `ShareChannels.getAboveChannels()` 返回值追加一个
`share_channel="QQ"` 的 `ChannelItem`（name/picture 设好，幂等去重）即可补回。

要点与坑：

* 渠道 getter 被多个面板复用（视频页 supermenu v2、番剧、fasthybrid），一处注入全覆盖；
* **只注入一排**：above/below 同时注入会让面板出现两个 QQ（实测）；6.3.0 视频页 WEIXIN
  在 above（第一排），QQ 应与微信同排，注入 above；
* 渠道 getter 在视频页加载时就会被预取调用（不是等面板打开才调），注入日志会提前出现；
* 未安装 QQ 时面板自身的渠道安装检查会隐藏该渠道，注入方无需自行判定；
* 区分「完整分享」与「降级实现」：引擎对个别渠道可能只做复制链接+启动目标 App
  （如 QuickWord 的 QQ 分支）。验证方法是看前台焦点——完整链路拉起的是目标 App 的
  分享中转页（QQ 为 `QPublicTransFragmentActivity`），降级路径只会打开 App 主界面。

## 14. 倍速上限只在 UI 菜单，内核链路无钳制（v1.5 倍速解锁的依据）

6.3.0 倍速的完整链路（实测反编译）：

* 菜单列表：`com.bilibili.playerbizcommonv2.utils.x.a(MediaResource, Integer,
  boolean, int)`（静态）返回 `ArrayList<q>`（`q`=PlaybackSpeedOption(float speed,
  boolean enabled)），硬编码 [2.0, 1.5, 1.25, 1.0, 0.75, 0.5]；3.0 仅长按实验组
  （SP 键 `sp_play_speed_experiment`，SmartLongPressAnd3x/Speed2And3x 组）追加，
  且要求视频 fps<50（DashMediaIndex.j 解析）且非离线缓存。
* 下发链路：SpeedFunctionWidget/设置面板 → `D.setPlaySpeed(float)`（实现
  `mF1.q`，PlayerCoreServiceV2）→ 持久化 pref `player_key_video_speed`
  （onPrepared 时恢复；`player_key_locked_video_speed` 为长按锁定标志，≤2.0 时
  清除）→ `Ops.OpSwitchSpeed` → `SG1.d.f()` → `IjkMediaPlayer.setSpeed(float)`
  → native。**全链路 Java 层无任何钳制**；`2.0` 会被 app 自身降为 `1.99` 下发
  （时钟同步边界规避），UI 端把 1.99 映射回 2.0 显示。
* 官方长按倍速默认实验组（TripleSpeed）即 3.0x——证明内核对 >2x 完全可用。
  2.0 只是菜单硬编码上限，**服务端不参与倍速校验**（互动视频 RPC 的 playbackRate
  同样裸 float 透传）。
* **做法**：菜单 hook `x.a` AFTER 头部注入阶梯项（与既有项 0.01 容差去重，
  enabled=true 保序降序）；内核 hook `IjkMediaPlayer.setSpeed(float)` BEFORE
  放行 + 钳制硬顶（模块取 16x）。`IjkMediaPlayer` 为未混淆公开 API，跨版本
  最稳；`x.a`/`q` 为混淆锚点，升级需按 #3/#8 校准。
* **坑**：所选倍速经 `player_key_video_speed` 持久化并跨视频恢复；高倍速为快进
  观感（音频 4x 以上可能失真、解码跟做跳帧）；离线缓存视频的实验组 3.0 项会被
  灰置，注入项不受此限。

## 15. native 有 3.0x 硬钳制（倍速解锁的实际上限，实测实锤）

* **状态：功能已于 v1.5.0 应要求撤回（未发布）；本节为技术存档，重启功能时直接取用，
  无需重新逆向/实测。**

* 菜单/内核 hook 全部生效（`setSpeed(12.0)` 送达 ijk），但 **native 时钟回报
  `rate=3.000`**——`libijkplayer.so` 的 `ffp_set_playback_rate` 开头调
  `GetAICenterOutput(9,&out,4,config_id)`（B站自有配置系统，导入名混淆）读上限，
  `rate > 上限` 即覆盖并打日志 `adjust_playback_rate: origin %f, new %f`。
  反汇编实锤（capstone，函数 0x3d158）：命中即 `s8 = limit`。
* **`FFP_PROP_FLOAT_MAX_SPEED=10011` 是只读上报属性**：经
  `doAsyncTask(obtainMessage(16,10011,0,Float))` 抬限无效，时钟仍 3.000
  （IjkMediaPlayerTracker 只把它当遥测读）。Java/LSPosed 层无解。
* 时钟探针（hook `AbstractMediaPlayer.notifyOnPlayerClockChangedListener`）是
  速率真值的唯一可靠来源；`clock rate=X` 即 native 实际生效速率。
* 要突破 3.0x 需要 native hook（PLT hook `GetAICenterOutput` 或 patch
  `ffp_set_playback_rate`），即 Zygisk 原生模块——超出本项目 libxposed Java 架构。
  **结论：>3 档位均为观感 placebo；如需诚实菜单，注入阶梯应收在 3.0。**

## 16. 混淆漂移总表（6.3.0/6.4.0/6.5.0）与候选并存策略

6.4.0 对身份链/播放器/参数注入做了大规模换名；6.5.0 又把身份链整组重排了一遍。
所有 hook 采用**候选列表**：旧锚点在前为主，新锚点在后为辅，按序解析、解析到即停——
多个版本共用同一 APK。实测漂移：

| 作用 | 6.3.0 | 6.4.0 | 6.5.0 | 备注 |
| --- | --- | --- | --- | --- |
| 二进制身份头提供者 | `up1.a.a()`（具体类） | `kr1.a.a()`（抽象基类，`a()` 为 final 具体方法） | 接口 `kr1.d` + 抽象 `vr1.a` + 5 个具体子类 | **6.5.0 无单点可 hook**——改走 grpc.c.f（见 #28） |
| moss RPC 上下文 descriptor | `jp1.g` | `Zq1.g`（a=包名,b=服务名,c=方法名） | `kr1.g`（字段语义不变，`toString` 可实证） | 取服务名读字段 b |
| 头包装（key+bytes） | `jp1.c` | `Zq1.c` | `kr1.c` | String+byte[] 构造器形状稳定，可作校验依据 |
| moss 服务名持有 | `jp1.k` | `Zq1.k` | `kr1.k` | 字段 a=服务名 |
| 拦截器/上下文/存储 | `kntr.base.moss.ignet.impl.header.b` / `MossInterceptor$e` / `ignet.impl.grpc.c`/`d` | 同左 | 同左 | **真名类，三版未动**——锚点尽量落这里 |
| metadata/device proto | `Metadata`/`Device` | `KMetadata`/`KDevice` | 同 6.4.0（真名仍在；`getMobiApp()` 仍返回 String） | kotlinx protobuf 生成类 |
| okhttp 身份提供者 | `mq0.a.e()/d()` | `oq0.a.e()/d()` | 无（两候选均不存在） | okhttp REST 层闲置 |
| 空间页 REST 参数注入 | `XA0.a` | `com.bilibili.app.comm.list.common.api.e` | 同 6.4.0（真名未变） | 见 #5 更新 |
| 评论区 moss 服务 | `bilibili.main.community.reply.v1` | 同左 | **主列表走 `reply.v2`**（v1 仍在） | 判定用 `startsWith("bilibili.main.community.reply")` |
| fnval 改写 | `FG1.b.c()/d()` | `GI1.e.c()/d()` | 未重验 | 方法名未变 |
| 首页 feed 加载 | `PegasusViewModel.z0` | `PegasusViewModel.y0` | 未重验 | 结构化匹配（第 3 参类型）跨版本通用 |
| 听模式完成入口 | mini-player biz 层 | 播放器核心 `RI1.l.onCompletion` | 未重验 | 见 #17 |
| 空间页 Activity | `ui.AuthorSpaceActivity` | `local.LocalAuthorSpaceActivity` | 同 6.4.0 | |
| fnval 计算 | `FG1.b` | `GI1.e` | `kJ1.a` | 三类同构：单例 a、I 缓存 c、J 缓存 d、a()Z/b()Z、c()I/d()J（**private 实例方法**，dexscan 显示 direct≠static） |
| 播放器核心完成监听器 | `RI1.l`（单字段 RI1.r） | 同左 | `CE1.f`（(Object,int) 合成类，R8 横向合并） | 核心容器：6.4.0 `RI1.r` ↔ 6.5.0 `vJ1.m`（字段 H 槽位一一对应，用 OnRawDataWriteListener 字段定位核心） |
| 底栏容器 | `bottomtab.g.a`(11 参函数) | 同左 | `bottomtab.g` 变 Function2 lambda（List 在构造器 p1）+ 容器组合函数 `bottomtab.h.a` | item 级：`l.a`/`n.a`(I,oD1.d,...) |
| 首页 tab 数据类 | `KC1.e`/`KC1.d` | 同左 | `oD1.e`/`oD1.d` | 形状锚定自动适配 ||

**教训**：升级后先跑一轮“探针版”（只加日志不动行为），用日志确认新链路再落改写；
旧锚点不要删——它们是回退旧版的依据。找提供者类新组名的捷径：真名类
`MossInterceptor$e` 的字段类型直接暴露当前组名（6.5.0 字段 b=`Lkr1/g;`）。

## 17. 听模式（全屏音频播放器）的完成入口与“播完暂停”的正确姿势

* 6.4.0 听模式（theseus ugc/listen 框架）的完成事件**完全离开** mini-player biz 层：
  biz 监听器链、广播器、决策方法在听模式下全部零触发（探针实锤）。真正在完成瞬间
  被调用的是播放器核心层的 `RI1.l.onCompletion(IMediaPlayer)`（实现 ijk 的
  OnCompletionListener，直接注册在裸播放器上）。
* **completed 状态下直接 `pause()` 是无操作**：onCompletion 触发时播放器已播完，
  pause 不改变任何可感知状态。正确做法：`seekTo(duration - ~800ms)`（completed 态
  下 seek 会让播放器回到“暂停在新位置”）再补一次 pause，然后**吞掉本次转发**
  （hooker 不调用 proceed）——上层收不到完成事件，自动连播即被抑制。
* 暂停目标用 onCompletion 的 **IMediaPlayer 参数**（真实播放器实例），不要反射猜
  外层包装对象的字段——包装层 pause 可能是空实现。
* ijk 的 OnCompletionListener 是单槽注册：谁注册在裸播放器上谁就是唯一入口，
  全 APK 实现该接口的类很少，枚举 + 探针即可定位。

## 18. 空间页身份在 REST 参数里：用“窗口武装”反证 + 页面专属拦截器正解

* 空间页 `/x/v2/space`（okretro REST）6.4.0 的身份载体是**URL 参数**
  （`mobi_app=android_i`），不是 moss/proto 头。判定方法：临时开一段“全局放行窗口”
  （UI 定域 armed），若窗口内 moss 身份提供者**零触发**而请求照发，即可断定身份
  走的是参数而非 proto 头。
* 正解：hook 空间页 API 专属拦截器的 `addCommonParam(Map)` AFTER 改写
  `mobi_app`。该拦截器只有空间请求经过，**天然按页面定域**，无需 svc 判定、
  无需时间窗；签名在参数组装之后计算，改写来得及生效（实测服务端认可）。
* 页面 Activity 也换了：6.4.0 用户实际打开的是 `local.LocalAuthorSpaceActivity`
  （`ui.AuthorSpaceActivity` 为遗留候选）。定位真实页面最省事的办法：临时 hook
  framework `android.app.Activity.onResume` 打去重类名清单（探针，不上改写逻辑）。

## 19. 找锚点的通用工具：解析 dex 二进制建类/方法索引

几十个 dex 里核对“jadx 显示名 vs dex 真实名”、按前缀枚举类、按类转储方法/字段
签名（含 jadx 因 DONT_GENERATE/碰撞漏掉的类），最可靠的是直接解析 dex 结构：
string_ids(56/60)、type_ids(64/68)、proto_ids(72/76)、field_ids(80/84)、
method_ids(88/92)、class_defs(96/100)；uleb128 读字符串；field_id 的类型在
偏移 +2（u16），别把声明类（+0）当字段类型读。工作区 `common/recon/` 有通用
扫描脚本（dexscan.py：全 dex 类枚举/前缀过滤/单类签名转储/字符串池检索）。

## 20. 解码黑屏修复：按硬解能力过滤请求位，但不替换解码偏好（v1.6.1）

* **现象**：分发反馈播放随机黑屏只有声音。根因：自动顺位下模块无条件把
  `FNVAL_AV1|FNVAL_H265` OR 进 fnval，服务端于是下发 AV1/HEVC 流；设备没有对应
  硬解时播放器软解/解码失败，音频轨正常走、画面黑。“随机”来自不同视频下发编码
  不同。模块此前只做了“服务端有什么”的顺位，没做“设备能解什么”的过滤。
* **做法（CodecCapability）**：反射遍历 `MediaCodecList(REGULAR_CODECS)` 找目标
  mime（HEVC=video/hevc，AV1=video/av01）的硬解：API 29+ 读
  `isHardwareAccelerated()`，更老设备回退名字启发（`omx.google.`/`c2.android.`/
  `c2.google.`/含 `.sw.` 为软解）。探测异常 fail-open 按支持处理；结果进程内缓存。
  自动顺位：无硬解的编码不写请求位——服务端不下发，原逻辑自然回退（AVC 恒在）。
  锁定模式不过滤，只告警一次（用户显式选择）。
* **设计约束（用户明确要求）：只过滤请求，不替换解码。** 不要在解码偏好落点
  （GeminiCommonResolverParams.c()）把选中的 HEVC/AV1 改写成 H264——“过滤掉不能
  硬解的”是删除请求位，让服务端少下发，选择权仍在原逻辑；“压回 H264”是主动指定
  另一种编码，会覆盖原逻辑在真实交付流集合上的选择（质量/回退语义都可能被改）。
  两者不是一回事。
* **坑**：软解 AV1（c2.android.av1.decoder，dav1d）在 MediaCodecList 里存在且可查询，
  名字启发必须把它排掉，否则过滤形同虚设；`MediaCodecInfo
  .isHardwareAccelerated()` API 29 才转公，低版本直调 NoSuchMethodError——反射 +
  名字回退。整体用反射还有一层原因：编译桩（build-stub）不含 android.media。

## 21. 6.4.0 首页底栏是 home.components 组件框架（khome 链）：删 tab 的单一数据源与形状锚定
机制：6.4.0 首页由 tv.danmaku.bili.home.page.BaseHomeFrameFragment 装配组件（真名类）：
底栏 tab_host ComposeView 的 content 由 home.components.bottomtab.BottomTabComponent 在
onViewCreated 里 setContent；tab 列表来自 HomeFrameViewModel（真名）root StateFlow 状态
对象，其页面 tab 状态类的 a 字段 = List<底栏 tab>（元素含路由 key 与选中位）。老的
main2 经典管线（MainFragment 派生 provider、HomeTabServiceImpl、CachedResourceResolver、
MainResourceManager）在 6.4.0 上是死代码——hook 装载成功但 UI 根本不消费。
做法：全部按形状锚定，不依赖混淆名——hook 真名 HomeFrameViewModel 构造器拿实例 → 轮询
其 StateFlowImpl.getValue() → 在 root 子对象里扫 size1..8 的 List 候选，按「元素 boolean
字段数」打分区分底栏（包装类，十几个布尔）与顶栏（信息类，近零布尔）→ 对页面状态类全部
构造器 AFTER 把列表字段替换为过滤副本（发布前改字段，无观察者）。
注意：消息 tab 删除是数据级（页面一并消失）；「我的」删除改做渲染级——数据保留、只隐藏
底栏画出来的 tab（hook Compose 容器函数参数替换），顶栏头像仍能经真实 tab 派发打开完整页。

## 22. B 站有运行时方法保护：setContent 类方法被重写成随机名合成类
机制：ComposeView.setContent 等热点方法的 dex 方法体在启动时被替换为每次运行随机命名的
合成委托类（栈里可见 android.util.ChoosKees 之类随机帧），每次启动都不同。
约束：不要把这类随机帧当锚点；hook 本身不受影响（按原方法 hook 照常触发，参数齐全）。
Compose setContent 收到的 content 是 ComposableLambdaImpl 包装，真 lambda 类在其 Function2
字段里；且 R8 横向合并让 lambda 载体类名不可信（底栏 lambda 会藏在 ad 包的合并类里）——
确认 composable 身份用调用栈（new Throwable 全量抓取；hook 线程里 Thread 栈 API 会截短），
不用类名。

## 23. 首页推荐 feed 分区（tname）过滤：解析出口一处过滤 + 注解反射定位字段
机制：feed 刷新/加载更多/预载提交三个 action 共用同一个解析器（request.g，@Singleton），
在其 a(okhttp 响应)GeneralResponse 方法 AFTER 原地移除命中卡即覆盖全部入口，下游 Store/
渲染消费同一列表对象。注意：PegasusViewModel 的分发入口真名随构建漂移（曾见 z0 实为无参
预载、入口是 y0），要按签名匹配不要按名字。
字段定位：卡片模型字段名混淆漂移，用 @SerializedName 注解反射（协议名稳定）；分区标签
args.tname 无注解但字段名与 JSON 同名，按字段名兜底即可。取 String getter 别用「最后一个
非空」启发式（会抓到 toString）。匹配语义建议用包含关系并在文档里讲清。

## 24. 模块配置持久化：无 root 主链路 + conf_gen 代次协议（全项目通用，与共享坑 #14-17 一致）
机制：设置页保存 → SP + 模块本地 conf 副本，每份带 conf_gen=毫秒代次；保存后以显式
ComponentName（MAIN+LAUNCHER）拉起目标应用并附 conf/gen extras；模块在宿主 launcher
Activity 的 onCreate（冷）/onNewIntent（热）截获，解析后写入宿主自有 files 的 host-conf
副本（宿主 uid 读写无阻），并热替换内存配置。读取端按候选序读多来源、代次大者胜、同代次
先到先得；无代次的旧副本（root 停写后的遗留）永远盖不过新代次。
要点：getLaunchIntentForPackage 对模块（未声明 <queries>）在 Android 11+ 返回 null——
必须显式组件启动；只有用户手动改动才拉起宿主（设置页自动 persist 不 launch）；宿主进程
会带着旧模块代码存活到 force-stop，验证前先强停宿主。

## 25. 代码生成模块做设备验证：先排除进程/日志/文件名的三重新鲜度
教训：① 模块 install -r 不杀宿主进程——宿主跑旧模块代码时一切新功能都「不生效」，先 am force-stop 宿主；② LSPosed 的 verbose 日志按会话轮转且文件名含冒号，grep 时按字面文件名（含冒号）或先 ls -t 取最新文件，别手改成分隔符；③ 设置页 UI 自动化定位用uiautomator dump + 正则取 bounds，不要在盲坐标上反复点（键盘弹出会盖住按钮）。

## 26. fnval 位改写必须「先清后设」：宿主 App 会自置 AV1/HEVC 位，只 OR 不清位=不过滤（v1.7.1 黑屏修复真正生效）

* **现象**：v1.6.1 的硬解过滤上线后分发仍反馈黑屏。对照宿主 fnval 计算
  （6.4.0/9100300 为 GI1.e.c()，6.3.0 为 FG1.b.c()）发现：宿主自己就会按自家
  能力检测（MediaCodec 支持查询 + 配置开关）预先置好 AV1(0x200)/AV1 软解(0x800)/
  HEVC·H266(0x10000) 位。宿主的判定是「解码器存在即支持」的乐观判断——OEM 硬解
  器运行时失败（profile/level 超限、驱动 bug）不在其考虑范围。
* **根因**：模块 hook 在宿主返回值上做 `nv = v | bits`——只加位、从不清位。
  宿主已置的 AV1/HEVC 位永远保留，模块的「按硬解能力过滤」实际是 no-op，
  服务端照旧下发设备解不动的流，黑屏照旧。过滤要生效必须对目标位做
  「先 `nv &= ~bits` 再按需 `nv |= bits`」的显式置/清。
* **档位语义（v1.7.1 定稿）**：codec_mode 0=自动（硬解过滤，无硬解的编码位清掉）
  1=锁 HEVC（清 AV1 位、置 HEVC 位）2=锁 AV1（反之）3=锁 H264（清 AV1/HEVC/H266
  全部高位，只请求 H264——兼容性兜底档）4=关闭（不触碰任何解码位，纯 App 行为）；
  hdr_mode 0=自动 1=锁 HDR 2=锁 Vivid 3=强制关（清两位）4=关闭（不触碰）；
  audio_quality 0=自动 1=锁 AAC 2=锁杜比（清无损位）3=锁无损（含杜比位）4=关闭
  （不触碰音质位与音轨）。soft_fnval（long）位表（9100300 实测）：bit0=AV1 软解、
  bit1=HEVC/H266 软解，与 int fnval 同策略先清后设。
* **设计约束（不变）**：仍只过滤请求位，不改写解码偏好；c() 偏好 hook 仅在
  「实发流 codecid == 锁定编码」时确认返回锁定枚举，流不匹配时交还原结果
  （不强行替换——替换会把流类型与解码器选择错配，本身是黑屏源）。
* **锚点漂移补记**：codecid 字段名 6.3.0 为 y、6.4.0 漂移为 z（y 在 6.4.0 变成
  常量 2）；按 z→y 序探测，全缺失则偏好 hook 退化为不干预（fnval 位仍是主机制）。

## 27. 顶栏子项可被服务端动态插入：UI 叠层不得依赖容器位置假设（v1.7.2）

* **现象**：6.4.0 顶栏「我的」入口与消息图标上线后，实机发现入口掉到了下面一行的
  分区栏（「推荐/动画」类 tab 行）上。服务器在顶栏下方**下发**了新分区栏——App
  版本未更新即生效，顶栏容器（垂直 LinearLayout）从单一内容行变成「内容行 + 分区栏」。
* **根因**：v1.7.0 的叠层注入用「addView 追加到容器末尾 + 高度 0 + OnLayoutChangeListener
  同步 topMargin=-h」做净零占位叠加。该写法依赖「容器只有一个子行且 overlay 紧跟其后」；
  服务器插入分区栏后 overlay 变成末尾子项，负 margin 相对的是分区栏，入口随分区栏错位。
  **任何「相对容器子项位置计算」的 UI 叠层都有同样脆弱性——宿主 UI 可以被服务端动态
  改版，不需要 App 升级。**
* **正解（v1.7.2）**：overlay 不追加到容器末尾，而是**把内容行（child 0）包进一个
  FrameLayout wrapper**：wrapper 继承内容行原槽位与原 LayoutParams（含 weight），
  overlay 作为 wrapper 的第二个子项（MATCH_PARENT/MATCH_PARENT 同尺寸、不可点击）。
  overlay 恒与内容行同层叠放，服务器再往下插行也不影响；幂等按 overlay tag 递归查找，
  命中旧 wrapper 时只往旧 wrapper 里补 overlay，不二次包裹。
* **约束**：① wrapper 必须整体继承内容行原 LayoutParams，否则垂直容器行高变化；
  ② wrapper 与 overlay 都不可拦截触摸；③ 幂等锚点用 wrapper 的 tag，重复 decorate
  只补 overlay（双层包裹会留空行）。

## 28. 身份链失效排查：先查宿主是否悄悄升了版本 + 两类新坑（v1.7.3）

* **现象**：评论区 IP 属地失效，用户侧「App 没更新」。实测装机包已自动升到 6.5.0
  （`dumpsys package` 的 versionCode/lastUpdateTime 是唯一真相），混淆锚点整组漂移，
  hook 静默失效——与服务器下发无关。**失效排查第一步永远是查装机版本。**
* **坑一：单字母类名跨构建撞名 + 弱形状校验 = hook 挂在无关类上且「成功」**。
  6.5.0 里 6.3.0 锚点 `up1.a` 被一个完全无关的类占用（返回动态/proto 类型），
  旧校验「类存在且有名为 a 的方法」照样通过，hook 安装成功但永不命中。
  候选列表的形状校验必须收紧到「无参且**非抽象**的方法 + 返回类型带 (String, byte[])
  构造器」级别，名字候选只是索引不是证据。
* **坑二：hook 包装方法的 pre-proceed 阶段 ≠ 方法体内联。** 头提供者的调用发生在
  `header.b.b()` 的**原始方法体内部**，本 hook 的 pre-proceed 代码跑在它之前——
  此时上下文头存储还是空的（日志实锤 `binHeaders=[]`）。想要「拦住写入存储的那一下」，
  正确锚点是存储写入入口本身：`kntr.base.moss.ignet.impl.grpc.c.f(String, byte[])`
  （二进制头唯一写入口，真名类 6.3.0-6.5.0 未漂移），AFTER 语义用
  `chain.proceed(new Object[]{key, rewrittenBytes})` 做参数替换。
* **6.5.0 提供者层结构**：单点 hook 彻底消失——接口 `kr1.d`（`a()` 返回包装）+
  抽象中转 `vr1.a`（持有 header key 字符串）+ 5 个具体子类。枚举接口实现无反射通路，
  逐个 hook 具体子类则下个版本必漂——**主改写点必须上移到真名类**（grpc.c.f），
  提供者 hook 只留作旧版本兜底。
* **找新组名的捷径**：真名基类 `MossInterceptor$e` 的字段类型直接暴露当前混淆组
  （6.5.0 字段 b=`Lkr1/g;` → descriptor/包装/服务全在 kr1 组）；descriptor 的
  kotlinx `toString()` 会打印全部字段语义（`KMethodDescriptor(packageName=…)`），
  字段语义核对不需要反编译调用方。
* **验证闭环**：armed 行（svc 判定）→ grpc write fired（key+scope）→ 改写生效探针 →
  截图看评论区属地渲染，四段缺一不可。

## 29. 形状扫描找漂移锚点的实操（6.5.0 三连适配）

* 6.5.0 适配用了三类「无名字依赖」扫描（工具在工作区 billibili/ 下，与 dexscan 同源）：
  - **方法形状扫描**（scan_shape.py）：全 dex 找「声明了指定名字+返回类型+无参方法」的类。
    定位 fnval 类 kJ1.a 的依据：与 6.4.0 GI1.e 逐字段同构（单例/I 缓存/J 缓存/两个懒加载
    boolean/c()I/d()J），再 jadx 确认 c() 体内有 512/2048/65536 位运算。**坑**：
    encoded_field/method 的 idx 是 uleb **差值**不是绝对值；dexscan 的 direct=static|private
    混在一起，别按「direct 即 static」写过滤条件（GI1.e 的 c()/d() 是 private 实例方法）。
  - **接口实现扫描**（scan_super.py）：按 interfaces/superclass 命中全部实现类。定位听模式
    完成监听器：先扫 `IMediaPlayer$OnCompletionListener` 实现，再用「类有
    OnRawDataWriteListener 类型字段」锁定播放器核心（6.4.0 RI1.r ↔ 6.5.0 vJ1.m），
    核心字段表里对应槽位（H）就是完成监听器字段。R8 会把同宿主的多个单字段监听器
    横向合并成 (Object capture, int tag) 合成类（RI1.l+RI1.m → CE1.f+CE1.g），签名不变
    即可直接进候选。
  - **方法参数类型扫描**：扫 method_ids 里参数含目标类的全部方法，定位消费方。
    底栏渲染：6.5.0 的 `bottomtab.g` 从 11 参容器函数变成 Function2 lambda（List 在构造器
    p1），真名嵌套类 `HomeBottomTabContainerKt$HomeBottomTabContainer$*` 和 item 级
    `l.a/n.a(I,oD1.d,...)` 可交叉印证。
* **Compose 渲染级过滤的固有竞态**：容器 lambda 在首帧组合时构造并捕获 tab List，
  hook 链（500ms 轮询重试）可能晚于首帧——ctor 路径会输；invoke 路径只在父作用域
  重组时触发，Compose 的 draw-phase 优化让「选中态切换」不重组容器。实测结论：
  该渲染级隐藏在 6.5.0 不可靠，**已决定不适配**——原 11 参 hook 保留（6.3.0/6.4.0
  专属），6.5.0 上找不到形状时打注记跳过（mine tab 保持默认显示，无害）。
  验证「头像→我的」派发不受影响才是硬指标（6.5.0 实测完好）。

## 30. 判定缓存命中别只读一个日志字段：`disk=0` 曾是我的账面假象（v1.7.8）

* 现象：真机回放一条**已经整段下过**的视频，六条流全记 `req=… out=2752512 disk=0 net=2752512`，
  而 `.bin/.idx` 的 mtime 一动不动、日志里也没有 `block cache served` ⇒ 结论差点被写成
  「跨会话块缓存从来没生效」。这个误判烧掉了一整轮排查（读遍 `loadExisting` 的每一条 return）。
* 真因在记账而不在缓存：`AccelProxy.stream` 的 `diskBytes = from - start` 取的是
  `serveCached` 的**返回值**，而播放器挂断会让 `sink.onChunk` 抛 IOException 从 `serveCached` 里
  穿出去，那次赋值整个被跳过。凡是「从盘上给、对端中途走人」的流必然 `disk=0 net=全部`；
  回放时播放器恰恰就是这么取数的。**mtime 不动**其实早就指向「只读没写」，是我把它当成了
  「缓存没命中」的证据。
* 修法：字节离开缓存的那一刻就累计（`disk[0] += got`），账面再按 `min(disk, out)` 截断。
  修后真机同一条视频：`disk=2752512 net=0`，半命中 `disk=4741080 net=3172164` 的切换点
  正好落在清单覆盖末尾 +1。
* **可复用的纪律**：① 一个字段说「没命中」、另一个独立信号（文件 mtime、`/proc/net/dev` 的 wlan
  增量）说「没走网」，两者打架时先怀疑账面；② 写自测时把症状**逐字复现**出来再修——
  `testDisconnectDuringCacheServe` 在旧代码上打出的正是 `out=524288 disk=0 net=524288`，
  与真机那六行同形，这才叫钉住了。

## 31. 对端挂断 ≠ 节点故障：把断开记到 CDN 账上，风暴是自己造的（v1.7.9）

* **现象**：冷缓存 + 续播场景下，播放器（native 播放器）饿缓冲时会从同一起点并发开多条
  连接、每条只取一块就断。代理把「已交付若干字节后写 sink 失败（Broken pipe）」记成
  节点的部分失败，封禁表又把部分失败按 0 字节空响应统计——两次封禁一个自家镜像；节点
  被封 → 其余节点退避 → 取数更慢 → 播放器更饿 → 并发更多。实测一次首播走网 36.7 MB
  只有 18.7 MB 是独一份内容（49% 浪费），伴随 6 次「无可用节点」与可感知的起播卡顿。
* **两个叠加的错**：① 归因错——对 sink 的写失败是客户端断开，与节点无关；② 契约被架空
  ——封禁表的语义是「拿到过字节的失败不封禁、只退避」，但调用方把实际字节数丢成 0，
  部分失败被当成空响应参与封禁统计。
* **修法**：sink 写失败单独捕获——取消令牌、安静返回、零记账（也别再为死连接等在飞
  子块）；CDN 中途掉线保持退避，并把真实字节数传进封禁表。修后同一场景 4 条连接、
  0 封禁、0 卡缓冲，走网字节与落盘覆盖逐字节相等。
* **可复用的纪律**：任何「失败计数」先问「这是谁的失败」——对端断开、主动取消、对冲
  输家都不是服务端的错。参考实现「跳过 AbortError」的语义必须覆盖**所有**客户端侧退出
  路径，而不是只有显式 cancel 那一条；带语义的记账函数（如「部分失败」）不要给「丢信息
  的窄签名」，参数一丢，下游契约就形同虚设。

## 32. once 探针会掩盖长会话中的钩子失效：改写点要配滚动计数（v1.7.10）

* **现象**：「评论区 IP 属地又失效了，版本没更新」。排查时改写点日志只在**每个进程的
  第一次改写**打一行（once-per-process 探针），而宿主进程已存活 27 小时——探针早已消耗，
  「没看到日志」既可能是钩子死了，也可能只是探针被用掉了。两种状态在日志上无法区分，
  排查被迫重启应用取证，重启本身又把现场洗掉了。
* **机制**：once 探针的设计目标是「verbose 关闭时确认链路通」，它回答的是
  「进程启动时钩子活过没有」，回答不了「钩子现在还活着吗」。混淆类名漂移、资源 id 漂移
  都是「构建时」失效，once 探针够用；但**长生命周期进程里的脱钩/降级是「运行时」失效**，
  必须用与业务调用同频的滚动计数观测。
* **做法**：改写点挂两个 AtomicLong（进入即 +1、实际改写 +1），低频打点（每 200 次一行），
  与已有的 moss RPC 计数成对。分层判读：moss 计数前进 + 改写点进入计数停滞 = 钩子脱钩；
  两者都前进 + 用户可见失效 = 服务端行为/传输路径变化；改写计数前进 + 无效果 = 服务端
  无视身份。零轮询：计数只在宿主自己发请求时执行，无定时器、无监听。
* **同轮教训**：①「未触发」的结论必须先核对探针是 once 还是逐条——once 探针被消耗后
  「无日志」不是证据；② 形状校验救不了「按名字加载」的第一步：动作类候选
  FC1.c→jD1.c（6.4.0→6.5.0）漂移时，先加载后校验的写法直接在加载步死亡——候选列表
  要放加载之前，且全失败时把关键签名（如派发接口名）打进日志，下次漂移一行定位；
  ③ 资源 id 每个构建重排（tab_host 0x7f0938b4→0x7f0938d3），findViewById 的常量只配
  做快路径，兜底必须按资源名 getIdentifier 运行时解析。

## 33. 完成回调不是唯一「播完」信号：拦事件不如拦动作（听模式听完暂停 v1.7.11）

* **现象**：「听完自动暂停」把钩子挂在播放器完成回调上 seek 回 0.8s + pause + 吞事件。
  听视频（全屏音频播放器）里暂停确实生效了，但框架**照样加载并开播下一集**——事件被吞
  了，切集却照常发生。
* **真因（反编译+真机探针定稿）**：6.5.0 的听视频切集判定**不走完成回调**——框架自己的
  进度检查/状态机独立判定「播完」并发起下一集。取证：完成回调触发后 **138ms**，框架才
  调播放器的 start() 起播下一集——切集是完成之后的一条独立快路径。此外该版本听页面跑在
  音乐/播客栈（页面 Activity 在 `com.bilibili.music.podcast`），与课程/多集播放的
  cheese 框架（`playselect`）互不相干，按语义猜的「播放模式工厂」锚点全程零触发。
* **修法（两层，全部事件驱动、零监听零轮询）**：① 完成回调照旧（停在片尾 + pause，用户
  可见的「听完暂停」）并拉起 6 秒守卫窗；② 守卫窗内拦截播放器实例上的一切
  setDataSource/prepareAsync/start——下一集的加载与起播必经播放器，躲不开。真机验证：
  完成后 138ms 的切集加载被守卫精确拦下（不自动播放），窗口过期后手动操作正常。注意守卫
  拦的是「播放」，框架自己的列表指针仍会前进（UI 切到下一集）——要连列表都不动，得再拦
  框架的切集决策点，本例未做（「不自动播放」已达要求）。
* **可复用的纪律**：
  - **拦「动作」优先于拦「通知」**：回调只是框架愿意告诉你的副本，它自己的决策走别的
    通道（轮询/状态机/原生回调）。要阻止一个行为，找该行为的必经调用点，而不是它旁边
    的通知。判断方法：吞掉事件后行为照旧 ⇒ 决策另有通道，立即转向。
  - **确认监听器唯一性用反射不要用猜**：从回调参数（播放器实例）反射其 listener 槽位，
    打印实际注册的对象类名（fanout 反射）。本例中播放器槽位只有一个监听器，栈里夹着的
    随机名类是运行时防护改写后的**方法体**而非第二监听器（帧顺序：派发方法体 ← 随机名
    包装 ← 真监听器）。
  - **运行时防护会吃掉热点方法**：`notifyOnCompletion` 这类分发点的方法体被改写后，
    hook 原方法可能永不触发——真监听器反而成了最可靠的锚。
  - **R8 横向合并会吃掉外类**：dex 里只剩 `Outer$inner` 内类而找不到 `Outer` 时，读任意
    内类的 `this$0` 字段类型即得合并后的新宿主类。
  - baksmali 单独跑缺依赖（jcommander/util），用反编译器自带 lib 目录拼 classpath；
    外类缺失时优先换 jadx --single-class 验证「类真的不存在」再下结论。

## 34. 多连接合并的两处「按计划在走、事实不在走」：截短留洞与失败砍尾（v1.7.12 已修）

* **现象**：播放器中段花屏，往后拖一点进度条才恢复；偶发画面定格而进度条与音频照走。
  两者都是加速内核（PieceDownloader.streamWindowed）把并行子块拼回单条连续流时的
  账面错位，桌面回归已各自复现（修复前红、修复后绿，967 项断言全过）：
  ① 部分镜像（节点只存了半份文件）把**中段**子块按它自己的短总长截短交付，
     attempt 视截短为合法的尾部 EOF，窗口循环收下后直接跳到**下一个计划子块的起点**——
     响应体留下 [截断点, 计划子块尾] 的空洞（复现里 20896 字节），解码器吃到错位数据
     就是花屏；用户往后拉进度条＝新请求按绝对偏移重新取数，于是「拉一下就好了」。
  ② 起播成功后某个子块三轮候选全败：旧代码直接向上抛，而 AccelProxy 的兜底只认
     「sink 未启动」——响应已经开了口，异常等于把视频流当场砍尾，画面定格、
     音频与进度条照走。真机账面（9-23/9-25 accel=true 会话）与此同形：178 次
     「accel stream failed」以 Connection reset 为主，多条「stream short」。
* **修法**：两处都改成「交付到事实边界，然后从断点单连接续传到计划末尾」：
  截短块吐出去后留存其余已完成子块、passthrough 从截断点续；子块永久失败则
  passthrough 从该子块起点续（等价官方单连接行为，不是装饰性兜底）。同时给
  streamWindowed 的 sink 写失败补上 token.cancel("sink closed")，与 passthrough 里
  v1.7.9 立的「挂断不算节点账、不等在飞子块」契约对齐。
* **顺带修掉的第三处**：续传通道第一次实跑就死在「没有可用 CDN」——能触发续传的
  失败风暴恰恰已把全部候选退避（指数退避 6~24s），startupCandidates 过滤 blocked
  后返回空表。**兜底通道没有第二选择**：passthrough 在候选全被退避挡掉时退回
  allUrls 全集，退避对它是排程提示而非硬闸门。（真机 v1.7.9 记录里就有 6 次
  「没有可用 CDN」，同一条坑。）
* **可复用的纪律**：① 并行转串行合并的循环里，「计划坐标」和「实际交付坐标」是两本
  账：凡是接受了对端截短，就必须从**实际末尾**续，不能从**计划下一个**跳；
  ② 响应开口之后，向上抛异常不是失败上报而是砍尾——流式服务端里「抛」的语义要按
  started 前/后分开设计；③ 兜底路径的候选过滤要松于快路径：快路径挑快节点，
  兜底路径只要还有节点肯答。

## 35. 6.6.0（9130300）漂移总表 + 形状校验自身会写错：Z/I/J ≠ boolean/int/long（v1.7.12）

* **本轮定位方式**：宿主升到 6.6.0 后，先用 `common/recon/shapesearch.py`（新增
  `--iface` / `--exact-methods`）与 `dexcall.py callers/calls` 在 dex 上按形状+角色反查，
  再装机用一行日志验收。下面每一条都是「dex 预测 → 真机日志确认」双证，不是猜名：

| 作用 | 6.5.0 | 6.6.0 | 真机验收 |
| --- | --- | --- | --- |
| fnval 计算 | `kJ1.a` | `aK1.a`（全 dex 形状唯一命中） | `codec: fnval hook ok -> aK1.a.c()/d()` + `fnval int 17364 -> 84948` |
| 听模式完成监听器 | `CE1.f`（容器 `vJ1.m` 字段 H） | `lK1.j`（容器 `lK1.o` 字段仍是 H，onCompletion 体同形） | `listen: completion listener resolved -> lK1.j` |
| feed 解析入口 | `pegasus.request.g.a(Lokhttp3/E;)` | `pegasus.request.h.a(Lokhttp3/E;)`（okhttp3.Response 仍是 `E`） | `feedtag: hook ok -> com.bilibili.pegasus.request.h.a` |
| 直播后台播放门 | `HX.c$b/$c.q1()Z`（接口 `GX.b`，日志方法 `p1`） | `JX.b$b/$c.m1()Z`（接口 `IX.b`，日志方法 `l1`）——逐条同形 | `livebg: background entry unlock ok -> JX.b$b.m1`（$c 同） |
| gRPC 描述符族 | `kr1.a..n` | `xr1.a..n`（14 类一一对应） | 已收口：评论区/空间页属地标签实机 A/B 双证，见下方「评论区改写收口」 |
| 听模式 cheese 决策工厂 | `theseus.cheese.player.playselect.PlaybackMode$a.a(I)` | 真名未漂移，`PAUSE_WHEN_ENDED` 仍在 | 现行主修复无需改动 |
| 首页底栏 | khome（`gE1.e` 过滤） | 同左 | `khome: filter armed on gE1.e, ctors=3` |
| 底栏动作总线（头像→我的） | `w0(LjD1/b;)` + `jD1.c(index)` | 总线是 `v0(LbE1/b;)`（`dispatchAction`），但**点击链已不走总线**：`w0` 被复用成 `w0(LdE1/h$a;)` 气泡态 | 6.6.0 候选表留空 → `tab dispatch anchor not resolvable`（假锚点教训见下） |

* **本轮踩到的新坑（比漂移本身更阴）**：形状校验代码里写
  `m.getReturnType().getName().equals("Z")` —— 反射里基本类型的
  `getName()`/`getSimpleName()` 返回的是 `boolean`/`int`/`long`，**不是 dex 描述符
  `Z`/`I`/`J`**。后果：类找对了、方法也齐，校验却恒 false，日志只留下一句
  「fnval class not found」，看上去像「6.6.0 又换名了」，会把人推回去重跑 dex 反查
  （我确实先怀疑了 dex 结论）。dex 描述符与反射名是两套词汇表，跨写必错。
* **修法与纪律**：① 候选全失败时把**每个候选的失败原因**打出来（`why: aK1.a=shape(...)`
  `kJ1.a=shape(no aboolean ...)`），一行日志直接区分「类名被复用」与「我的判定写错」，
  不用二次反编译；② 判定「缺哪一条」的分支要与判定本身共用同一张表，别各写一份
  （本轮就是两份表口径不一致才暴露的）；③ 旧名（`FG1.b`/`GI1.e`/`kJ1.a`）在 6.6.0 已被
  R8 复用成无关类（菜单工具 / Runnable / lazy 持有者），所以**只加新名不够，必须过形状**
  ——这与 #16 的结论一致，但本轮补了一条：形状校验自己也要被真机验收。
* **顺带清掉的噪音**：6.5.0+ 已消亡的 main2 底栏路径（`MainFragment.Zl()`、tab 模型列表）
  和 mini-player biz 层（`biz.b` 无字段 `r`）此前每天以 ERROR+完整栈刷一遍。它们在新版是
  **预期 miss**，降为 debug 并注明现行路径（khome / cheese+监听器）——ERROR 只留给真故障，
  否则真故障会淹没在每日例行噪音里。
* **评论区改写收口（6.6.0 实机 A/B 双证）**：默认开时评论区时间戳带属地
  （「3天前 山东」「9月27日 广东」），空间页带「IP属地：四川」；把
  `ip_location_enabled=false` 落进宿主 files 副本后**换两个不同视频**都只剩
  「19分钟前 」「9月27日 」（尾随空格还在、属地为空）——服务端确实按请求身份决定
  是否下发 `location`，因果成立、6.6.0 链路没断。
  **但验收指标要换**：这条链在 6.6.0 上 `grpc write seen=N (rewritten=0)` 里的
  `rewritten` 恒为 0，因为 `x-bili-device-bin` 里没有 mobi_app token
  （`rewriteMobiAppBytes` 返回 null 即原样放行），而 `x-bili-metadata-bin` 不经过
  这条写口；真正起作用的是 REST 公共参数改写
  （`ip: space rest params rewritten mobi_app android_i -> android`，宿主类
  `com.bilibili.app.comm.list.common.api.e.addCommonParam`——**注释里的「space」是
  误名，它是评论/列表 REST 的公共参数口**）加上空间页那 15 s 时间窗。
  所以「计数器为 0」不等于功能失效，属地标签才是事实；旧的 `Aq0.a/Cq0.a`
  REST 拦截器在 6.6.0 已不存在（日志 `rest interceptor ... not present; skip`），
  那是历史通道，不影响主页标签——主页标签照样出。

* **头像→我的页：本轮差点挂上一个「同形但不同职」的假锚点**。6.5.0 的真实派发是
  `HomeFrameViewModel.w0(new jD1.c(index))`（底栏点击 lambda 里 new，实机确证切页）。
  6.6.0 反查：总线换成 `v0(LbE1/b;)`（`v0` 里 `new HomeFrameViewModel$dispatchAction$1`
  ——真名自证），而 `bE1/c;` 与 `jD1/c;` **逐条同形**（`field I a` + `<init>(I)` +
  equals/hashCode/toString），照形状规则把它加进候选表、启动期空跑日志也确实解析成功
  （`tab dispatch anchor ready -> HomeFrameViewModel.v0(bE1.b) action=bE1.c`）。
  但再查一步就否了：全 dex `new-instance` 扫描 52 个 `bE1.*` 动作类共 53 处构造点，
  **底栏点击链一处都不 new 它们**（6.6.0 点 tab 改的是 Compose 状态对象
  `khome/widget/bottomtab/a#c(gE1.d,I)` + `I0/u#z()`）；`bE1.c` 只被
  `com.bilibili.search2.halfscreen.i` 构造、由 `PageRouteComponent` 消费后转成
  `bE1/g(String,I,I)` 再投回总线——它是「路由索引」不是「tab 索引」。
  于是候选表只留实机确证过的 `jD1.c`/`FC1.c`，6.6.0 让它解析失败走兜底。
  **纪律：形状命中只是入场券，「谁 new 它」才是角色证明**；两者都要在 dex 里查完
  才允许进候选表。工具落到 `common/recon/dexsite.py`：
  `python dexsite.py new --apk <apk> "LbE1/c;"`（全 dex 找 new-instance 调用点）、
  `python dexsite.py field --apk <apk> "LbE1/c;" a`（找字段读写点）；
  先在 6.5.0 上跑出已知答案（`LjD1/c;` 只有 1 处、正是 `bottomtab/c#invoke`）再信它的
  6.6.0 输出。注意 `field_id_item` 前两个 ushort 是「声明类 / 字段类型」，
  别按官方注释当「type / unused」读（`dexscan.field_sig` 同口径，实测校准过）。
* **顺带修掉一个几何 bug**：开着「隐藏底栏我的」时，渲染过滤把 `keptTabCount` 覆写成
  隐藏后的数量，而 `mineSlotIndex` 还是数据层下标，`(mineSlotIndex+0.5)/keptTabCount`
  会 >=1——合成点击必然点到别的 tab。现在渲染隐藏时置 `mineHiddenInRender`，
  头像点击直接跳过合成点击（走 tab 服务/深链），日志注明跳过原因。
  （**这条修法已被 #37 推翻重做**：那个布尔会被数据钩子抹掉，事故在 6.6.0 真机复发了。）

## 36. 一份服务端配置被两个消费者各自取列表：只补一处就「标题 3 个、页面 2 页」（首页直播板块）

* **现象**：国际版首页顶栏只有 推荐/动画，没有国内版的 直播。板块本体随包在
  （`LiveTabFragment` + 路由 `bilibili://live/home` 全套都在，deeplink 能实例化，
  只是内容为空——因为作为首页 tab 被框架驱动的那几个回调没人调）。
  定性：**服务端按身份裁剪 `tab/v2` 下发的 tab 列表**，不是客户端过滤。
  反证很清楚：同一份顶栏数据里根本没有那条记录，而路由和页面都是好的。
* **走过的三条死路**（每条都留了证据，别再试回去）：
  1. **只补 `$initPageData$1$1` 的 args[0]**（顶栏标题那条流）→ 标题 3 个、pager 只有 2 页。
     追加时第 3 格下标越界，点它没反应；头部插入时标题整体右移一格，
     点「直播」出推荐流、点「推荐」出动画流。**同一份错位在一台机器复现、另一台不复现**
     （冷启动首帧就带 3 项时头部插入不露馅），别把「没复现」当「没问题」。
  2. **以为页过滤器 `w()` 把注入项滤掉了** → 带 uri 明细的一次性探针实测
     `in=3 out=3 out_uris=[promo, pgc, live] nothing dropped`，过滤器是干净的。
  3. **以为「顶栏标题」和「pager 页面」各有一个 `PageBuildComponent`**
     （`home.tab.components.pagebuild` 与 `home.components.pagebuild`）→ 后者本体被 R8
     改名成 `mi0.a`（按真名 load 直接 CNFE），而且它做的是**底栏**页
     （`gE1.d` + `key_main_tab_*`），跟顶栏无关。
* **正解**：`dexcall.py callers` 查出来顶栏这两条支路**共用同一个组件的同一个
  `w(List)`**（全 apk 只有 3 处调用：`initPageData$1$1` → 标题；
  `setupViewPager$dataJob$1$1` 的两个分支 → `adapter.T(uri 列表)` + `setPagingEnabled`）。
  注入点设在 **`w()` 的入参**上，标题和页必然同源；点标题走
  `PageBuildComponent.k()`→`adapter.e().indexOf(uri)`，页列表里有那条 uri 才切得动
  （-1 直接 return —— 那枚「点了没反应」就是它）。
* **通则**：「一处数据、两处渲染」的组件，先去数**消费者有几个、各自从哪儿取列表**。
  补在共享收口上（同一次调用的入/出参）永远比补在某个消费者的入参上安全，
  因为消费者的两份列表是**两次独立取值**，只喂到一边就必然错位。
  另一个纪律：注入位置必须**追加**而不是头部插入——ViewPager2 + 按位置取 id 的
  `FragmentStateAdapter`，头部插入会让既有页被错配到新下标（整体错一格），
  追加则既有下标一个都不动。
* **对照实机 A/B（必须做）**：同一构建、同一账号，关掉开关后重启宿主，
  顶栏标签从 `推荐/动画/直播` 退回 `推荐/动画` 且注入日志（`appended …`）不再出现
  ——这才叫钩子在起作用。「屏幕没变」不算证据。
* **抗漂移**：`PageBuildComponent` 是 Kotlin 真名（6.3.0–6.6.0 逐字相同），
  但页过滤器方法名每版会重排（四版都是 `w`，仍按「唯一 `(List)->List` 实例方法」兜底，
  多义就放弃并记 warn，宁可漏挂也不挂错）。数据类名每版都换
  （6.3.0 `k` / 6.4.0 `n` / 6.5.0 `nD1.k` / 6.6.0 `fE1.k`），
  字段字母四版同序（a=id b=name c=uri f=default_selected g=pos h=tab_id m=type），
  但字母只当入口，真正验收的是**值**：uri 含 `://`、id 是纯数字、name 非空且不含 `://`。

## 37. 渲染级隐藏 ≠ 数据级隐藏：点击位置只能用渲染快照；DI 组件抓不到实例就照抄它投的动作（头像→我的）

* **事故**：开着「隐藏底栏我的」的 6.6.0 上，点顶栏头像进的是**动态**页。
  合成点击的比例是 `(数据层槽位+0.5)/数据层数量` = `(2+0.5)/3=0.833`，
  而底栏只**画了 2 格**——0.833 落在左边那一格上。数据和渲染是两套数量，
  拿数据的几何去点渲染的东西必歪。
* **修法**：渲染钩子（底栏容器 Compose 函数的入参过滤）每次执行都写一份**渲染快照**
  （`renderedTabCount` / `renderedMineIndex`，隐藏了就是 -1），合成点击只在
  `renderedMineIndex>=0` 时做，比例用快照算。**唯一写入点只能是渲染钩子**：
  上一版用 `mineHiddenInRender` 布尔 + 数据钩子里「重置为 false」，而页面状态每次重建
  都会进数据钩子，「已隐藏」这个事实被悄悄抹掉，事故复发。
  数据钩子不许写渲染快照，反过来也不许删数据（数据一丢，pager 页和派发目标一起没了）。
* **6.6.0 上「点头像进我的」的正确派发**（dex 逐条对读，四版同形）：宿主国内版自己的
  头像 lambda 里就一行 ——
  `HomeFrameViewModel.v0(new bE1.g("bilibili://user_center/mine?bottom_tab_id=我的Bottom", 0, 2))`，
  国际版同一处被一个 oversea/intl 判定挡成 `avatar click disabled for oversea/intl, do nothing`。
  我们照抄这一发即可（真机验收：日志 `avatar -> host route action …我的Bottom`，
  页面停在宿主 `MainActivityV2` 内的「我的」页，底栏还在，不是深链那种独立壳）。
  | 版本 | 路由动作类 | 总线方法 |
  | --- | --- | --- |
  | 6.3.0 | `FA1.g(String,I,I)` | `x0` |
  | 6.4.0 | `FC1.g` | `w0` |
  | 6.5.0 | `jD1.g` | `w0` |
  | 6.6.0 | `bE1.g` | `v0` |
  （四版 `PageRouteComponent` 的那个 `void(Intent)` 处理口函数体都是
  「读 vm 字段 → new `<包>.g(url,0,2)` → `vm.<总线>(g)`」三步，名字分别是
  `U3/Q3/T3/b4`。）
  **发布前又用四个本地包逐个复核过一遍**（`dexscan.py class` 直读类表，不靠记忆）：
  VM 类名 6.3.0–6.6.0 **没漂**（恒为 `tv.danmaku.bili.khome.vm.HomeFrameViewModel`），
  总线方法的单参类型正是同族动作接口（`bE1/jD1/FC1/FA1.b`），四条动作类的构造都是
  `<init>(Ljava/lang/String;,I,I)`；而 `bottom_tab_id=我的Bottom` 这串在四个包的宿主
  深链常量里都存在（`bilibili://root?bottom_tab_id=我的Bottom&biz_key=his` 之类），
  所以「现读不到就退回这串常量」在旧版本上也不是赌。
* **两个关键坑**：
  - **路由动作的匹配键在 url 的 query 里**，不在动作对象的字段里。处理器
    （`dispatchAction` 的 `g` 分支）先 `Uri.parse(url).getQueryParameter("bottom_tab_id"/"bottom_tab_name")`，
    再拿它去底栏列表逐项比内层 tab 数据的 `h`(tab_id)/`b`(name)；**两个键都取不到就整发空转**，
    页面一动不动——「投了动作没反应」不是锚点错，是 url 少了那串参数。
    所以键值要从宿主自己下发的那一格数据里**现读**（读不到才退宿主硬编码的 `我的Bottom`），
    别把国内版的常量当国际版的真值：本轮实机读出来恰好也是 `我的Bottom`，是证据不是假设。
  - **别去抓 DI 组件的实例**。`PageRouteComponent` 反射
    `getDeclaredConstructors()` 长度 **0**（日志 `ctors=0`），构造器钩子永远不触发，
    于是「拿到实例再调它的方法」这条路整条失效；而它做的事只是往总线投一发动作，
    我们本来就已经握着 VM 实例——直接投同一个动作，锚点少一层、也不会被 DI 改名影响。
* **纪律**：只解析不派发的**启动期空跑**（`route dispatch anchor ready -> …`）要有，
  它让「锚点没漂」在点击发生前就有一条独立证词；解析规则与 #35 的
  「形状命中只是入场券，谁 new 它才是角色证明」同源——本轮候选表四条都是从
  `dexsite.py new` 的构造点上抄下来的，不是从形状猜的。

## 38. 对照轮可以整轮跑在「默认值」上：日志短标签不是 conf 键名，而 confSrc 要认主进程那一行

* **事故**：给评论区身份改写做「功能关闭」对照，conf 里写了 `ip=false`，跑完看不出
  任何差异，差点结论成「6.6.0 服务端本来就发属地、我们的钩子多余」。实际是**那份
  conf 一个字都没被读**：真正的键名是 `ip_location_enabled`，`ip` 是启动日志里的
  短标签；解析器「未知键跳过」+「有效键为空就当作没有这份副本」，两重静默叠起来，
  进程照 `confSrc=defaults` 跑，等于又跑了一遍默认开。整轮时间白烧在一条拼错的关键字上。
* **键名的唯一权威来源是配置类里那张键常量表**（`ALL_KEYS`），不是日志行、不是设置页
  文案、不是上一版记忆里的写法。日志短标签天生是为了读日志省事而起的别名，别名会漂。
* **认 confSrc 要认进程**：宿主是**多进程**的（主进程 + `:download` / `:ijkservice` /
  `:web`），每个进程都打一行 `confSrc=`。对照轮只看**主进程那条同名行**；抓错子进程
  会把「defaults」当成对照生效的证据（本轮就是先读到 `:download` 那行才去查的）。
  更进一步：**判断这份 conf 生不生效的唯一依据是那行来源+代次**
  （`confSrc=<来源> gen=<号>`），不是行为差异——默认值往往恰好等于对照值，行为上看不出来。
* **写宿主 files 副本的三道工序缺一步就静默不可读**：属主要改成宿主自己的 uid/gid、
  权限 660、SELinux 标签要 `restorecon`。少了标签那步，文件在、权限对、进程读不到，
  表现同样是 `confSrc=defaults`；`/data/local/tmp` 那份对宿主进程根本不可用
  （标签属于 shell 数据域），只能作为开发兜底的最后一级，别指望它。
* **纪律**：对照实验开工前先花一秒确认「关闭态真的被加载了」，再花十分钟跑行为；
  跑完删掉开发用副本回默认，别让下一轮继承一个自己都不知道的开关。


## 39. 移植老模块功能：字段名「在 apk 里」不等于「数据走这条路」（MBGA 三项，v1.8.0）

* **场景**：把老版 play 国际版模块 MBGA 的三项首页功能（只展示 UGC／干净的视频卡片／
  禁止竖屏播放器）搬到本模块。它的三条锚点分别是
  `BaseTMApiParser.(JSONArray)->ArrayList` 解析方法、`SmallCoverV2Item` 的三个字段、
  `BasicIndexItem.getUri()`。
* **第一个坑：接缝整族没了**。`BaseTMApiParser` 在 6.6.0 已不存在（dex 查无此类），
  解析出口是本模块早就在用的 `request.h.a(okhttp3.E)->GeneralResponse`。照抄接缝
  会得到一个「安装成功、日志漂亮、屏幕毫无变化」的功能。
* **第二个坑：类在、字段在，但那个类是死的**。`SmallCoverV2Item.rcmdReason /
  storyCardIcon / descButton` 逐个 `dexscan.py class` 都能查到，`DescButton()` 空参
  构造也在——看起来可以原样搬。**构造点一查就翻车**：`dexsite.py new
  Lcom/bilibili/pegasus/api/modelv2/SmallCoverV2Item;` 命中 0 处，即该构建里没有任何代码
  造过这个对象，它只是随包残留的 API 模型类。字段存在性证明不了「数据流经它」，
  只有「谁 new 它 / 谁读它」能证明（与 #35 的角色证明同源）。
  - **但这条推理有个洞，同日就被现场撞破了**：`new` 检索对 **gson/fastjson 反射实例化**
    的模型类**天生不会命中**——反序列化不走 `new` 指令，走 `Class.newInstance`/`Unsafe`。
    所以「0 new 点」只能证明「没有代码显式造它」，**不能证明它是死类**；把前者写成
    后者，就会顺手砍掉一个本来能做的功能（clean-card 当时就是这么被判死刑的，
    见下面补录）。要判模型类生死，用「谁声明了该类型的字段」「谁读它的 getter」，
    或者干脆看屏上有没有它的数据。
* **真相是载体换代**：6.6.0 推荐流列表元素是 holder 类族（`LXD0/a;`、`LXD0/h..x;`，
  实现 `LWD0/a;` ← `com.bilibili.pegasus.PegasusHolderData`）。它把卡片属性用 getter
  发出来（`getCardGoto/getCardType/getUri/getArgs/getTitle/getPlayerArgs`），内部字段全
  是单字母。6.4.0 那代是 `BasicIndexItem` 后代 + public 字段。所以「读属性」必须写成
  **getter 优先、字段兜底**，硬编任何一种都只在一代宿主上有效。
* **③ 的静态证明只证到「必要」，没证到「充分」——装机当天被运行时推翻**（原话保留在下面，
  因为它正是那条错误推理的样子）。当时的证据是：`BasicIndexItem` 只剩 3 个 new 点，看着像
  也退役了；但 `com.bilibili.pegasus.card.C#onClick(View)` 的指令流里明确两次调
  `BasicIndexItem#getUri`（一次填 `param`、一次 `setUri` 转存）后才交给 CardClickProcessor，
  再用「整个 pegasus 包只有 `BasicIndexItem` 声明 `getUri()`」排除子类覆写、
  用「方法体就是一条 `iget uri`」排除内部缓存绕过——据此判定挂这里就改得到点击落点。
  **实际一条也没触发**：在钩子入口放了一次性存活日志（第一次调用才印），点遍竖屏卡后
  那行始终没出，而点击确实按卡片自己的 `uri` 协议字段（`XD0.u` 等）分发了。
  `dexcall.py callers` 数出几十个调用点也只证明「有人调它」，**不证明「这一代的数据从这儿走」**。
  结论：路由改写要落在**流经现场的那份 uri**上（与①②同一个解析出口，顺带覆盖刷新/
  加载更多/预载三条路径）；`getUri` 那个挂法只作为老宿主兜底保留，在本版本线上是死代码。
  一次性存活日志不是探针滥用——它是把「没生效」和「根本没进来」这两种在日志里长得一模一样
  的情况分开的唯一办法（同 #32）。
* **配套纪律**：①这类删卡功能必须有 fail-open——一整批卡片都不匹配判据时**不过滤**
  并告警一次，同时打印判据直方图供现场校准；单卡读不到判据按「未知」保留。
  否则判据一漂就是首页整屏空白，而「首页空白」看起来像服务端炸了，不会有人怀疑到
  模块的一行等值比较上。
* **不可证的就明说不证**：clean-card 在 6.6.0 上锚点必然不命中，代码里留一条一次性
  `anchor probe` + `no-op` 告警，而不是留一个「装了但没作用」的静默开关。

* **补录（同一坑的第二层，复核已发布功能时撞见）**：「混淆后字段名读不到」这件事
  有两个方向相反的结论，别把它们混成一条。
  - **注解那一路是活的**。`getArgs()` 返回的 args 模型（6.6.0 = `LbE0/a;`，
    `toString` 自证是 `ArgsData`）字段名全被改成单字母 `a`..`p`，但
    `@SerializedName("tname")` **原样保留**。于是已发布的分区屏蔽（先按注解找、
    注解落空才按字段名兜底）在这一代仍然命中——命中的是**注解**那一路，字段名兜底
    必然失效。反过来若把两级顺序写颠倒（先字段名、失败才看注解），功能会静默变成
    空操作而日志毫无异样。**顺序纪律：协议名优先，Java 字段名只能当兜底。**
  - **有些属性「看着不存在」，是我找错了对象**（这条先前写成了定论，装机当天被现场
    推翻，留在这里当反面教材）。我当时的证据是：holder `LXD0/a;` 的 `@SerializedName`
    取值逐个列出来是 `card_type/title/cover/uri/subtitle/card_goto/goto/args/
    player_args/up_args/three_point_v2/…`，**没有** `rcmd_reason`/`desc_button`；
    再加 `shapesearch.py --need-field "modelv2/RcmdReason"` 全 dex `hits=0`，
    于是写下「推荐理由这条数据面在新宿主已经不存在」。
    **打脸来得很快**：`uiautomator dump` 抓首页一屏，两张卡的角标 TextView 的
    resource-id 都叫 `desc`（一张写着「竖屏」、一张写着「1万点赞」），UP 名在
    `real_desc`——数据明明就在屏上。回到 dex 按协议名找声明
    `@SerializedName("desc")`/`("left_bottom_rcmd_reason_style")`/`("desc_button")`/
    `("up")` 的类，命中 `Lra/c;`：一个 gson 直解、协议名齐全、字段名被 R8 改成单字母
    （`Y`=desc、`Z`=desc_button、`a0`=up、`j`=card_goto）的**活卡模型**，它和 holder
    互相引用（holder 有 `LWD0/a;` 字段，`ra.c` 有 `transient XD0.a` 字段）。
    真正的关系是 **holder 只是渲染层包装，数据在它下层**；我拿包装类的字段表当成了
    数据类的事实。
  - **教训**：静态查表只能证明「存在」，**不能证否「不存在」**——「我扫过的类里没有
    这个键」和「这条数据不存在」是两句话。要证否必须拿现场样本（uiautomator 的
    resource-id/contentDescription 是最便宜的一手证据，屏上有什么就是什么），
    或者证明没有任何类持有它。把「没找到」写成「没有」，下一步就会据此砍掉功能。
* **因此写路径必须按协议名**：数据对象的字段名是单字母且 `private final`、又没有 setter，
  所以「先按同名字段找、找不到再说」这条路对写是必然落空的。`FeedItems` 里落地成三件事：
  `fieldByJsonName`（按 `@SerializedName` 值找字段，注解类经宿主 loader 取——这条路
  的运行时可行性由已发布的分区屏蔽验证过）、`writeJson`（写只走这一路，读才有三路）、
  `readProp` 第三路兜底（camelCase →
  snake_case 的候选名，`cardGoto`→`card_goto`）、`cardOf`（元素自己没有 `card_type`
  键时向下钻一层实例字段找到真正带键的对象，并把下钻入口缓存）。

## 40. 改写型钩子最坏的失败模式是把宿主点崩：路由要按目标页的规范形式重写，不是在源串上做最小替换（禁竖屏，v1.8.0）

* **场景**：③ 把竖屏卡的 `bilibili://story/<id>?…` 换成 `bilibili://video/<id>?…`，
  只动 scheme 前缀、query 原样保留。看起来同构的证据很硬：宿主自己的原生 av 卡带的
  就是同样那三个键（`cid/player_height/player_preload`），连长度都一致。
* **装机结果**：落点确实换了（关闭时进竖屏播放器、开启时进横屏播放页，A/B 成立），
  但横屏播放页在 `onCreate` 抛 Dagger
  `IllegalStateException: Scoped provider was invoked recursively … BusinessScopeDriverImpl`
  **把宿主直接崩掉**，两张不同的竖屏卡各复现一次。
* **分离变量（同一台机、同一批卡，四条各证一件事）**：
  1. 原生 av 卡点击 → 横屏播放页正常、进程存活。说明播放页本身没坏，①②同时开着也无辜。
  2. 崩溃那张卡的 **aid** 用宿主自己的路由外壳打开，最小 uri（不带 query）→ 正常。
  3. 同一个 aid、**原样保留整条 story query**（近 4000 字符的 preload JSON）走路由外壳 → 也正常。
  4. 只有在「卡片点击」这个上下文里带着 query 才崩。
* **做法**：改写路由要写成**目标页自己的规范最小形式**（`scheme://authority/<id>`，
  query 整段丢掉），而不是「在源串上动最少的刀」。目标页会解析你留下的每个参数，
  参数值的形状（这里是竖屏那套预载描述）是它没预料到的输入。
  **未定项照实留着**：具体是哪个 extra 与作用域驱动打架并未反解出来——最小 uri 是
  用干预证明的修法，不是用读代码证明的机制；别把前者写成后者。
* **纪律**：改写／注入型功能的验收必须走到**用户会走的那一步**，并同查
  「落点是预期页面 + 进程还活着 + 崩溃缓冲 0 条」。「屏幕换成了想要的页面」不算通过：
  崩点在下一跳，只看第一跳的日志会误判成功。删卡类要有 fail-open（#39），
  改写类要有「宁可少改、不可改崩」的最小形式，两者是同一件事的两半。

## 41. 「模块没注入」往往是两个读数坑凑出来的假警报（v1.8.0 装机）

* **场景**：连续重装模块后，功能轮次的日志一片空白；模块配置库里记录的 base.apk 路径
  与包管理器报出的实时安装路径也确实不一致。当时据此写下「框架缓存了旧路径 → 模块
  停止注入」，并把一整轮 A/B 判成无效。
* **事实**：同一时刻按模块 tag 直接读 logcat，新宿主进程里有 58 条 `hook installed`、
  三个钩子组都装上、conf 也按 `files-conf` 加载。**注入一直是活的**，坏的是读数：
  * 框架的模块日志文件**按会话轮转**（文件名带启动时间戳）。脚本写死了上一次那个文件名，
    就永远 tail 到 0 行——现场与「模块没跑」完全无法区分。要么每轮重新取最新的那个，
    要么直接读 logcat。
  * 每重装一次模块都会换安装路径，所以「库里记着旧路径」不能当判据：它可能已被内存里的
    新值覆盖，也可能只是一页没合并的旧数据。用文件里的字符串推断运行时状态，
    等于用缓存猜缓存。
* **可靠判据（按顺序，全中就别再怀疑注入）**：①新宿主进程有没有 `hook installed` 行；
  ②`confSrc=` 是不是主进程那一行、`gen` 对不对得上（同 #38）；③功能自己的生效行有没有出。
* **教训**：**「日志没有」有两种原因，读数链路坏了和代码没跑，而它们在屏幕上长得一样**。
  读数链路本身（日志文件名时效、路径缓存、缓冲区轮转）要当作被验证对象；把读数的失败
  写成运行时的失败，既会误杀功能，也会把自己刚做出来的 A/B 判成噪声——后者更贵。
* **复现（v1.9.0 装机，同一坑同一形态）**：又是因为 tail 了已经写满轮转的旧文件——那份
  结尾带着 `-----part N end-----`，只印到上一轮的 hint/follow popup，据此差点判成
  「新 apk 的两个钩子组根本没装」。按启动时间重取活动文件后，两组 ready 行与功能生效行
  都在。**只要读到「结尾带 part end 标记」的文件，就是拿到了历史分片，必须先换新活动件再说。**

## 42. 日志说「写了 4 个」，屏上 0 个槽位：数据面写成功 ≠ 渲染层有东西（干净卡片，v1.8.0）

* **场景**：#39 那轮把 ②「干净的视频卡片」做完时，顺手照抄了 MBGA 的第二半——卡片的
  `desc_button` 为空就造一个「UP 名 → `bilibili://space/<mid>`」按钮。字段拿得到
  （`XD0.u.getDescButton()` 真实存在）、写回成功、日志打出 `descButton filled=4`，
  三项功能一并报「实机通过」。
* **事实**：这一代卡片的布局里**根本没有 desc_button 这个槽位**。三处独立读数：
  * uiautomator 抓那张卡的底行，只有 `bottom_layout` / `real_desc` / `desc_v3` / `more`，
    一个 desc_button 节点都没有；屏上也没有多出任何东西。
  * dex 里 `getDescButton` 全仓只有 2 个调用点，**两个都在 `onClick(View)` 里**——也就是
    只有点击回调读它，没有任何 bind 路径把它摆上屏。
  * 宿主自己的名字行（`desc_v3`）点击本来就进 `LocalAuthorSpaceActivity`，UP 入口早就存在，
    MBGA 那半边在这代是**冗余**的，不只是无效的。
* **为什么会写成「通过」**：`filled=4` 是**我自己那行代码的自证**，不是屏幕的读数。①③的
  验收口径当时已经落到屏幕（卡片直方图对撞 uiautomator、点穿落点查进程存活），②只对了
  「该少的东西少了」（角标清空）这一半，漏了「该多的东西多了」那一半——注入型改动没有
  单独走一遍屏幕取数，就被抑制型改动的通过带着走了。
* **纪律**：**功能的上游实现不是需求来源，屏幕才是**。移植老模块的每一条都要按当代宿主
  重新验收：老版有的字段这代可能没渲染、老版没有的入口这代可能已经有了。抑制类看
  「屏上少了什么」，注入/新增类看「屏上多了什么、点它去哪」，两类的证据都得从屏幕上取；
  自己打的计数日志只能证明代码跑了，不能证明用户看见了。
* **一处方法论修正**：dex 扫描在这里只能**佐证**「没有 bind 路径」，不能证否（#39 的同一条
  教训：静态扫到的是存在，扫不到不等于不存在，接收者的静态类型不同就数漏）。定论仍以
  uiautomator 的一手屏幕读数为准，dex 读数只用来解释「为什么屏上没有」。
* **落地的改动**：`fillDescButton` 与其计数日志、`FeedItems.writeProp`（随它一起失去唯一
  调用方）一并删除，UI 文案改为「UP 入口沿用宿主自己的名字行」。README/RELEASE_NOTES 里
  「补 UP 名入口」的宣称同步撤回——**这句当时是写在用户可见文档里的过度承诺**。

## 43. 起播卡在 0 KB：压住首字节的不是网络，是我们自己「凑满一整块才吐」（v1.8.0 已修）

* **现象**：多线程加速打开后，开头速度 0 KB 要卡好一会才正常加载。插探针都未必抓得到——
  卡的不是网络，是字节已经到了我们手里、却被压在交付之前。
* **根因（读代码定的）**：旧 `stream()` 把起播段当成一个普通子块处理——`headLength =
  max(64 KiB, minChunkBytes)` = 256 KiB，交给 `downloadPiece(..., startup=true, ...)` 收成
  一个 `PieceResult.bytes`，**收满之后才第一次 `sink.onChunk`**；而 `AccelProxy.StreamingSink`
  是「第一次交付才发响应头」。于是播放器第一个字节的延迟 = 一整块 256 KiB 下完的时间。
  再叠第二层：起播那次 `downloadPiece` 的 `width = min(ROUND_CANDIDATES=8, 候选数)`，
  **一次发 8 条对冲候选**，每条都要占一个共享 `gate` 许可（`accel_concurrency=8`）把整块下完，
  而赢家只有一个、其余 7 条白占许可后作废；播放器一次播放又并发开 6–8 条流（音频/视频/多清晰度）
  ——每条流的首字节都排在别人的整块后面，许可不够时还得在池里排队。0 KB 就是这么来的。
* **修法**：① 首字节不再等整块：先用 **1 个字节**探路（`probeHead`：最多 4 条候选、父循环按
  120 ms 错峰发、第一个答应就收队、许可立刻归还，不让池线程 `sleep` 占位子），拿到活地址后在
  **一条**连接上边收边吐（`headAttempt`：每口 ≤64 KiB，`in.read` 到多少就 `onChunk` 多少，
  不凑满；响应头随之立刻发出）；② 探路超时只算退避不算封禁——1 个字节问不到不等于节点死；
  ③ 起播段有 6 s 总预算，超了把剩下的交给窗口并发，别让「开头这一段」独占许可。
* **证据（反向对照）**：把提交里的旧 `PieceDownloader.java` 整份换回来编译，新加的回归测试
  `testStartupHeadFlushesIncrementally` 就红：读数「第一口 262144 字节、首字节等了 620 ms」
  （两条并发流各一次）；换回新实现全绿（断言第一口 ≤64 KiB、<400 ms，且两条流合计 CDN 请求 ≤4）。
  为此给假 CDN 加了 `paceNsPerByte`：**原来的假 CDN 瞬间给整块，量不出「第一个字节等了多大一口」**，
  这正是这个 bug 能在全绿自测里活下来的原因。真机侧：未缓存视频 3 秒出画面，两条轨各一条
  `req=0--1` 长连接、各交付 2.9 MB，无「起播失败」、无回退单连接。
* **纪律**：**流式服务端里「凑满再吐」就是自己造的延迟**——交付粒度要跟着「读到多少」走，
  不是跟着「计划要多少」走；测试替身如果比真网络更快，它会把首字节类问题整类屏蔽掉，
  要测延迟就必须给替身装上「每字节耗时」。
* **后续**：这一版修法把探路串在了起播请求前面，于是首字节变成两段往返相加，用户实测「更严重」；
  见 #47。**修延迟时新加的那一步也要计入预算。**

## 44. 清单只在连接关闭时落盘 = 播放中途永远没有缓存可读（seek 回看仍加载，v1.8.0 已修）

* **现象**：看一半把进度拖回前面看过的位置，还是要加载。按这套块缓存的设计本不该走网。
* **根因**：数据文件 `.bin` 是稀疏文件、字节当场就在盘上，但**覆盖区间清单 `.idx` 只在
  `close()` 里刷盘**，而视频轨实机是一条 `req=0--1` 的长连接（13 分钟只开了一条、138 MB 从这一条走），
  播放期间根本不关闭；`:ijkservice` 又随时可能被杀，关闭时那一次刷盘也没执行。结果
  **字节在盘上、清单不存在**：新连接 `loadExisting()` 看到没有 `.idx` 直接 `return false`，
  整条流退回全量重下。缓存目录里那批只有 `.bin` 没有 `.idx` 的旧文件就是这条坑的化石记录。
* **修法**：① 每落盘 8 MiB 就刷一次清单（`put()` 里 `dirtyBytes` 计数），关闭时照旧；
  ② 在既有的 `req=` 账面行末尾挂上 `cache[spans=… covered=…KiB last=… total=… usable=y/n idx=ok|missing]`——
  下次判「回看还走网」不用再插探针：`idx=missing`＝清单没落盘，`spans=0`/`covered` 小＝那段本来没下过，
  三个数都正常却仍走网＝请求起点落在洞上。
* **证据**：实机播放中途（连接未关）清单已经在了：`total 151071749` + 区间 `0-128096848`；
  重开同一条视频，从 8,191,262 字节起的那条请求读数是 `out=1207864 disk=1207864 net=0`，
  音频轨 `out=2621440 disk=2621440 net=0`。桌面侧 `testIndexSurvivesWithoutClose` 钉住
  「写方没有 `close`，第二个句柄也读得到清单」。
* **纪律**：**共享状态的可见性边界是「落盘」，不是「写入」**；只在优雅退出路径上刷盘的索引，
  等于在真实进程生命周期里从不刷盘。凡是「另一个进程/另一次连接要能看见」的东西，
  刷盘条件必须按数据量或时间设定，不能挂在会话结束上。

## 45. 反编译印出来的 `public` 不能信：方法可见性只有运行时说了算（干掉云视听小电视，v1.9.0）

* **场景**：移植 MBGA 的「干掉云视听小电视」——清空弹幕回包 `DmViewReply` 里的
  `activity_meta`。jadx 把 `clearActivityMeta()` / `getActivityMetaCount()` /
  `getActivityMeta(int)` 都印成 `public`，于是按常规用 `publicMethod()` 取句柄。
* **装机结果**：钩子组直接 unavailable，
  `NoSuchMethodException: com.bapis…DmViewReply.clearActivityMeta []`。类找得到、方法名
  没漂、参数表也对——差的只是可见性。
* **根因**：protobuf 生成代码里 `clearXxx()` 常落在包级/受级访问上（或被 R8 收紧），
  而 **jadx 的修饰符打印会按 `access_flags` 之外的启发式补 `public`**（尤其对接口默认方法
  与合成方法）。也就是说反编译源码是「形状证据」，不是「访问权限证据」。
* **修法**：取方法句柄统一走「public 优先、`declaredMethod` + `setAccessible(true)` 兜底」
  这一条小函数，其余三个 getter 同样处理；对 protobuf 消息本体做反射清空字段属于本模块
  既有范围（①②④也在反射读它的协议字段），没有新增攻击面。
* **顺带一条同族坑**：这个类已经换成 protobuf-**lite**（`GeneratedMessageLite`），
  老 `repeated ActivityMeta` 消息类型整个不存在了，`activityMeta_` 是 `ProtobufList<String>`。
  MBGA 那版按消息类型写的代码在这代**连编译对象都找不到**——移植老模块时「类在但内部表示换代」
  是常态，接缝要按当代字段类型重写，不能按老签名照抄（与 #39 的载体换代同源）。
* **纪律**：**凡是拿反射句柄当依据的判断，可见性一律以运行时抛不抛 `NoSuchMethodException` 为准**；
  静态源码里读到的修饰符只能用来猜，猜错了要有一句兜底而不是让整组钩子下线。

## 46. 「查不到调用点」证不了「数据不驱动渲染」，静态拍出的判据也要当场让位（v1.9.0）

* **场景一：宿主不调这个 getter**。静态在 dex 里找 `getActivityMetaList()` 的宿主调用点，
  结果只有 protobuf Builder 自己的委托方法——看起来像是「渲染方根本没读这块数据」，
  据此会把移植的整条思路判死。
* **实际**：R8 会把 trivial getter **内联成直接字段访问**，内联之后调用图上当然没有那个
  调用点，而数据照样被读走。现场证据反向对上：清空 `activity_meta` 之后日志里那批条目
  （形如 `{"id":10011,"start":0,"end":5,…,"picture":{"resource":…}}`，时间段+图片资源）
  确实是这次活动浮层的素材。
* **所以这一条的可证边界必须写清楚**：挂点、命中、清空三步都有实机日志；**「浮层从屏上
  消失」没有证**——它要在活动期拿同一个稿件肉眼 A/B，本版没做到，就不声称做到
  （注释、设置页文案、发布说明三处同口径）。与 #39 那条「静态查表只能证存在、不能证不存在」
  是同一句话的正反两面：这次是**调用点集合**空 ≠ **数据流**空。
* **场景二：我按常识拍的判据值域，被自己新加的日志推翻**。写 ④「关闭大卡片」之前，我按
  老 pegasus 的常识断言「大封面卡 `large_cover_v9` 的 `card_goto` 是 `av`，所以 ①『只展示
  UGC』挡不住它，④是补一个漏」，并据此写了代码注释和设置页文案。④ 跑起来后 removed 日志
  给的是 `[large_cover_v9/inline_av_v2]`——goto 压根不是 `av`，①本来就删得掉它；
  开着 ① 连滑 5 屏约 40 张卡，界面上也确实全是双列宽。
* **处理方式**：注释与文案**当天改成实测口径**（明写「原先静态推断已被现场推翻」，
  并把 ④ 的保留理由换成经得起查的那两条：①的判据押在服务端会变的 `card_goto` 上、
  且 ① 会连直播卡与广告卡一起删，④ 只按 `card_type` 删跨列内容卡，两者互为备份）。
* **纪律**：**两条纪律**。①「没有调用点」是弱证据，不能当成功能死刑判决书；要判死一条数据面，
  得同时给现场样本（屏上有没有它）和持有者检索（有没有任何类持有它）。
  ②凡是「按常识/按老版本推断」出来的值域，都要在写进注释和文案之前先问一句现场日志给不给得出——
  给得出就等它，别把未证推断印进用户会读到的文案里；给不出就像场景一那样把边界写明。
  自己新加的日志是最便宜的推翻工具，推翻之后立刻改注释，留着错误推理比错误代码更贵。

## 47. 「0 KB」修完又回来、而且更慢：探路自己就是一次完整往返，串在起播前面就是新造延迟（v1.9.0 已修）

* **现象**：#43 声称已修的「开局 0 KB」，用户实际用了一段时间后反馈「还是会卡，而且比之前严重」。
* **我的第一个解释是错的**：日志里 430 ms 内出现 30 条地址改写，我据此讲成「30 条并发流撞上限被拒」。
  为此在代理侧加了拒绝计数（`busy: rejected`，只在真要拒时才 +1），实测**一条都没有**——
  假设被自己加的读数当场否掉。那条改写计数是**每次地址改写**打一行，播放器逐条请求都会重签地址，
  与并发连接数无关；拿它的条数反推并发度从口径上就不成立。
* **真机制（常驻读数定的）**：#43 的修法把「问 1 个字节」的探路放在起播请求**之前**串行执行，
  而探路并不免费——它就是一次完整的 TCP+TLS+往返。实机每流一行的起播账面读到
  `probe=858~948 ms`、`first byte 1000~1276 ms`：首字节成了「探路往返 + 起播往返」两段串联，
  候选全黑时还要把探路预算整段等满。桌面用「每个请求 300 ms 才回响应头」的替身复现出同一形状（631 ms）。
* **修法**：第一条候选的起播请求和其余候选的探路**同时出发**，探路只提供「还有谁活着、文件多大」；
  更快的邻居答应了、而这条连响应头都没回，就在一个「同级往返」的观察窗口之后把它顶掉、改投对方。
  顶掉与写入之间用一把锁做判定，保证同一时刻只有一个写手（不去等被顶掉那条收场，否则首字节又被拖成两段）。
  同时把每条流的许可开销从「起播 1 + 探路 3」降到「起播 1 + 探路 2」：现场读到过 `free=0`，
  两条流就能把并发闸门占满，后来的流连起播许可都要排队。
* **证据（先红后绿）**：三条新回归在旧实现下分别红在 631 ms / 929 ms（黑洞探路等满预算），
  并行后是 306 ms / 5 ms，黑洞候选另加一条 167 ms（旧形状要靠读超时才收场）——机制结论以桌面为准，
  因为它能把「一次往返多少钱」钉成常量。真机只能佐证「串起来那一段没了」：装机后同一场播放里
  出现过 `probes=0 … first byte 190 ms`（前面什么都没串）与 `probe=910ms … first byte 913 ms`
  （预算等满也没多花钱——起播那条早就在吐了）。至于绝对值，改前 818/1122 ms、改后 190~913 ms，
  但两次相隔半小时且不是同一条网络链路，**不能当同网 A/B 讲**；半小时后同一台机器同一份包
  在劣化窗口里读到 `fastest=0`（探路全超时）、首字节 971~1789 ms——那已经是节点侧往返本身变慢。
  闸门从改前的 `free=0` 变成 `free=2/3`，但 `live=4` 时又会 `free=0`、`queue=147ms`（探路还在占许可）。
* **纪律**：① 修「自己造的延迟」时，新加的那一步也要计入首字节预算——**任何一次额外往返都是延迟**；
  ② 账面要在**事情发生的那一刻**落地（每流一行 `startup:`）：关闭时才写的 `req=` 账分不清
  「节点慢 / 许可排队 / 探路白花 / 压根没被收进代理」，这四种病在屏上是同一个 0 KB；
  ③ 计数类日志先核对它数的是什么，再拿它反推并发或容量；
  ④ 真机跨时段的两组数不许写成「同一网络」——网络窗口不在我的控制里，能说出口的只有机制。
