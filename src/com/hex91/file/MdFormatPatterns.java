package com.hex91.file;

/*#######################################################
 *
 *   Maintained 2018-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 *   摘录自 Markor（gsantner/markor）format/markdown/MarkdownReplacePatternGenerator
 *   与 format/wikitext/WikitextReplacePatternGenerator 的前缀模式，
 *   用于 AutoTextFormatter 的自动缩进 / 列表续写。
 *
#########################################################*/

import java.util.regex.Pattern;

public final class MdFormatPatterns {

    private MdFormatPatterns() {
    }

    // ---- Markdown ----
    public static final Pattern PREFIX_ORDERED_LIST = Pattern.compile("^(\\s*)((\\d+)(\\.|\\))(\\s))");
    public static final Pattern PREFIX_ATX_HEADING = Pattern.compile("^(\\s{0,3})(#{1,6}\\s)");
    public static final Pattern PREFIX_QUOTE = Pattern.compile("^(>\\s)");
    public static final Pattern PREFIX_CHECKED_LIST = Pattern.compile("^(\\s*)((-|\\*|\\+)\\s\\[(x|X)]\\s)");
    public static final Pattern PREFIX_CHECKBOX_LIST = Pattern.compile("^(\\s*)(([-*+]\\s\\[)[\\sxX](]\\s))");
    public static final Pattern PREFIX_UNCHECKED_LIST = Pattern.compile("^(\\s*)((-|\\*|\\+)\\s\\[\\s]\\s)");
    public static final Pattern PREFIX_UNORDERED_LIST = Pattern.compile("^(\\s*)((-|\\*|\\+)\\s)");
    public static final Pattern PREFIX_LEADING_SPACE = Pattern.compile("^(\\s*)");

    /** Markdown 自动缩进 pattern（indentSlack=2） */
    public static final AutoTextFormatter.FormatPatterns MARKDOWN = new AutoTextFormatter.FormatPatterns(
            PREFIX_UNORDERED_LIST,
            PREFIX_CHECKBOX_LIST,
            PREFIX_ORDERED_LIST,
            2);

    // ---- Wikitext（可选，编辑器按语言选用）----
    public static final Pattern WIKI_PREFIX_UNORDERED_LIST = Pattern.compile("^(\\s*)((\\*+)\\s)");
    public static final Pattern WIKI_PREFIX_ORDERED_LIST = Pattern.compile("^(\\s*)((\\#+)\\s)");

    public static final AutoTextFormatter.FormatPatterns WIKITEXT = new AutoTextFormatter.FormatPatterns(
            WIKI_PREFIX_UNORDERED_LIST,
            PREFIX_CHECKBOX_LIST,
            WIKI_PREFIX_ORDERED_LIST,
            2);
}
