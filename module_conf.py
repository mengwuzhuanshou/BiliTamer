# -*- coding: utf-8 -*-
"""BiliTamer 构建配置（供共享构建引擎 builder.py 读取）。"""
MODULE = {
    "package": "com.tamer.bili",
    "version_name": "1.6.0",
    "version_code": 10,
    "app_label": u"B站国际版增强 BiliTamer",
    "xposed_description": u"国际版哔哩哔哩 (com.bilibili.app.in 6.3.0/6.4.0) 增强：评论区/主页 IP 属地、HEVC/AV1 解码（顺位 AV1>HEVC>H264）、Hi-Res/杜比/AAC 音质（顺位 杜比>无损>AAC）、隐藏视频内互动提示",
    "xposed_scope": "com.bilibili.app.in",
    "dist_name": "BiliTamer-v%s.apk",
    "icon_png": "tools/icon/ic_launcher.png",
    # libxposed 模式：META-INF/xposed/java_init.list 声明入口，无 classic metadata
    "libxposed": True,
    "xposed_entry": "com.tamer.bili.MainHook",
}