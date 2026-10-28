package com.hex91.file;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import jadx.core.xmlgen.BinaryXMLStrings;
import jadx.core.xmlgen.ResTableBinaryParser.ChunkSpan;
import jadx.core.xmlgen.ResTableBinaryParser.EntrySpan;
import jadx.core.xmlgen.ResTableBinaryParser.PkgSpan;
import jadx.core.xmlgen.ResTableBinaryParser.TypeChunk;
import jadx.core.xmlgen.entry.RawValue;
import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * Serializes an edited {@link ArscFile} back to resources.arsc bytes.
 *
 * <p>Strategy (write-back on top of the jadx parser):
 * <ul>
 *   <li>string values / key names live in string pools, so editing them never changes
 *       entry sizes - only pool contents. New strings are <b>appended</b> to the pool,
 *       existing strings and their indices stay untouched;</li>
 *   <li>an edited entry is a 4-byte patch (2-byte for compact-entry keys) inside the
 *       verbatim-copied raw type chunk;</li>
 *   <li>chunks that grew (string pools) force a full re-layout: package headers get their
 *       pool offsets patched, everything else is copied byte-for-byte.</li>
 * </ul>
 * Result: unmodified files round-trip byte-identical; modified files stay valid.
 */
public final class ArscWriter {

	private ArscWriter() {
	}

	/** One user edit. */
	public static final class Edit {
		public static final int VALUE_STRING = 0; // simple TYPE_STRING value
		public static final int VALUE_RAW = 1;    // simple inline value (int/bool/color/...)
		public static final int NAME = 2;         // rename resource key
		public static final int MAP_STRING = 3;   // named value inside a complex (bag) entry

		public final ResourceEntry entry;
		public final int kind;
		public final String str;   // VALUE_STRING / NAME / MAP_STRING
		public final int raw;      // VALUE_RAW
		public final int mapIndex; // MAP_STRING

		public Edit(ResourceEntry entry, String newStringValue) {
			this.entry = entry;
			this.kind = VALUE_STRING;
			this.str = newStringValue;
			this.raw = 0;
			this.mapIndex = -1;
		}

		public Edit(ResourceEntry entry, int newRawValue) {
			this.entry = entry;
			this.kind = VALUE_RAW;
			this.str = null;
			this.raw = newRawValue;
			this.mapIndex = -1;
		}

		public Edit(ResourceEntry entry, String newName, boolean rename) {
			this.entry = entry;
			this.kind = NAME;
			this.str = newName;
			this.raw = 0;
			this.mapIndex = -1;
		}

		public Edit(ResourceEntry entry, int mapIndex, String newStringValue) {
			this.entry = entry;
			this.kind = MAP_STRING;
			this.str = newStringValue;
			this.raw = 0;
			this.mapIndex = mapIndex;
		}
	}

	// ------------------------------------------------------------

	public static byte[] write(ArscFile arsc, List<Edit> edits) throws IOException {
		if (edits == null || edits.isEmpty()) {
			return arsc.raw;
		}
		W w = new W(arsc, edits);
		return w.run();
	}

	private static final class W {
		private final ArscFile arsc;
		private final byte[] raw;
		private final List<Edit> edits;

		/** new strings to append to the global value pool */
		private final List<String> globalAppends = new ArrayList<String>();
		/** new key names to append, per package name */
		private final Map<String, List<String>> keyAppends = new IdentityHashMap<String, List<String>>();
		/** resolved pool index per edit (identity) */
		private final Map<Edit, Integer> resolved = new IdentityHashMap<Edit, Integer>();

		W(ArscFile arsc, List<Edit> edits) {
			this.arsc = arsc;
			this.raw = arsc.raw;
			this.edits = edits;
		}

		byte[] run() throws IOException {
			resolveAppends();
			byte[] body = assemble();
			ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 12);
			byte[] hdr = new byte[12];
			System.arraycopy(raw, 0, hdr, 0, 12);
			ArscDefs.putU32(hdr, 4, 12 + body.length);
			out.write(hdr);
			out.write(body);
			return out.toByteArray();
		}

		// ----------------------------------------------------------
		// step 1: figure out which strings must be appended to pools
		// ----------------------------------------------------------

		private void resolveAppends() {
			for (Edit e : edits) {
				switch (e.kind) {
					case Edit.VALUE_STRING:
					case Edit.MAP_STRING: {
						int idx = indexOf(arsc.globalStrings(), e.str);
						if (idx < 0) {
							if (!globalAppends.contains(e.str)) {
								globalAppends.add(e.str);
							}
							idx = arsc.globalPoolSize() + globalAppends.indexOf(e.str);
						}
						resolved.put(e, Integer.valueOf(idx));
						break;
					}
					case Edit.NAME: {
						String pkg = e.entry.getPkgName();
						List<String> pool = arsc.keyStrings(pkg);
						List<String> appends = keyAppends.get(pkg);
						if (appends == null) {
							appends = new ArrayList<String>();
							keyAppends.put(pkg, appends);
						}
						int idx = indexOf(pool, e.str);
						if (idx < 0) {
							if (!appends.contains(e.str)) {
								appends.add(e.str);
							}
							idx = pool.size() + appends.indexOf(e.str);
						}
						resolved.put(e, Integer.valueOf(idx));
						break;
					}
					case Edit.VALUE_RAW:
					default:
						break;
				}
			}
		}

		private static int indexOf(List<String> list, String s) {
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i) != null && list.get(i).equals(s)) {
					return i;
				}
			}
			return -1;
		}

		// ----------------------------------------------------------
		// step 2: assemble the new file body
		// ----------------------------------------------------------

		private byte[] assemble() throws IOException {
			ByteArrayOutputStream body = new ByteArrayOutputStream(raw.length + 4096);
			for (ChunkSpan c : arsc.topChunks) {
				switch (c.type) {
					case ArscDefs.RES_STRING_POOL_TYPE:
						body.write(poolBytes(arsc.globalPoolSpan(), globalAppends, arsc.globalPoolUtf8()));
						break;
					case ArscDefs.RES_TABLE_PACKAGE_TYPE:
						body.write(packageBytes(pkgSpanAt(c.start)));
						break;
					default:
						body.write(raw, (int) c.start, (int) (c.end - c.start));
						break;
				}
			}
			return body.toByteArray();
		}

		private PkgSpan pkgSpanAt(long start) {
			for (PkgSpan p : arsc.packages) {
				if (p.start == start) {
					return p;
				}
			}
			throw new IllegalStateException("package span not found at " + start);
		}

		private byte[] packageBytes(PkgSpan pkg) throws IOException {
			List<String> appends = keyAppends.get(pkg.name);
			boolean keyDirty = appends != null && !appends.isEmpty();
			byte[] typePool = (pkg.typeStringsStart >= 0)
					? poolBytes(spanOf(pkg.typeStringsStart, pkg.typeStringsEnd), null, poolUtf8(pkg.typeStringsStart))
					: new byte[0];
			byte[] keyPool = (pkg.keyStringsStart >= 0)
					? poolBytes(spanOf(pkg.keyStringsStart, pkg.keyStringsEnd), keyDirty ? appends : null, poolUtf8(pkg.keyStringsStart))
					: new byte[0];

			// inner chunks (with type-chunk entry patches applied)
			ByteArrayOutputStream inner = new ByteArrayOutputStream();
			for (ChunkSpan c : pkg.chunks) {
				if (c.type == ArscDefs.RES_TABLE_TYPE_TYPE) {
					inner.write(patchedTypeChunk(typeChunkAt(pkg, c.start), c));
				} else {
					inner.write(raw, (int) c.start, (int) (c.end - c.start));
				}
			}

			int headerSize = pkg.headerSize;
			byte[] hdr = new byte[headerSize];
			System.arraycopy(raw, (int) pkg.start, hdr, 0, headerSize);
			// pools are always laid out right after the package header
			if (pkg.typeStringsStart >= 0) {
				ArscDefs.putU32(hdr, ArscDefs.PKG_TYPESTRINGS_OFF, headerSize);
			}
			if (pkg.keyStringsStart >= 0) {
				ArscDefs.putU32(hdr, ArscDefs.PKG_KEYSTRINGS_OFF, headerSize + typePool.length);
			}
			int total = headerSize + typePool.length + keyPool.length + inner.size();
			ArscDefs.putU32(hdr, 4, total);

			ByteArrayOutputStream out = new ByteArrayOutputStream(total);
			out.write(hdr);
			out.write(typePool);
			out.write(keyPool);
			out.write(inner.toByteArray());
			return out.toByteArray();
		}

		private ChunkSpan spanOf(long start, long end) {
			for (ChunkSpan c : arsc.topChunks) {
				if (c.start == start && c.end == end) {
					return c;
				}
			}
			for (PkgSpan p : arsc.packages) {
				for (ChunkSpan c : p.chunks) {
					if (c.start == start && c.end == end) {
						return c;
					}
				}
			}
			return new ChunkSpan(0, start, end);
		}

		private boolean poolUtf8(long poolStart) {
			if (poolStart < 0 || poolStart + 20 > raw.length) {
				return false;
			}
			int flags = ArscDefs.u32(raw, (int) poolStart + 16);
			return (flags & ArscDefs.UTF8_FLAG) != 0;
		}

		private TypeChunk typeChunkAt(PkgSpan pkg, long start) {
			for (TypeChunk tc : pkg.typeChunks) {
				if (tc.start == start) {
					return tc;
				}
			}
			return null;
		}

		/** Copy a raw type chunk, patching the 4-byte value/key fields of edited entries. */
		private byte[] patchedTypeChunk(TypeChunk tc, ChunkSpan c) {
			int len = (int) (c.end - c.start);
			byte[] chunk = new byte[len];
			System.arraycopy(raw, (int) c.start, chunk, 0, len);
			if (tc == null) {
				return chunk;
			}
			for (Edit e : edits) {
				EntrySpan es = arsc.spanOf(e.entry);
				if (es == null || es.typeChunk != tc) {
					continue;
				}
				switch (e.kind) {
					case Edit.VALUE_STRING:
					case Edit.MAP_STRING: {
						int idx = resolved.get(e).intValue();
						if (e.kind == Edit.MAP_STRING) {
							if (e.mapIndex < 0 || e.mapIndex >= es.mapDataPoses.size()) {
								throw new IllegalArgumentException("bad map index");
							}
							ArscDefs.putU32(chunk, (int) (es.mapDataPoses.get(e.mapIndex).longValue() - tc.start), idx);
						} else {
							if (es.dataPos < 0) {
								throw new IllegalArgumentException("entry has no value field");
							}
							ArscDefs.putU32(chunk, (int) (es.dataPos - tc.start), idx);
						}
						break;
					}
					case Edit.VALUE_RAW:
						if (es.dataPos < 0) {
							throw new IllegalArgumentException("entry has no value field");
						}
						ArscDefs.putU32(chunk, (int) (es.dataPos - tc.start), e.raw);
						break;
					case Edit.NAME: {
						int idx = resolved.get(e).intValue();
						if (es.keyPos >= 0) {
							ArscDefs.putU32(chunk, (int) (es.keyPos - tc.start), idx);
						} else if (es.compactKeyPos >= 0) {
							ArscDefs.putU16(chunk, (int) (es.compactKeyPos - tc.start), idx);
						} else {
							throw new IllegalArgumentException("entry has no key field");
						}
						break;
					}
					default:
						break;
				}
			}
			return chunk;
		}

		// ----------------------------------------------------------
		// step 3: string pool rebuild (append-only)
		// ----------------------------------------------------------

		private byte[] poolBytes(ChunkSpan span, List<String> appends, boolean utf8) throws IOException {
			int start = (int) span.start;
			int end = (int) span.end;
			int len = end - start;
			if (appends == null || appends.isEmpty()) {
				byte[] copy = new byte[len];
				System.arraycopy(raw, start, copy, 0, len);
				return copy;
			}
			int stringCount = ArscDefs.u32(raw, start + 8);
			int styleCount = ArscDefs.u32(raw, start + 12);
			int flags = ArscDefs.u32(raw, start + 16);
			int stringsStartField = ArscDefs.u32(raw, start + 20);
			int stylesStartField = ArscDefs.u32(raw, start + 24);

			// copy existing string bytes verbatim (prefix + chars + terminator)
			ByteArrayOutputStream data = new ByteArrayOutputStream();
			int[] offs = new int[stringCount + appends.size()];
			int emptySlot = -1; // shared empty string for garbage offsets
			for (int i = 0; i < stringCount; i++) {
				int relOff = ArscDefs.u32(raw, start + 28 + i * 4);
				int sStart = start + stringsStartField + relOff;
				if (relOff < 0 || sStart >= end) {
					// invalid/garbage offset (hand-edited or obfuscated arsc):
					// point this index at a shared empty string instead of copying garbage
					if (emptySlot < 0) {
						emptySlot = data.size();
						data.write(utf8 ? new byte[] { 0, 0, 0 } : new byte[] { 0, 0, 0, 0 }, 0,
								utf8 ? 3 : 4);
					}
					offs[i] = emptySlot;
					continue;
				}
				int sEnd;
				if (i + 1 < stringCount) {
					int nextRel = ArscDefs.u32(raw, start + 28 + (i + 1) * 4);
					sEnd = (nextRel > relOff) ? start + stringsStartField + nextRel : scanTerminator(sStart, utf8, end);
				} else {
					sEnd = (styleCount > 0 && stylesStartField > 0)
							? start + stylesStartField
							: scanTerminator(sStart, utf8, end);
				}
				if (sEnd > end) {
					sEnd = scanTerminator(sStart, utf8, end);
				}
				offs[i] = data.size();
				data.write(raw, sStart, sEnd - sStart);
			}
			// style data (kept verbatim after the strings)
			byte[] styleData = new byte[0];
			if (styleCount > 0 && stylesStartField > 0 && start + stylesStartField < end) {
				styleData = new byte[end - start - stylesStartField];
				System.arraycopy(raw, start + stylesStartField, styleData, 0, styleData.length);
			}
			// appended strings
			for (int i = 0; i < appends.size(); i++) {
				offs[stringCount + i] = data.size();
				byte[] enc = utf8 ? encodeUtf8(appends.get(i)) : encodeUtf16(appends.get(i));
				data.write(enc);
			}

			int newCount = stringCount + appends.size();
			int newStringsStart = 28 + (newCount + styleCount) * 4;
			int dataLen = data.size();
			// chunk 总长必须是 4 的倍数（aapt: "total size ... is not on an integer
			// boundary"）：追加字符串后若数据区未对齐，补零到 4 字节边界，styles 顺延
			int pad = (4 - (dataLen & 3)) & 3;
			int newStylesStart = styleCount > 0 ? newStringsStart + dataLen + pad : 0;
			int total = newStringsStart + dataLen + pad + styleData.length;
			// appending can break the sorted invariant -> clear SORTED flag
			int newFlags = flags & ~ArscDefs.SORTED_FLAG;

			byte[] pool = new byte[total];
			ArscDefs.putU16(pool, 0, ArscDefs.RES_STRING_POOL_TYPE);
			ArscDefs.putU16(pool, 2, 28);
			ArscDefs.putU32(pool, 4, total);
			ArscDefs.putU32(pool, 8, newCount);
			ArscDefs.putU32(pool, 12, styleCount);
			ArscDefs.putU32(pool, 16, newFlags);
			ArscDefs.putU32(pool, 20, newStringsStart);
			ArscDefs.putU32(pool, 24, newStylesStart);
			for (int i = 0; i < newCount; i++) {
				ArscDefs.putU32(pool, 28 + i * 4, offs[i]);
			}
			// style offsets: copy verbatim from the old array
			if (styleCount > 0) {
				int oldStyleOffStart = start + 28 + stringCount * 4;
				System.arraycopy(raw, oldStyleOffStart, pool,
						28 + newCount * 4, styleCount * 4);
			}
			System.arraycopy(data.toByteArray(), 0, pool, newStringsStart, dataLen);
			for (int i = 0; i < pad; i++) pool[newStringsStart + dataLen + i] = 0;
			if (styleData.length > 0) {
				System.arraycopy(styleData, 0, pool, newStylesStart, styleData.length);
			}
			return pool;
		}

		private int scanTerminator(int from, boolean utf8, int limit) {
			int i = from;
			int end = Math.min(limit, raw.length);
			if (utf8) {
				while (i < end && raw[i] != 0) {
					i++;
				}
				return Math.min(i + 1, end);
			}
			while (i + 1 < end && !(raw[i] == 0 && raw[i + 1] == 0)) {
				i += 2;
			}
			return Math.min(i + 2, end);
		}

		/**
		 * UTF-8 pool strings carry TWO length fields: [charCount][byteCount][bytes][\0]
		 * (verified against aapt2 output; jadx skips the first and reads the second).
		 */
		private static byte[] encodeUtf8(String s) throws IOException {
			byte[] bytes = s.getBytes("UTF-8");
			int byteLen = bytes.length;
			int charLen = s.length();
			ByteArrayOutputStream bo = new ByteArrayOutputStream(byteLen + 8);
			writeLen8(bo, charLen);
			writeLen8(bo, byteLen);
			bo.write(bytes);
			bo.write(0);
			return bo.toByteArray();
		}

		private static void writeLen8(ByteArrayOutputStream bo, int len) {
			if (len > 0x7F) {
				bo.write(0x80 | (len >> 8));
			}
			bo.write(len & 0xFF);
		}

		private static byte[] encodeUtf16(String s) throws IOException {
			int len = s.length();
			ByteArrayOutputStream bo = new ByteArrayOutputStream(len * 2 + 8);
			if (len < 0x8000) {
				putU16(bo, len);
			} else {
				putU16(bo, 0xFFFF);
				putU32(bo, len);
			}
			for (int i = 0; i < len; i++) {
				putU16(bo, s.charAt(i));
			}
			putU16(bo, 0);
			return bo.toByteArray();
		}

		private static void putU16(ByteArrayOutputStream bo, int v) {
			bo.write(v & 0xFF);
			bo.write((v >>> 8) & 0xFF);
		}

		private static void putU32(ByteArrayOutputStream bo, int v) {
			bo.write(v & 0xFF);
			bo.write((v >>> 8) & 0xFF);
			bo.write((v >>> 16) & 0xFF);
			bo.write((v >>> 24) & 0xFF);
		}
	}

	// ------------------------------------------------------------
	// small helpers shared with the UI layer
	// ------------------------------------------------------------

	/** True if the entry holds a simple value of the given data type. */
	public static boolean isSimple(ResourceEntry e) {
		return e.getNamedValues() == null && e.getSimpleValue() != null;
	}

	public static boolean isStringEntry(ResourceEntry e) {
		return isSimple(e) && e.getSimpleValue().getDataType() == ArscDefs.TYPE_STRING;
	}

	public static RawValue rawValueOf(ResourceEntry e) {
		return e.getSimpleValue();
	}
}
