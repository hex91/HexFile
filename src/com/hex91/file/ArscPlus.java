package com.hex91.file;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * ARSC++（v1.64.0）：资源表的 values-XML 源码视图（对标 MT Manager「ARSC++ 编辑」）。
 *
 * <ul>
 *   <li>{@link #buildXml}：类型 + 配置 → {@code <resources>} 风格 XML，
 *       每个资源条目一行 {@code <type name="key">value</type>}；bag/复杂条目
 *       （style 布局等）输出为注释，提示去「ARSC 编辑」逐项修改。</li>
 *   <li>{@link #applyXml}：把编辑后的 XML 解析回 {@link ArscWriter.Edit} 列表，
 *       只对有变化的条目生成修改；值解析复用 {@link ArscValues#parseInput}
 *       （字符串 / 整数 / 布尔 / 颜色 / 引用等）。</li>
 * </ul>
 * 保存链路：applyXml → ArscWriter.write（池追加 + 4 字节补丁）→ ApkRepack → 重签名。
 */
public final class ArscPlus {

    private ArscPlus() {}

    /** 该类型 + 配置下的全部资源条目（cfg 为空串 = 默认配置）。 */
    public static List<ResourceEntry> entriesOf(ArscFile arsc, String type, String cfg) {
        List<ResourceEntry> out = new ArrayList<ResourceEntry>();
        for (ResourceEntry e : arsc.storage.getResources()) {
            if (!type.equals(e.getTypeName())) continue;
            String c = e.getConfig() == null ? "" : e.getConfig();
            if (c.equals(cfg)) out.add(e);
        }
        return out;
    }

    /** 生成 values 风格 XML（UTF-8 文本）。 */
    public static String buildXml(ArscFile arsc, String type, String cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n");
        sb.append("<resources>\n");
        for (ResourceEntry e : entriesOf(arsc, type, cfg)) {
            String name = e.getKeyName() == null ? "?" : e.getKeyName();
            if (e.getSimpleValue() != null) {
                String val = ArscValues.valueDisplay(arsc, e);
                sb.append("    <").append(type).append(" name=\"").append(esc(name)).append("\">")
                        .append(esc(val == null ? "" : val))
                        .append("</").append(type).append(">\n");
            } else {
                sb.append("    <!-- ").append(type).append(" name=\"").append(esc(name))
                        .append("\" : bag/复杂条目，请在「ARSC 编辑」中逐项修改 -->\n");
            }
        }
        sb.append("</resources>\n");
        return sb.toString();
    }

    /**
     * 解析编辑后的 XML → 修改列表。未变化的条目跳过；
     * 解析失败抛异常（带条目名，方便定位哪一行写错了）。
     *
     * <p>手写轻量解析器（纯 Java，无 XML 依赖，桌面端可单测）：只处理本工具生成的
     * 扁平结构 {@code <resources> … <type name="x">v</type> … </resources>} 与注释；
     * 嵌套元素 / 自闭合标签直接报错，避免静默写坏资源表。</p>
     */
    public static List<ArscWriter.Edit> applyXml(ArscFile arsc, String type, String cfg,
                                                 String xml) throws Exception {
        Map<String, ResourceEntry> map = new HashMap<String, ResourceEntry>();
        for (ResourceEntry e : entriesOf(arsc, type, cfg)) map.put(e.getKeyName(), e);

        List<ArscWriter.Edit> edits = new ArrayList<ArscWriter.Edit>();
        int len = xml.length();
        int i = 0;
        String openTag = null, openName = null;
        StringBuilder text = new StringBuilder();
        while (i < len) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                if (openTag != null) text.append(xml, i, len);
                break;
            }
            if (openTag != null) text.append(xml, i, lt);
            if (xml.startsWith("<!--", lt)) {
                int end = xml.indexOf("-->", lt + 4);
                if (end < 0) throw new Exception("注释未闭合");
                i = end + 3;
                continue;
            }
            if (xml.startsWith("<?", lt)) {
                int end = xml.indexOf("?>", lt + 2);
                if (end < 0) throw new Exception("处理指令未闭合");
                i = end + 2;
                continue;
            }
            if (xml.startsWith("</", lt)) {
                int gt = xml.indexOf('>', lt);
                if (gt < 0) throw new Exception("标签未闭合");
                String tag = xml.substring(lt + 2, gt).trim();
                if ("resources".equals(tag)) { i = gt + 1; continue; }   // 容器闭合，直接放行
                if (openTag == null)
                    throw new Exception("多余的结束标签 </" + tag + ">");
                if (!tag.equals(openTag))
                    throw new Exception("标签不匹配: <" + openTag + "> … </" + tag + ">");
                applyOne(arsc, map, edits, openTag, openName, unescape(text.toString()));
                openTag = null;
                openName = null;
                i = gt + 1;
                continue;
            }
            int gt = findTagEnd(xml, lt);
            if (gt < 0) throw new Exception("标签未闭合");
            String inner = xml.substring(lt + 1, gt).trim();
            boolean selfClose = inner.endsWith("/");
            if (selfClose) inner = inner.substring(0, inner.length() - 1).trim();
            String tagName = inner.split("\\s+", 2)[0];
            if (openTag != null && !"resources".equals(tagName))
                throw new Exception("不支持嵌套元素: <" + tagName + ">（请保持一行一个资源）");
            if (!"resources".equals(tagName)) {
                String name = attrValue(inner, "name");
                if (name == null) throw new Exception("<" + tagName + "> 缺少 name 属性");
                if (selfClose)
                    throw new Exception("暂不支持自闭合标签 <" + tagName + " name=\"" + name
                            + "\"/>，请写成 <…>值</…>");
                openTag = tagName;
                openName = name;
                text.setLength(0);
            }
            i = gt + 1;
        }
        if (openTag != null)
            throw new Exception("<" + openTag + " name=\"" + openName + "\"> 未闭合");
        return edits;
    }

    /** 结算一个 <tag name="x">value</tag>：与当前显示值比较，有变化才生成修改。 */
    private static void applyOne(ArscFile arsc, Map<String, ResourceEntry> map,
                                 List<ArscWriter.Edit> edits, String tag, String name,
                                 String val) throws Exception {
        ResourceEntry e = map.get(name);
        if (e == null)
            throw new Exception("找不到资源: <" + tag + " name=\"" + name + "\">"
                    + "（资源名不能改，改名请用「ARSC 编辑」）");
        jadx.core.xmlgen.entry.RawValue v = e.getSimpleValue();
        if (v == null) return;   // bag 注释已被跳过；防御性忽略
        String disp = ArscValues.valueDisplay(arsc, e);
        if (disp.equals(val)) return;
        if (ArscValues.isStringValue(v)) {
            edits.add(new ArscWriter.Edit(e, val));
        } else {
            Integer raw = ArscValues.parseInput(v.getDataType(), val, arsc);
            if (raw == null)
                throw new Exception("值格式错误: " + name + " = " + val);
            edits.add(new ArscWriter.Edit(e, raw.intValue()));
        }
    }

    /** 找开始标签的 '>'（跳过引号内的 '>'）。 */
    private static int findTagEnd(String s, int from) {
        char q = 0;
        for (int j = from + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (q != 0) {
                if (c == q) q = 0;
            } else if (c == '"' || c == '\'') {
                q = c;
            } else if (c == '>') {
                return j;
            }
        }
        return -1;
    }

    private static final Pattern ATTR = Pattern.compile(
            "(?:^|\\s)name\\s*=\\s*\"([^\"]*)\"|(?:^|\\s)name\\s*=\\s*'([^']*)'");

    /** 从开始标签文本里取 name 属性值（支持单双引号，反解码 XML 实体）。 */
    private static String attrValue(String tag, String attr) {
        Matcher m = ATTR.matcher(tag);
        if (!m.find()) return null;
        String g = m.group(1) != null ? m.group(1) : m.group(2);
        return unescape(g);
    }

    private static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '&') { sb.append(c); continue; }
            int semi = s.indexOf(';', i);
            if (semi < 0) { sb.append(c); continue; }
            String ent = s.substring(i + 1, semi);
            if ("amp".equals(ent)) sb.append('&');
            else if ("lt".equals(ent)) sb.append('<');
            else if ("gt".equals(ent)) sb.append('>');
            else if ("quot".equals(ent)) sb.append('"');
            else if ("apos".equals(ent)) sb.append('\'');
            else if (ent.startsWith("#x") || ent.startsWith("#X"))
                sb.append((char) Integer.parseInt(ent.substring(2), 16));
            else if (ent.startsWith("#"))
                sb.append((char) Integer.parseInt(ent.substring(1)));
            else { sb.append('&').append(ent).append(';'); }
            i = semi;
        }
        return sb.toString();
    }

    /** 配置节点显示名：默认配置 → 类型名本身，其余 → 类型名-配置（MT 同款）。 */
    public static String cfgLabel(String type, String cfg) {
        return cfg == null || cfg.isEmpty() ? type : type + "-" + cfg;
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
