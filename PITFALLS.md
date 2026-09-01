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
  进程注入）；正路是 libxposed service-api（经 binder 直写），不是 self-hook。

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

## 13. 分享面板渠道：服务端渠道表 + 客户端白名单双闸，注入渠道补回入口

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

## 16. 6.4.0 混淆漂移总表与候选并存策略

6.4.0 对身份链/播放器/参数注入做了大规模换名。所有 hook 采用**候选列表**：
6.3.0 旧锚点在前（主），6.4.0 新锚点在后（辅），按序解析、解析到即停——
两个版本共用同一 APK。实测漂移（6.3.0 → 6.4.0）：

| 作用 | 6.3.0 | 6.4.0 | 备注 |
| --- | --- | --- | --- |
| KMP 身份头提供者 | `up1.a.a()` | `kr1.a.a()` | 6.4.0 变为抽象基类，`a()` 为 final，子类只覆写 `b()` |
| moss RPC 上下文 descriptor | `jp1.g`（字段 a/b） | `Zq1.g`（字段 a=包名,b=服务名,c=方法名） | **字段语义移位**：取服务名要读字段 b |
| metadata/device proto | `Metadata`/`Device` | `KMetadata`/`com.bilibili.metadata.device.KDevice` | kotlinx protobuf 生成类换名 |
| okhttp 身份提供者 | `mq0.a.e()/d()` | `oq0.a.e()/d()` | 6.4.0 实际零触发（okhttp REST 层闲置） |
| 空间页 REST 参数注入 | `XA0.a` | `com.bilibili.app.comm.list.common.api.e` | 见 #5 更新 |
| fnval 改写 | `FG1.b.c()/d()` | `GI1.e.c()/d()` | 方法名未变 |
| 首页 feed 加载 | `PegasusViewModel.z0` | `PegasusViewModel.y0` | 结构化匹配（第 3 参类型）跨版本通用 |
| 听模式完成入口 | mini-player biz 层 | 播放器核心 `RI1.l.onCompletion` | 见 #17 |
| 空间页 Activity | `ui.AuthorSpaceActivity` | `local.LocalAuthorSpaceActivity` | 6.4.0 用户实际打开的是 local 变体 |

**教训**：升级后先跑一轮“探针版”（只加日志不动行为），用日志确认新链路再落改写；
旧锚点不要删——它们是回退 6.3.0 的依据。

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