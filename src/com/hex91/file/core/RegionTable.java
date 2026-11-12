package com.hex91.file.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.71.0 行政区划码表（省 / 市 / 区县三级）。
 *
 * <p>数据来自 {@code res/raw/regions.txt}，每行 {@code 6位码|名称|父级6位码}，
 * 省级父级为空。共 3357 条（31 个省级行政区、342 个市、2984 个区县），
 * 由国家统计局行政区划代码整理而成，覆盖到区县级。
 *
 * <p>解析放在纯 Java 里（{@link #parse(String)}），这样桌面单测能直接读同一个文件校验，
 * 不用起模拟器。
 */
public final class RegionTable {

    /** 一条区划 */
    public static final class Region {
        public final String code;
        public final String name;
        /** 父级 6 位码；省级为空串 */
        public final String parent;

        Region(String code, String name, String parent) {
            this.code = code;
            this.name = name;
            this.parent = parent;
        }

        @Override
        public String toString() {
            return code + " " + name;
        }
    }

    private final Map<String, Region> byCode = new LinkedHashMap<String, Region>();
    private final Map<String, List<Region>> byParent = new LinkedHashMap<String, List<Region>>();

    private RegionTable() {
    }

    /**
     * 解析码表文本。格式不对的行直接跳过（宁可少一条，也不让整个工具崩）。
     */
    public static RegionTable parse(String text) {
        RegionTable t = new RegionTable();
        if (text == null) return t;
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line.length() < 10) continue;
            int a = line.indexOf('|');
            if (a != 6) continue;
            int b = line.indexOf('|', a + 1);
            String code = line.substring(0, 6);
            String name = b > a ? line.substring(a + 1, b) : line.substring(a + 1);
            String parent = b > a ? line.substring(b + 1).trim() : "";
            if (name.length() == 0) continue;
            Region r = new Region(code, name, parent);
            t.byCode.put(code, r);
            List<Region> list = t.byParent.get(parent);
            if (list == null) {
                list = new ArrayList<Region>();
                t.byParent.put(parent, list);
            }
            list.add(r);
        }
        return t;
    }

    public int size() {
        return byCode.size();
    }

    public Region get(String code) {
        return code == null ? null : byCode.get(code);
    }

    public String name(String code) {
        Region r = get(code);
        return r == null ? "" : r.name;
    }

    /** 省级（父级为空） */
    public List<Region> provinces() {
        return children("");
    }

    public List<Region> children(String parent) {
        List<Region> l = byParent.get(parent == null ? "" : parent);
        return l == null ? new ArrayList<Region>() : l;
    }

    /** 市级展示名：直辖市的「市辖区」直接用省名，免得下拉框出现四个「市辖区」 */
    public String cityLabel(String cityCode) {
        Region c = get(cityCode);
        if (c == null) return "";
        if ("市辖区".equals(c.name)) {
            Region p = get(c.parent);
            if (p != null) return p.name;
        }
        return c.name;
    }

    /** 完整地址：「北京市 东城区」（直辖市跳过空的市级） */
    public String fullName(String districtCode) {
        Region d = get(districtCode);
        if (d == null) return "";
        // 东莞/中山/儋州这类「直筒子市」没有区县，码表里区县项就是市本身（父级指向自己）。
        // 此时不能顺着 parent 往上找（会绕回自己），按码值前两位还原省级。
        if (d.code.equals(d.parent)) {
            Region prov = get(d.code.substring(0, 2) + "0000");
            return prov == null ? d.name : prov.name + " " + d.name;
        }
        Region c = get(d.parent);
        Region p = c == null ? null : get(c.parent);
        StringBuilder sb = new StringBuilder();
        if (p != null) sb.append(p.name);
        if (c != null && !"市辖区".equals(c.name) && !c.name.equals(p == null ? "" : p.name)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.name);
        }
        if (sb.length() > 0) sb.append(' ');
        sb.append(d.name);
        return sb.toString();
    }

    /** 按名称模糊找区县码（默认项定位用），找不到返回 null */
    public String findDistrict(String provinceName, String districtName) {
        for (Region p : provinces()) {
            if (provinceName != null && !p.name.equals(provinceName)) continue;
            for (Region c : children(p.code)) {
                for (Region d : children(c.code)) {
                    if (districtName == null || d.name.equals(districtName)) return d.code;
                }
            }
        }
        return null;
    }
}
