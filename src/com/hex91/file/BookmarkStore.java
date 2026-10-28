package com.hex91.file;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 书签持久化（SharedPreferences 存路径集合） */
public class BookmarkStore {

    private static final String PREF = "hexfile_bookmarks";

    private final SharedPreferences sp;

    public BookmarkStore(Context ctx) {
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<String> all() {
        Set<String> s = sp.getStringSet("paths", new HashSet<String>());
        List<String> list = new ArrayList<String>(s);
        Collections.sort(list);
        return list;
    }

    public boolean add(String path) {
        if (path == null || path.isEmpty()) return false;
        Set<String> s = new HashSet<String>(all());
        boolean changed = s.add(path);
        if (changed) sp.edit().putStringSet("paths", s).apply();
        return changed;
    }

    public void remove(String path) {
        Set<String> s = new HashSet<String>(all());
        if (s.remove(path)) sp.edit().putStringSet("paths", s).apply();
    }
}
