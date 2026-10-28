package com.hex91.file;

import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 回收站：删除 = 移入 HexFile/Trash/store，meta 记录原始路径，支持恢复/彻底删除。
 * 目录结构：
 *   HexFile/Trash/store/<id>       实际内容
 *   HexFile/Trash/meta/<id>.txt    元数据（name/origPath/time/isDir 四行）
 */
public class Trash {

    public static final String DIR_NAME = "HexFile/Trash";

    public static class Entry {
        public String id;
        public String name;
        public String origPath;
        public long time;
        public boolean isDir;
        public File store;
    }

    public static File dir() {
        return new File(Environment.getExternalStorageDirectory(), DIR_NAME);
    }

    private static File metaDir() { return new File(dir(), "meta"); }
    private static File storeDir() { return new File(dir(), "store"); }

    /** 移入回收站；失败返回 null（调用方应提示用户） */
    public static Entry moveToTrash(File f) {
        try {
            boolean wasDir = f.isDirectory();
            storeDir().mkdirs();
            metaDir().mkdirs();
            String base = System.currentTimeMillis() + "_" + Math.abs(f.getName().hashCode());
            File target = new File(storeDir(), base);
            int i = 1;
            while (target.exists()) target = new File(storeDir(), base + "_" + (i++));
            if (!moveFile(f, target)) return null;
            Entry e = new Entry();
            e.id = target.getName();
            e.name = f.getName();
            e.origPath = f.getAbsolutePath();
            e.time = System.currentTimeMillis();
            e.isDir = wasDir;
            e.store = target;
            writeMeta(e);
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 恢复到原路径；若已存在则自动追加 (1)/(2)... */
    public static boolean restore(Entry e) {
        try {
            File dest = new File(e.origPath);
            if (dest.exists()) {
                String p = e.origPath;
                int dot = p.lastIndexOf('.');
                String stem = dot > 0 ? p.substring(0, dot) : p;
                String ext = dot > 0 ? p.substring(dot) : "";
                int i = 1;
                do { dest = new File(stem + "(" + (i++) + ")" + ext); } while (dest.exists());
            }
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            if (!moveFile(e.store, dest)) return false;
            new File(metaDir(), e.id + ".txt").delete();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 彻底删除单条 */
    public static boolean purge(Entry e) {
        FileOps.deleteRecursive(e.store);
        return new File(metaDir(), e.id + ".txt").delete();
    }

    /** 清空回收站 */
    public static boolean clearAll() {
        return FileOps.deleteRecursive(dir());
    }

    public static List<Entry> list() {
        List<Entry> out = new ArrayList<Entry>();
        File md = metaDir();
        File[] metas = md.listFiles();
        if (metas == null) return out;
        for (File m : metas) {
            if (!m.getName().endsWith(".txt")) continue;
            try {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(m), "UTF-8"));
                Entry e = new Entry();
                e.id = m.getName().substring(0, m.getName().length() - 4);
                e.name = r.readLine();
                e.origPath = r.readLine();
                e.time = Long.parseLong(r.readLine());
                e.isDir = Boolean.parseBoolean(r.readLine());
                r.close();
                if (e.name == null || e.origPath == null) continue;
                e.store = new File(storeDir(), e.id);
                out.add(e);
            } catch (Exception ignored) {
            }
        }
        // 新的在前（id 含时间戳，字典序倒序即可）
        java.util.Collections.sort(out, new java.util.Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                return b.id.compareTo(a.id);
            }
        });
        return out;
    }

    private static void writeMeta(Entry e) throws Exception {
        metaDir().mkdirs();
        File m = new File(metaDir(), e.id + ".txt");
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(m), "UTF-8");
        w.write(e.name + "\n");
        w.write(e.origPath + "\n");
        w.write(e.time + "\n");
        w.write(e.isDir + "\n");
        w.close();
    }

    private static boolean moveFile(File src, File dst) {
        if (src.renameTo(dst)) return true;
        try {
            if (src.isDirectory()) {
                dst.mkdirs();
                File[] kids = src.listFiles();
                if (kids != null) for (File k : kids) {
                    if (!moveFile(k, new File(dst, k.getName()))) return false;
                }
                return src.delete();
            }
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            return src.delete();
        } catch (Exception ex) {
            return false;
        }
    }
}
