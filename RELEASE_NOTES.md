# BiliTamer Release notes

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
