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
与 `reply.v2`（SubjectDescription）；AI 字幕走 `bilibili.community.service.dm.v1`。
日志自证：每条改写行必伴随同线程的 armed 行。

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
