package com.hex91.file;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 批量重命名核心逻辑（纯 Java，无 Android 依赖，便于桌面测试）。
 *
 * <p>三种命名模式：
 * <ol>
 *   <li>编号命名：{前缀}{零填编号}{后缀}{原扩展名?}</li>
 *   <li>查找替换：对文件名（可选仅主名）做字面/正则替换</li>
 *   <li>扩展名替换：改写或去掉扩展名</li>
 * </ol>
 *
 * 冲突检测：空名 / 目标重名（区分大小写）/ 目标已存在且非源文件 三类。
 */
public class BatchRenameCore {

    /** 拆分文件名为主名与扩展名。ext 含前导点（如 ".txt"），无扩展名则空串。
     *  点文件（以 . 开头且无后续点）整体视为主名；".config.bak" 拆分为 ".config" + ".bak"。 */
    public static String[] splitName(String name) {
        if (name == null) name = "";
        if (name.startsWith(".")) {
            int second = name.indexOf('.', 1);
            if (second < 0) return new String[]{name, ""};
            return new String[]{name.substring(0, second), name.substring(second)};
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return new String[]{name, ""};
        return new String[]{name.substring(0, dot), name.substring(dot)};
    }

    /** 编号命名：num = start + index*step，按 digits 零填充（digits<=0 不填充）。 */
    public static String modeNumber(String name, String prefix, int start, int step,
            int digits, String suffix, boolean keepExt, int index) {
        if (prefix == null) prefix = "";
        if (suffix == null) suffix = "";
        String[] be = splitName(name);
        int num = start + index * step;
        String numStr = (digits > 0) ? zeroPad(num, digits) : String.valueOf(num);
        String base = prefix + numStr + suffix;
        if (keepExt) base += be[1];
        return base;
    }

    private static String zeroPad(int num, int digits) {
        String s = String.valueOf(num);
        if (num < 0) { // 负数不参与前导零对齐，直接返回
            return s;
        }
        if (s.length() >= digits) return s;
        StringBuilder sb = new StringBuilder(digits);
        for (int i = s.length(); i < digits; i++) sb.append('0');
        sb.append(s);
        return sb.toString();
    }

    /** 查找替换。keepExt 时仅对主名替换；regex 时 find/repl 按正则（repl 支持 $1 等引用）；
     *  非 regex 时做字面全替换，caseSensitive 控制大小写。 */
    public static String modeReplace(String name, String find, String repl,
            boolean regex, boolean caseSensitive, boolean keepExt) {
        if (find == null) find = "";
        if (repl == null) repl = "";
        if (find.isEmpty()) return name;
        String[] be = keepExt ? splitName(name) : new String[]{name, ""};
        String src = be[0];
        String out;
        if (regex) {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            try {
                Pattern p = Pattern.compile(find, flags);
                out = p.matcher(src).replaceAll(repl);
            } catch (Exception e) {
                return name; // 正则非法则不下改名
            }
        } else if (caseSensitive) {
            out = src.replace(find, repl);
        } else {
            out = replaceIgnoreCase(src, find, repl);
        }
        return out + be[1];
    }

    private static String replaceIgnoreCase(String src, String find, String repl) {
        StringBuilder sb = new StringBuilder();
        String lower = src.toLowerCase(Locale.US);
        String fl = find.toLowerCase(Locale.US);
        int i = 0;
        while (true) {
            int j = lower.indexOf(fl, i);
            if (j < 0) { sb.append(src.substring(i)); break; }
            sb.append(src.substring(i, j)).append(repl);
            i = j + find.length();
        }
        return sb.toString();
    }

    /** 扩展名替换。onlyWithExt 时仅对已有扩展名的文件生效；newExt 留空则去掉扩展名；可带或不带前导点。 */
    public static String modeExt(String name, String newExt, boolean onlyWithExt) {
        if (newExt == null) newExt = "";
        String[] be = splitName(name);
        if (onlyWithExt && be[1].isEmpty()) return name;
        String ext = newExt.trim();
        if (ext.startsWith(".")) ext = ext.substring(1);
        if (ext.isEmpty()) return be[0];
        return be[0] + "." + ext;
    }

    /** 冲突检测：返回长度等于 newNames 的布尔数组（true=冲突）。
     *  冲突条件：空名 / 目标彼此重名（区分大小写）/ 目标已存在于磁盘且不是源文件之一。 */
    public static boolean[] detectConflicts(List<File> sources, List<String> newNames) {
        int n = newNames.size();
        boolean[] conflict = new boolean[n];
        for (int i = 0; i < n; i++) if (newNames.get(i).isEmpty()) conflict[i] = true;
        for (int i = 0; i < n; i++) {
            if (conflict[i]) continue;
            for (int j = 0; j < n; j++) {
                if (i != j && newNames.get(i).equals(newNames.get(j))) { conflict[i] = true; break; }
            }
        }
        HashSet<String> srcPaths = new HashSet<String>();
        for (File s : sources) srcPaths.add(s.getAbsolutePath());
        for (int i = 0; i < n; i++) {
            if (conflict[i]) continue;
            File t = new File(sources.get(i).getParentFile(), newNames.get(i));
            if (t.exists() && !srcPaths.contains(t.getAbsolutePath())) conflict[i] = true;
        }
        return conflict;
    }

    /** 便捷方法：由参数一次性算出全部新名（供 Activity 复用）。 */
    public static List<String> computeAll(List<File> files, int mode,
            String prefix, int start, int step, int digits, String suffix, boolean keepExtNum,
            String find, String repl, boolean regex, boolean caseSensitive, boolean keepExtRep,
            String newExt, boolean onlyWithExt) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            String nn;
            if (mode == 0) nn = modeNumber(f.getName(), prefix, start, step, digits, suffix, keepExtNum, i);
            else if (mode == 1) nn = modeReplace(f.getName(), find, repl, regex, caseSensitive, keepExtRep);
            else nn = modeExt(f.getName(), newExt, onlyWithExt);
            out.add(nn);
        }
        return out;
    }
}
