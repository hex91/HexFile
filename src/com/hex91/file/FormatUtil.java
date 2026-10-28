package com.hex91.file;

/**
 * 文本格式化引擎（v1.29.0）：JSON / XML(HTML) / 大括号语言（java/c/cpp/js）。
 *
 * 设计原则——**只动空白，不动内容**：
 *  - JSON：自写 parser，保留原始数字字面量（避免 org.json 把 1.0/长整数转 double 丢精度），
 *    字符串按原 token 输出（转义形式不变）；
 *  - XML/HTML：扫描式标签流缩进，属性、文本、注释、CDATA 全部原样保留；
 *  - 代码：扫描式状态机（字符串/字符/行注释/块注释），花括号缩进 + 分号换行，
 *    字符串与注释内的任何符号（; { } 等）绝不参与规则。
 *
 * 所有方法均为纯函数（输入字符串 → 输出字符串），桌面可独立验证。
 */
public final class FormatUtil {

    public static final class FormatException extends Exception {
        public FormatException(String msg) { super(msg); }
    }

    private FormatUtil() {}

    // ============================================================ JSON

    /** JSON 美化（2 空格缩进）。失败抛 FormatException。 */
    public static String formatJson(String src) throws FormatException {
        JsonParser p = new JsonParser(src);
        StringBuilder sb = new StringBuilder();
        p.parseValue(sb, 0);
        p.skipWs();
        if (!p.eof()) throw new FormatException("JSON 尾部有多余内容（位置 " + p.pos + "）");
        return sb.toString();
    }

    /** JSON 压缩（去掉所有多余空白）。失败抛 FormatException。 */
    public static String minifyJson(String src) throws FormatException {
        JsonParser p = new JsonParser(src);
        StringBuilder sb = new StringBuilder();
        p.minifyValue(sb);
        p.skipWs();
        if (!p.eof()) throw new FormatException("JSON 尾部有多余内容（位置 " + p.pos + "）");
        return sb.toString();
    }

    private static final class JsonParser {
        final String s;
        int pos;

        JsonParser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        char peek() throws FormatException {
            if (eof()) throw new FormatException("JSON 意外结束");
            return s.charAt(pos);
        }

        void expect(char c) throws FormatException {
            if (eof() || s.charAt(pos) != c)
                throw new FormatException("JSON 期望 '" + c + "'（位置 " + pos + "）");
            pos++;
        }

        void parseValue(StringBuilder out, int depth) throws FormatException {
            skipWs();
            char c = peek();
            if (c == '{') parseObject(out, depth);
            else if (c == '[') parseArray(out, depth);
            else if (c == '"') out.append(readString());
            else if (c == 't' || c == 'f' || c == 'n') out.append(readLiteral());
            else if (c == '-' || (c >= '0' && c <= '9')) out.append(readNumber());
            else throw new FormatException("JSON 非法字符 '" + c + "'（位置 " + pos + "）");
        }

        void minifyValue(StringBuilder out) throws FormatException {
            skipWs();
            char c = peek();
            if (c == '{') {
                expect('{');
                out.append('{');
                skipWs();
                if (peek() == '}') { expect('}'); out.append('}'); return; }
                while (true) {
                    skipWs();
                    String key = readString();
                    out.append(key);
                    skipWs();
                    expect(':');
                    out.append(':');
                    minifyValue(out);
                    skipWs();
                    char n = peek();
                    if (n == ',') { expect(','); out.append(','); continue; }
                    if (n == '}') { expect('}'); out.append('}'); return; }
                    throw new FormatException("JSON 对象期望 ',' 或 '}'（位置 " + pos + "）");
                }
            } else if (c == '[') {
                expect('[');
                out.append('[');
                skipWs();
                if (peek() == ']') { expect(']'); out.append(']'); return; }
                while (true) {
                    minifyValue(out);
                    skipWs();
                    char n = peek();
                    if (n == ',') { expect(','); out.append(','); continue; }
                    if (n == ']') { expect(']'); out.append(']'); return; }
                    throw new FormatException("JSON 数组期望 ',' 或 ']'（位置 " + pos + "）");
                }
            } else if (c == '"') {
                out.append(readString());
            } else if (c == 't' || c == 'f' || c == 'n') {
                out.append(readLiteral());
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                out.append(readNumber());
            } else {
                throw new FormatException("JSON 非法字符 '" + c + "'（位置 " + pos + "）");
            }
        }

        void parseObject(StringBuilder out, int depth) throws FormatException {
            expect('{');
            skipWs();
            if (peek() == '}') { expect('}'); out.append("{}"); return; }
            out.append("{\n");
            int idx = 0;
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                indent(out, depth + 1);
                out.append(key).append(": ");
                parseValue(out, depth + 1);
                skipWs();
                char n = peek();
                idx++;
                if (n == ',') {
                    expect(',');
                    out.append(",\n");
                    skipWs();
                    // 宽容尾随逗号（很多手写 JSON 带）：回退已输出的 ",\n"
                    if (peek() == '}') {
                        if (out.length() >= 2 && out.charAt(out.length() - 1) == '\n'
                                && out.charAt(out.length() - 2) == ',') {
                            out.setLength(out.length() - 2);
                        }
                        expect('}');
                        break;
                    }
                    continue;
                }
                if (n == '}') { expect('}'); break; }
                throw new FormatException("JSON 对象期望 ',' 或 '}'（位置 " + pos + "）");
            }
            out.append('\n');
            indent(out, depth);
            out.append('}');
        }

        void parseArray(StringBuilder out, int depth) throws FormatException {
            expect('[');
            skipWs();
            if (peek() == ']') { expect(']'); out.append("[]"); return; }
            out.append("[\n");
            while (true) {
                indent(out, depth + 1);
                parseValue(out, depth + 1);
                skipWs();
                char n = peek();
                if (n == ',') {
                    expect(',');
                    out.append(",\n");
                    skipWs();
                    if (peek() == ']') {
                        if (out.length() >= 2 && out.charAt(out.length() - 1) == '\n'
                                && out.charAt(out.length() - 2) == ',') {
                            out.setLength(out.length() - 2);
                        }
                        expect(']');
                        break;
                    }
                    continue;
                }
                if (n == ']') { expect(']'); break; }
                throw new FormatException("JSON 数组期望 ',' 或 ']'（位置 " + pos + "）");
            }
            out.append('\n');
            indent(out, depth);
            out.append(']');
        }

        static void indent(StringBuilder out, int depth) {
            for (int i = 0; i < depth * 2; i++) out.append(' ');
        }

        /** 读取字符串 token（含引号与原始转义序列，原样返回） */
        String readString() throws FormatException {
            expect('"');
            StringBuilder sb = new StringBuilder("\"");
            while (true) {
                if (eof()) throw new FormatException("JSON 字符串未闭合");
                char c = s.charAt(pos++);
                sb.append(c);
                if (c == '\\') {
                    if (eof()) throw new FormatException("JSON 字符串转义未闭合");
                    char e = s.charAt(pos++);
                    sb.append(e);
                    if (e == 'u') {
                        if (pos + 4 > s.length()) throw new FormatException("JSON \\u 转义非法");
                        sb.append(s, pos, pos + 4);
                        pos += 4;
                    }
                } else if (c == '"') {
                    return sb.toString();
                } else if (c == '\n' || c == '\r') {
                    throw new FormatException("JSON 字符串含未转义换行");
                }
            }
        }

        String readLiteral() throws FormatException {
            for (String lit : new String[]{"true", "false", "null"}) {
                if (s.startsWith(lit, pos)) { pos += lit.length(); return lit; }
            }
            throw new FormatException("JSON 非法字面量（位置 " + pos + "）");
        }

        /** 读取数字 token，原样保留字面量文本 */
        String readNumber() throws FormatException {
            int start = pos;
            if (!eof() && s.charAt(pos) == '-') pos++;
            while (!eof()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || c == '+' || c == '-') pos++;
                else break;
            }
            String num = s.substring(start, pos);
            if (num.isEmpty() || num.equals("-")) throw new FormatException("JSON 数字非法（位置 " + start + "）");
            // 粗校验：不允许 "1-" / "1e" 之类明显残缺
            char last = num.charAt(num.length() - 1);
            if (last == '+' || last == '-' || last == 'e' || last == 'E' || last == '.')
                throw new FormatException("JSON 数字格式非法: " + num);
            return num;
        }
    }

    // ============================================================ XML / HTML 扫描式美化

    // 常见 HTML 块级标签（会引入缩进）；行内/void 标签不缩进
    private static final String HTML_BLOCK_TAGS =
            "|html|head|body|title|meta|link|script|style|div|p|span|section|article|aside|" +
            "header|footer|nav|main|ul|ol|li|dl|dt|dd|table|thead|tbody|tfoot|tr|td|th|caption|" +
            "form|fieldset|legend|label|h1|h2|h3|h4|h5|h6|blockquote|pre|hr|br|figure|figcaption|" +
            "details|summary|template|svg|canvas|iframe|a|b|i|u|em|strong|small|code|center|font|";

    // HTML void 元素（无闭合标签，不引入缩进层级）
    private static final String VOID_TAGS =
            "|meta|link|br|hr|img|input|source|track|area|base|col|embed|param|wbr|";

    /**
     * XML/HTML 美化：标签流缩进，内容零改动。
     * - 每个开始标签前按栈深度缩进换行；结束标签退缩进；
     * - 自闭合标签、注释、CDATA、DOCTYPE、处理指令独立成行；
     * - 元素间纯文本节点 trim 后单独一行缩进（保留内部空白）；
     * - html 模式下 a/b/i 等行内标签不引入额外缩进层级（避免 li 里 a 被拆行）。
     */
    public static String formatXml(String src) {
        return formatXmlLike(src, src.length() > 512 * 1024);
    }

    public static String formatXmlLike(String src, boolean html) {
        StringBuilder out = new StringBuilder(src.length() + src.length() / 4 + 64);
        int len = src.length();
        int depth = 0;
        boolean lineStart = true;   // 当前是否处于行首（尚未输出行首内容）
        int textStart = -1;         // 文本节点累积起点
        int i = 0;

        while (i < len) {
            char c = src.charAt(i);
            if (c != '<') {
                if (textStart < 0) textStart = i;
                i++;
                continue;
            }
            // ---- 遇到 '<'：先冲刷之前累积的文本节点
            if (textStart >= 0) {
                String raw = src.substring(textStart, i);
                String trimmed = raw.trim();
                if (trimmed.length() > 0) {
                    newline(out, depth, lineStart);
                    out.append(trimmed);
                    lineStart = false;
                }
                textStart = -1;
            }
            // ---- 分类处理
            if (src.startsWith("<!--", i)) {
                int end = src.indexOf("-->", i + 4);
                int stop = (end < 0) ? len : end + 3;
                newline(out, depth, lineStart);
                out.append(src, i, stop);
                lineStart = false;
                i = stop;
            } else if (src.startsWith("<![CDATA[", i)) {
                int end = src.indexOf("]]>", i + 9);
                int stop = (end < 0) ? len : end + 3;
                newline(out, depth, lineStart);
                out.append(src, i, stop);
                lineStart = false;
                i = stop;
            } else if (src.startsWith("<?", i)) {
                int end = src.indexOf("?>", i + 2);
                int stop = (end < 0) ? len : end + 2;
                newline(out, depth, lineStart);
                out.append(src, i, stop);
                lineStart = false;
                i = stop;
            } else if (src.startsWith("<!", i)) { // DOCTYPE 等
                int end = src.indexOf('>', i + 2);
                int stop = (end < 0) ? len : end + 1;
                newline(out, depth, lineStart);
                out.append(src, i, stop);
                lineStart = false;
                i = stop;
            } else if (i + 1 < len && src.charAt(i + 1) == '/') { // 结束标签
                int end = src.indexOf('>', i + 2);
                int stop = (end < 0) ? len : end + 1;
                if (depth > 0) depth--;
                newline(out, depth, lineStart);
                out.append(src, i, stop);
                lineStart = false;
                i = stop;
            } else {
                int end = src.indexOf('>', i + 1);
                if (end < 0) { // 残缺 '<'：当普通文本
                    if (textStart < 0) textStart = i;
                    i++;
                    continue;
                }
                String tag = src.substring(i, end + 1);
                boolean selfClose = tag.endsWith("/>");
                String name = tagName(tag);
                boolean inline = html && !HTML_BLOCK_TAGS.contains("|" + name + "|");
                newline(out, depth, lineStart);
                out.append(tag);
                lineStart = false;
                if (!selfClose && !inline && !isVoidTag(name)) depth++;
                i = end + 1;
                // 单一短文本 + 紧接结束标签 → 同行（如 <string name="x">值</string>）
                if (i < len && src.charAt(i) != '<') {
                    int j = i;
                    while (j < len && src.charAt(j) != '<') j++;
                    String text = src.substring(i, j);
                    String trimmed = text.trim();
                    if (trimmed.length() > 0 && j < len && src.startsWith("</", j)) {
                        int closeEnd = src.indexOf('>', j);
                        if (closeEnd > 0) {
                            if (depth > 0) depth--; // 消费的结束标签同样退一层缩进
                            out.append(trimmed);
                            out.append(src, j, closeEnd + 1);
                            i = closeEnd + 1;
                            lineStart = false;
                        }
                    }
                }
            }
        }
        // 收尾：残余文本
        if (textStart >= 0) {
            String trimmed = src.substring(textStart).trim();
            if (trimmed.length() > 0) {
                newline(out, depth, lineStart);
                out.append(trimmed);
            }
        }
        out.append('\n');
        return out.toString();
    }

    private static void newline(StringBuilder out, int depth, boolean atLineStart) {
        if (!atLineStart) out.append('\n');
        for (int i = 0; i < depth; i++) out.append("  ");
    }

    private static String tagName(String tag) {
        // "<div class=\"x\">" -> "div"；"</div>" -> "div"
        int a = tag.startsWith("</") ? 2 : 1;
        int b = a;
        while (b < tag.length()) {
            char c = tag.charAt(b);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '>' || c == '/') break;
            b++;
        }
        return tag.substring(a, b).toLowerCase();
    }

    private static boolean isVoidTag(String name) {
        return VOID_TAGS.contains("|" + name + "|");
    }

    // ============================================================ 大括号语言美化（java/c/cpp/js）

    /**
     * 规则式代码美化：字符串/字符/注释状态机保护 + 花括号缩进 + 分号换行。
     * 不做完整语法分析（for(;;)、lambda 等按括号深度特判），保证内容零损。
     */
    public static String formatCode(String src) {
        StringBuilder out = new StringBuilder(src.length() + src.length() / 8 + 64);
        final int len = src.length();
        int depth = 0;
        int parenDepth = 0;   // () 深度：其内的 ; 不换行（for 语句）
        int lineLen = 0;      // 当前输出行已累积长度
        boolean blankRun = false; // 连续空行折叠
        boolean pendingNL = false; // 规则刚输出过换行：吃掉源中紧随的换行符，避免多余空行
        int i = 0;

        while (i < len) {
            char c = src.charAt(i);

            // ---- 字符串
            if (c == '"') {
                int j = i + 1;
                while (j < len) {
                    char d = src.charAt(j);
                    if (d == '\\') j += 2;
                    else if (d == '"') { j++; break; }
                    else if (d == '\n') break; // 异常字符串：停在行尾
                    else j++;
                }
                appendToken(out, src.substring(i, Math.min(j, len)));
                lineLen += Math.min(j, len) - i;
                i = j;
                blankRun = false; pendingNL = false;
                continue;
            }
            // ---- 字符字面量
            if (c == '\'') {
                int j = i + 1;
                while (j < len) {
                    char d = src.charAt(j);
                    if (d == '\\') j += 2;
                    else if (d == '\'') { j++; break; }
                    else if (d == '\n') break;
                    else j++;
                }
                appendToken(out, src.substring(i, Math.min(j, len)));
                lineLen += Math.min(j, len) - i;
                i = j;
                blankRun = false; pendingNL = false;
                continue;
            }
            // ---- 行注释
            if (c == '/' && i + 1 < len && src.charAt(i + 1) == '/') {
                int j = src.indexOf('\n', i);
                if (j < 0) j = len;
                String comment = rtrim(src.substring(i, j));
                if (lineLen == 0) {
                    indent(out, depth);
                    lineLen = depth * 4;
                }
                out.append(comment);
                lineLen += comment.length();
                i = j;
                blankRun = false; pendingNL = false;
                continue;
            }
            // ---- 块注释（可跨行，原样保留内部排版）
            if (c == '/' && i + 1 < len && src.charAt(i + 1) == '*') {
                int j = src.indexOf("*/", i + 2);
                j = (j < 0) ? len : j + 2;
                String block = src.substring(i, j);
                if (lineLen == 0) {
                    indent(out, depth);
                    lineLen = depth * 4;
                }
                out.append(block);
                // 多行块注释：续行保持原样
                int nl = block.lastIndexOf('\n');
                if (nl >= 0) lineLen = block.length() - nl - 1;
                else lineLen += block.length();
                i = j;
                blankRun = false; pendingNL = false;
                continue;
            }
            // ---- 空白压缩
            if (c == ' ' || c == '\t') {
                if (lineLen > 0 && !endsWithSpace(out)) {
                    out.append(' ');
                    lineLen++;
                }
                i++;
                continue;
            }
            if (c == '\r') { i++; continue; }
            if (c == '\n') {
                if (pendingNL) { pendingNL = false; i++; continue; } // 规则换行后：吃掉源换行
                if (lineLen == 0) {
                    if (blankRun) { i++; continue; } // 折叠连续空行
                    out.append('\n'); // 真空行（不带缩进空白）
                    lineLen = 0;
                    blankRun = true;
                } else {
                    out.append('\n');
                    lineLen = 0;
                    blankRun = false;
                }
                i++;
                continue;
            }
            // ---- 结构符号
            if (c == '{') {
                if (lineLen == 0) indent(out, depth);
                else if (!endsWithSpace(out)) {
                    char prev = out.charAt(out.length() - 1);
                    // "(" / "[" 后不补空格：数组初始化、匿名类等场景
                    if (prev != '(' && prev != '[') out.append(' ');
                }
                out.append('{');
                depth++;
                out.append('\n');
                lineLen = 0;
                blankRun = false; pendingNL = true;
                i++;
                continue;
            }
            if (c == '}') {
                if (lineLen > 0) { out.append('\n'); lineLen = 0; }
                if (depth > 0) depth--;
                indent(out, depth);
                out.append('}');
                lineLen = depth * 4 + 1;
                blankRun = false; pendingNL = true;
                i++;
                // "} else {" / "} catch (...) {" / "} while (...);" / "} finally {" 续排
                int k = i;
                while (k < len && (src.charAt(k) == ' ' || src.charAt(k) == '\t')) k++;
                String rest = src.substring(k, Math.min(len, k + 16));
                if (rest.startsWith("else") || rest.startsWith("catch") || rest.startsWith("finally")) {
                    // 原样吃掉空白后输出 " else" 形式，交由后续 { 处理
                    out.append(' ');
                    out.append(rest.startsWith("else") ? "else" :
                            rest.startsWith("catch") ? "catch" : "finally");
                    i = k + (rest.startsWith("else") ? 4 : rest.startsWith("catch") ? 5 : 7);
                    lineLen += 1 + (i - k);
                    pendingNL = false; // 续排：同一行继续
                } else if (rest.startsWith("while") && parenDepth == 0) {
                    out.append(' ');
                    out.append("while");
                    i = k + 5;
                    lineLen += 6;
                    pendingNL = false;
                }
                continue;
            }
            if (c == '(') { parenDepth++; appendToken(out, "("); lineLen++; i++; blankRun = false; pendingNL = false; continue; }
            if (c == ')') { if (parenDepth > 0) parenDepth--; appendToken(out, ")"); lineLen++; i++; blankRun = false; pendingNL = false; continue; }
            if (c == ';') {
                appendToken(out, ";");
                lineLen++;
                if (parenDepth == 0) {
                    out.append('\n');
                    lineLen = 0;
                    pendingNL = true;
                }
                i++;
                blankRun = false;
                continue;
            }
            if (c == ',') {
                appendToken(out, ",");
                lineLen++;
                if (parenDepth == 0) {
                    out.append('\n');
                    lineLen = 0;
                    pendingNL = true;
                }
                i++;
                blankRun = false;
                continue;
            }
            // ---- case / default 标签：冒号后换行（标签与体同级缩进，整齐且不伤内容）
            if (c == ':' && lineLen > 0 && isCaseLabelLine(out)) {
                out.append(':');
                out.append('\n');
                lineLen = 0;
                pendingNL = true;
                i++;
                continue;
            }
            // ---- 普通 token：连续非空白非符号字符一并输出
            int j = i;
            while (j < len) {
                char d = src.charAt(j);
                if (d == ' ' || d == '\t' || d == '\n' || d == '\r'
                        || d == '{' || d == '}' || d == '(' || d == ')'
                        || d == ';' || d == ',' || d == ':' || d == '"' || d == '\''
                        || d == '/') break;
                j++;
            }
            if (j == i) j = i + 1; // 兜底：至少吃 1 字符
            String tok = src.substring(i, j);
            if (lineLen == 0) {
                indent(out, depth);
                lineLen = depth * 4;
            }
            out.append(tok);
            lineLen += tok.length();
            i = j;
            blankRun = false; pendingNL = false;
        }
        // 收尾：确保以单个换行结束
        while (out.length() > 0 && (out.charAt(out.length() - 1) == ' '
                || out.charAt(out.length() - 1) == '\t')) out.setLength(out.length() - 1);
        if (out.length() == 0 || out.charAt(out.length() - 1) != '\n') out.append('\n');
        return out.toString();
    }

    private static void appendToken(StringBuilder out, String tok) {
        out.append(tok);
    }

    private static boolean endsWithSpace(StringBuilder sb) {
        return sb.length() > 0 && (sb.charAt(sb.length() - 1) == ' ' || sb.charAt(sb.length() - 1) == '\t');
    }

    /** 当前输出行（最后一个 '\n' 之后）trim 后是否为 case/default 标签行 */
    private static boolean isCaseLabelLine(StringBuilder out) {
        int nl = out.lastIndexOf("\n");
        String line = out.substring(nl + 1).trim();
        return line.startsWith("case ") || line.equals("default") || line.startsWith("default ");
    }

    private static String rtrim(String s) {
        int e = s.length();
        while (e > 0 && (s.charAt(e - 1) == ' ' || s.charAt(e - 1) == '\t' || s.charAt(e - 1) == '\r')) e--;
        return s.substring(0, e);
    }

    private static void indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) out.append("    ");
    }

    // ============================================================ 统一入口

    public static final int FMT_JSON = 1, FMT_JSON_MIN = 2, FMT_XML = 3, FMT_CODE = 4;

    /** 按文件名/内容推断可用格式（返回 0 表示无法识别） */
    public static int detectFormat(String fileName, String src) {
        String low = fileName.toLowerCase();
        if (low.endsWith(".json")) return FMT_JSON;
        if (low.endsWith(".xml") || low.endsWith(".html") || low.endsWith(".htm")
                || low.endsWith(".svg") || low.endsWith(".xsd") || low.endsWith(".axml")
                || low.endsWith(".plist")) return FMT_XML;
        if (low.endsWith(".java") || low.endsWith(".kt") || low.endsWith(".c") || low.endsWith(".h")
                || low.endsWith(".cpp") || low.endsWith(".cc") || low.endsWith(".hpp")
                || low.endsWith(".js") || low.endsWith(".ts") || low.endsWith(".cs")
                || low.endsWith(".go") || low.endsWith(".rs") || low.endsWith(".swift")
                || low.endsWith(".smali")) return FMT_CODE;
        String t = src == null ? "" : src.trim();
        if (t.startsWith("{") || t.startsWith("[")) return FMT_JSON;
        if (t.startsWith("<")) return FMT_XML;
        return 0;
    }

    /** 执行格式化；mode 为 FMT_* 常量 */
    public static String format(String src, int mode) throws FormatException {
        switch (mode) {
            case FMT_JSON: return formatJson(src);
            case FMT_JSON_MIN: return minifyJson(src);
            case FMT_XML: return formatXml(src);
            case FMT_CODE: return formatCode(src);
            default: throw new FormatException("未知格式化类型");
        }
    }
}
