package com.hex91.file;

/*#######################################################
 *
 *   HexFile 自研（v1.28）。桥接层：把 SyntaxHighlight 的 12 语言正则规则
 *   （smali/xml/html/json/java/python/shell/properties/c/cpp/js/markdown/text）
 *   适配到 Markor 的 SyntaxHighlighterBase 框架，获得：
 *   - 视口内动态应用 span（大文件不卡）
 *   - 后台线程计算 + 编辑 fixup
 *   - 与 Markor 原生 MarkdownSyntaxHighlighter / PlaintextSyntaxHighlighter 一致的 span 管理
 *
 *   优先级处理：原引擎规则顺序即优先级（注释/字符串在前，避免把字符串里的
 *   关键字染成关键字色）。此处逆序 addSpanGroup，使排前面的规则最后应用，
 *   在 span 叠加时胜出，保持与原引擎一致的视觉优先级。
 *
#########################################################*/

import java.util.List;
import java.util.regex.Pattern;

/**
 * MT 风格多语言语法高亮器：关键词/字符串/注释/数字/注解分色，
 * 覆盖 Java/Kotlin/JS/Python/XML/Shell 等常见语言。
 */
public class MtSyntaxHighlighter extends SyntaxHighlighterBase {

    private static final int FLAGS = Pattern.DOTALL | Pattern.MULTILINE;

    private final String langId;
    private List<SyntaxHighlight.RuleInfo> rules;

    public MtSyntaxHighlighter(final String langId) {
        super();
        this.langId = langId;
        _delay = 900;
        rules = SyntaxHighlight.rulesOf(langId);
    }

    /** 工厂：按语言 id 选高亮器。markdown 用 Markor 原生规则；c/cpp/java/python 优先 Markor 原生代码规则。 */
    public static SyntaxHighlighterBase forLang(final String langId) {
        if (langId == null) return baseOnly();
        if (langId.equals("markdown")) {
            return new MarkdownSyntaxHighlighter();
        }
        // Markor 原生代码高亮（cpp/java/python），规则更完整（含转义/注释/函数）
        if (langId.equals("c") || langId.equals("cpp") || langId.equals("java") || langId.equals("python")) {
            final PlaintextSyntaxHighlighter p = new PlaintextSyntaxHighlighter(langId);
            return p;
        }
        final MtSyntaxHighlighter m = new MtSyntaxHighlighter(langId);
        if (m.rules.isEmpty()) return baseOnly();
        return m;
    }

    /** 无规则语言（text 等）：只应用基础 span（tab/hex 色/URL） */
    private static SyntaxHighlighterBase baseOnly() {
        return new MtSyntaxHighlighter("text");
    }

    @Override
    protected void generateSpans() {
        createTabSpans(_tabSize);
        createUnderlineHexColorsSpans();
        createSmallBlueLinkSpans();

        // 逆序应用：原引擎中排前的规则（注释/字符串）在此最后应用，span 叠加时胜出
        for (int i = rules.size() - 1; i >= 0; i--) {
            final SyntaxHighlight.RuleInfo r = rules.get(i);
            try {
                // 暗色模式：深色系高亮色在深色底上不可读，整体提亮
                createColorSpanForMatches(Pattern.compile(r.regex, FLAGS),
                        ThemeManager.syntaxColor(r.color));
            } catch (Exception e) {
                // 跳过非法正则，不影响其它规则
            }
        }
    }
}
