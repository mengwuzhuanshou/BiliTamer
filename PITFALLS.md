# 实现笔记与坑 / Implementation notes & pitfalls

面向维护者、移植者与同类模块作者：每条先给机制与约束，再给可行做法。
节号保持稳定（README 与 RELEASE_NOTES 有引用）；新坑追加在末尾。

---

## 1. 注入入口用 libxposed API，经典 API 在主进程不可靠

主进程上经典 `IXposedHookLoadPackage` 有两类失效：webview provider 先加载，回调以
`com.google.android.webview` 名义触发、classLoader 不对；厂商进程管理预加载/冻结时干脆不触发。
改用在 `XposedModule.onPackageReady(PackageReadyParam)` 取 `param.getClassLoader()`。

## 2. libxposed 打包声明与 classic 不同

* 入口：`META-INF/xposed/java_init.list`；`module.prop` 写
  `minApiVersion=101` / `targetApiVersion=102` / `staticScope=true`；`scope.list` 写目标包名；
* manifest 不写 classic 的 `xposedmodule` / `xposedminversion`；
* 编译必须用官方 libxposed API jar，自写 stub 会因运行时类改名导致继承链 `ClassNotFoundException`。

## 3. jadx 显示的类名不等于 dex 里的真实类名

jadx 的去混淆/别名会改写混淆名，照抄会 hook 到不存在的类：

| jadx 显示 | dex 真实名 | 说明 |
| --- | --- | --- |
| `p061ip1.h` / `p488mq0.a` | `ip1.h` / `mq0.a` | 前缀是 jadx 加的 |
| `p304hl.d` / `p101bq0.b` | 不存在 | okretro 参数注入的假名 |
| `oq0\C0999a.java` | `oq0.a` | jadx 碰撞改名 |
| `gI1\e.java`（目录小写） | `GI1.e` | 目录大小写≠包名，以源码头为准 |

校准：解析 dex 头部（string_ids 56/60、type_ids 64/68、method_ids 88/92、class_defs 96/100）
建 descriptor→dex 索引。**字符串命中不等于类定义在该 dex**。

## 4. KMP 身份头是 protobuf 字节手术，长度前缀要重建

`x-bili-metadata-bin` / `x-bili-device-bin` 的 mobiApp 字段是 `tag + length(varint) + bytes`。
`android_i`(9 字节) → `android_hd`(10 字节) 必须重建数组并更新长度前缀；只有同长度才能原位覆盖。

## 5. REST 身份在 URL 查询参数里，不在 header

`mobi_app`/`build`/`channel` 由 okretro 的 `addCommonParamToUrl` 注入查询串并参与签名，
只改 UA 或 metadata header 无效。6.4.0+ 空间页身份由页面专属拦截器
`com.bilibili.app.comm.list.common.api.e.addCommonParam(Map)` 组装（6.6.0 也覆盖评论/列表 REST，
类名里的“space”是误名），hook AFTER 改写 `mobi_app` 即可，签名在组装之后算。
**REST 主页/空间页**用 `mobi_app=android`，不要用 `android_hd`（这条只管 REST 查询串那一条链；
moss 二进制头取 hd 见 §55，两条链各自独立取值）。

## 6. KMP 一元 RPC 的头来源是 header.b 拦截器

`kntr.base.moss.ignet.impl.header.b`（moss-common-headers，priority 0）在 `b(chain, cont)` 里
**同步**遍历头提供者并写入本次调用的 grpc 上下文。两个“像但不可用”的锚点：
`header.j` 只被 stream tunnel 引用；okhttp 的 `Aq0.a.intercept` 两类请求都不走。
**hook 点必须先用探针证明真的被调用。**

## 7. 作用域化改写：ThreadLocal 可用性取决于入口对

`ip1.h.a`（传输发送）与 `up1.a.a()`（头生成）跨线程，ThreadLocal 会丢；
`header.b` 与 `up1.a.a()` 同线程同栈，可用。时序：必须在 `chain.proceed()` 之前从 chain 取
grpc 上下文、按 service/method 判定并设标记；proceed 返回时请求已发完。
评论服务：`bilibili.main.community.reply.v1` 与 `reply.v2`，判前缀
`bilibili.main.community.reply`。日志上每条改写行应伴随同线程 armed 行。

## 8. 方法名锚可能不存在，按签名匹配

jadx 显示的调用点方法名可能不在 dex 的 method_ids 里。遍历目标类
`getDeclaredMethods()`，按参数个数/参数类型名筛选；匹配失败把候选签名打进日志。

## 9. 配置读取：宿主 files + 默认值单一来源

* 钩子运行在宿主进程，直接读宿主自己的 `/data/data/<宿主包>/files/<conf>`，不要依赖 XSharedPreferences；
* 解析处理 BOM；日志必须带 `confSrc=`（defaults / 文件来源），否则“配置没下发”会被误判成 hook 失效；
* 默认值只能有一张 `defaultValueOf` 表，getter 和日志都经它取数；
* 本地 conf 需显式 `dev_override=true` 才覆盖出厂默认，防止旧版残留配置劫持新默认值；
* 模块私有目录（0700 + SELinux MLS）对宿主永远不可读，self-hook 也不可行；免 root 正路是
  启动 Intent 投递到宿主 files，或 libxposed service-api。

## 10. verbose 关闭时的首条探针

每类改写点配 `AtomicBoolean` + `logRewrite(once, msg)`：verbose 开走全量日志；关时每类第一条
必打，进程内去重。探针只挂在 else 分支，不改变详志行为。

## 11. 大 APK 逆向纪律

* 几十个 dex 先建类/方法索引再查，比反复 `jadx --single-class` 快；
* 全量反编译放后台，输出当资料库 grep；
* 复杂脚本落盘执行，不在命令行拼转义；
* 设备循环：install -r → force-stop → 启动 → 等 12~15 s → grep 日志；注入偶发丢失就再杀再启。

## 12. B 站在前台时 UI 自动化不可靠

`uiautomator dump` 可能抓到后台应用或报 `could not get idle state`。用 deep link
（`bilibili://video/<BV>`）或固定坐标盲操作；验收以模块日志/网络行为为准，
`logcat --pid=<pid>` 避开旧缓冲。

## 13. 分享面板渠道（功能已移除，技术存档）

服务端 `ShareChannels` 下发渠道表，客户端 `Gt0.f.a` 另有白名单；图标文案硬编码，点击进
`ShareTargetTask.f(channelId)` → BShare → tauth QQ SDK。给 `getAboveChannels()` 追加一个
`share_channel="QQ"` 的 `ChannelItem` 即可补回（只注入 above 一排，否则出现两个 QQ）。
渠道 getter 在页面加载时就被预取；未安装 QQ 时面板自己会隐藏。完整链路看前台是否拉起
QQ 的分享中转页，降级路径只开主界面。
QQ 互联对重签名包返回 25201，服务端收紧后该功能整体删除（v1.7.10）。

## 14. 倍速菜单上限（功能已撤回，技术存档）

菜单由 `x.a(MediaResource, Integer, boolean, int)` 返回硬编码档位（2.0/1.5/1.25/1.0/0.75/0.5），
3.0 仅实验组且要求 fps<50。下发链：`D.setPlaySpeed` → pref `player_key_video_speed` →
`Ops.OpSwitchSpeed` → `IjkMediaPlayer.setSpeed` → native，Java 层无钳制；2.0 会被 app 降为
1.99 下发。菜单 hook AFTER 注入档位，内核 hook `setSpeed` 放行即可。

## 15. native 有 3.0x 硬钳制（功能已撤回，技术存档）

`libijkplayer.so` 的 `ffp_set_playback_rate` 先调 `GetAICenterOutput(9,…)` 读上限，超限即覆盖并
打 `adjust_playback_rate`。`FFP_PROP_FLOAT_MAX_SPEED` 只是只读遥测。`setSpeed(12.0)` 送达后
时钟仍回报 `rate=3.000`。**>3 档位都是 placebo**；要突破需 Zygisk 原生 hook，超出本模块范围。
`notifyOnPlayerClockChangedListener` 是速率真值的唯一可靠来源。

## 16. 6.3.0–6.5.0 混淆漂移与候选并存

所有 hook 用候选列表：旧锚点在前，按序解析、命中即停，多版本共用一个 APK。

| 作用 | 6.3.0 | 6.4.0 | 6.5.0 | 备注 |
| --- | --- | --- | --- | --- |
| 二进制头提供者 | `up1.a.a()` | `kr1.a.a()` | 接口 `kr1.d` + 抽象 `vr1.a` + 5 子类 | 6.5+ 无单点，改走 grpc.c.f（#28） |
| moss 上下文描述符 | `jp1.g` | `Zq1.g` | `kr1.g` | 字段 a=包名 b=服务名 c=方法名 |
| 头包装 key+bytes | `jp1.c` | `Zq1.c` | `kr1.c` | 构造器形状稳定 |
| moss 服务名持有 | `jp1.k` | `Zq1.k` | `kr1.k` | 字段 a=服务名 |
| 拦截器/上下文/存储 | `header.b` / `MossInterceptor$e` / `grpc.c,d` | 同左 | 同左 | **真名，三版未动，优先落这里** |
| metadata/device proto | `Metadata`/`Device` | `KMetadata`/`KDevice` | 同 6.4.0 | `getMobiApp()` 仍返回 String |
| okhttp 身份提供者 | `mq0.a.e()/d()` | `oq0.a.e()/d()` | 不存在 | REST 层闲置 |
| 空间页参数注入 | `XA0.a` | `comm.list.common.api.e` | 同左 | 见 #5 |
| 评论服务 | `reply.v1` | 同左 | 主列表 `reply.v2`（v1 仍在） | 按前缀判定 |
| fnval 计算 | `FG1.b` | `GI1.e` | `kJ1.a` | 6.6.0 为 `aK1.a`，见 #35 |
| 首页 feed 入口 | `PegasusViewModel.z0` | `y0` | 未重验 | 按签名匹配 |
| 听模式完成 | mini-player biz | `RI1.l.onCompletion` | `CE1.f` | 6.6.0 `lK1.j` |
| 播放器核心 | `RI1.r` | 同左 | `vJ1.m` | 字段 H=完成监听器 |
| 底栏容器 | `bottomtab.g.a`(11 参) | 同左 | `bottomtab.g` Function2 + `bottomtab.h.a` | item `l.a/n.a` |
| 首页 tab 数据 | `KC1.e/d` | 同左 | `oD1.e/d` | 形状锚定 |

升级后先跑“只加日志不动行为”的探针版；旧锚点保留作回退依据。找新混淆组名的捷径：
`MossInterceptor$e` 的字段类型直接暴露当前组名（如 `Lkr1/g;`）。

## 17. 听模式“播完暂停”的正确姿势

6.4.0 听模式的完成事件完全离开 mini-player biz 层，真正入口是播放器核心的
`RI1.l.onCompletion(IMediaPlayer)`（直接注册在裸播放器上）。completed 状态下 `pause()` 是 no-op：
先 `seekTo(duration-~800ms)` 回到“暂停在新位置”，再 pause，并吞掉本次完成事件。
暂停用回调的 `IMediaPlayer` 参数，不要反射外层包装；ijk 的完成监听器是单槽注册。

## 18. 空间页身份在 REST 参数里

判定：临时开全局放行窗口，若 moss 头提供者零触发而请求照发，身份走的就是 URL 参数。
正解是页面专属拦截器 `addCommonParam` AFTER 改写。6.4.0 用户实际打开的是
`local.LocalAuthorSpaceActivity`（`ui.AuthorSpaceActivity` 是遗留候选）。找真实页面可用
`Activity.onResume` 去重类名探针。

## 19. 找锚点：解析 dex 二进制建索引

按 dex 头偏移建 string/type/proto/field/method/class 索引；uleb128 读字符串；
`field_id` 的字段类型在 +2（u16），+0 是声明类。几十个 dex 里核对真名、按前缀枚举、
转储签名都靠这一套。字符串池命中只能当线索。

## 20. 解码黑屏：按硬解能力过滤请求位，不替换解码偏好

模块曾无条件把 AV1/HEVC 位 OR 进 fnval，服务端下发设备解不动的流 → 有声黑屏。
做法：反射遍历 `MediaCodecList(REGULAR_CODECS)`，API 29+ 读 `isHardwareAccelerated()`，
低版本按名字启发（`omx.google.`/`c2.android.`/`c2.google.`/`.sw.` 为软解）；软解 AV1
（dav1d）必须排除。异常 fail-open，结果缓存。自动模式只清无硬解的编码请求位；锁定模式
不过滤只告警。**不要在解码偏好落点把选中编码改写成 H264**——那是替换选择，不是过滤请求。

## 21. 6.4.0 首页底栏是 khome 组件框架

`BaseHomeFrameFragment` 装配组件；底栏 `BottomTabComponent` 在 `onViewCreated` 里
`setContent`；tab 列表来自真名 `HomeFrameViewModel` 的 root StateFlow（页面状态类 a 字段）。
老的 main2 管线（MainFragment/HomeTabServiceImpl/MainResourceManager）是死代码。
做法：按形状锚定——轮询 StateFlow、在 root 子对象里按“元素布尔字段数”区分底栏与顶栏，
构造器 AFTER 替换列表字段。删“消息”是数据级；隐藏“我的”是渲染级（见 #29、#37）。

## 22. 运行时方法保护：热点方法体被换成随机名合成类

`ComposeView.setContent` 等方法体在启动时被替换为每次运行随机命名的合成委托类。
随机栈帧不能当锚点；按原方法 hook 仍正常触发。`setContent` 的参数是
`ComposableLambdaImpl`，真 lambda 在其 Function2 字段里；R8 横向合并让载体类名不可信，
确认 composable 身份用调用栈，不用类名。

## 23. 首页 feed 分区过滤：解析出口一处过滤

刷新/加载更多/预载三个 action 共用一个解析器（6.6.0 `pegasus.request.h.a(okhttp3.E)`），
在其 AFTER 原地删卡即覆盖全部入口，下游渲染消费同一列表。`PegasusViewModel` 分发入口
名会漂（`z0` 曾是无参预载），按签名匹配。卡片字段名混淆，用 `@SerializedName` 反射
（协议名稳定）；`args.tname` 无注解但字段名与 JSON 同名。取 getter 别用“最后一个非空”。

## 24. 配置持久化：无 root 主链路 + conf_gen 代次

设置页保存 SP + conf 副本（每份带毫秒代次 `conf_gen`），再用显式 ComponentName 拉起宿主
并附 `conf/gen` extras；宿主 launcher Activity 的 `onCreate`/`onNewIntent` 截获，写入宿主
files 的 host-conf 并热替换内存配置。读取端多来源、代次大者胜、同代先到先得；无代次的旧副本
永远盖不过新代次。注意：Android 11+ 对未声明 `<queries>` 的模块
`getLaunchIntentForPackage` 返回 null，必须显式组件启动；只有用户手动改动才拉起宿主；
验证前先 force-stop 宿主，否则还跑着旧模块代码。

## 25. 设备验证先排除“三重新鲜度”

① `install -r` 不杀宿主进程，先 `am force-stop`；② LSPosed 日志按会话轮转、文件名含冒号，
按字面文件名或取最新文件；③ 设置页 UI 自动化用 uiautomator dump + bounds，别盲点坐标。

## 26. fnval 位改写必须“先清后设”

宿主自己会按“解码器存在即支持”预置 AV1(0x200)/AV1 软解(0x800)/HEVC(0x10000) 位。
模块若只做 `nv = v | bits`，就是 no-op。必须对目标位 `nv &= ~bits` 后再按需置位。
档位语义：codec_mode 0=自动（按硬解过滤）1=锁 HEVC 2=锁 AV1 3=锁 H264（清全部高位）
4=关闭；hdr_mode 0=自动 1=HDR 2=Vivid 3=强制关 4=关闭；audio 0=自动 1=AAC 2=杜比
3=无损 4=关闭。`soft_fnval`：bit0=AV1 软解、bit1=HEVC 软解，同样先清后设。
仍只改请求位；解码偏好 hook 仅在实发流 codecid 与锁定一致时确认，否则交还原结果。
codecid 字段名 6.3.0 为 `y`、6.4.0 漂移为 `z`（`y` 变常量 2）。

## 27. 顶栏叠层不能依赖容器位置

服务器可在顶栏下方动态下发分区栏，`addView` 到末尾 + 负 margin 的叠层会跟着错位。
正解：把内容行（child 0）包进 `FrameLayout` wrapper，wrapper 继承原槽位与原
LayoutParams（含 weight），overlay 作为 wrapper 第二个子项同尺寸不可点击；幂等按 tag
递归查找，重复 decorate 只补 overlay（双层包裹会留空行）。

## 28. 身份链失效：先查宿主版本，再查形状与写入点

* 失效第一步永远是 `dumpsys package` 查 versionCode/lastUpdateTime——用户说“没更新”不算；
* 单字母类名跨构建会撞名：6.5.0 的 `up1.a` 被无关类占用，旧校验“有 a 方法”照样通过。
  形状校验要收紧到“无参非抽象 + 返回类型带 (String,byte[]) 构造器”；
* hook 包装方法的 pre-proceed ≠ 方法体内联：头提供者的调用在 `header.b.b()` 原始方法体
  里发生，pre-proceed 时头存储还是空的。正确锚点是写入口
  `kntr.base.moss.ignet.impl.grpc.c.f(String, byte[])`，AFTER 用
  `chain.proceed(new Object[]{key, rewrittenBytes})` 替换参数；
* 6.5.0 提供者层：接口 `kr1.d` + 抽象 `vr1.a` + 5 子类，逐个 hook 必漂；主改写点必须上移到
  真名类 grpc.c.f，提供者 hook 只留旧版兜底；
* 验证闭环四段：armed 行 → grpc write fired（key/scope）→ 改写探针 → 屏幕上看评论区属地。

## 29. 形状扫描找漂移锚点

* **方法形状扫描**：全 dex 找“指定名字 + 返回类型 + 无参”的类。`kJ1.a` 就是与 `GI1.e`
  逐字段同构（单例/I 缓存/J 缓存/两个懒加载 boolean/c()I/d()J）再核对位运算确认的。
  注意 encoded_field/method 的 idx 是 uleb **差值**；`direct` 是 static|private 的合集，
  别把 `direct` 当 `static`；
* **接口实现扫描**：按 interfaces/superclass 找实现类。听模式完成监听器先扫
  `IMediaPlayer$OnCompletionListener`，再用“类有 OnRawDataWriteListener 字段”锁定核心
  （6.4.0 `RI1.r` ↔ 6.5.0 `vJ1.m`），字段表槽位 H 即监听器。R8 会把单字段监听器横向合并成
  `(Object,int)` 合成类，签名不变即可进候选；
* **方法参数类型扫描**：扫 method_ids 参数含目标类的全部方法定位消费方。
* Compose 渲染过滤有固有竞态：容器 lambda 在首帧构造并捕获 tab List，hook 晚于首帧就输；
  invoke 路径只在父作用域重组时触发。6.5.0 实测不可靠，**已决定不适配**，原 11 参 hook
  只服务 6.3.0/6.4.0，找不到形状就注记跳过（“我的”保持默认显示）。

## 30. 缓存命中别只读一个日志字段

`disk=0 net=全部` 可能只是记账被异常跳过：`diskBytes = from - start` 取的是 `serveCached`
返回值，播放器挂断让 `sink.onChunk` 抛 IOException 时赋值整段被跳过。
修法：字节离开缓存那一刻就累计，账面按 `min(disk,out)` 截断。一个字段说“没命中”、
文件 mtime/网卡增量说“没走网”时，先怀疑账面。自测要逐字复现症状（旧代码打出同形读数）。

## 31. 对端挂断 ≠ 节点故障

播放器饿缓冲会并发开多条连接、每条约一块就断。把“已交付后写 sink 失败”记成节点失败，
会封禁自家镜像 → 其余节点退避 → 更慢 → 并发更多。修法：sink 写失败单独捕获，取消令牌、
安静返回、零记账；CDN 掉线才退避，并把真实字节数传给封禁表。任何失败计数先问“这是谁的失败”，
客户端侧退出路径（取消/对冲输家/挂断）都不能算服务端。

## 32. once 探针会掩盖长会话中的钩子失效

once-per-process 探针只能回答“进程启动时钩子活过没有”，回答不了“现在还活着吗”。
长生命周期进程里的脱钩要用与业务同频的滚动计数：改写点挂“进入 +1 / 实际改写 +1”，
低频打点（每 200 次一行）。判读：RPC 计数前进而进入计数停滞 = 脱钩；两者都前进但用户可见失效
= 服务端/传输变化；改写计数前进但无效果 = 服务端无视身份。
资源 id 每个构建重排，`findViewById` 常量只做快路径，兜底用 `getIdentifier` 按名解析。

## 33. 完成回调不是唯一“播完”信号

听模式里吞掉完成事件后框架仍会在 138ms 后自己起播下一集（独立状态机/快路径），
听页面跑在 `com.bilibili.music.podcast`，与 cheese 的 `playselect` 无关。
修法两层：完成回调照旧停在片尾 + pause，并拉起 6 秒守卫窗；窗内拦截播放器实例上的
`setDataSource`/`prepareAsync`/`start`。列表指针仍会前进（UI 切到下一集），要连列表都不动
得再拦框架决策点。**拦动作优先于拦通知**：吞事件后行为照旧，就说明决策另有通道。

## 34. 并行子块拼回单流：计划坐标 ≠ 实际交付坐标

* 部分镜像会把中段子块按自己的短总长截短；若把截短当尾部 EOF 后直接跳到“计划下一个子块”，
  响应体留下空洞 → 花屏，拖进度条重新取数就好。修法：从**实际末尾**单连接续传到计划末尾；
* 响应已经开口后向上抛异常不是失败上报，而是把流砍尾（画面定格、音频照走）。失败兜底要区分
  started 前/后；子块永久失败也从该子块起点续传（等价官方单连接）；
* 兜底通道的候选过滤要松于快路径：续传时全部节点可能都在退避，passthrough 应退回 `allUrls`；
* 并行循环里给 sink 写失败补 `token.cancel("sink closed")`，与“挂断不算节点账”契约一致。

## 35. 6.6.0 漂移表与形状校验自身的坑

| 作用 | 6.5.0 | 6.6.0 | 真机验收 |
| --- | --- | --- | --- |
| fnval 计算 | `kJ1.a` | `aK1.a` | `fnval hook ok -> aK1.a.c()/d()` |
| 听模式完成监听器 | `CE1.f`（容器 `vJ1.m` 字段 H） | `lK1.j`（容器 `lK1.o` 字段 H） | `listen: completion listener resolved` |
| feed 解析入口 | `pegasus.request.g.a` | `pegasus.request.h.a` | `feedtag: hook ok` |
| 直播后台播放门 | `HX.c$b/$c.q1()Z` | `JX.b$b/$c.m1()Z` | `livebg: … -> JX.b$b.m1` |
| gRPC 描述符族 | `kr1.a..n` | `xr1.a..n`（14 类一一对应） | 评论区/空间页 A/B 双证 |
| 听模式 cheese 工厂 | `PlaybackMode$a.a(I)` | 真名未漂 | 无需改动 |
| 首页底栏过滤 | khome `gE1.e` | 同左 | `filter armed on gE1.e` |
| 底栏动作总线 | `w0(LjD1/b;)` + `jD1.c` | `v0(LbE1/b;)`，但点击链已不走总线 | 候选表留空 → 解析失败兜底 |

* **形状校验自身会写错**：反射里 `getName()` 对基本类型返回 `boolean/int/long`，不是 dex
  描述符 `Z/I/J`。校验恒 false 时日志却像“类又换名了”。候选全失败要打出每个候选的失败原因；
  判定分支与报错共用同一张表；旧名在 6.6.0 已被 R8 复用成无关类，只加新名不够，必须过形状。
* **评论区改写收口**：6.6.0 的 `grpc write seen=N (rewritten=0)` 是正常——`x-bili-device-bin`
  没有 mobi_app token（改写函数返回 null 放行），`x-bili-metadata-bin` 不走这个写口；
  真正起作用的是 REST 公共参数改写（类名里的“space”是误名）加空间页时间窗。
  验收看属地标签，不看这个计数器。旧的 `Aq0.a/Cq0.a` REST 拦截器已不存在。
* **假锚点**：`bE1.c` 与 `jD1.c` 逐条同形，但全 dex 52 个 `bE1.*` 动作类的 53 处构造点里，
  底栏点击链一处都不 new 它——它是搜索半屏的“路由索引”。**形状命中只是入场券，
  “谁 new 它”才是角色证明**；6.6.0 头像→我的直接投 `bE1.g(url,0,2)`，见 #37。
* 6.5.0+ 已消亡的 main2 底栏与 mini-player biz 层属预期 miss，日志降为 debug，ERROR 只留真故障。

## 36. 首页直播板块：一份数据、两个消费者

国际版顶栏没有“直播”是服务端按身份裁剪 `tab/v2`，不是客户端过滤。
三条死路：只补标题流的 args[0] → 标题 3 个、pager 2 页；怀疑页过滤器——实测没丢；
以为有两个 `PageBuildComponent`——后者被 R8 改名且管的是底栏。
正解：顶栏标题与 pager 共用同一个组件的 `w(List)`（全 APK 仅 3 处调用），在 `w()` 入参注入；
标题点击按 `adapter.indexOf(uri)`，页列表里没有就 -1 直接 return。
必须**追加**不能头部插入（FragmentStateAdapter 按位置取 id 会整体错格）。
A/B：关开关重启宿主，顶栏从“推荐/动画/直播”退回两项且注入日志消失。
抗漂移：`PageBuildComponent` 是真名（6.3.0–6.6.0 未变），过滤器按“唯一 (List)->List 实例方法”
兜底；数据类字段字母四版同序，但验收看值（uri 含 `://`、id 纯数字、name 非空）。

## 37. 渲染级隐藏 ≠ 数据级隐藏

合成点击若用数据层槽位/数量算比例，会在“底栏少画一格”时点错（(2+0.5)/3 落在左格）。
修法：渲染钩子每次执行写渲染快照（`renderedTabCount`/`renderedMineIndex`，隐藏为 -1），
合成点击只在 `renderedMineIndex>=0` 时用快照算。**唯一写入点只能是渲染钩子**；数据钩子
不许写快照，渲染钩子不许删数据。
6.6.0 点头像进“我的”的正确派发（四版同形）：
`HomeFrameViewModel.v0(new bE1.g("bilibili://user_center/mine?bottom_tab_id=我的Bottom", 0, 2))`。
路由匹配键在 url query 的 `bottom_tab_id`/`bottom_tab_name`，两个都取不到就整发空转；
键值应从宿主下发的那格数据现读。别抓 DI 组件实例（`PageRouteComponent` 构造器长度 0），
直接投它本来要投的动作。版本表：6.3.0 `FA1.g`/`x0`、6.4.0 `FC1.g`/`w0`、
6.5.0 `jD1.g`/`w0`、6.6.0 `bE1.g`/`v0`。

## 38. 对照轮可能整轮跑在默认值上

日志短标签不是 conf 键名：`ip` 是启动日志的别名，真正的键是 `ip_location_enabled`；
未知键被静默跳过 + 有效键为空就当没有副本，结果 `confSrc=defaults`，等于又跑了一遍默认开。
键名唯一权威是配置类的 `ALL_KEYS`。宿主多进程，每进程都打 `confSrc=`，对照只看主进程那行；
判断配置生效的依据是来源+代次，不是行为差异。
写宿主 files 副本三道工序：宿主 uid/gid、权限 660、SELinux `restorecon`；少标签那步
文件在、权限对、进程读不到。`/data/local/tmp` 对宿主不可用，只能开发兜底。

## 39. 移植老功能：字段在 APK 里 ≠ 数据走这条路

* 接缝整族可能已消失（`BaseTMApiParser` 在 6.6.0 查无此类；解析出口是 `request.h.a`）；
* 类在、字段在也可能是死类：`SmallCoverV2Item` 0 个 new 点，只是随包残留的 API 模型。
  但**“0 new”不能证明死类**——gson/fastjson 反射实例化不走 new 指令；判生死要用
  “谁声明该类型字段/谁读 getter/屏上有没有数据”；
* 载体换代：6.6.0 推荐流是 holder 族（`LXD0/a;` 等实现 `PegasusHolderData`），
  属性用 getter 发；6.4.0 是 `BasicIndexItem` 后代 + public 字段。读取必须 getter 优先、字段兜底；
* 路由改写要落在流经现场的那份 uri 上，`getUri` 的调用点数量只证明“有人调”，
  不证明“这一代数据从这走”；
* 读属性能用 `@SerializedName`（协议名）就优先用，Java 字段名只做兜底；写路径只能按协议名，
  因为字段是单字母 private final 且无 setter；
* 删卡必须有 fail-open：整批不匹配判据时不过滤并告警，单卡读不到按未知保留；
* 静态查表只能证明“存在”，不能证否“不存在”。判死要有现场样本（uiautomator 的
  resource-id/contentDescription）或“没有任何类持有它”的证明。

## 40. 改写型钩子最坏是把宿主点崩

把 `bilibili://story/<id>?<preload>` 只换 scheme 为 `bilibili://video/<id>` 并保留整段 query，
落点确实换成横屏，但播放页在 onCreate 抛 Dagger 递归异常直接崩宿主。
四条分离变量证明：原生卡正常；最小 uri 正常；整段 query 经路由外壳也正常；
只有“卡片点击 + query”崩。
修法：改写成目标页的**规范最小形式**（`scheme://authority/<id>`，query 全丢），
不是在源串上做最小替换。验收三件套：落点对 + 进程还活着 + 崩溃缓冲 0 条。

## 41. “模块没注入”常是读数假警报

模块日志按会话轮转（文件名带启动时间戳），脚本写死旧文件名就永远 tail 到 0 行；
重装模块会换安装路径，“库里记着旧路径”不能当判据。可靠判据：
① 新宿主进程有 `hook installed`；② `confSrc` 是主进程那行且 gen 对得上；③ 功能生效行出现。
读到结尾带 `-----part N end-----` 的文件就是历史分片，先换活动件。

## 42. 数据面写成功 ≠ 渲染层有东西

给卡片填 `desc_button` 后日志 `filled=4`，但这代布局根本没有该槽位：
uiautomator 底行只有 `bottom_layout/real_desc/desc_v3/more`，
`getDescButton` 全仓只有 2 个调用点且都在 `onClick`。宿主名字行本来就进空间页，
MBGA 那半边是冗余。功能的上游实现不是需求来源，屏幕才是：抑制类看“少了什么”，
新增类看“多了什么、点它去哪”。自己的计数日志只证明代码跑了。

## 43. 起播 0 KB：凑满一整块才吐

旧 `stream()` 把起播段当普通子块（256 KiB），收满才第一次 `sink.onChunk`，而响应头要等
第一次交付；同时并发 8 条对冲候选各占一个 gate 许可，播放器又同时开 6–8 条流 → 首字节排队。
修法：1 字节探路 → 单连接边收边吐（每口 ≤64 KiB，不凑满）；探路超时只算退避不算封禁；
起播段 6 s 总预算。测试替身必须加“每字节耗时”，否则测不出首字节问题。
这条修法后来又把探路串在起播前，见 #47。

## 44. 清单只在连接关闭时落盘 = 播放中途没有缓存

`.bin` 是稀疏文件、字节当场落盘，但 `.idx` 覆盖清单只在 `close()` 刷；视频轨是一条
`req=0--1` 的长连接，播放期间不关闭，进程被杀就永远没有清单。修法：每落盘 8 MiB 刷一次，
关闭时照旧；在 `req=` 行末挂
`cache[spans=… covered=… last=… total=… usable=y/n idx=ok|missing]`。
判读：`idx=missing`=清单没落盘，`spans=0`=那段没下过，都正常却走网=请求起点落在洞上。
共享状态的可见性边界是“落盘”，不是“写入”。

## 45. 反编译印出的 `public` 不能信

protobuf 的 `clearActivityMeta()` 被 jadx 印成 public，`publicMethod()` 却抛
`NoSuchMethodException`——实际是包级/受保护或被 R8 收紧。取句柄统一用
“public 优先、`declaredMethod` + `setAccessible(true)` 兜底”。
同族坑：消息已换成 protobuf-lite，`activityMeta_` 是 `ProtobufList<String>`，
老 `repeated ActivityMeta` 类型不存在，移植要按当代字段类型重写。

## 46. “查不到调用点”证不了“数据不驱动渲染”

R8 会把 trivial getter 内联成字段访问，调用图上没有那个点，数据照样被读走。
清空 `activity_meta` 的挂点/命中/清空都有日志，但“浮层从屏上消失”未做活动期肉眼 A/B，
注释、设置页、发布说明三处都写明边界。
另一例：按常识断言大封面卡 `card_goto=av`，实测日志是 `large_cover_v9/inline_av_v2`，
①本来就能删掉它——“按老版本推断”的值域必须先让现场日志验证，再写进用户可见文案。

## 47. 探路串在起播前 = 新造延迟

#43 的 1 字节探路本身是一次完整 TCP+TLS 往返，串在起播请求前面后，首字节变成
“探路 + 起播”两段（实测 probe 858–948 ms、first byte 1000–1276 ms）。
修法：第一条候选的起播请求与其余候选探路同时出发；更快的邻居答应且原连接还没回响应头时，
在“同级往返”窗口后顶掉它；顶掉与写入用一把锁保证只有一个写手。
许可从“起播 1 + 探路 3”降到“起播 1 + 探路 2”。
账要落在此刻（每流一行 `startup:`），关闭时才写的 `req=` 分不清节点慢/许可排队/探路白花/没进代理。
跨时段两组数不能当同网 A/B 讲。

## 48. “清完留一条空白行”的多层假象

* 代码层：把“推荐理由”整对象置 null，宿主把对象存在当“名字行要渲染”的信号，整行消失；
  改成保留对象只清 `text`/配色/图标；
* 度量层：开启轮统计 5 张“空白行”，关闭轮同样口径 6 个——那是本来就没 text 的外层容器。
  A/B 的异常计数必须两个方向都读；
* 现场层：探针打的是网络新批，屏幕渲染的是缓存旧批（首页不自动刷新），两批标题不相交，
  任何“屏上读数”都对不上。先证明两边是同一批数据；
* 探针自身：整条 uri 两万字节把后面的 badges 截掉，看起来像“没角标”；
  探针值统一截 48 字符并标原长度，加 title 当关联键；
* v1.9.2 补全：带 `rcmd_reason_style` 角标且 `desc=null` 的小卡，名字位读的是 `desc`
  而不是 `args.up_name`；清角标时若 `desc` 为空且有 `up_name`，回填 `desc`。
  修“整行不渲染”和“渲染了但取空值”是两件事。

## 49. 取稿件的身份锚点：unite 族的短名在 `b`、全名在 `a`

一批稿件在国际版详情页直接「视频不见了哦」，而同一手机同一网络的第三方客户端能播、PC 网页也
能看 —— 不是 IP 区域封锁，是服务端按 `mobi_app` 裁剪下发，所以抓手和 IP 属地是同一个：把
`android_i` 换成 `android`，只是要换个位置生效。

* 定位手段：限定档下把 moss 服务名清单升到 info（`probe_enabled`），打开一次详情页即拿到
  `svc=View pkg=bilibili.app.viewunite.v1`、`svc=Player pkg=bilibili.app.playerunite.v1`。
  注意全局档看不到这份清单——判定函数在全局档提前返回，取证必须先切回限定档。
* 真正的坑：这两族的描述符字段填法与评论区**相反**，短名在 `b`、全名在 `a`，而评论区
  （`bilibili.main.community.reply.v1`）是全名在 `b`。沿用「拿 `b` 比全名前缀」的写法对
  view 恒不命中，且不会报错——只会表现成“改了代码但视频还是打不开”。判定必须同时认 `pkg`。
* 判据要能反转：中间跑过一次全局档，详情页可能留缓存，所以“加了锚点就能开”不够——把那一行
  摘掉重出一版，同一个 BV 必须重新打不开，才算排除缓存假阳性。
* 全局档（`ip_scope_mode=0`）在 6.6.0 仍然有效（`grpc write seen` 照涨、改写照生效），但它的
  覆盖面是全部 moss 请求，不该拿它换这一处收益。

## 50. “每个新对象一行”在真机等于“每条请求一行”

网络取证行原先按「每建一个解析器打一行」写，实测一次播放两行（视频流＋音频流两个 key）、
长会话一小时 1067 行——日志自己变成噪音，把真正要看的行埋掉。

* 归因先错了一次：怀疑是空间页开关行，按 pid 分组统计后它只占 50 开 + 50 关，真大头是那条
  取证行。刷屏必须按 pid 分组数行，不能凭印象。
* 统计口径也会错：用 `net=` 计数得到 11 条，其实是撞上了流完成行里的 `net=字节数`。
  数某类行要用只有它会写的标记（当时是 `delivered=`）。
* 根因不在日志：解析器缓存键带着 query，而播放器每重连一次整批重签（deadline/trid/upsig…），
  于是几乎每条请求都新建一个解析器。per-object 的日志之前先核对该对象在真机上多久建一个。
* 落点：观测要么挂在事件上（降级真的发生），要么搭已有那一行的顺风车——现在 `startup:` 行末
  带 `fp=`/`demoted=`，正常播放零新增行，同时「这条流属于哪个网络」随时可读。
  缓存键本身没动：换成文件级键会连带改变退避记忆的共享范围，那是另一件事。
* 顺带一条：手写文件去验证一条持久化路径，先确认模块真的读那个路径。账本落在
  `files/bili_tamer_accel/` 子目录（`AccelHooks#resolveAccelDir`），写到 `files/` 下面是
  无人读取的静默失败——两轮「投毒没生效」全栽在这，跟属主和 SELinux 标签都无关。
  读数改成 `demoted=` 之后一眼就能看出账有没有读进来，这个验证才算跑得动。

## 51. 「网络身份」算错一格，跨进程那本账就等于没写

不可达节点账本的键是（网络身份，节点）。网络身份连着算错两次，两次都是靠把 `fp=` 挂到
`startup:` 行末才看见的——没有这个读数，这两条在日志里长得跟「功能没生效」一模一样。

* 第一版拿「playurl 本次下发的 host 集合」做哈希。听起来正解（下发什么就是什么网络），实际在线
  通路唯一改得到地址的锚点只返回一条 base_url，backup 列表恒空，于是「host 集合」恒为一个元素，
  指纹退化成「这条流母本域名的哈希」。真机同一次播放里视频流母本 cosov、音频流母本 akamai，
  两把身份两本账，在 cosov 那本上记下的死节点 akamai 那条流一个都读不到。
  判据就在旧取证行里：`delivered=1` 从来不是巧合，它就是这条错的证据。
* 第二版改成进程级，但把全部非回环网卡都进了哈希：`rmnet_data*` 跟着基带拨号起落，同一张 wifi
  上前后两次起播算出 `9ffa3b5e…` 和 `57f8231b…` 两把身份，盘上刚写下的降级下一趟查无此书。
  判据：同一台机器、同一个网络，跨进程的 `fp=` 必须逐字相同；做不到就是身份里混进了会漂的东西。
* 落点：只认局域网网卡（wlan/eth）的 v4 地址；不在任何局域网里就归到一个常量蜂窝桶——运营商每次
  拨号都换出口 IP，按地址记会让蜂窝下永远攒不起账，而蜂窝恰恰最需要「别再撞死节点」。
* 附带一条量法：「播放器挂断就不记节点的账」这条豁免同时把日志和记账一起吞了（挂断路径直接
  return，既不打失败行也不落账），所以被吞的次数从日志里根本数不出来。拿防火墙的 SYN 计数器当
  分母才量清楚：改前 5 次被拒 → 2 条可见失败 + 3 笔记账；改后 4 次被拒 → 0 条可见失败 + 5 笔
  记账。链路连不连得上是机器的性质，不是这一条流的性质，挂不挂断都该记。
* 同一本账的两个进程原来并不会互相累加：主进程与 `:ijkservice` 各写同一份文件，落盘前的读盘合并按「谁的
  时刻新听谁的整笔」覆盖，另一进程那份较小的 strikes 把刚写下的较大计数顶回去，`after 2 unreachable tries`
  因此在同一次播放里印两遍——看着像「降级两次」，实际是计数被顶回后又重新攒够。改成两条线各自取自己最新的
  一笔（`note()` 先读盘再计数），修的过程里踩到三个坑：
  - 同一毫秒的平手必须让「计数大的赢」。否则刚写进盘的那条较小计数会顶掉内存里较大的，等于没改。判据是
    量出来的：拿 20 轮「A 记两笔、B 记一笔」的循环，要求每轮降级行恰好印一条；把平手判据从「平手取大」
    退回「时刻不小于就采纳」，立刻 4/20 轮印两遍——这条测试才算真的红过。
  - 空转独立记时间戳之后，只有空转的那行 `strikesAt` 是 0，而读盘的合法性校验写着「时间戳必须为正」，于是
    整条空转记录被当非法丢弃：内存里判对，落盘再读回来就没了。手写一行只有空转的记录、`new` 一个账本读回来，
    才是这条路径的判据；正解是合法性只看「两条线是否都过期」。
  - 「交付过字节」那条 0/0 墓碑必须照写。取最新而不是取最大之后，不写墓碑就等于让另一进程那份旧的失败计数
    凭时间戳赢回来，销账失效。
* 零字节失败有两种，证据强度差一档，所以分两本账、门槛也不同：`UnknownHost`/`ConnectException` 是这台机器
  叫不应那个名字，与这条流无关，两次就降级；`SocketTimeoutException` 发生在「拨通了、首字节没来」，一次全网
  卡顿足够让 8 个候选各攒一笔空转，全降级＝排序不变、白丢记忆，所以给到 3 笔，且仍旧只挪队尾、不删候选。
  分得开的依据是通道里 `firstByteTimeoutMs` 同时当连接超时用，超时于是必然意味着连接已建立——排队时间不算，
  三处拨号都在拿到并发闸之后才开始计时。
* 4xx/416 一律不进账本，这是刻意的：账本的键只有（网络身份，节点），没有（节点，地址）那一层。416 说的是
  「这条流没有你要的字节区间」，属于文件与签名的争执，归封禁表按地址处理；写进账本会因为同一个域名同时服务
  多个文件，把一次缺文件放大成整台机器在任何视频里被降级。
* 另一条设计张力（不是 bug）：节点一旦被降到队尾，正常播放几乎不会再拨它，于是「交付过字节就销账」
  那条恢复路径走不到，实际靠十分钟 TTL 自愈。想让恢复更灵敏，调 TTL 比加探测更划算。

## 52. javac 不删输出类：删掉的匿名内部类照样打进 apk

* 症状：删掉 `LiveBgHooks.installGateDump()` 重建后，`gate dump`、`onPlayerServiceEvent` 都从
  `classes.dex` 里消失了，`livebg[gate]: w0=` 却还在——同一段代码的字符串一半没删干净。
* 根因：`build/app_classes` 是增量目录，javac 只覆盖同名输出。匿名内部类的编号按源码里出现顺序排，
  删掉一个方法就把 `LiveBgHooks$2`（探针的 `Hooker`）空出来，而旧文件还躺在目录里，被 d8 一起 dex 进包。
  它是死代码（没人再 `new` 它），但会跟着 Release 发出去。
* 判据两条：① 拿 apk 的 dex 字节 grep「这次删掉的字符串」，命中即残留；② 比对 `build/app_classes/**/*.class`
  的 mtime，早于本次编译的就是旧件。**别看 apk 大小**——这一轮前后都是 160438 B，看着像没重建。
* 修法：`tools/build_module.py` 编译前 `shutil.rmtree(build/app_classes)`。只改本项目的入口脚本，
  `tools/engine/builder.py` 是和 QQ 共用的引擎，别为了这件事去动它。

## 53. 宿主升级轮的验收顺序：总闸基线 → 开关对照 → 才允许说「环境问题」

* 6.7.1 这轮在线取流停在 0 KB/s。先跑的对照是 `accel_enabled=false`，**这不算基线**：`ip_location_enabled`
  默认 true，moss/grpc 那几枚钩子照挂。换成 `master_enabled=false` 并确认该 pid 零条 `hook installed` 之后，
  一样 0 KB/s，才允许把结论写成网络/账号侧。
* 「链路通不通」要一条与模块无关的读数：手机上 `curl -o /dev/null -w '%{http_code} %{time_total}'` 直连
  `upos-sz-mirror*` 拿到 959/403、0.3 s 返回——403 是缺 Referer 与签名，不是超时，和「连不上」是两回事。
* 抑制类开关的 OFF 腿必须配「值域探针照打」这一条：探针与开关无关，它证明解析出口这一批确实被走过，
  `removed` 归零才是「关对了」而不是「钩子死了」。
* 词表类功能（`feed_blocked_tnames`）的正证别猜分区名：把 `tname=` 搭在已有探针行末尾跑一轮，用现场值当
  词表，`blocked 1 card(s) [植物大战僵尸]` 这一行才算同时验到 `getArgs()` 与 `@SerializedName` 两条读路径。

## 54. 只在新宿主上验收 = 给旧宿主发回归：锚点写死混淆名 + 「装上即成功」的兜底链

* 触发场景：荣耀那台宿主是 6.6.0，小米是 6.7.1。6.7.1 的离线锚点这轮写成加载
  `kntr.base.downloader.c`（builder `c$b`），而 6.6.0 上同一个实现类 `KDownloaderImpl` 实现的接口叫
  `b`、builder 叫 `b$b`——名字加载失败，enqueue 锚点整条落空。
* 更糟的是兜底链：`installKDownloadServiceUrl()` 的「成功」只代表钩子挂上了，而 `KDownloadService`
  与 `onStartCommand(Intent,int,int)->int` 在 6.6.0 里形状完全对得上，于是它 `return true` 把后面的
  `SingleSpec` 挡掉。真机早就证明这条服务路径不带 URL（新下一集零日志），**用「装上」当「走通」，
  一条已证伪的旁路就能吃掉唯一能用的锚点**。
* 修法两条：① 接口按角色取——实现类里唯一的 `kntr.base.downloader.*` 接口，方法返回类型是该接口的
  嵌套接口即 enqueue，两版同一份代码；② 已证伪的旁路只作旁路，不再作为链路终点。
* 判据：改锚点时把同一条角色谓词在**每个受支持版本的 apk** 上跑一遍（dex 扫描按类描述符 `L...;` 过滤
  旧宿主），别只在新宿主上验；发版前若第二台设备版本不同，这条就是必做项。
* 同一形状在 6.7.1 又撞了一次（底栏「我的」）：渲染级隐藏的锚点写死了类名 `bottomtab.g`，
  而 6.7.1 里容器挪到了 `bottomtab.j`（`g` 退化成一个 Function3 lambda），日志老实打了
  `not effective ... skip`，用户侧就是「我的 tab 又出来了」。修法同 §54：类名只作候选表
  （`BOTTOMTAB_CANDIDATES`），认签名 11 参 / p1=List / p9=Composer / p10=int 找函数。

## 55. 「身份取值」是一整片开关：换 `mobi_app` 一个词，国内广告面整片消失

* 起因：IP 属地/稿件可见性把 moss 二进制头里的 `android_i` 报成国内版，服务端按国内版排包，
  于是「视频提及」游戏推广卡、播放器下挂商品卡这些**国际版原生没有**的槽位全下来了。
  逐位拦是治标，能不能换个取值让服务端根本不发？
* 单变量实验（小米测试机，宿主 6.7.1，同账号同一条稿件「热门枕头从夯到拉排行榜…」，
  2026-10-28 14:14–14:26，只改 `rewriteMobiAppBytes()` 的 `newStr`，REST 侧一行没动）：
  `android_hd` 两轮（pid 32630 / 29935）简介区 8 屏/4 屏里 `视频提及`=0、`ivGameIcon`=0、`星途天城`=0；
  换回 `android`（pid 30424 / 25087）同一条稿件前两屏三项 = 1/1/2；再切 `android_hd`（r2）又归零。
  30 分钟内 hd→android→hd 三次往返、同一判据 ⇒ 不是广告轮换漂移。
* 未回掉的半边：`android_hd` 下评论区属地 6/6 与 `android` 档同值（`中国香港`/`湖南`），
  「换 hd 就拿不到 IP 属地」这个担心当场排除。
* 补上的一格（同机同账号，锁区正样本 aid=117512234668259「中国建造了大量的数据中心…」幽默老日，
  路径固定为冷启动→我的→历史记录→**按标题**点开（设备驱动脚本按这条路径逐格跑）；此前搜索页
  `input tap` 不落地，改走历史页就绕开了）：ip OFF 档这一条在历史页标成「已失效/稿件失效」、
  点进去「加载失败/页面加载失败，请重试」且重试仍失败；`android` 档与 `android_hd` 档都渲染出
  真实标题、详情页读数一致（评论 568 / 播放 6.3万 / UP 幽默老日）、连拍三帧互不相同=在播。
  ⇒ hd 不牺牲锁区解锁，hd 档三项（属地、锁区解锁、广告面）全过，**moss 头已正式取 `android_hd`**。
* 仍留的口子：① §5 那条 REST 主页/空间页取 `android` 的旧结论没回头补验（用户指示跑掉），
  两条链各自独立取值，所以现在 moss=hd / REST=android 是**故意不对称**，别顺手统一；
  ② 只验了 UGC 一条稿件，番剧/影视类锁区、以及播放音质/解码档位会不会因端不同，都没测。
* 判据：这类「取值」实验必须同一台机、同一账号、同一条稿件、同一取帧步长（400px×8 屏），
  且每轮都拉一遍 `ip[probe]: grpc 改写生效` 与 `fired scope=` 证明钩子在这条进程里真跑过——
  否则「界面干净」和「根本没进页面」分不清。
* 出货包复验（1.9.7/35，小米测试机，出厂默认 conf、`confSrc=defaults`）：反汇编出货 dex 直读
  出货 dex，`rewriteMobiAppBytes` 里成对常量是 `android_i` → `android_hd`；运行时日志
  `ip[probe]: grpc 改写生效 x1 x-bili-device-bin / x2 x-bili-metadata-bin` 与
  `ip: space rest params rewritten mobi_app android_i -> android` 两条链各自生效（不对称如设计）；
  锁区正样本同路径照开（`UnitedBizDetailsActivity`、评论 580、连拍三帧不同）；评论区属地 7/7
  （`19小时前 四川`/`20小时前 广东`，见 §56）；枕头那条 8/8 屏四个标记全 0
  （同一套设备驱动脚本跑广告扫描格，同一条稿件在广告拦截关掉时的正样本 dump = 1/1/2）。

## 56. 按可见文案数「有没有」之前，要先枚举这个字段的全部渲染格式

* 复验属地时评论区扫描脚本报「评论日期行=0 带属地=0」，看着像换 hd 把属地打掉了。打开当屏
  dump 一看：面板是开着的、评论也在，只是这条较新的稿件把时间渲染成「19小时前 四川」，而正则只认
  绝对日期「9月22日 中国香港」⇒ 一条都匹配不上。**判据本身错，不是被测对象错。**
* 修的地方两处：① 时间分支补全（分钟前/小时前/天前/昨(天|日) HH:MM/今天 HH:MM）；② 「带属地」
  的判据从 `split("日")[1]` 改成「这条文案里有没有空格后缀」——只补①不补②，新分支照样数不到。
* 判据：凡是拿「某段文案出现与否」当功能开关读数的脚本，先在真实 dump 里把这个字段的所有渲染形态
  列全（绝对/相对时间是同一字段的两种形态），再谈计数；读数为 0 时的第一动作是回去把那屏 dump
  打开目视找这个字段，而不是先接受「它没了」。

## 57. 「比较新旧」的基准必须在变更动作之前取；解析函数顺手改全局读数＝自己判自己陈旧

* 投递修复①②③ 的第一版 1.9.8（`f4f3310b…`）装机后，设置页手动改一项，宿主侧只留下一条
  `conf delivery: stale (incoming=1900000000015 current=1900000000015)`，没有 `conf delivered`
  ——第一次投递把自己判成陈旧件丢掉，改的开关根本进不去。根因是 `BiliConfig.fromConfText()`
  在解析的同时把全局读数 `sConfSource/sConfGen` 写成了**传入**代次，而 `deliver()` 在它之后才
  取 `currentGen()` 做比较：基准已经被这次待采纳的配置污染，`incoming <= current` 恒成立。
  1.9.7 之所以没事，是因为那版 `fromConfText` 不碰 `sConfGen`（这个静态是修复③新加的）。
* 两处改法：① `fromConfText` 做成纯工厂，不写全局；「生效来源/代次」由调用方在**采纳之后**盖章。
  ② `deliver()` 里 `currentGen()` 上移到解析之前。顺带补一条：stale 分支也要发回执
  （`sendConfAck(actObj, nonce, current)`）——文件通道先到位、Intent 只是重复时，宿主确实处在
  这个代次，界面报「没投递」会逼人反复划掉重开。
* 判据：凡是「新值 vs 现值」的门（代次、时间戳、hash 比对），先单测一条**只投一次**的路径。
  两次以上才成立的用例（重复 Intent、A/B 交替）会掩盖基准污染；只看编译通过 + 日志有输出，
  看不出「输出的是被丢掉那条」。
* 另一条同期踩到的：`confSrc=defaults` 只说明**宿主没读到任何 conf 文件**，不等于模块 SP 是出厂
  默认。这台小米的 SP 里留着前几轮实验的 `accel_enabled/feed_only_ugc/hide_*/no_auto_refresh=true`
  （清理轮只删了 conf 副本没清 SP），任何一次保存都会把整份 SP 灌进宿主。跑 A/B 前要么先在界面上
  逐项归位，要么把「SP 现值」也拉出来看一眼，别拿 defaults 读数当环境干净的依据。

## 58. 世界可写的 conf 落点要「先验键再降档」；su 多命令脚本不能拿退出码判成败，`cp FILE DIR` 还会套娃假成功

* 起因：root 兜底的落点从宿主 files 搬到 `/data/local/tmp`（小米实测 KSU 域在宿主 files
  **能删** Hook 建的那份、**建不了**新文件，`cat > …` Permission denied，MLS 类别继承不过去；
  而 tmp 是 shell 的家，ksu 可写、非 root `adb push` 落出来即 0666、宿主读得到）。换来能用，
  代价是世界可写——任何 root/shell 都能塞一份高代次 conf 抢配置。
* 读侧两道锁（`BiliConfig.loadForHook`）：① `conf_key` 防伪键——写侧 `kvConfText` 每次都带
  `BiliConfig.CONF_KEY`，**只有 tmp 那一档**要求匹配，可信通道（host-conf/模块副本/启动 Intent）
  不查，免得既有副本因为没这行而集体失效；② 过了键也仍降档，只在没有可信副本压得住它时才采纳，
  同代次算可信件赢。装机两条正读数：无键 gen=`…060`（高于 host-conf 的 `…030`）冷启动 →
  `confSrc=host-conf gen=…030`（拒）；带键 gen=`…070` → `confSrc=tmp-conf gen=…070`（采）。
  强度上限写进常量注释，别自欺：这把键编译在**公开 APK** 里，反解发布件就拿得到，它挡的是
  「随手写个 tmp 文件抢配置」，不是「专门冲着模块来的人」。
* 判据（§57 的延续）：「被拒绝」这种什么都不发生的结果，必须换成一条正读数来验。回前台腿拒绝时
  不打日志，同一手势随后在带键件上打出 `conf reloaded on resume` 只能证明手势有效、证明不了
  那次真的评估过；改用**冷启动的 `confSrc=` 行**——同一段选源代码，正读数，拒/采都看得见。
* `su` 脚本的成败不能用退出码：命令是 `A ; B` 形态，`sh` 只把最后一条的码交出来，而宿主 files
  那条腿建不了新文件属**正常**，会把已经成功的 tmp 报成失败。改成每条腿各自 `echo 标记`、读 stdout。
* 更阴的一条：落点若已是**同名目录**，`cp 文件 目录` 会把文件塞进那个目录里并返回 0，`chmod` 对
  目录同样返回 0——兜底报「已写入」而读侧永远找不到（19:52 那次实测正是这个假成功）。每条腿补
  `test -f` 才算数。
* 失败分支要找**可控的真实失败**做对照：`mv /data/local/tmp /data/local/tmp.bak` 十几秒再改回来，
  比去翻用户设备上的 KSU 授权名单干净（那条动的是他机器的安全设置）。这一趟还顺手抓到文案两个缺陷：
  另一条腿的 `echo` 标记混进失败详情、su 输出带换行会把状态行/logcat 撑成多行——摘标记 + 折平空白。
