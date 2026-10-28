#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v1.48.0 Java 侧配色改造：硬编码色值 → ThemeManager 语义取色。

按 (文件, 行内上下文) 精确替换，避免误伤语义不同的同色值。
替换后暗色模式/换主题时界面文字、底色自动跟随。

用法： python tools/theme_java.py
"""
import os
import re

# (文件, 原文本, 新文本)
# 新文本里 C 代表 this/ctx，调用 ThemeManager.xxx(C)
RULES = {
    "MainActivity.java": [
        # 属性弹窗：标签 / 值
        ('label.setTextColor(0xFF757575);', 'label.setTextColor(ThemeManager.textSub(this));'),
        ('value.setTextColor(0xFF212121);', 'value.setTextColor(ThemeManager.text(this));'),
        # 键值详情弹窗
        ('lv.setTextColor(0xFF90A4AE);', 'lv.setTextColor(ThemeManager.textSub(this));'),
        ('vv.setTextColor(0xFF212121);', 'vv.setTextColor(ThemeManager.text(this));'),
        # 十六进制详情
        ('oct.setTextColor(0xFF1565C0);', 'oct.setTextColor(ThemeManager.accent(this));'),
        # 粘贴校验提示
        ('pasteLabel.setTextColor(0xFF90A4AE);', 'pasteLabel.setTextColor(ThemeManager.textSub(this));'),
        ('resultV.setTextColor(0xFF90A4AE);', 'resultV.setTextColor(ThemeManager.textSub(this));'),
        ('resultV.setTextColor(0xFF2E7D32);', 'resultV.setTextColor(ThemeManager.ok(this));'),
        ('resultV.setTextColor(0xFFC62828);', 'resultV.setTextColor(ThemeManager.err(this));'),
        # 分卷对话框
        ('tv.setTextColor(0xFF37474F);', 'tv.setTextColor(ThemeManager.text(this));'),
        # 提示文案
        ('msg.setTextColor(0xFF546E7A);', 'msg.setTextColor(ThemeManager.textSub(this));'),
        # 操作项
        ('tv.setTextColor(0xFF1565C0);', 'tv.setTextColor(ThemeManager.accent(this));'),
        # 加密方式说明
        ('stv.setTextColor(0xFF37474F);', 'stv.setTextColor(ThemeManager.text(this));'),
    ],
    "FileAdapter.java": [
        ('convert.setBackgroundColor(sel ? 0xFFBBDEFB : 0x00000000);',
         'convert.setBackgroundColor(sel ? ThemeManager.selected(ctx) : 0x00000000);'),
    ],
    "AppManagerActivity.java": [
        ('countView.setTextColor(0xFF78909C);', 'countView.setTextColor(ThemeManager.textSub(this));'),
        ('chips[i].setBackgroundColor(0xFF1565C0);', 'chips[i].setBackgroundColor(ThemeManager.accent(this));'),
        ('chips[i].setTextColor(0xFFFFFFFF);', 'chips[i].setTextColor(ThemeManager.onAccent(this));'),
        ('chips[i].setTextColor(0xFF607D8B);', 'chips[i].setTextColor(ThemeManager.textSub(this));'),
        ('title.setTextColor(0xFF212121);', 'title.setTextColor(ThemeManager.text(this));'),
        ('sub.setTextColor(0xFF78909C);', 'sub.setTextColor(ThemeManager.textSub(this));'),
    ],
    "ArscEditorActivity.java": [
        ('tv.setTextColor(0xFF00695C);', 'tv.setTextColor(ThemeManager.accent(this));'),
        ('tv.setTextColor(0xFF1565C0);', 'tv.setTextColor(ThemeManager.accent(this));'),
        ('val.setTextColor(0xFF424242);', 'val.setTextColor(ThemeManager.text(this));'),
        ('cfgTv.setTextColor(0xFF9E9E9E);', 'cfgTv.setTextColor(ThemeManager.textSub(this));'),
        ('t.setTextColor(0xFF00695C);', 't.setTextColor(ThemeManager.accent(this));'),
    ],
    "ManifestEditorActivity.java": [
        ('tv.setTextColor(0xFF00695C);', 'tv.setTextColor(ThemeManager.accent(this));'),
        ('name.setTextColor(0xFF212121);', 'name.setTextColor(ThemeManager.text(this));'),
        ('val.setTextColor(changed ? 0xFFE91E63 : 0xFF757575);',
         'val.setTextColor(changed ? ThemeManager.accent(this) : ThemeManager.textSub(this));'),
        ('info.setTextColor(0xFF757575);', 'info.setTextColor(ThemeManager.textSub(this));'),
        ('hint.setTextColor(0xFF9E9E9E);', 'hint.setTextColor(ThemeManager.textSub(this));'),
    ],
    "DexEditorActivity.java": [
        ('tv.setTextColor(0xFF1565C0);', 'tv.setTextColor(ThemeManager.accent(this));'),
        ('tv.setTextColor(0xFF212121);', 'tv.setTextColor(ThemeManager.text(this));'),
    ],
    "BatchRenameActivity.java": [
        ('a.setTextColor(0xFF9E9E9E);', 'a.setTextColor(ThemeManager.textSub(this));'),
        ('b.setTextColor(r.conflict ? 0xFFE53935 : 0xFF212121);',
         'b.setTextColor(r.conflict ? ThemeManager.err(this) : ThemeManager.text(this));'),
    ],
    "HexViewerActivity.java": [
        ('tv.setTextColor(0xFF4A1528);', 'tv.setTextColor(ThemeManager.text(this));'),
        ('tv.setBackgroundColor(inSel ? 0xFFD6EAF5 : 0x00000000);',
         'tv.setBackgroundColor(inSel ? ThemeManager.selected(this) : 0x00000000);'),
    ],
    "StringsViewerActivity.java": [
        ('root.setBackgroundColor(0xFFFFFFFF);', 'root.setBackgroundColor(ThemeManager.bg(this));'),
        ('title.setTextColor(0xFFFFFFFF);', 'title.setTextColor(ThemeManager.topBarText(this));'),
    ],
    "CompareActivity.java": [
        ('private static final int C_TX = 0xFF37474F;', ''),
        ('private static final int C_SUB = 0xFF8AA0B4;', ''),
    ],
}


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src_dir = os.path.join(root, "src", "com", "hex91", "file")
    total = 0
    for fn, rules in RULES.items():
        path = os.path.join(src_dir, fn)
        if not os.path.exists(path):
            print("MISS", fn)
            continue
        with open(path, encoding="utf-8") as f:
            src = f.read()
        orig = src
        for old, new in rules:
            n = src.count(old)
            if n == 0:
                print("  NOMATCH %s :: %s" % (fn, old[:60]))
                continue
            src = src.replace(old, new)
            total += n
        if src != orig:
            with open(path, "w", encoding="utf-8") as f:
                f.write(src)
    print("replacements:", total)


if __name__ == "__main__":
    main()
