package com.hex91.file;

/*#######################################################
 *
 *   Maintained 2018-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 *   移植自 Markor（gsantner/markor）format/markdown/MarkdownSyntaxHighlighter。
 *   改动：去 AppSettings 开关（改为默认开启，字段可调）；去 writeily
 *   WrMarkdownHeaderSpanCreator 依赖（标题改为颜色 + RelativeSizeSpan 放大）。
 *
#########################################################*/

import android.graphics.Typeface;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 语法高亮器：标题层级/粗体斜体/行内与围栏代码/链接引用/
 * 列表标记分色，基于 SyntaxHighlighterBase 的正则模式驱动。
 */
public class MarkdownSyntaxHighlighter extends SyntaxHighlighterBase {

    public final static Pattern BOLD = Pattern.compile("(?<=(\\n|^|\\s|\\[|\\{|\\())(([*_]){2,3})(?=\\S)(.*?)\\S\\2(?=(\\n|$|\\s|\\.|,|:|;|-|\\]|\\}|\\)))");
    public final static Pattern ITALICS = Pattern.compile("(?<=(\\n|^|\\s|\\[|\\{|\\())([*_])(?=((?!\\2)|\\2{2,}))(?=\\S)(.*?)\\S\\2(?=(\\n|$|\\s|\\.|,|:|;|-|\\]|\\}|\\)))");
    public final static Pattern HEADING = Pattern.compile("(?m)((^#{1,6}[^\\S\\n][^\\n]+)|((\\n|^)[^\\s]+.*?\\n(-{2,}|={2,})[^\\S\\n]*$))");
    public final static Pattern HEADING_SIMPLE = Pattern.compile("(?m)^(#{1,6}\\s.*$)");
    public final static Pattern HEADING_SETEXT = Pattern.compile("(?m)^\\S.*\\n(-{2,}|={2,})[^\\S\\n]*$");
    // Group 1 matches image, Group 2 matches text, group 3 matches path
    public static final Pattern LINK = Pattern.compile("(?m)(!)?\\[([^]]*)]\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");
    public final static Pattern LIST_UNORDERED = Pattern.compile("(\\n|^)\\s{0,16}([*+-])( \\[[ xX]\\])?(?= )");
    public final static Pattern LIST_ORDERED = Pattern.compile("(?m)^\\s{0,16}(\\d+)(:?\\.|\\))\\s");
    public final static Pattern QUOTATION = Pattern.compile("(\\n|^)>");
    public final static Pattern STRIKETHROUGH = Pattern.compile("~{2}(.*?)\\S~{2}");
    public final static Pattern CODE = Pattern.compile("(?m)(`(?!`)(.*?)`)|(^[^\\S\\n]{4}(?![0-9\\-*+]).*$)");
    public final static Pattern DOUBLESPACE_LINE_ENDING = Pattern.compile("(?m)(?<=\\S)([^\\S\\n]{2,})\\n");

    private static final int MD_COLOR_HEADING = 0xffef6D00;
    private static final int MD_COLOR_LINK = 0xff1ea3fe;
    private static final int MD_COLOR_LIST = 0xffdaa521;
    private static final int MD_COLOR_QUOTE = 0xff88b04c;
    private static final int MD_COLOR_CODEBLOCK = 0x60afafaf;

    // 可配置开关（默认全开）
    public boolean highlightLineEnding = true;
    public boolean highlightCodeChangeFont = true;
    public boolean highlightBiggerHeadings = true;
    public boolean highlightCodeBlock = true;

    public MarkdownSyntaxHighlighter() {
        super();
        _delay = 900; // markdown 高亮延迟（比默认 2400ms 更跟手）
    }

    @Override
    protected void generateSpans() {

        createTabSpans(_tabSize);
        createUnderlineHexColorsSpans();
        createSmallBlueLinkSpans();

        if (highlightBiggerHeadings) {
            // 标题：颜色 + 加粗 + 放大（替代 writeily 的 HeaderSpanCreator）
            createSpanForMatches(HEADING_SIMPLE, new MatcherFn() {
                @Override
                public Object callback(Matcher matcher) {
                    return new HighlightSpan().setForeColor(MD_COLOR_HEADING).setBold(true).setTextScale(1.25f);
                }
            });
            createColorSpanForMatches(HEADING_SETEXT, MD_COLOR_HEADING);
        } else {
            createColorSpanForMatches(HEADING, MD_COLOR_HEADING);
        }

        createColorSpanForMatches(LINK, MD_COLOR_LINK);
        createColorSpanForMatches(LIST_UNORDERED, MD_COLOR_LIST);
        createColorSpanForMatches(LIST_ORDERED, MD_COLOR_LIST);

        if (highlightLineEnding) {
            createColorBackgroundSpan(DOUBLESPACE_LINE_ENDING, MD_COLOR_CODEBLOCK);
        }

        createStyleSpanForMatches(BOLD, Typeface.BOLD);
        createStyleSpanForMatches(ITALICS, Typeface.ITALIC);
        createColorSpanForMatches(QUOTATION, MD_COLOR_QUOTE);
        createStrikeThroughSpanForMatches(STRIKETHROUGH);

        if (highlightCodeChangeFont) {
            createMonospaceSpanForMatches(CODE);
        }

        if (highlightCodeBlock) {
            createColorBackgroundSpan(CODE, MD_COLOR_CODEBLOCK);
        }
    }
}
