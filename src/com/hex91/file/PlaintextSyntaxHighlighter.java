package com.hex91.file;

/*#######################################################
 *
 *   Maintained 2018-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 *   移植自 Markor（gsantner/markor）format/plaintext/PlaintextSyntaxHighlighter。
 *   改动：原版从 assets/highlight/*.json 加载语言规则（HighlightConfigLoader），
 *   此处将规则内建为 Java 常量（内容来自 Markor 的 cpp/java/python.json），
 *   去除 Context/assets/JSON 依赖。主题色同样内建（来自 default.json）。
 *
#########################################################*/

import android.util.Log;

import java.util.HashMap;
import java.util.regex.Pattern;

public class PlaintextSyntaxHighlighter extends SyntaxHighlighterBase {

    /** 语言规则：扩展名 -> 规则列表（编译惰性） */
    public static final class LangRules {
        public final String[] regexes;
        public final String[] types;
        public final Pattern[] compiled;

        LangRules(String[] regexes, String[] types) {
            this.regexes = regexes;
            this.types = types;
            this.compiled = new Pattern[regexes.length];
        }

        public Pattern pattern(int i) {
            if (compiled[i] == null) {
                compiled[i] = Pattern.compile(regexes[i]);
            }
            return compiled[i];
        }
    }

    private static final HashMap<String, LangRules> RULES = new HashMap<>();
    private static final HashMap<String, Integer> THEME = new HashMap<>();

    static {
        THEME.put("KEYWORDS", 0xFF008AD8);
        THEME.put("KEYWORDS_CONTROL", 0xFFC100DB);
        THEME.put("TYPES", 0xFF1339FF);
        THEME.put("NUMBER", 0xFF00A600);
        THEME.put("STRING", 0xFFC36A2B);
        THEME.put("ESCAPE_CHAR", 0xFF1339FF);
        THEME.put("CONSTANT", 0xFFFA4616);
        THEME.put("LINE_COMMENT", 0xFF8F8F88);
        THEME.put("BLOCK_COMMENT", 0xFF8F8F88);
        THEME.put("CLASS", 0xFFF2827F);
        THEME.put("FUNCTION", 0xFFBE8126);

        // ---- cpp.json ----
        RULES.put("c", new LangRules(new String[]{
                "\\b(typedef|inline|auto|class|explicit|extern|friend|mutable|operator|register|template|private|protected|public|typeid|virtual|volatile|constexpr|nullptr|decltype)\\b",
                "\\b(goto|break|return|continue|asm|case|default|if|else|switch|while|for|do|sizeof|typeof|stdcall|cdecl|const_cast|delete|dynamic_cast|namespace|new|pascal|reinterpret_cast|static_cast|this|throw|try|catch|using|true|false|bitand|and|bitor|or|xor|compl|and_eq|or_eq|xor_eq|not|not_eq|assert|static_assert)\\b",
                "\\b(int|long|short|char|void|signed|unsigned|float|double|size_t|wchar_t|ptrdiff_t|sig_atomic_t|fpos_t|clock_t|time_t|va_list|jmp_buf|FILE|DIR|div_t|ldiv_t|static|mbstate_t|wctrans_t|wint_t|wctype_t|const|bool|complex|struct|union|enum|int8_t|int16_t|int32_t|int64_t|uint8_t|uint16_t|uint32_t|uint64_t|int_least8_t|int_least16_t|int_least32_t|int_least64_t|int_fast8_t|int_fast16_t|int_fast32_t|int_fast64_t|intptr_t|uintptr_t|intmax_t|uintmax_t)\\b",
                "\\b\\d+(\\.\\d*)?(e(\\+|\\-)?\\d+)?\\b",
                "(\".*\")|('.*')",
                "\\\\([0bfnrtv'\"]|u[0-9a-fA-F]{4}|[0-7]{3})",
                "//.*",
                "/\\*[\\s\\S]*?\\*/",
                "([a-zA-Z0-9_$]+)(?=\\()",
        }, new String[]{
                "KEYWORDS", "KEYWORDS_CONTROL", "TYPES", "NUMBER", "STRING",
                "ESCAPE_CHAR", "LINE_COMMENT", "BLOCK_COMMENT", "FUNCTION",
        }));
        RULES.put("cpp", RULES.get("c"));

        // ---- java.json ----
        RULES.put("java", new LangRules(new String[]{
                "\\b(abstract|continue|for|new|switch|assert|default|if|package|synchronized|boolean|do|goto|private|this|break|double|implements|protected|throw|byte|else|import|public|throws|case|enum|instanceof|return|transient|catch|extends|int|short|try|char|final|interface|static|void|class|finally|long|strictfp|volatile|const|float|native|super|while|var|record|sealed|yield)\\b",
                "\\b(int|long|short|char|void|signed|unsigned|float|double|size_t|boolean|byte|String|Object|Integer|Long|Double|Float|Boolean|Character|List|Map|Set|ArrayList|HashMap|HashSet|Exception|Throwable|System)\\b",
                "\\b\\d+(\\.\\d*)?(e(\\+|\\-)?\\d+)?[LlFfDd]?\\b",
                "(\".*\")|('.*')",
                "\\\\([0bfnrtv'\"]|u[0-9a-fA-F]{4})",
                "//.*",
                "/\\*[\\s\\S]*?\\*/",
                "([a-zA-Z0-9_$]+)(?=\\()",
                "(@[A-Za-z_][A-Za-z0-9_]*)",
        }, new String[]{
                "KEYWORDS", "TYPES", "NUMBER", "STRING",
                "ESCAPE_CHAR", "LINE_COMMENT", "BLOCK_COMMENT", "FUNCTION", "CLASS",
        }));

        // ---- python.json ----
        RULES.put("python", new LangRules(new String[]{
                "\\b(and|as|assert|async|await|break|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|nonlocal|not|or|pass|raise|return|try|while|with|yield|match|case)\\b",
                "\\b(True|False|None|self|cls)\\b",
                "\\b\\d+(\\.\\d*)?(e(\\+|\\-)?\\d+)?[jJ]?\\b",
                "([rbf]?\"\"\"[\\s\\S]*?\"\"\")|([rbf]?''[\\s\\S]*?'')|([rbf]?\"(?:[^\"\\\\]|\\\\.)*\")|([rbf]?'(?:[^'\\\\]|\\\\.)*')",
                "\\\\([0bfnrtv'\"])",
                "#.*",
                "([a-zA-Z_][a-zA-Z0-9_]*)(?=\\()",
        }, new String[]{
                "KEYWORDS_CONTROL", "CONSTANT", "NUMBER", "STRING",
                "ESCAPE_CHAR", "LINE_COMMENT", "FUNCTION",
        }));
    }

    /** Markor 的扩展名映射（map.properties）：c/cpp->cpp, java->java, py->python */
    public static LangRules rulesForExtension(final String ext) {
        if (ext == null) return null;
        final String e = ext.toLowerCase();
        if (e.equals("c") || e.equals("cpp") || e.equals("cc") || e.equals("cxx")
                || e.equals("h") || e.equals("hpp") || e.equals("hxx")) return RULES.get("cpp");
        if (e.equals("java")) return RULES.get("java");
        if (e.equals("py") || e.equals("python")) return RULES.get("python");
        return null;
    }

    private LangRules rules;

    public PlaintextSyntaxHighlighter() {
        super();
        _delay = 900;
    }

    public PlaintextSyntaxHighlighter(final String extension) {
        this();
        rules = rulesForExtension(extension);
    }

    @Override
    protected void generateSpans() {
        createTabSpans(_tabSize);
        createUnderlineHexColorsSpans();
        createSmallBlueLinkSpans();

        if (rules == null) {
            return;
        }

        for (int i = 0; i < rules.types.length; i++) {
            final Integer color = THEME.get(rules.types[i]);
            if (color != null) {
                try {
                    // 暗色模式：深色系高亮色在深色底上不可读，整体提亮
                    createColorSpanForMatches(rules.pattern(i),
                            ThemeManager.syntaxColor(color.intValue()));
                } catch (Exception e) {
                    Log.e(getClass().getName(), "bad pattern: " + e);
                }
            }
        }
    }
}
