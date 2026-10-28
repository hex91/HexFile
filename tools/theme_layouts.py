#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v1.48.0 布局配色改造：硬编码 #RRGGBB → ?attr/hfXxx 语义引用。

按文件逐条精确替换（不同文件里同一个色值语义不同，不能全局替换）。
替换后布局只依赖语义色，切换主题 / 明暗模式时自动生效。

用法： python tools/theme_layouts.py
"""
import os
import re

# 每个文件：(文件内出现次数, 原文本, 新文本)
# 顺序敏感：先替换更长的模式，避免子串误伤。
RULES = {
    "activity_main.xml": [
        # 顶栏深色底 → 主题顶栏色
        ('android:background="#212121">', 'android:background="?attr/hfTopBar">'),
        # 顶栏白字
        ('android:textColor="#FFFFFF"', 'android:textColor="?attr/hfTopBarText"'),
        # 顶栏统计行灰字
        ('android:textColor="#9E9E9E"', 'android:textColor="?attr/hfTopBarTextSub"'),
        # 主文字 / 次文字
        ('android:textColor="#37474F"', 'android:textColor="?attr/hfText"'),
        ('android:textColor="#90A4AE"', 'android:textColor="?attr/hfTextSub"'),
        ('android:textColor="#616161"', 'android:textColor="?attr/hfTextSub"'),
        ('android:textColor="#757575"', 'android:textColor="?attr/hfTextSub"'),
        # 根底色
        ('android:background="#FFFFFF">', 'android:background="?attr/hfBg">'),
        # 抽屉底色
        ('android:background="#FFFFFF"\n        android:visibility="gone"',
         'android:background="?attr/hfSurface"\n        android:visibility="gone"'),
        # 抽屉图标块（根目录/内部存储）
        ('android:background="#5C6BC0"', 'android:background="?attr/hfAccent"'),
        ('android:background="#26A69A"', 'android:background="?attr/hfAccent"'),
        # 选中批量栏底色
        ('android:background="#ECEFF1"', 'android:background="?attr/hfSurfaceAlt"'),
        # 底栏底色
        ('android:background="#FAFAFA"', 'android:background="?attr/hfSurfaceAlt"'),
        # 分隔线
        ('android:divider="#E0E0E0"', 'android:divider="?attr/hfDivider"'),
        ('android:background="#E0E0E0"', 'android:background="?attr/hfDivider"'),
        ('android:background="#BDBDBD"', 'android:background="?attr/hfDivider"'),
    ],
    "item_file.xml": [
        ('android:textColor="#212121"', 'android:textColor="?attr/hfText"'),
        ('android:textColor="#757575"', 'android:textColor="?attr/hfTextSub"'),
    ],
    "activity_trash.xml": [
        ('android:background="#FFF0F5"', 'android:background="?attr/hfBg"'),
        ('android:background="#F8BBD0"', 'android:background="?attr/hfTopBar"'),
        ('android:textColor="#880E4F"', 'android:textColor="?attr/hfTopBarText"'),
        ('android:textColor="#999999"', 'android:textColor="?attr/hfTextSub"'),
        ('android:divider="#F8BBD0"', 'android:divider="?attr/hfDivider"'),
    ],
    "activity_search.xml": [
        ('android:background="#FFF0F5"', 'android:background="?attr/hfBg"'),
        ('android:textColor="#880E4F"', 'android:textColor="?attr/hfText"'),
        ('android:textColor="#C2185B"', 'android:textColor="?attr/hfTextSub"'),
        ('android:background="#FFFFFF"', 'android:background="?attr/hfSurface"'),
        ('android:divider="#F8BBD0"', 'android:divider="?attr/hfDivider"'),
    ],
    "activity_zip.xml": [
        ('android:background="#FFF2F7FB"', 'android:background="?attr/hfBg"'),
        ('android:textColor="#4A1528"', 'android:textColor="?attr/hfText"'),
        ('android:background="#FFE1ECF5"', 'android:background="?attr/hfSurfaceAlt"'),
    ],
    "activity_hex.xml": [
        ('android:background="#FFF2F7FB"', 'android:background="?attr/hfBg"'),
        ('android:textColor="#4A1528"', 'android:textColor="?attr/hfText"'),
        ('android:background="#FFE1ECF5"', 'android:background="?attr/hfSurfaceAlt"'),
    ],
    "activity_editor.xml": [
        ('android:background="#FFF2F7FB"', 'android:background="?attr/hfBg"'),
        ('android:textColor="#4A1528"', 'android:textColor="?attr/hfText"'),
        ('android:background="#FFE1ECF5"', 'android:background="?attr/hfSurfaceAlt"'),
        ('android:background="#FFEAF2F8"', 'android:background="?attr/hfSurfaceAlt"'),
        ('android:background="#FFF6ECF7"', 'android:background="?attr/hfSurfaceAlt"'),
        ('android:textColor="#8A6FB0"', 'android:textColor="?attr/hfTextSub"'),
        ('android:background="#FFFFFFFF"', 'android:background="?attr/hfSurface"'),
        ('android:textColor="#333333"', 'android:textColor="?attr/hfText"'),
    ],
    "activity_manifestpp.xml": [
        ('android:background="#FFFFFF">', 'android:background="?attr/hfBg">'),
        ('android:background="#212121"', 'android:background="?attr/hfTopBar"'),
        ('android:textColor="#FFFFFF"', 'android:textColor="?attr/hfTopBarText"'),
        ('android:textColor="#B0BEC5"', 'android:textColor="?attr/hfTopBarTextSub"'),
        ('android:background="#E0E0E0"', 'android:background="?attr/hfDivider"'),
    ],
    "activity_arscpp.xml": [
        ('android:background="#FFFFFF">', 'android:background="?attr/hfBg">'),
        ('android:background="#212121"', 'android:background="?attr/hfTopBar"'),
        ('android:textColor="#FFFFFF"', 'android:textColor="?attr/hfTopBarText"'),
        ('android:textColor="#B0BEC5"', 'android:textColor="?attr/hfTopBarTextSub"'),
        ('android:background="#E0E0E0"', 'android:background="?attr/hfDivider"'),
    ],
    "activity_dexpp.xml": [
        ('android:background="#FFFFFF">', 'android:background="?attr/hfBg">'),
        ('android:background="#212121"', 'android:background="?attr/hfTopBar"'),
        ('android:textColor="#FFFFFF"', 'android:textColor="?attr/hfTopBarText"'),
        ('android:textColor="#B0BEC5"', 'android:textColor="?attr/hfTopBarTextSub"'),
        ('android:background="#E0E0E0"', 'android:background="?attr/hfDivider"'),
    ],
    "activity_batch_rename.xml": [
        ('android:background="#FFFFFF">', 'android:background="?attr/hfBg">'),
        ('android:background="#212121"', 'android:background="?attr/hfTopBar"'),
        ('android:textColor="#FFFFFF"', 'android:textColor="?attr/hfTopBarText"'),
        ('android:textColor="#E53935"', 'android:textColor="?attr/hfAccent"'),
        ('android:divider="#E0E0E0"', 'android:divider="?attr/hfDivider"'),
        ('android:background="#E0E0E0"', 'android:background="?attr/hfDivider"'),
        ('android:background="#FAFAFA"', 'android:background="?attr/hfSurfaceAlt"'),
        ('android:textColor="#616161"', 'android:textColor="?attr/hfTextSub"'),
        ('android:textColor="#1E88E5"', 'android:textColor="?attr/hfAccent"'),
    ],
    "item_result.xml": [
        ('android:background="#FFFFFF"', 'android:background="?attr/hfSurface"'),
        ('android:textColor="#880E4F"', 'android:textColor="?attr/hfText"'),
        ('android:textColor="#999999"', 'android:textColor="?attr/hfTextSub"'),
    ],
    "dialog_apk_info.xml": [
        ('android:textColor="#212121"', 'android:textColor="?attr/hfText"'),
    ],
    "dialog_hex_edit.xml": [
        ('android:textColor="#4A1528"', 'android:textColor="?attr/hfText"'),
        ('android:textColor="#B71C1C"', 'android:textColor="?attr/hfAccent"'),
    ],
    "dialog_hex_search.xml": [
        ('android:textColor="#4A1528"', 'android:textColor="?attr/hfText"'),
        ('android:textColor="#B71C1C"', 'android:textColor="?attr/hfAccent"'),
    ],
}

# 允许保留的色值（不属于主题语义的纯装饰色，如角标、语法色）
ALLOW = {
    "#B07E57C2",  # item_file GIF 角标底
    "#4A1528",
}


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    lay = os.path.join(root, "res", "layout")
    total = 0
    for fn, rules in RULES.items():
        path = os.path.join(lay, fn)
        if not os.path.exists(path):
            print("MISS", fn)
            continue
        with open(path, encoding="utf-8") as f:
            src = f.read()
        orig = src
        for old, new in rules:
            if old not in src:
                continue
            n = src.count(old)
            src = src.replace(old, new)
            total += n
        if src != orig:
            with open(path, "w", encoding="utf-8") as f:
                f.write(src)
        # 报告残留
        left = re.findall(r'#[0-9A-Fa-f]{6,8}', src)
        left = [c for c in left if c.upper() not in ALLOW]
        if left:
            print("LEFTOVER %-28s %s" % (fn, sorted(set(left))))
    print("replacements:", total)


if __name__ == "__main__":
    main()
