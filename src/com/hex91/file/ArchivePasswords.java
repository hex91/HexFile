package com.hex91.file;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 会话级压缩包密码存储（v1.16）。
 * key = 归档绝对路径，value = 密码；App 退出即失效，不落盘、不持久化。
 * 打开加密归档失败时由 MainActivity 弹密码框写入，各后端打开/提取时读取。
 */
public class ArchivePasswords {

    private ArchivePasswords() {}

    private static final Map<String, String> MAP = new HashMap<String, String>();

    public static String get(File f) {
        if (f == null) return null;
        synchronized (MAP) { return MAP.get(f.getAbsolutePath()); }
    }

    public static void put(File f, String pw) {
        if (f == null || pw == null || pw.isEmpty()) return;
        synchronized (MAP) { MAP.put(f.getAbsolutePath(), pw); }
    }

    public static void clear(File f) {
        if (f == null) return;
        synchronized (MAP) { MAP.remove(f.getAbsolutePath()); }
    }
}
