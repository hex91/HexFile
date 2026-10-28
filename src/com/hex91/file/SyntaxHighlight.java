package com.hex91.file;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多语言语法高亮引擎（v1.26，取代原仅支持 smali/xml 的版本）。
 *
 * 设计：
 *  - 用「通用正则规则引擎」描述每种语言：一组 (regex, 颜色) 规则，编译为单条组合 Pattern。
 *  - 整篇文本一次 find() 扫描（O(n)），命中的区间打上 {@link SyntaxSpan}（ForegroundColorSpan 子类，
 *    便于重高亮时精准清除，不影响搜索高亮 BackgroundColorSpan）。
 *  - 规则顺序即优先级：注释/字符串排在关键字之前，避免把字符串里的关键字染成关键字色。
 *  - 后台线程调用，超过 MAX_LEN 直接返回原文（防卡顿）。
 *
 * 支持：smali / xml / html / json / java / python / shell / properties / c / cpp / js / markdown / text。
 */
public final class SyntaxHighlight {

    private SyntaxHighlight() {
    }

    public static final int MAX_LEN = 768 * 1024; // 768KB

    // ---------- 调色板（白底友好）----------
    private static final int C_COMMENT = 0xFF9E9E9E;   // 注释 灰
    private static final int C_STRING  = 0xFF2E7D32;   // 字符串 绿
    private static final int C_NUMBER  = 0xFFE65100;   // 数字 深橙
    private static final int C_KEY     = 0xFF7C4DFF;   // 关键字 紫
    private static final int C_TYPE    = 0xFF1565C0;   // 类型/类名 蓝
    private static final int C_DIRECT  = 0xFFD81B60;   // 指令/伪指令 粉
    private static final int C_LABEL   = 0xFFF57C00;   // 标签 橙
    private static final int C_REG     = 0xFF00838F;   // 寄存器/变量 青
    private static final int C_ANNO    = 0xFFAD1457;   // 注解 玫红
    private static final int C_TAG     = 0xFF7C4DFF;   // 标签名 紫
    private static final int C_ATTR    = 0xFF0288D1;   // 属性名 蓝
    private static final int C_BRACKET = 0xFF546E7A;   // 尖括号 灰蓝
    private static final int C_OP      = 0xFF546E7A;   // 运算符 灰蓝
    private static final int C_MDHEAD  = 0xFF1565C0;   // md 标题 蓝
    private static final int C_MDBOLD  = 0xFFD81B60;   // md 粗体 粉
    private static final int C_MDITAL  = 0xFF00838F;   // md 斜体 青
    private static final int C_MDCODE  = 0xFF2E7D32;   // md 行内代码 绿
    private static final int C_MDLINK  = 0xFF1565C0;   // md 链接 蓝
    private static final int C_MDQUOTE = 0xFF607D8B;   // md 引用 灰蓝

    /** 单条高亮规则 */
    private static final class Rule {
        final String regex;
        final int color;
        Rule(String r, int c) { regex = r; color = c; }
    }

    /** 语言定义 */
    private static final class Lang {
        final String id;
        final String label;
        final List<Rule> rules = new ArrayList<Rule>();
        final Set<String> exts;
        final Set<String> completions = new java.util.HashSet<String>();
        Lang(String id, String label, String[] exts) {
            this.id = id; this.label = label;
            this.exts = new java.util.HashSet<String>(Arrays.asList(exts));
        }
    }

    private static final Map<String, Lang> LANGS = new LinkedHashMap<String, Lang>();
    private static final Map<String, String> EXT2ID = new ConcurrentHashMap<String, String>();
    private static final Map<String, Pattern> PAT_CACHE = new ConcurrentHashMap<String, Pattern>();

    private static final int FLAGS = Pattern.DOTALL | Pattern.MULTILINE;

    static {
        buildSmali();
        buildXml();
        buildJson();
        buildJava();
        buildPython();
        buildShell();
        buildProperties();
        buildC();
        buildCpp();
        buildJs();
        buildMarkdown();
        buildText();
        for (Lang l : LANGS.values())
            for (String e : l.exts) EXT2ID.put(e, l.id);
    }

    // ============================================================ 语言定义

    private static Lang lang(String id, String label, String[] exts) {
        Lang l = new Lang(id, label, exts);
        LANGS.put(id, l);
        return l;
    }

    private static void add(Lang l, String regex, int color) {
        l.rules.add(new Rule(regex, color));
    }

    private static void kw(Lang l, String[] words, int color) {
        if (words == null || words.length == 0) return;
        StringBuilder sb = new StringBuilder("\\b(?:");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(words[i]);
        }
        sb.append(")\\b");
        add(l, sb.toString(), color);
        for (String w : words) l.completions.add(w);
    }

    private static void buildSmali() {
        Lang l = lang("smali", "Smali", new String[]{"smali", "smali.txt"});
        add(l, "#[^\\n]*", C_COMMENT);                                   // 行注释
        add(l, "\"(?:\\\\.|[^\"\\\\])*\"", C_STRING);                    // 字符串
        add(l, "\\.[A-Za-z][\\w\\-]*", C_DIRECT);                        // 伪指令 .class .method
        add(l, ":[A-Za-z_][\\w]*", C_LABEL);                            // 标签 :goto_0
        add(l, "L[\\w\\/.$]*;", C_TYPE);                                // 类型 Lcom/x/Y;
        add(l, "[vp]\\d+", C_REG);                                      // 寄存器 v0 p1
        kw(l, new String[]{"invoke", "invoke-virtual", "invoke-static", "invoke-direct",
                "invoke-super", "invoke-interface", "invoke-virtual/range", "const",
                "const-string", "const-class", "return", "return-void", "return-object",
                "if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le", "if-eqz", "if-nez",
                "if-ltz", "if-gez", "if-gtz", "if-lez", "goto", "move", "move-result",
                "move-result-object", "new-instance", "new-array", "iget", "iput", "sget",
                "sput", "aget", "aput", "add", "sub", "mul", "div", "rem", "neg", "not",
                "and", "or", "xor", "shl", "shr", "ushr", "int-to", "long-to", "float-to",
                "double-to", "check-cast", "instance-of", "throw", "monitor", "fill-array",
                "packed-switch", "sparse-switch", "cmp", "cmpl", "cmpg", "nop", "execute"}, C_KEY);
        add(l, "(?:-?0x[\\da-fA-F]+|-?\\d+(?:\\.\\d+)?)", C_NUMBER);
    }

    private static void buildXml() {
        Lang l = lang("xml", "XML / HTML", new String[]{"xml", "html", "htm", "xhtml", "svg"});
        add(l, "<!--.*?-->", C_COMMENT);
        add(l, "</?[A-Za-z][\\w:-]*", C_TAG);                            // 标签名
        add(l, "[A-Za-z_:][\\w:-]*(?==)", C_ATTR);                      // 属性名
        add(l, "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", C_STRING); // 属性值
        add(l, "</?|/?>", C_BRACKET);                                   // 尖括号
    }

    private static void buildJson() {
        Lang l = lang("json", "JSON", new String[]{"json"});
        add(l, "\"(?:\\\\.|[^\"\\\\])*\"", C_STRING);
        kw(l, new String[]{"true", "false", "null"}, C_KEY);
        add(l, "-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?", C_NUMBER);
    }

    private static void buildJava() {
        Lang l = lang("java", "Java", new String[]{"java", "kt", "kotlin", "gradle"});
        add(l, "/\\*.*?\\*/", C_COMMENT);
        add(l, "//[^\\n]*", C_COMMENT);
        add(l, "\"(?:\\\\.|[^\"\\\\])*\"", C_STRING);
        add(l, "'(?:\\\\.|[^'\\\\])'", C_STRING);
        add(l, "@[A-Za-z][\\w]*", C_ANNO);
        kw(l, new String[]{"public", "private", "protected", "static", "final", "abstract",
                "class", "interface", "enum", "extends", "implements", "new", "return", "if",
                "else", "for", "while", "do", "switch", "case", "break", "continue", "throw",
                "try", "catch", "finally", "import", "package", "void", "int", "long", "double",
                "float", "boolean", "char", "byte", "short", "this", "super", "null", "true",
                "false", "instanceof", "synchronized", "volatile", "transient", "native",
                "strictfp", "default", "var", "record", "sealed", "permits", "yield", "assert",
                "const", "goto"}, C_KEY);
        add(l, "(?:-?0x[\\da-fA-F]+|-?\\d[\\d_]*(?:\\.\\d+)?[lLfFdD]?)", C_NUMBER);
    }

    private static void buildPython() {
        Lang l = lang("python", "Python", new String[]{"py", "pyw"});
        add(l, "#[^\\n]*", C_COMMENT);
        add(l, "\"\"\".*?\"\"\"|'''?.*?'''?", C_STRING);
        add(l, "\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'", C_STRING);
        add(l, "@[A-Za-z_][\\w]*", C_ANNO);
        kw(l, new String[]{"def", "class", "import", "from", "as", "return", "if", "elif",
                "else", "for", "while", "in", "not", "and", "or", "with", "try", "except",
                "finally", "raise", "lambda", "yield", "global", "nonlocal", "pass", "break",
                "continue", "assert", "del", "None", "True", "False", "self", "async", "await",
                "is"}, C_KEY);
        add(l, "\\b\\d[\\d_]*(?:\\.\\d+)?\\b", C_NUMBER);
    }

    private static void buildShell() {
        Lang l = lang("shell", "Shell", new String[]{"sh", "bash", "zsh", "cfg"});
        add(l, "#[^\\n]*", C_COMMENT);
        add(l, "\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'", C_STRING);
        add(l, "\\$\\w+|\\$\\{[^}]*\\}", C_REG);
        kw(l, new String[]{"if", "then", "else", "elif", "fi", "for", "while", "do", "done",
                "case", "esac", "function", "in", "return", "exit", "echo", "export", "local",
                "select", "until", "source"}, C_KEY);
        add(l, "\\b\\d+\\b", C_NUMBER);
    }

    private static void buildProperties() {
        Lang l = lang("properties", "Properties", new String[]{"properties", "prop", "ini"});
        add(l, "[#!][^\\n]*", C_COMMENT);
        add(l, "^[ \\t]*[\\w.\\-]+(?==|:)", C_ATTR);
        add(l, "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", C_STRING);
    }

    private static void buildC() {
        Lang l = lang("c", "C", new String[]{"c", "h"});
        add(l, "/\\*.*?\\*/", C_COMMENT);
        add(l, "//[^\\n]*", C_COMMENT);
        add(l, "\"(?:\\\\.|[^\"\\\\])*\"", C_STRING);
        add(l, "'(?:\\\\.|[^'\\\\])'", C_STRING);
        add(l, "#[A-Za-z]+", C_DIRECT);
        kw(l, new String[]{"int", "long", "short", "char", "float", "double", "void",
                "unsigned", "signed", "const", "static", "struct", "union", "enum", "typedef",
                "class", "public", "private", "protected", "virtual", "template", "typename",
                "namespace", "using", "new", "delete", "return", "if", "else", "for", "while",
                "do", "switch", "case", "break", "continue", "sizeof", "true", "false", "null",
                "nullptr", "auto", "bool", "this", "throw", "try", "catch", "include", "define",
                "ifdef", "ifndef", "endif", "pragma"}, C_KEY);
        add(l, "(?:-?0x[\\da-fA-F]+|-?\\d[\\d_]*(?:\\.\\d+)?[lLfFdD]?)", C_NUMBER);
    }

    private static void buildCpp() {
        buildC(); // 复用 C 规则底子
        Lang base = LANGS.get("c");
        Lang l = lang("cpp", "C++", new String[]{"cpp", "cc", "cxx", "hpp", "hxx"});
        l.rules.addAll(base.rules);
        l.completions.addAll(base.completions);
        // C++ 额外关键字
        kw(l, new String[]{"std", "vector", "string", "cout", "cin", "endl", "class",
                "template", "typename", "namespace", "using", "auto", "nullptr", "override",
                "final", "constexpr", "noexcept", "explicit", "friend", "mutable", "virtual",
                "public", "private", "protected", "operator", "new", "delete", "this"}, C_KEY);
    }

    private static void buildJs() {
        Lang l = lang("js", "JavaScript", new String[]{"js", "jsx", "mjs", "ts", "tsx", "vue"});
        add(l, "/\\*.*?\\*/", C_COMMENT);
        add(l, "//[^\\n]*", C_COMMENT);
        add(l, "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`", C_STRING);
        kw(l, new String[]{"function", "var", "let", "const", "if", "else", "for", "while",
                "do", "switch", "case", "break", "continue", "return", "new", "class", "extends",
                "super", "this", "typeof", "instanceof", "in", "of", "try", "catch", "finally",
                "throw", "await", "async", "yield", "import", "export", "from", "default", "null",
                "true", "false", "undefined", "void", "delete"}, C_KEY);
        add(l, "(?:-?0x[\\da-fA-F]+|-?\\d[\\d_]*(?:\\.\\d+)?)", C_NUMBER);
    }

    private static void buildMarkdown() {
        Lang l = lang("markdown", "Markdown", new String[]{"md", "markdown", "mkd"});
        add(l, "^#{1,6}[^\\n]*", C_MDHEAD);                              // 标题
        add(l, "^[ \\t]*(?:-{3,}|\\*{3,}|_{3,})[ \\t]*$", C_MDQUOTE);    // 分隔线
        add(l, "\\*\\*[^*\\n]+\\*\\*", C_MDBOLD);                       // 粗体
        add(l, "\\*[^*\\n]+\\*", C_MDITAL);                             // 斜体
        add(l, "`[^`\\n]+`", C_MDCODE);                                 // 行内代码
        add(l, "\\[[^\\]]*\\]\\([^)]*\\)", C_MDLINK);                   // 链接
        add(l, "^>[^\\n]*", C_MDQUOTE);                                // 引用
        add(l, "^[ \\t]*[-*+][ \\t]|^[ \\t]*\\d+\\.[ \\t]", C_MDQUOTE); // 列表标记
    }

    private static void buildText() {
        lang("text", "纯文本", new String[]{"txt", "text", "log", "csv"});
    }

    // ============================================================ 公开 API

    /** 根据文件名扩展名推断语言 id；未知返回 "text" */
    public static String detectLang(String fileName) {
        if (fileName == null) return "text";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "text";
        String ext = fileName.substring(dot + 1).toLowerCase();
        String id = EXT2ID.get(ext);
        return id != null ? id : "text";
    }

    /** 兼容旧调用：按文件名自动选语言高亮 */
    public static CharSequence highlight(String fileName, CharSequence cs) {
        return highlightById(detectLang(fileName), cs);
    }

    /** 按语言 id 高亮（可用 {@link #listLanguages()} 中的 id，或 "text" 不高亮） */
    public static CharSequence highlightById(String id, CharSequence cs) {
        if (cs == null) return "";
        String text = cs.toString();
        if (text.length() > MAX_LEN) return text;
        Lang lang = LANGS.get(id);
        if (lang == null || lang.rules.isEmpty()) return text;
        Pattern pat = getPattern(lang);
        Matcher m = pat.matcher(text);
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int pos = 0;
        while (m.find()) {
            int s = m.start(), e = m.end();
            if (s < pos) continue;            // 跳过重叠
            int rule = -1;
            for (int g = 1; g <= lang.rules.size(); g++) {
                if (m.start(g) == s && m.end(g) == e) { rule = g - 1; break; }
            }
            if (rule < 0) { pos = e; continue; }
            sb.setSpan(new SyntaxSpan(lang.rules.get(rule).color), s, e,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            pos = e;
        }
        return sb;
    }

    private static Pattern getPattern(Lang lang) {
        Pattern p = PAT_CACHE.get(lang.id);
        if (p != null) return p;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lang.rules.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append("(?<g").append(i).append(">").append(lang.rules.get(i).regex).append(")");
        }
        p = Pattern.compile(sb.toString(), FLAGS);
        PAT_CACHE.put(lang.id, p);
        return p;
    }

    /** 语言清单（id + 显示名），供"语法"菜单使用 */
    public static List<LangInfo> listLanguages() {
        List<LangInfo> out = new ArrayList<LangInfo>();
        for (Lang l : LANGS.values()) out.add(new LangInfo(l.id, l.label));
        return out;
    }

    /** 代码补全候选词（按当前行已输入前缀在 Activity 内过滤） */
    public static Set<String> completions(String id) {
        Lang l = LANGS.get(id);
        return l != null ? l.completions : java.util.Collections.<String>emptySet();
    }

    /** 专用于语法高亮的 Span 子类，便于重高亮时精准清除 */
    public static final class SyntaxSpan extends ForegroundColorSpan {
        public SyntaxSpan(int color) { super(color); }
    }

    /** 调试用：强制编译并返回某语言的组合 Pattern（供桌面测试验证正则合法性，不依赖 Android 渲染） */
    public static String debugPattern(String id) {
        Lang l = LANGS.get(id);
        if (l == null) return null;
        return getPattern(l).pattern();
    }

    /**
     * 暴露某语言的规则列表（regex + 颜色），供 MtSyntaxHighlighter 桥接到
     * Markor 的 SyntaxHighlighterBase 框架（视口高亮 + 后台计算）。
     * 规则顺序即原引擎优先级（注释/字符串在前）。
     */
    public static List<RuleInfo> rulesOf(String id) {
        Lang l = LANGS.get(id);
        if (l == null) return java.util.Collections.<RuleInfo>emptyList();
        List<RuleInfo> out = new ArrayList<RuleInfo>();
        for (Rule r : l.rules) out.add(new RuleInfo(r.regex, r.color));
        return out;
    }

    /** 单条高亮规则（regex + 颜色） */
    public static final class RuleInfo {
        public final String regex;
        public final int color;
        RuleInfo(String regex, int color) { this.regex = regex; this.color = color; }
    }

    public static final class LangInfo {
        public final String id;
        public final String label;
        LangInfo(String id, String label) { this.id = id; this.label = label; }
        @Override public String toString() { return label; }
    }
}
