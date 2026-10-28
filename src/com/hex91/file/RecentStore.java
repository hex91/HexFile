package com.hex91.file;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** 最近打开记录（SharedPreferences 持久化，最新在前，最多 50 条） */
public class RecentStore {

    private static final String PREF = "hexfile_recent";
    private static final int MAX = 50;

    public static class Item {
        public String path;
        public long time;
        public String name() {
            int i = path.lastIndexOf('/');
            return i >= 0 ? path.substring(i + 1) : path;
        }
    }

    private final SharedPreferences sp;

    public RecentStore(Context ctx) {
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 记录一次打开：同路径去重置顶，超限裁剪 */
    public void record(String path) {
        if (path == null || path.isEmpty()) return;
        List<Item> list = load();
        for (int i = list.size() - 1; i >= 0; i--)
            if (list.get(i).path.equals(path)) list.remove(i);
        Item it = new Item();
        it.path = path;
        it.time = System.currentTimeMillis();
        list.add(0, it);
        while (list.size() > MAX) list.remove(list.size() - 1);
        save(list);
    }

    public List<Item> list() { return load(); }

    public void clear() { sp.edit().remove("items").apply(); }

    private List<Item> load() {
        List<Item> out = new ArrayList<Item>();
        String raw = sp.getString("items", "");
        for (String line : raw.split("\n")) {
            int t = line.indexOf('\t');
            if (t <= 0) continue;
            try {
                Item it = new Item();
                it.time = Long.parseLong(line.substring(0, t));
                it.path = line.substring(t + 1);
                out.add(it);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private void save(List<Item> list) {
        StringBuilder sb = new StringBuilder();
        for (Item it : list) sb.append(it.time).append('\t').append(it.path).append('\n');
        sp.edit().putString("items", sb.toString()).apply();
    }
}
