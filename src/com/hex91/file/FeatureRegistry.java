package com.hex91.file;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 功能注册表 —— 对标 MT Manager 的 Features 注册表设计。
 * 所有功能模块（APK 工具/压缩/编辑器...）统一注册，UI 菜单由注册表驱动生成。
 */
public class FeatureRegistry {

    public interface Feature {
        String id();
        String title();
        void run();
    }

    private static final Map<String, Feature> FEATURES = new LinkedHashMap<String, Feature>();

    public static void register(Feature f) {
        FEATURES.put(f.id(), f);
    }

    public static Feature get(String id) {
        return FEATURES.get(id);
    }

    public static Iterable<Feature> all() {
        return FEATURES.values();
    }
}
