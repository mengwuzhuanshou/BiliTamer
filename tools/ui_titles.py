# -*- coding: utf-8 -*-
"""ui_titles.py — 从 uiautomator dump 里按屏幕顺序抽可见文本，用来比对首页推荐流标题变没变。

用法：python tools/ui_titles.py <ui.xml> [y_min]（y_min 用来跳过顶部 tab 栏）
"""
import re
import sys

import xml.etree.ElementTree as ET


def main():
    path = sys.argv[1]
    y_min = int(sys.argv[2]) if len(sys.argv) > 2 else 300
    root = ET.parse(path).getroot()
    rows = []
    for n in root.iter("node"):
        t = (n.get("text") or "").strip()
        if not t:
            continue
        b = n.get("bounds") or ""
        m = re.findall(r"-?\d+", b)
        if len(m) != 4:
            continue
        x1, y1, x2, y2 = [int(v) for v in m]
        if y1 < y_min or x2 <= x1:
            continue
        rows.append((y1, x1, t))
    rows.sort()
    for _, _, t in rows:
        print(t)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    main()
