package com.hex91.file;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jadx.core.xmlgen.BinaryXMLStrings;

/**
 * 二进制 AndroidManifest.xml / AXML 解析器（v1.11）。
 *
 * 与 ARSC 写回层同一策略：解析时记录每个可编辑字段的【绝对偏移】（span），
 * 编辑 = 字符串池追加 + 4 字节补丁，其余字节原样保留。
 *
 * AXML token 流（AOSP ResourceTypes.h）：
 *   文件头 0x0003 (RES_XML_TYPE)：type u16 / headerSize u16 / size u32
 *   chunk  0x0001 字符串池（与 ARSC 全局池同构）
 *   chunk  0x0180 资源映射表（属性资源 id 数组，可选）
 *   token  0x0100 START_NAMESPACE / 0x0101 END_NAMESPACE（16 字节头：line+comment+prefix+uri）
 *   token  0x0102 START_ELEMENT（16 字节头 + ns/name + 属性数组，每属性 20 字节）
 *   token  0x0103 END_ELEMENT
 *
 * 属性编码（20 字节）：ns(4) name(4) rawValue(4) | typed: size u16=8, res0 u8, dataType u8, data(4)
 * 字符串属性：rawValue = 字符串池索引（-1 表示无），data 也是字符串池索引；
 * 非字符串属性：rawValue = -1，data 为 typed 值（int/bool/ref…）。
 */
public final class AxmlFile {

	// token types
	public static final int RES_XML_STRING_POOL = 0x0001;
	public static final int RES_XML_RESOURCE_MAP = 0x0180;
	public static final int START_NAMESPACE = 0x0100;
	public static final int END_NAMESPACE = 0x0101;
	public static final int START_ELEMENT = 0x0102;
	public static final int END_ELEMENT = 0x0103;

	public byte[] raw;
	public boolean poolUtf8;
	public int poolStart, poolEnd;
	public int fileHeaderSize = 8;
	public int resMapStart = -1, resMapEnd = -1;

	/** 解析出的字符串池（index → 值） */
	public final List<String> strings = new ArrayList<String>();

	/** 元素树（START_ELEMENT 顺序；嵌套关系在 buildTree 时记录） */
	public final List<XElem> elements = new ArrayList<XElem>();

	/** ---------- 元素 ---------- */
	public static final class XElem {
		public String ns;              // 命名空间 uri（可为 null）
		public String name;            // 元素名
		public int nameOff = -1;       // name 字段绝对偏移（可补丁处）
		public int nameIdx;            // 当前 name 的池索引
		public int tokenStart, tokenEnd;
		public List<XAttr> attrs = new ArrayList<XAttr>();
		public int depth;              // 树深度（显示缩进/路径用）
		public String path;            // 如 "manifest/application/activity[1]"
	}

	/** ---------- 属性（含可编辑 span） ---------- */
	public static final class XAttr {
		public String ns;              // 属性 ns（"http://schemas.android.com/apk/res/android" → 显示 android:）
		public String name;
		public int nameOff;            // name 字段绝对偏移
		public int rawValueOff;        // rawValue 字段绝对偏移（字符串属性时有效）
		public int dataOff;            // typed data 字段绝对偏移
		public int dataType;           // ArscDefs.TYPE_*
		public int dataValue;          // 当前 data 原始值（int）
		public int rawValueIdx;        // rawValue 池索引（-1 = 无字符串）
		public XElem owner;

		public boolean isString() { return dataType == ArscDefs.TYPE_STRING; }
	}

	// ------------------------------------------------------------ parse

	public static AxmlFile parse(byte[] data) throws IOException {
		AxmlFile f = new AxmlFile();
		f.raw = data;
		if (data.length < 8) throw new IOException("文件过小");
		int fileType = ArscDefs.u16(data, 0);
		if (fileType != 0x0003) throw new IOException("不是 AXML 文件（type=0x"
				+ Integer.toHexString(fileType) + "）");
		f.fileHeaderSize = ArscDefs.u16(data, 2);
		int fileSize = ArscDefs.u32(data, 4);
		if (fileSize > data.length) throw new IOException("AXML 头部声明的长度超出文件");

		int pos = f.fileHeaderSize;
		int len = Math.min(fileSize, data.length);
		List<Integer> elemStack = new ArrayList<Integer>(); // 元素栈（START/END 配对求深度）
		while (pos + 8 <= len) {
			int type = ArscDefs.u16(data, pos);
			int size = ArscDefs.u32(data, pos + 4);
			if (size < 8 || pos + size > len) throw new IOException(
					"chunk 越界（type=0x" + Integer.toHexString(type) + " at " + pos + "）");
			switch (type) {
				case RES_XML_STRING_POOL:
					f.poolStart = pos;
					f.poolEnd = pos + size;
					f.poolUtf8 = (ArscDefs.u32(data, pos + 16) & ArscDefs.UTF8_FLAG) != 0;
					f.parsePool(pos, pos + size);
					break;
				case RES_XML_RESOURCE_MAP:
					f.resMapStart = pos;
					f.resMapEnd = pos + size;
					break;
				case START_ELEMENT:
					f.parseStartElement(pos, size, elemStack.size());
					elemStack.add(f.elements.size() - 1);
					break;
				case END_ELEMENT:
					if (!elemStack.isEmpty()) elemStack.remove(elemStack.size() - 1);
					break;
				default:
					break; // namespace / cdata：不关心
			}
			pos += size;
		}
		f.buildPaths();
		return f;
	}

	private void parsePool(int start, int end) throws IOException {
		int count = ArscDefs.u32(raw, start + 8);
		int stringsStartField = ArscDefs.u32(raw, start + 20);
		if (count < 0 || count > 100000) throw new IOException("字符串池数量异常: " + count);
		strings.clear();
		// BinaryXMLStrings 语义：buffer 从偏移数组起始（chunk+28）切片，
		// get(id) = buffer.getInt(id*4) + stringsStart → 传 stringsStartField-28
		byte[] chunk = java.util.Arrays.copyOfRange(raw, start + 28, end);
		BinaryXMLStrings pool = new BinaryXMLStrings(count, stringsStartField - 28,
				chunk, poolUtf8);
		for (int i = 0; i < count; i++) strings.add(pool.get(i));
	}

	private void parseStartElement(int pos, int size, int depth) {
		XElem e = new XElem();
		e.tokenStart = pos;
		e.tokenEnd = pos + size;
		e.depth = depth;
		e.ns = str(ArscDefs.u32(raw, pos + 16));
		e.nameIdx = ArscDefs.u32(raw, pos + 20);
		e.name = str(e.nameIdx);
		e.nameOff = pos + 20;
		// 结构：node 头 16（chunk 8 + line 4 + comment 4）后是 attrExt：
		// ns(4)@+0 name(4)@+4 attributeStart(2)@+8 attributeSize(2)@+10 attributeCount(2)@+12 …
		// attributeStart 相对 attrExt（pos+16），典型 20 → 属性数组从 pos+36 开始
		int attrExt = pos + 16;
		int attrStart = attrExt + ArscDefs.u16(raw, attrExt + 8);
		int attrSize = ArscDefs.u16(raw, attrExt + 10);   // 每属性字节数（20）
		int attrCount = ArscDefs.u16(raw, attrExt + 12);
		for (int i = 0; i < attrCount && attrStart + attrSize <= e.tokenEnd; i++) {
			int a = attrStart + i * attrSize;
			XAttr at = new XAttr();
			at.owner = e;
			at.ns = str(ArscDefs.u32(raw, a));
			at.name = str(ArscDefs.u32(raw, a + 4));
			at.nameOff = a + 4;
			int rawVal = ArscDefs.u32(raw, a + 8);
			at.rawValueIdx = rawVal == 0xFFFFFFFF ? -1 : (int) rawVal;
			at.rawValueOff = a + 8;
			at.dataType = raw[a + 15] & 0xFF;
			at.dataValue = ArscDefs.u32(raw, a + 16);
			at.dataOff = a + 16;
			e.attrs.add(at);
		}
		elements.add(e);
	}

	private String str(int idx) {
		if (idx == 0xFFFFFFFF || idx < 0 || idx >= strings.size()) return null;
		return strings.get(idx);
	}

	/** 计算元素路径（父路径 + 同名序号，用于展示分组） */
	private void buildPaths() {
		Map<String, Integer> counter = new java.util.HashMap<String, Integer>();
		for (XElem e : elements) {
			String base = e.name == null ? "?" : e.name;
			String key = (e.depth <= 0 ? "" : parentPath(elements, e)) + base;
			Integer c = counter.get(key);
			c = c == null ? 1 : c + 1;
			counter.put(key, c);
			e.path = c > 1 ? key + "[" + c + "]" : key;
		}
	}

	private static String parentPath(List<XElem> all, XElem e) {
		// 找深度为 e.depth-1 且在 e 之前最近的元素
		for (int i = all.indexOf(e) - 1; i >= 0; i--) {
			XElem p = all.get(i);
			if (p.depth == e.depth - 1) return p.path + "/";
		}
		return "";
	}

	// ------------------------------------------------------------ display

	/** 属性值显示串（与编辑器输入互逆的规范形式） */
	public String valueDisplay(XAttr a) {
		if (a.isString()) {
			if (a.rawValueIdx >= 0) return poolStr(a.rawValueIdx);
			return poolStr(a.dataValue);
		}
		switch (a.dataType) {
			case ArscDefs.TYPE_REFERENCE:
			case ArscDefs.TYPE_ATTRIBUTE:
				return "@0x" + hex(a.dataValue);
			case ArscDefs.TYPE_INT_DEC:
				return String.valueOf(a.dataValue);
			case ArscDefs.TYPE_INT_HEX:
				return "0x" + hex(a.dataValue);
			case ArscDefs.TYPE_INT_BOOLEAN:
				return a.dataValue != 0 ? "true" : "false";
			case ArscDefs.TYPE_FLOAT:
				return String.valueOf(Float.intBitsToFloat(a.dataValue));
			default:
				return "0x" + hex(a.dataValue);
		}
	}

	private static String hex(int v) {
		return Integer.toHexString(v);
	}

	public String poolStr(int idx) {
		if (idx < 0 || idx >= strings.size()) return "";
		String s = strings.get(idx);
		return s == null ? "" : s;
	}

	public String typeName(XAttr a) {
		switch (a.dataType) {
			case ArscDefs.TYPE_REFERENCE: return "ref";
			case ArscDefs.TYPE_ATTRIBUTE: return "attr";
			case ArscDefs.TYPE_STRING: return "string";
			case ArscDefs.TYPE_FLOAT: return "float";
			case ArscDefs.TYPE_INT_DEC: return "int";
			case ArscDefs.TYPE_INT_HEX: return "hex";
			case ArscDefs.TYPE_INT_BOOLEAN: return "bool";
			case ArscDefs.TYPE_INT_COLOR_ARGB8:
			case ArscDefs.TYPE_INT_COLOR_RGB8:
			case ArscDefs.TYPE_INT_COLOR_ARGB4:
			case ArscDefs.TYPE_INT_COLOR_RGB4: return "color";
			default: return "raw";
		}
	}

	/** 属性显示名：android:versionCode 形式 */
	public String attrDisplayName(XAttr a) {
		String n = a.name == null ? "?" : a.name;
		if (a.ns != null && a.ns.endsWith("/res/android")) return "android:" + n;
		if (a.ns != null) return n;
		return n;
	}

	// ------------------------------------------------------------ pretty XML（预览/测试校验用）

	/** 生成缩进的 XML 文本（不含 xml 声明；属性逐行缩进，便于预览与测试校验） */
	public String prettyXml() {
		StringBuilder sb = new StringBuilder();
		for (XElem e : elements) renderElement(sb, e);
		return sb.toString();
	}

	private void renderElement(StringBuilder sb, XElem e) {
		for (int i = 0; i < e.depth; i++) sb.append("    ");
		sb.append('<').append(e.name == null ? "?" : e.name);
		for (XAttr a : e.attrs) {
			sb.append('\n');
			for (int i = 0; i < e.depth + 1; i++) sb.append("    ");
			sb.append(attrDisplayName(a)).append("=\"")
					.append(xmlEscape(valueDisplay(a))).append('"');
		}
		sb.append(" />\n");
	}

	private static String xmlEscape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;");
	}
}
