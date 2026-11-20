package com.hex91.file.apk;

import com.hex91.file.core.ArscDefs;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manifest 文本往返（v1.24）：把编辑后的 pretty XML 文本 diff 回属性级补丁。
 *
 * prettyXml() 的输出是受控格式：每个元素自闭合 <name ... />，属性逐行缩进 4 空格，
 * 无文本节点、无 xmlns 声明——因此配对解析器只需处理这一种形状。
 *
 * 支持的编辑：修改既有属性的值（字符串/整数/hex/布尔/引用/浮点/颜色）。
 * 不支持（报错提示）：新增/删除属性、增删/改名元素——请改用 Manifest 编辑器++ 或属性编辑。
 */
public final class AxmlTextSync {

	private AxmlTextSync() {
	}

	/** 同步失败码：文案由 UI 层决定（v2.88.0 F-14/75） */
	public static final int E_COUNT = 1;
	public static final int E_RENAME = 2;
	public static final int E_DEL_ATTR = 3;
	public static final int E_BAD_VALUE = 4;
	public static final int E_ADD_ATTR = 5;
	public static final int E_LINE_NO_ELEM = 6;
	public static final int E_LINE_UNCLOSED = 7;
	public static final int E_TAIL_UNCLOSED = 8;
	public static final int E_LINE_NO_EQ = 9;
	public static final int E_LINE_NO_QUOTE = 10;
	public static final int E_LINE_NEWLINE = 11;

	/** 同步/解析失败：{@link #code} 决定文案，{@link #dataType} 供 UI 换成本地化的类型名 */
	public static class SyncException extends IllegalArgumentException {
		public final int code;
		public final int dataType;
		public final String[] args;

		public SyncException(int code, String... args) {
			this(code, 0, args);
		}

		public SyncException(int code, int dataType, String... args) {
			super("AxmlTextSync error " + code);
			this.code = code;
			this.dataType = dataType;
			this.args = args == null ? new String[0] : args;
		}
	}

	/** pretty 文本的一行解析结果（测试可见） */
	public static final class ParsedElem {
		public String name;
		public int depth;
		public boolean closed;
		public final List<String[]> attrs = new ArrayList<String[]>(); // [displayName, value]
	}

	/** 编辑文本 → 与原 AXML diff → 属性级补丁 → 新 AXML 字节 */
	public static byte[] sync(AxmlFile axml, String editedPretty) throws IOException {
		List<ParsedElem> parsed = parse(editedPretty);
		List<AxmlFile.XElem> orig = axml.elements;
		if (parsed.size() != orig.size())
			throw new SyncException(E_COUNT,
					String.valueOf(orig.size()), String.valueOf(parsed.size()));

		List<AxmlWriter.Edit> edits = new ArrayList<AxmlWriter.Edit>();
		for (int i = 0; i < orig.size(); i++) {
			AxmlFile.XElem oe = orig.get(i);
			ParsedElem pe = parsed.get(i);
			// v1.65.0：元素名按 pretty 转义形式比较（加固包元素名可能含 <> 等）
			if (!AxmlFile.escLine(oe.name).equals(pe.name))
				throw new SyncException(E_RENAME, oe.name, pe.name);

			// v1.65.0：贪心配对——加固包 manifest 存在重名垃圾属性，
			// 按「名字相同优先、显示值也相同者优先」一一配对，避免错配成假编辑
			boolean[] used = new boolean[pe.attrs.size()];
			for (AxmlFile.XAttr oa : oe.attrs) {
				// trim：parseAttrs 对属性行做 trim，垃圾属性名首尾空格需一致化
				String key = AxmlFile.escLine(axml.attrDisplayName(oa)).trim();
				String oldV = axml.valueDisplay(oa);
				if (oldV == null) oldV = "";
				int pick = -1, pickSame = -1;
				for (int j = 0; j < pe.attrs.size(); j++) {
					if (used[j] || !key.equals(pe.attrs.get(j)[0])) continue;
					if (pick < 0) pick = j;
					if (pickSame < 0 && oldV.equals(pe.attrs.get(j)[1])) { pickSame = j; break; }
				}
				int j = pickSame >= 0 ? pickSame : pick;
				if (j < 0)
					throw new SyncException(E_DEL_ATTR, axml.attrDisplayName(oa), oe.path);
				used[j] = true;
				String paV = pe.attrs.get(j)[1];
				if (!oldV.equals(paV)) {
					if (oa.dataType != ArscDefs.TYPE_STRING
							&& AxmlWriter.parseValue(axml, oa, paV) == null)
						throw new SyncException(E_BAD_VALUE, oa.dataType,
								axml.attrDisplayName(oa), oe.path, paV);
					edits.add(new AxmlWriter.Edit(oa, paV));
				}
			}
			// 未被消费的解析属性行 = 用户新增
			for (int j = 0; j < pe.attrs.size(); j++) {
				if (!used[j])
					throw new SyncException(E_ADD_ATTR, pe.attrs.get(j)[0], oe.path);
			}
		}
		return AxmlWriter.write(axml, edits);
	}

	// ------------------------------------------------------------ 受控 pretty 格式解析

	/** 解析 prettyXml 形状的文本：元素行 "<name" 或 "<name />"，属性行 'name="v"'，"/>" 结束 */
	public static List<ParsedElem> parse(String text) {
		List<ParsedElem> out = new ArrayList<ParsedElem>();
		String[] lines = text.replace("\r\n", "\n").split("\n", -1);
		ParsedElem cur = null;
		for (int li = 0; li < lines.length; li++) {
			String line = lines[li];
			if (line.trim().isEmpty()) continue;
			int indent = line.indexOf('<');
			boolean isElemLine = indent >= 0
					&& line.substring(0, indent).trim().isEmpty();
			if (!isElemLine) {
				// 属性行：'ns:name="value"' 或最后一条 'ns:name="value" />'
				if (cur == null)
					throw new SyncException(E_LINE_NO_ELEM, String.valueOf(li + 1));
				String t = line.trim();
				boolean last = t.endsWith("/>");
				String body = last ? t.substring(0, t.length() - 2).trim() : t;
				parseAttrs(body, cur, li + 1);
				if (last) cur.closed = true;
				continue;
			}
			int depth = indent / 4;
			String t = line.substring(indent).trim();
			if (cur != null && !cur.closed)
				throw new SyncException(E_LINE_UNCLOSED, String.valueOf(li + 1));
			ParsedElem e = new ParsedElem();
			e.depth = depth;
			if (t.endsWith("/>")) {
				// 单行元素 <name /> 或 <name a="v" />
				String inner = t.substring(1, t.length() - 2).trim();
				int sp = inner.indexOf(' ');
				if (sp < 0) e.name = inner;
				else {
					e.name = inner.substring(0, sp);
					parseAttrs(inner.substring(sp + 1), e, li + 1);
				}
				e.closed = true;
			} else {
				int sp = t.indexOf(' ');
				e.name = sp < 0 ? t.substring(1) : t.substring(1, sp);
			}
			out.add(e);
			cur = e.closed ? null : e;
		}
		if (cur != null && !cur.closed)
			throw new SyncException(E_TAIL_UNCLOSED);
		return out;
	}

	private static void parseAttrs(String s, ParsedElem e, int lineNo) {
		int i = 0, n = s.length();
		while (i < n) {
			while (i < n && Character.isWhitespace(s.charAt(i))) i++;
			if (i >= n) break;
			int eq = s.indexOf('=', i);
			if (eq < 0) throw new SyncException(E_LINE_NO_EQ, String.valueOf(lineNo), s);
			String name = s.substring(i, eq).trim();
			int q1 = s.indexOf('"', eq);
			int q2 = findCloseQuote(s, q1 + 1);
			if (q1 < 0 || q2 < 0)
				throw new SyncException(E_LINE_NO_QUOTE, String.valueOf(lineNo), name);
			String v = unescape(s.substring(q1 + 1, q2));
			if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0)
				throw new SyncException(E_LINE_NEWLINE, String.valueOf(lineNo));
			e.attrs.add(new String[] { name, v });
			i = q2 + 1;
		}
	}

	private static int findCloseQuote(String s, int from) {
		for (int i = from; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\') { i++; continue; }   // 转义跳过（罕见）
			if (c == '"') return i;
		}
		return -1;
	}

	private static String unescape(String s) {
		return s.replace("&#10;", "\n").replace("&#13;", "\r")
				.replace("&quot;", "\"").replace("&lt;", "<")
				.replace("&gt;", ">").replace("&amp;", "&");
	}


}
