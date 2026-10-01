# -*- coding: utf-8 -*-
"""BiliTamer 构建配置（供共享构建引擎 builder.py 读取）。"""
MODULE = {
    "package": "com.tamer.bili",
    "version_name": "1.7.14",
    "version_code": 26,
    "app_label": u"B站国际版增强 BiliTamer",
    "xposed_description": u"国际版哔哩哔哩 (com.bilibili.app.in 6.3.0/6.4.0/6.5.0/6.6.0) 增强：评论区/主页 IP 属地、HEVC/AV1/H264 解码（顺位/锁定/关闭）、Hi-Res/杜比/AAC 音质（顺位/锁定/关闭）、HDR 控制、隐藏视频内互动提示",
    "xposed_scope": "com.bilibili.app.in",
    "dist_name": "BiliTamer-v%s.apk",
    "icon_png": "tools/icon/ic_launcher.png",
    # libxposed 模式：META-INF/xposed/java_init.list 声明入口，无 classic metadata
    "libxposed": True,
    "xposed_entry": "com.tamer.bili.MainHook",
}