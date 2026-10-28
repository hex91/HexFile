#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v1.48.0 主题系统资源生成器
==========================
依据 MT Manager 主题设置页截图提取的 22 个主题色，为每套色板生成：
  - 亮色变体 style（父 Theme_Hex_Light）
  - 暗色变体 style（父 Theme_Hex_Dark）
输出：res/values/themes.xml  +  src/com/hex91/file/ThemeSchemes.java

约定：基座 style（Theme_Hex_Light / Theme_Hex_Dark）必须定义【全部】语义色 attr，
      否则布局中的 ?attr/hfXxx 在基座下会解析失败导致崩溃；
      色板 style 只覆盖「随主题变化」的那几个（顶栏/强调色/选中/状态栏）。

用法： python tools/gen_themes.py
"""
import os

# MT 主题设置页 22 个色条（自截图逐像素提取，顺序即页面顺序）
SCHEMES = [
    ("蓝色",   "3E9AFB"),
    ("绿色",   "61BC80"),
    ("琥珀",   "E3A23C"),
    ("珊瑚",   "F16D6D"),
    ("灰绿",   "8E9587"),
    ("石墨",   "626566"),
    ("曜石",   "333033"),
    ("粉色",   "FC5C99"),
    ("正红",   "FC0001"),
    ("紫罗兰", "981FFF"),
    ("深蓝",   "032DA3"),
    ("湖蓝",   "80D7CF"),
    ("藏青",   "02304F"),
    ("靛蓝",   "6A67A8"),
    ("松石",   "06837B"),
    ("玫红",   "C4112C"),
    ("古铜",   "A45C2D"),
    ("暗紫",   "45012A"),
    ("酒红",   "4E000A"),
    ("橙红",   "EB5F21"),
    ("金黄",   "FAD16B"),
    ("深褐",   "442E20"),
]

# ---------- 色彩工具 ----------
def hex2rgb(h):
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))

def rgb2hex(r, g, b):
    return "%02X%02X%02X" % (max(0, min(255, int(r))),
                             max(0, min(255, int(g))),
                             max(0, min(255, int(b))))

def mix(c, other, t):
    """c 与 other 按 t(0..1) 混合，t=0 全 c，t=1 全 other"""
    r1, g1, b1 = hex2rgb(c)
    r2, g2, b2 = hex2rgb(other)
    return rgb2hex(r1 + (r2 - r1) * t, g1 + (g2 - g1) * t, b1 + (b2 - b1) * t)

def luminance(c):
    """WCAG 相对亮度"""
    def f(v):
        v = v / 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = hex2rgb(c)
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)

def contrast_text(bg):
    """背景色上的前景色：取 WCAG 对比度更高者（深字 #212121 或白字 #FFFFFF）。

    不能只按亮度阈值二选一 —— 阈值法在中间亮度色（如琥珀/湖蓝/金黄）
    上会选错，导致实际对比度不足 3.0。这里直接比较两种前景色的对比度。
    """
    dark = "212121"
    white = "FFFFFF"
    return dark if contrast_ratio(bg, dark) >= contrast_ratio(bg, white) else white


def contrast_ratio(c1, c2):
    """WCAG 对比度 (L1+0.05)/(L2+0.05)"""
    l1 = luminance(c1)
    l2 = luminance(c2)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)

# ---------- 基座调色板 ----------
LIGHT = dict(bg="FFFFFF", surface="FFFFFF", surface_alt="F5F5F5",
             text="212121", text_sub="757575", divider="E0E0E0",
             top="212121", top_text="FFFFFF", top_sub="9E9E9E",
             accent="1E88E5", on_accent="FFFFFF", selected="BBDEFB",
             status="212121", nav="212121")

DARK = dict(bg="121212", surface="1E1E1E", surface_alt="2A2A2A",
            text="ECECEC", text_sub="9E9E9E", divider="3A3A3A",
            top="1E1E1E", top_text="FFFFFF", top_sub="9E9E9E",
            accent="1E88E5", on_accent="FFFFFF", selected="263238",
            status="121212", nav="121212")


def base_block(name, parent, p, comment):
    """输出一个基座 style：定义全部语义色 attr"""
    o = []
    o.append('')
    o.append('    <!-- ================= %s ================= -->' % comment)
    o.append('    <style name="%s" parent="%s">' % (name, parent))
    o.append('        <item name="android:windowBackground">#%s</item>' % p['bg'])
    o.append('        <item name="android:colorPrimary">#%s</item>' % p['top'])
    o.append('        <item name="android:colorPrimaryDark">#%s</item>' % p['status'])
    o.append('        <item name="android:textColorPrimary">#%s</item>' % p['text'])
    o.append('        <item name="android:textColorSecondary">#%s</item>' % p['text_sub'])
    o.append('        <item name="hfBg">#%s</item>' % p['bg'])
    o.append('        <item name="hfSurface">#%s</item>' % p['surface'])
    o.append('        <item name="hfSurfaceAlt">#%s</item>' % p['surface_alt'])
    o.append('        <item name="hfText">#%s</item>' % p['text'])
    o.append('        <item name="hfTextSub">#%s</item>' % p['text_sub'])
    o.append('        <item name="hfDivider">#%s</item>' % p['divider'])
    o.append('        <item name="hfTopBar">#%s</item>' % p['top'])
    o.append('        <item name="hfTopBarText">#%s</item>' % p['top_text'])
    o.append('        <item name="hfTopBarTextSub">#%s</item>' % p['top_sub'])
    o.append('        <item name="hfAccent">#%s</item>' % p['accent'])
    o.append('        <item name="hfOnAccent">#%s</item>' % p['on_accent'])
    o.append('        <item name="hfSelected">#%s</item>' % p['selected'])
    o.append('        <item name="hfStatusBar">#%s</item>' % p['status'])
    o.append('        <item name="hfNavBar">#%s</item>' % p['nav'])
    o.append('        <item name="android:statusBarColor">#%s</item>' % p['status'])
    o.append('        <item name="android:navigationBarColor">#%s</item>' % p['nav'])
    o.append('        <item name="android:colorAccent">#%s</item>' % p['accent'])
    o.append('    </style>')
    return o


def scheme_block(idx, name, c, dark):
    """输出一个色板 style：只覆盖随主题变化的那几个 attr"""
    o = []
    if dark:
        top = mix(c, "000000", 0.34)          # 暗色底：顶栏压暗
        on_top = contrast_text(top)
        sub_top = mix(top, on_top, 0.45)      # 顶栏次要文字
        accent = c                            # 暗底上强调色用原色更跳
        on_accent = contrast_text(accent)
        selected = mix(c, DARK['bg'], 0.20)
        status = mix(c, "000000", 0.55)
        nav = mix(c, "000000", 0.65)
    else:
        top = c                               # 亮色底：顶栏即主题色
        on_top = contrast_text(top)
        sub_top = mix(top, on_top, 0.42)
        accent = c
        on_accent = contrast_text(accent)
        selected = mix(c, LIGHT['bg'], 0.86)
        status = mix(c, "000000", 0.30)
        nav = mix(c, "000000", 0.42)

    o.append('')
    o.append('    <!-- %d %s #%s%s -->' % (idx, name, c, '（暗）' if dark else ''))
    o.append('    <style name="Theme_Hex_%s%d" parent="Theme_Hex_%s">' % (
        'Dark_' if dark else '', idx, 'Dark' if dark else 'Light'))
    o.append('        <item name="hfTopBar">#%s</item>' % top)
    o.append('        <item name="hfTopBarText">#%s</item>' % on_top)
    o.append('        <item name="hfTopBarTextSub">#%s</item>' % sub_top)
    o.append('        <item name="hfAccent">#%s</item>' % accent)
    o.append('        <item name="hfOnAccent">#%s</item>' % on_accent)
    o.append('        <item name="hfSelected">#%s</item>' % selected)
    o.append('        <item name="hfStatusBar">#%s</item>' % status)
    o.append('        <item name="hfNavBar">#%s</item>' % nav)
    o.append('        <item name="android:statusBarColor">#%s</item>' % status)
    o.append('        <item name="android:navigationBarColor">#%s</item>' % nav)
    o.append('        <item name="android:colorAccent">#%s</item>' % accent)
    o.append('    </style>')
    return o


def build():
    out = []
    out.append('<?xml version="1.0" encoding="utf-8"?>')
    out.append('<!--')
    out.append('  本文件由 tools/gen_themes.py 自动生成，请勿手工编辑。')
    out.append('  v1.48.0 主题系统：22 套 MT 色板 × 明/暗 = 44 个色板 style + 2 个基座 style。')
    out.append('  基座（Theme_Hex_Light / Theme_Hex_Dark）定义全部语义色 attr，')
    out.append('  色板 style 仅覆盖随主题变化的部分（顶栏/强调色/选中态/状态栏）。')
    out.append('-->')
    out.append('<resources>')
    out += base_block("Theme_Hex_Light", "@android:style/Theme.Material.Light.NoActionBar",
                      LIGHT, "模式基座：亮色")
    out += base_block("Theme_Hex_Dark", "@android:style/Theme.Material.NoActionBar",
                      DARK, "模式基座：暗色")
    for idx, (name, c) in enumerate(SCHEMES):
        out += scheme_block(idx, name, c, False)
        out += scheme_block(idx, name, c, True)
    out.append('')
    out.append('</resources>')
    return "\n".join(out) + "\n"


def build_java():
    lines = []
    lines.append("package com.hex91.file;")
    lines.append("")
    lines.append("/**")
    lines.append(" * v1.48.0 主题色板定义 —— 由 tools/gen_themes.py 与 res/values/themes.xml 同步生成。")
    lines.append(" * 顺序与 MT Manager 主题设置页一致（索引 0..%d）。" % (len(SCHEMES) - 1))
    lines.append(" */")
    lines.append("public final class ThemeSchemes {")
    lines.append("")
    lines.append("    private ThemeSchemes() {")
    lines.append("    }")
    lines.append("")
    lines.append("    /** 色板名称 */")
    lines.append("    public static final String[] NAMES = {")
    for name, _ in SCHEMES:
        lines.append('            "%s",' % name)
    lines.append("    };")
    lines.append("")
    lines.append("    /** 色板主色（ARGB） */")
    lines.append("    public static final int[] COLORS = {")
    for _, c in SCHEMES:
        lines.append("        0xFF%s," % c)
    lines.append("    };")
    lines.append("")
    lines.append("    public static int count() {")
    lines.append("        return NAMES.length;")
    lines.append("    }")
    lines.append("}")
    return "\n".join(lines) + "\n"


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    target = os.path.join(root, "res", "values", "themes.xml")
    with open(target, "w", encoding="utf-8") as f:
        f.write(build())
    print("generated:", target, "schemes=%d" % len(SCHEMES))

    java_path = os.path.join(root, "src", "com", "hex91", "file", "ThemeSchemes.java")
    with open(java_path, "w", encoding="utf-8") as f:
        f.write(build_java())
    print("generated:", java_path)


if __name__ == "__main__":
    main()
