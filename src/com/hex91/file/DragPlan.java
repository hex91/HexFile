package com.hex91.file;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.88.0 双栏拖放判定（纯 Java，可在桌面单测）。
 *
 * <p>拖放有两个必须拦住的情况，它们都会造成「看起来做了，其实什么都没发生」或者更糟的
 * 递归复制：</p>
 *
 * <ul>
 *   <li><b>拖回原目录</b>：源和目标父目录相同时，复制等于把 {@code a.txt} 复制成
 *       {@code a (1).txt}。用户本意往往只是「拖回来算了」，不该凭空多出副本。</li>
 *   <li><b>把文件夹拖进它自己（或它的子目录）</b>：递归复制会无限展开，直接卡死；
 *       移动更会把目录搬进自己里面，数据直接丢失。</li>
 * </ul>
 *
 * <p>所以这里先把源列表过滤成「真正需要传的项」，调用方只看 {@link Check#items}。</p>
 */
public final class DragPlan {

    private DragPlan() {}

    /** 判定结果 */
    public static final class Check {
        /** 真正需要传输的项（已剔除同目录/自我嵌套的） */
        public final List<File> items;
        /** 因为「已经在目标目录里」被跳过的项数 */
        public final int skippedSame;
        /** 因为「目标在源目录内部」被跳过的项数 */
        public final int skippedSelf;
        /** 不可拖放时的人话原因；可拖放为空串 */
        public final String reason;

        Check(List<File> items, int skippedSame, int skippedSelf, String reason) {
            this.items = items;
            this.skippedSame = skippedSame;
            this.skippedSelf = skippedSelf;
            this.reason = reason;
        }

        public boolean ok() {
            return !items.isEmpty();
        }

        /** 面板高亮用：能放才亮强调色 */
        public boolean canDrop() {
            return ok();
        }
    }

    /**
     * 判定「把 srcs 拖到 dstDir」是否可行，并给出过滤后的清单。
     *
     * @param srcs   被拖动的文件/目录（不存在的项会被忽略）
     * @param dstDir 目标目录（须存在且是目录）
     */
    public static Check check(List<File> srcs, File dstDir) {
        if (dstDir == null || !dstDir.isDirectory())
            return new Check(new ArrayList<File>(), 0, 0, "目标是压缩包内或不是文件夹，不能作为拖放目标");
        if (srcs == null || srcs.isEmpty())
            return new Check(new ArrayList<File>(), 0, 0, "没有可拖动的项");

        List<File> out = new ArrayList<File>(srcs.size());
        int same = 0, self = 0;
        for (File s : srcs) {
            if (s == null || !s.exists()) continue;
            if (samePath(s.getParentFile(), dstDir)) {
                same++;                       // 拖回原目录：不产生副本
                continue;
            }
            // 目标 == 源目录本身也要拦：isInside 按定义不含「相等」，漏判就会开始无限递归复制
            if (s.isDirectory() && (samePath(dstDir, s) || isInside(dstDir, s))) {
                self++;                       // 目标在源目录内部：会递归爆炸/丢数据
                continue;
            }
            out.add(s);
        }

        String reason = "";
        if (out.isEmpty()) {
            if (self > 0) reason = "不能把文件夹拖进它自己里面";
            else if (same > 0) reason = "已经在目标目录里了";
            else reason = "没有可拖动的项";
        }
        return new Check(out, same, self, reason);
    }

    /** child 是否在 parent 内部（不含相等）。 */
    public static boolean isInside(File child, File parent) {
        if (child == null || parent == null) return false;
        String c = child.getAbsolutePath();
        String p = parent.getAbsolutePath();
        if (c.equals(p)) return false;
        if (!p.endsWith(File.separator)) p = p + File.separator;
        return c.startsWith(p);
    }

    public static boolean samePath(File a, File b) {
        if (a == null || b == null) return false;
        return a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    /** 对话框标题，如「3 项 → Download」 */
    public static String title(Check ck, File dstDir) {
        String n = ck.items.size() == 1 ? ck.items.get(0).getName() : ck.items.size() + " 项";
        return n + " → " + (dstDir == null ? "?" : dstDir.getName());
    }
}
