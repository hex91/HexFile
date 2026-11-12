package com.hex91.file;

import com.hex91.file.core.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AXML 写回器（v1.11，与 ArscWriter 同策略）：
 *
 *   1. 字符串值编辑 → 新串【追加】进字符串池（去重），条目内【补丁】rawValue + typed data 两个字段；
 *   2. 非字符串值编辑（int/hex/bool/ref/float/color）→ 只补丁 typed data 4 字节；
 *   3. 池有追加时重建字符串池 chunk（与 ArscWriter.poolBytes 同逻辑），其余 chunk/token 逐字节复制；
 *   4. 修文件头 size（offset 4）与字符串池 chunk size。
 *
 * AXML 池中无偏移指向（只用索引），所以池增长不影响任何其他字段——
 * 比 ARSC 的全量重布局简单得多。
 */
public final class AxmlWriter {

	private AxmlWriter() {
	}

	/** 一条编辑：attr 目标 + 新值（显示串，由 parseValue 按类型解析） */
	public static final class Edit {
		public final AxmlFile.XAttr attr;
		public final String newValue;

		public Edit(AxmlFile.XAttr attr, String newValue) {
			this.attr = attr;
			this.newValue = newValue;
		}
	}

	public static byte[] write(AxmlFile f, List<Edit> edits) throws IOException {
		return new W(f, edits).run();
	}

	// ------------------------------------------------------------ 值解析（显示串 → 内部值）

	/** 按属性类型解析输入串；非法输入返回 null */
	public static Long parseValue(AxmlFile f, AxmlFile.XAttr a, String s) {
		s = s.trim();
		try {
			switch (a.dataType) {
				case ArscDefs.TYPE_INT_DEC:
					return Long.parseLong(s) & 0xFFFFFFFFL;
				case ArscDefs.TYPE_INT_HEX:
					return parseHex(s);
				case ArscDefs.TYPE_INT_BOOLEAN:
					if ("true".equalsIgnoreCase(s)) return 0xFFFFFFFFL; // -1 = true
					if ("false".equalsIgnoreCase(s)) return 0L;
					return null;
				case ArscDefs.TYPE_REFERENCE:
				case ArscDefs.TYPE_ATTRIBUTE: {
					String h = s.startsWith("@") ? s.substring(1) : s;
					return parseHex(h);
				}
				case ArscDefs.TYPE_FLOAT:
					return (long) Float.floatToIntBits(Float.parseFloat(s)) & 0xFFFFFFFFL;
				case ArscDefs.TYPE_INT_COLOR_ARGB8:
				case ArscDefs.TYPE_INT_COLOR_RGB8:
				case ArscDefs.TYPE_INT_COLOR_ARGB4:
				case ArscDefs.TYPE_INT_COLOR_RGB4:
					return parseHex(s.startsWith("#") ? s.substring(1) : s);
				default:
					return null;
			}
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Long parseHex(String s) {
		if (s == null || s.isEmpty()) return null;
		String h = s.toLowerCase();
		if (h.startsWith("0x")) h = h.substring(2);
		return Long.parseLong(h, 16) & 0xFFFFFFFFL;
	}

	// ------------------------------------------------------------ 写回实现

	private static final class W {
		final AxmlFile f;
		final byte[] raw;
		final List<Edit> edits;
		final List<String> appends = new ArrayList<String>();
		final Map<Edit, Integer> resolved = new HashMap<Edit, Integer>();

		W(AxmlFile f, List<Edit> edits) {
			this.f = f;
			this.raw = f.raw;
			this.edits = edits == null ? new ArrayList<Edit>() : edits;
		}

		byte[] run() throws IOException {
			resolveAppends();

			ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + 256);
			// 1. 文件头（8 字节，size 回填）
			byte[] hdr = new byte[f.fileHeaderSize];
			System.arraycopy(raw, 0, hdr, 0, f.fileHeaderSize);
			out.write(hdr);
			// 2. 字符串池（可能重建变大）
			out.write(poolBytes());
			// 3. 其后所有 chunk（资源映射表 + token 流）逐字节复制，START_ELEMENT 补丁；
			//    token 内的字符串引用全是【池索引】，池增大不影响它们
			int pos = f.poolEnd;
			while (pos + 8 <= raw.length) {
				int type = ArscDefs.u16(raw, pos);
				int size = ArscDefs.u32(raw, pos + 4);
				if (size < 8 || pos + size > raw.length) break;
				if (type == AxmlFile.START_ELEMENT) {
					out.write(patchedElement(pos, size));
				} else {
					out.write(raw, pos, size);
				}
				pos += size;
			}

			byte[] result = out.toByteArray();
			ArscDefs.putU32(result, 4, result.length);
			return result;
		}

		/** 字符串编辑的目标串若已在池中则复用索引，否则登记追加 */
		private void resolveAppends() {
			for (Edit e : edits) {
				if (!e.attr.isString()) continue;
				String v = e.newValue;
				int idx = indexOf(f.strings, v);
				if (idx < 0) {
					int rel = indexOf(appends, v);
					if (rel < 0) { appends.add(v); rel = appends.size() - 1; }
					idx = f.strings.size() + rel;   // appends 相对索引 → 绝对池索引
				}
				resolved.put(e, idx);
			}
		}

		private static int indexOf(List<String> list, String s) {
			for (int i = 0; i < list.size(); i++) {
				if (s.equals(list.get(i))) return i;
			}
			return -1;
		}

		/** 复制一个 START_ELEMENT token，应用其中属性补丁 */
		private byte[] patchedElement(int pos, int size) {
			byte[] chunk = new byte[size];
			System.arraycopy(raw, pos, chunk, 0, size);
			for (Edit e : edits) {
				AxmlFile.XAttr a = e.attr;
				int aOff = a.dataOff - pos;      // 属性字段在 chunk 内的相对位置
				int rOff = a.rawValueOff - pos;
				if (aOff < 0 || aOff + 4 > size) continue;
				if (a.owner.tokenStart != pos) continue; // 只补丁本 token 的属性
				if (a.isString()) {
					int idx = resolved.get(e);
					if (rOff >= 0 && rOff + 4 <= size && a.rawValueIdx >= 0) {
						ArscDefs.putU32(chunk, rOff, idx);       // rawValue 字段
					}
					ArscDefs.putU32(chunk, aOff, idx);           // typed data（string 语义下也是池索引）
				} else {
					Long v = parseValue(f, a, e.newValue);
					if (v != null) ArscDefs.putU32(chunk, aOff, (int) (v & 0xFFFFFFFFL));
				}
			}
			return chunk;
		}

		/** 重建字符串池 chunk（追加串；无追加时原样复制）——逻辑同 ArscWriter.poolBytes */
		private byte[] poolBytes() throws IOException {
			int start = f.poolStart;
			int end = f.poolEnd;
			int len = end - start;
			if (appends.isEmpty()) {
				byte[] copy = new byte[len];
				System.arraycopy(raw, start, copy, 0, len);
				return copy;
			}
			boolean utf8 = f.poolUtf8;
			int stringCount = ArscDefs.u32(raw, start + 8);
			int styleCount = ArscDefs.u32(raw, start + 12);
			int flags = ArscDefs.u32(raw, start + 16);
			int stringsStartField = ArscDefs.u32(raw, start + 20);
			int stylesStartField = ArscDefs.u32(raw, start + 24);

			ByteArrayOutputStream data = new ByteArrayOutputStream();
			int[] offs = new int[stringCount + appends.size()];
			int emptySlot = -1; // 垃圾偏移共享空串（与 ArscWriter.poolBytes 同语义）
			for (int i = 0; i < stringCount; i++) {
				int relOff = ArscDefs.u32(raw, start + 28 + i * 4);
				int sStart = start + stringsStartField + relOff;
				// v1.65.0 关键修复：按长度前缀定界（与 BinaryXMLStrings 同语义），
				// 不再用「下一串偏移」当结尾——畸形/膨胀池（加固包）偏移乱序会写出数 GB 导致 OOM
				int sEnd = strEnd(sStart, utf8, end);
				if (sStart < start + stringsStartField || sEnd > end) sEnd = sStart;
				if (sEnd <= sStart) {
					// 无效/空串：不能让 offs[i] 指向 data.size()——那会把【下一串】
					// 的内容当成这一串读出来；指向共享空串占位
					if (emptySlot < 0) {
						emptySlot = data.size();
						data.write(utf8 ? new byte[] { 0, 0, 0 } : new byte[] { 0, 0, 0, 0 }, 0,
								utf8 ? 3 : 4);
					}
					offs[i] = emptySlot;
					continue;
				}
				offs[i] = data.size();
				data.write(raw, sStart, sEnd - sStart);
			}
			byte[] styleData = new byte[0];
			if (styleCount > 0 && stylesStartField > 0 && start + stylesStartField < end) {
				styleData = new byte[end - start - stylesStartField];
				System.arraycopy(raw, start + stylesStartField, styleData, 0, styleData.length);
			}
			for (int i = 0; i < appends.size(); i++) {
				offs[stringCount + i] = data.size();
				data.write(utf8 ? encodeUtf8(appends.get(i)) : encodeUtf16(appends.get(i)));
			}
			// v1.65.0 关键修复：字符串数据补零对齐到 4 字节——二进制 XML 所有 chunk
			// 必须 4 字节对齐，否则 PackageManager/aapt2 报
			// "XML size ... is not on an integer boundary" → APK 无法解析（安装失败）
			// 写法与 ArscWriter.poolBytes 的 pad 变量等价：此处先补 data，
			// 下方 dataLen 读取时已含补零，newStylesStart/total 因此天然对齐
			while ((data.size() & 3) != 0) data.write(0);

			int newCount = stringCount + appends.size();
			int newStringsStart = 28 + (newCount + styleCount) * 4;
			int dataLen = data.size();
			int newStylesStart = styleCount > 0 ? newStringsStart + dataLen : 0;
			int total = newStringsStart + dataLen + styleData.length;
			int newFlags = flags & ~ArscDefs.SORTED_FLAG; // 追加可能破坏排序不变量

			byte[] pool = new byte[total];
			ArscDefs.putU16(pool, 0, ArscDefs.RES_STRING_POOL_TYPE);
			ArscDefs.putU16(pool, 2, 28);
			ArscDefs.putU32(pool, 4, total);
			ArscDefs.putU32(pool, 8, newCount);
			ArscDefs.putU32(pool, 12, styleCount);
			ArscDefs.putU32(pool, 16, newFlags);
			ArscDefs.putU32(pool, 20, newStringsStart);
			ArscDefs.putU32(pool, 24, newStylesStart);
			for (int i = 0; i < newCount; i++) ArscDefs.putU32(pool, 28 + i * 4, offs[i]);
			if (styleCount > 0) {
				System.arraycopy(raw, start + 28 + stringCount * 4, pool,
						28 + newCount * 4, styleCount * 4);
			}
			System.arraycopy(data.toByteArray(), 0, pool, newStringsStart, dataLen);
			if (styleData.length > 0) {
				System.arraycopy(styleData, 0, pool, newStylesStart, styleData.length);
			}
			return pool;
		}

		/** v1.65.0：按长度前缀 + 终止符定界一个池串（[absStart, end) 的结尾偏移，含终止符） */
		private int strEnd(int sStart, boolean utf8, int limit) {
			if (sStart < 0 || sStart >= limit) return sStart;
			int i = sStart;
			if (utf8) {
				// u8/u16 变长 charLen + u8/u16 变长 byteLen，随后 byteLen 字节 + \0
				int c1 = raw[i] & 0xFF;
				i += (c1 & 0x80) != 0 ? 2 : 1;
				if (i >= limit) return sStart;
				int b1 = raw[i] & 0xFF;
				i += (b1 & 0x80) != 0 ? 2 : 1;
				if (i >= limit) return sStart;
				int byteLen = (b1 & 0x80) != 0
						? (((b1 & 0x7F) << 8) | (raw[i - 1] & 0xFF)) : b1;
				int e = i + byteLen;
				if (e >= limit || raw[Math.min(e, limit - 1)] != 0)
					return scanTerminator(sStart, true, limit);   // 前缀异常 → 回退扫描
				return e + 1;
			}
			// UTF-16：u16 len（高位标记则再一个 u16 拼 32 位），len 个字符 + \0\0
			int len = ArscDefs.u16(raw, i);
			i += 2;
			if ((len & 0x8000) != 0) {
				if (i + 2 > limit) return scanTerminator(sStart, false, limit);
				len = ((len & 0x7FFF) << 16) | ArscDefs.u16(raw, i);
				i += 2;
			}
			long e = (long) i + (long) len * 2 + 2;
			if (e >= limit) return scanTerminator(sStart, false, limit);
			return (int) e;
		}

		private int scanTerminator(int from, boolean utf8, int limit) {
			int i = from;
			int end = Math.min(limit, raw.length);
			if (utf8) {
				while (i < end && raw[i] != 0) i++;
				return Math.min(i + 1, end);
			}
			while (i + 1 < end && (raw[i] != 0 || raw[i + 1] != 0)) i += 2;
			return Math.min(i + 2, end);
		}

		private byte[] encodeUtf16(String s) {
			// UTF-16 池串只有【一个】u16 长度字段（与 UTF-8 池的双长度不同！）
			// 长串约定：u16 高位 1 开头的标记 + u16 低段（skipStrLen16 按高位跳 2/4 字节）
			ByteArrayOutputStream bo = new ByteArrayOutputStream(s.length() * 2 + 8);
			int n = s.length();
			if (n < 0x8000) {
				bo.write(n & 0xFF);
				bo.write((n >>> 8) & 0xFF);
			} else {
				// v1.65.0 修正长串标记：首 u16 = 0x8000 | (n>>16)，次 u16 = n & 0xFFFF
				int hi = 0x8000 | ((n >>> 16) & 0x7FFF);
				bo.write(hi & 0xFF);
				bo.write((hi >>> 8) & 0xFF);
				bo.write(n & 0xFF);
				bo.write((n >>> 8) & 0xFF);
			}
			for (int i = 0; i < n; i++) {
				char c = s.charAt(i);
				bo.write(c & 0xFF);
				bo.write((c >>> 8) & 0xFF);
			}
			bo.write(0);
			bo.write(0);
			return bo.toByteArray();
		}

		/** AXML 池通常 UTF-16；UTF-8 池串编码为 [charLen][byteLen][bytes][\0]（双长度字段） */
		private byte[] encodeUtf8(String s) {
			byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			int n = s.length();
			ByteArrayOutputStream bo = new ByteArrayOutputStream(b.length + 10);
			// v1.66.3 修复：长度 >127 时须写双字节变长（0x80|高8位 + 低8位），
			// 此前只写低 7 位导致长字符串（如长权限名）池损坏、APK 无法解析
			writeLen8(bo, n);          // 字符数
			writeLen8(bo, b.length);   // 字节数
			bo.write(b, 0, b.length);
			bo.write(0);
			return bo.toByteArray();
		}

		/** 变长长度编码（与 ArscWriter.writeLen8 及 strEnd 解码同语义）：≤0x7F 单字节，否则双字节 */
		private static void writeLen8(ByteArrayOutputStream bo, int len) {
			if (len > 0x7F) bo.write(0x80 | ((len >> 8) & 0x7F));
			bo.write(len & 0xFF);
		}
	}
}
