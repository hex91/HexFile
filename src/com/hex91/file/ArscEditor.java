package com.hex91.file;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jadx.core.xmlgen.ResTableBinaryParser.ChunkSpan;
import jadx.core.xmlgen.ResTableBinaryParser.EntrySpan;
import jadx.core.xmlgen.ResTableBinaryParser.PkgSpan;
import jadx.core.xmlgen.ResTableBinaryParser.TypeChunk;
import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * ARSC++ 全量解析器/编辑器（v1.64.2，参考 NP Manager 的「全内存模型 + 全表重编码」架构）。
 *
 * <p>在 {@link ArscWriter}（字符串池追加 + 字节补丁，只能改值）之上补齐结构化编辑：
 * <ul>
 *   <li>{@link ArscWriter.Edit#VALUE_STRING} / VALUE_RAW / NAME / MAP_STRING —— 与补丁版完全兼容，
 *       无结构化编辑时直接走 {@link ArscWriter}（已验证路径）；</li>
 *   <li><b>DELETE</b> —— 删除条目：type chunk 偏移数组置 NO_ENTRY（0xFFFFFFFF），
 *       条目体不再写出，其余条目原样保留；</li>
 *   <li><b>ADD</b> —— 新增条目：entryId 取同 type 已用 id 的最大值 +1（含各 config 的
 *       entryCount 预留位），key 追加进包 key 池，值暂支持 TYPE_STRING；</li>
 * </ul>
 *
 * <p>重编码策略（块保持，非整表重建）：
 * <ul>
 *   <li>字符串池：原样保留 + 尾部追加（追加破坏有序性则清 SORTED 标志）；</li>
 *   <li>type chunk：无编辑 → 原字节拷贝；有编辑 → 重序列化（偏移数组重建 + 条目体逐个
 *       拷贝/补丁/合成，entriesStart/size 重算）；</li>
 *   <li>SPEC / LIBRARY / 其他 chunk → 原字节拷贝；</li>
 *   <li>包头只补 typeStrings/keyStrings 偏移与总长，其余字段原样。</li>
 * </ul>
 *
 * <p>安全防御（与 NP 一致，绝不静默写坏）：
 * <ul>
 *   <li>type chunk 头 flags 含 SPARSE(0x01)/COMPACT(0x02)/OFFSET16(0x04) → 拒绝编辑该块
 *       （Android 15 紧凑格式，结构未知字段多，只读）；</li>
 *   <li>单个条目 flags 含 COMPACT(0x0008) → 拒绝编辑该条目；</li>
 *   <li>ADD 的 entryId 与现有资源冲突 → 拒绝。</li>
 * </ul>
 */
public final class ArscEditor {

	private ArscEditor() {
	}

	// type chunk 头 flags（ResourceTypes.h ResTable_type.flags）
	private static final int TC_SPARSE = 0x01;
	private static final int TC_COMPACT = 0x02;
	private static final int TC_OFFSET16 = 0x04;

	/** 一次编辑请求（结构化部分；详见 {@link Op}）。 */
	public static final class Op {
		static final int DELETE = 0;
		static final int ADD = 1;

		final int kind;
		final ResourceEntry entry; // DELETE
		final String typeName;     // ADD
		final String cfgName;      // ADD（"" = 默认配置）
		final String key;          // ADD：新资源名
		final String value;        // ADD：字符串值（TYPE_STRING）

		private Op(int kind, ResourceEntry entry, String typeName, String cfgName, String key, String value) {
			this.kind = kind;
			this.entry = entry;
			this.typeName = typeName;
			this.cfgName = cfgName;
			this.key = key;
			this.value = value;
		}

		public static Op delete(ResourceEntry e) {
			return new Op(DELETE, e, null, null, null, null);
		}

		public static Op addString(String typeName, String cfgName, String key, String value) {
			return new Op(ADD, null, typeName, cfgName, key, value);
		}
	}

	/**
	 * 全量写回入口。
	 *
	 * @param ops 结构化编辑（删除/新增），可为 null 或空
	 * @see ArscWriter#write(ArscFile, List) 值级编辑
	 */
	public static byte[] write(ArscFile arsc, List<ArscWriter.Edit> edits, List<Op> ops) throws IOException {
		boolean hasStruct = ops != null && !ops.isEmpty();
		if (!hasStruct) {
			return ArscWriter.write(arsc, edits); // 兼容路径（含空 edits）
		}
		return new E(arsc, edits, ops).run();
	}

	// ============================================================
	// 引擎
	// ============================================================

	private static final class E {
		private final ArscFile arsc;
		private final byte[] raw;
		private final List<ArscWriter.Edit> edits; // 值级（可与结构化混用）
		private final List<Op> ops;

		// 解析产物：type chunk 原始信息（绝对偏移）
		private final Map<TypeChunk, Tc> tcs = new IdentityHashMap<TypeChunk, Tc>();

		// 每个被改动的 Tc 上挂的改动
		private final Map<Tc, List<EditAt>> editsAt = new IdentityHashMap<Tc, List<EditAt>>();
		private final Map<Tc, List<Op>> delsAt = new IdentityHashMap<Tc, List<Op>>();
		private final Map<Tc, List<Add>> addsAt = new IdentityHashMap<Tc, List<Add>>();

		// 字符串追加
		private final List<String> globalAppends = new ArrayList<String>();
		private final Map<String, List<String>> keyAppends = new HashMap<String, List<String>>();

		E(ArscFile arsc, List<ArscWriter.Edit> edits, List<Op> ops) {
			this.arsc = arsc;
			this.raw = arsc.raw;
			this.edits = edits == null ? new ArrayList<ArscWriter.Edit>() : edits;
			this.ops = ops;
		}

		byte[] run() throws IOException {
			parseTypeChunkHeaders();
			planAdds();
			planValueEdits();
			planDeletes();

			// ---- 组装 ----
			ByteArrayOutputStream body = new ByteArrayOutputStream(raw.length + 4096);
			for (ChunkSpan c : arsc.topChunks) {
				if (c.type == ArscDefs.RES_STRING_POOL_TYPE) {
					boolean first = c == arsc.globalPoolSpan();
					body.write(first
							? poolBytes(arsc.globalPoolSpan(), globalAppends, arsc.globalPoolUtf8())
							: verbatim(c));
				} else if (c.type == ArscDefs.RES_TABLE_PACKAGE_TYPE) {
					body.write(packageBytes(pkgAt(c.start)));
				} else {
					body.write(verbatim(c));
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream(body.size() + 12);
			byte[] hdr = new byte[12];
			System.arraycopy(raw, 0, hdr, 0, 12);
			ArscDefs.putU32(hdr, 4, 12 + body.size());
			out.write(hdr);
			out.write(body.toByteArray());
			return out.toByteArray();
		}

		// ----------------------------------------------------------
		// 解析（惰性：头部全解析，条目体仅在被编辑的块上解析）
		// ----------------------------------------------------------

		/** type chunk 头 + 每个条目的字段绝对位置 */
		private static final class Tc {
			TypeChunk jadx;             // jadx 侧块（typeName/typeId）
			ChunkSpan span;
			int headerSize;             // 头+config 总大小（偏移数组从此开始）
			int flags;                  // 头 flags（bit0 sparse / bit1 compact / bit2 offset16）
			int entryCount;
			int entriesStart;           // 相对块起点
			Map<Integer, En> entries;   // 惰性填充（ensureEntries）
		}

		private static final class En {
			int id;
			long start;                 // 绝对
			int len;
			boolean compact;            // 条目 flags & 0x0008
			long keyPos = -1;           // 绝对（regular 条目）
			long dataPos = -1;          // 绝对（simple 值 data 字段）
			List<Long> mapDataPos;      // complex：各 named value 的 data 字段绝对位置
			int sizeField;              // 条目 size 字段值（8/16/...）
			int flags;
		}

		private void parseTypeChunkHeaders() {
			for (PkgSpan p : arsc.packages) {
				for (TypeChunk jt : p.typeChunks) {
					ChunkSpan span = null;
					for (ChunkSpan c : p.chunks) {
						if (c.type == ArscDefs.RES_TABLE_TYPE_TYPE && c.start == jt.start) {
							span = c;
							break;
						}
					}
					if (span == null) {
						continue;
					}
					Tc tc = new Tc();
					tc.jadx = jt;
					tc.span = span;
					int s = (int) span.start;
					tc.headerSize = ArscDefs.u16(raw, s + 2);
					tc.flags = raw[s + 10] & 0xFF;
					tc.entryCount = ArscDefs.u32(raw, s + 12);
					tc.entriesStart = ArscDefs.u32(raw, s + 16);
					tcs.put(jt, tc);
				}
			}
		}

		/** 解析一个 type chunk 的全部条目字段位置（仅在需要编辑该块时调用） */
		private void ensureEntries(Tc tc) {
			if (tc.entries != null) {
				return;
			}
			tc.entries = new HashMap<Integer, En>();
			int s = (int) tc.span.start;
			for (int i = 0; i < tc.entryCount; i++) {
				int offPos = s + tc.headerSize + i * 4;
				if (offPos + 4 > s + tc.entriesStart) {
					break; // 偏移数组越界（异常文件），保留已知部分
				}
				int off = ArscDefs.u32(raw, offPos);
				if (off == ArscDefs.NO_ENTRY) {
					continue;
				}
				En en = new En();
				en.id = i;
				en.start = s + tc.entriesStart + off;
				if (en.start + 8 > tc.span.end) {
					continue;
				}
				en.sizeField = ArscDefs.u16(raw, (int) en.start);
				en.flags = ArscDefs.u16(raw, (int) en.start + 2);
				en.compact = (en.flags & ArscDefs.FLAG_COMPACT) != 0;
				en.keyPos = en.start + 4; // regular 条目：size(2)+flags(2)+key(4) → key 恒在 +4
				if ((en.flags & ArscDefs.FLAG_COMPLEX) != 0) {
					// complex: +8 parent(4) +12 count(4) +16 起每项 name(4)+Res_value(8)
					long countPos = en.start + 12;
					// 防御：count 受块内剩余空间钳制（异常/混淆 arsc 不炸内存）
					long room = (tc.span.end - (countPos + 8)) / 12;
					int count = ArscDefs.u32(raw, (int) countPos);
					if (count < 0 || count > room) {
						count = (int) Math.max(0, room);
					}
					en.mapDataPos = new ArrayList<Long>(count);
					for (int m = 0; m < count; m++) {
						en.mapDataPos.add(Long.valueOf(countPos + 4 + m * 12 + 4));
					}
					en.len = 16 + count * 12;
				} else {
					en.dataPos = en.start + 12; // Res_value.data（Res_value 起于 +8，data 在 +4）
					en.len = 16;
				}
				tc.entries.put(Integer.valueOf(i), en);
			}
		}

		// ----------------------------------------------------------
		// 计划：ADD / 值编辑 / DELETE
		// ----------------------------------------------------------

		private static final class Add {
			Tc tc;
			int newId;
			int keyIdx;
			int valIdx;
		}

		private void planAdds() throws IOException {
			if (ops == null) {
				return;
			}
			for (Op op : ops) {
				if (op.kind != Op.ADD) {
					continue;
				}
				// 定位包 + type chunk：单包 APK 场景按第一包处理（多包时 add 仅支持第一包）
				PkgSpan pkg = arsc.packages.get(0);
				TypeChunk target = null;
				int typeId = -1;
				for (TypeChunk jt : pkg.typeChunks) {
					if (typeId < 0 && op.typeName.equals(jt.typeName)) {
						typeId = jt.typeId;
					}
				}
				if (typeId < 0) {
					throw new IOException("RESOURCE_ADD_FAILED: 该包没有类型 \"" + op.typeName + "\"");
				}
				for (TypeChunk jt : pkg.typeChunks) {
					if (jt.typeId == typeId && cfgMatches(jt, op.cfgName)) {
						target = jt;
						break;
					}
				}
				if (target == null) {
					throw new IOException("RESOURCE_ADD_FAILED: 配置不存在: " + op.typeName
							+ " (" + (op.cfgName.isEmpty() ? "默认" : op.cfgName)
							+ ")。新增资源只能加到已有配置，请先用「ARSC 编辑」处理");
				}
				Tc tc = tcs.get(target);
				checkWritable(tc, "新增 " + op.typeName);

				// entryId = 同 typeId 各 config 已用最大 id + 1（含各 config 偏移数组的预留位）
				int maxId = -1;
				for (TypeChunk jt : pkg.typeChunks) {
					if (jt.typeId != typeId) {
						continue;
					}
					Tc t = tcs.get(jt);
					if (t != null && t.entryCount - 1 > maxId) {
						maxId = t.entryCount - 1;
					}
				}
				int newId = maxId + 1;
				int fullId = (pkg.id << 24) | (typeId << 16) | newId;
				for (ResourceEntry re : arsc.storage.getResources()) {
					if (re.getId() == fullId) {
						throw new IOException("RESOURCE_ADD_FAILED: 资源 ID 0x"
								+ Integer.toHexString(fullId) + " 已被占用");
					}
				}

				Add a = new Add();
				a.tc = tc;
				a.newId = newId;
				// key 追加进包 key 池
				List<String> appends = keyAppends.get(pkg.name);
				if (appends == null) {
					appends = new ArrayList<String>();
					keyAppends.put(pkg.name, appends);
				}
				int ki = appendIdx(keyStrings(pkg), appends, op.key);
				a.keyIdx = ki;
				a.valIdx = appendIdx(arsc.globalStrings(), globalAppends, op.value);
				List<Add> l = addsAt.get(tc);
				if (l == null) {
					l = new ArrayList<Add>();
					addsAt.put(tc, l);
				}
				l.add(a);
			}
		}

		private boolean cfgMatches(TypeChunk jt, String cfg) {
			// jadx 的 ResourceEntry 已带 config 串；用同一包内同 typeId 的 entry 反查最可靠
			for (ResourceEntry re : arsc.storage.getResources()) {
				EntrySpan es = arsc.spanOf(re);
				if (es != null && es.typeChunk == jt) {
					String c = re.getConfig() == null ? "" : re.getConfig();
					return c.equals(cfg);
				}
			}
			return cfg.isEmpty();
		}

		private List<String> keyStrings(PkgSpan p) {
			List<String> out = new ArrayList<String>();
			if (p.keyStrings != null) {
				for (int i = 0; i < p.keyStrings.size(); i++) {
					out.add(p.keyStrings.get(i));
				}
			}
			return out;
		}

		private int appendIdx(List<String> pool, List<String> appends, String s) {
			for (int i = 0; i < pool.size(); i++) {
				if (s.equals(pool.get(i))) {
					return i;
				}
			}
			if (!appends.contains(s)) {
				appends.add(s);
			}
			return pool.size() + appends.indexOf(s);
		}

		private void planValueEdits() throws IOException {
			for (ArscWriter.Edit e : edits) {
				EntrySpan es = arsc.spanOf(e.entry);
				if (es == null) {
					continue;
				}
				Tc tc = tcs.get(es.typeChunk);
				if (tc == null) {
					continue; // 该块解析失败 → 交由 ArscWriter 补丁路径？此处结构化模式必须接管
				}
				checkWritable(tc, "编辑 " + e.entry.getKeyName());
				ensureEntries(tc);
				En en = tc.entries.get(Integer.valueOf(es.entryId));
				if (en == null) {
					throw new IOException("条目不可定位: " + e.entry.getKeyName());
				}
				if (en.compact) {
					throw new IOException("拒绝编辑: " + e.entry.getKeyName()
							+ " 是 compact 条目（Android 15 紧凑格式），请用「ARSC 编辑」逐项修改");
				}
				int poolIdx = -1;
				switch (e.kind) {
					case ArscWriter.Edit.VALUE_STRING:
					case ArscWriter.Edit.MAP_STRING:
						poolIdx = appendIdx(arsc.globalStrings(), globalAppends, e.str);
						break;
					case ArscWriter.Edit.NAME: {
						PkgSpan pkg = arsc.pkgOf(e.entry);
						List<String> appends = keyAppends.get(pkg.name);
						if (appends == null) {
							appends = new ArrayList<String>();
							keyAppends.put(pkg.name, appends);
						}
						poolIdx = appendIdx(keyStrings(pkg), appends, e.str);
						break;
					}
					default:
						break;
				}
				List<EditAt> l0 = editsAt.get(tc);
				if (l0 == null) {
					l0 = new ArrayList<EditAt>();
					editsAt.put(tc, l0);
				}
				l0.add(new EditAt(e, en, poolIdx));
			}
		}

		private void planDeletes() throws IOException {
			if (ops == null) {
				return;
			}
			for (Op op : ops) {
				if (op.kind != Op.DELETE) {
					continue;
				}
				EntrySpan es = arsc.spanOf(op.entry);
				if (es == null) {
					throw new IOException("无法删除: 条目不可定位 " + op.entry.getKeyName());
				}
				Tc tc = tcs.get(es.typeChunk);
				checkWritable(tc, "删除 " + op.entry.getKeyName());
				ensureEntries(tc);
				if (!tc.entries.containsKey(Integer.valueOf(es.entryId))) {
					throw new IOException("无法删除: 条目不存在 " + op.entry.getKeyName());
				}
				List<Op> dl = delsAt.get(tc);
				if (dl == null) {
					dl = new ArrayList<Op>();
					delsAt.put(tc, dl);
				}
				dl.add(op);
			}
		}

		private void checkWritable(Tc tc, String what) throws IOException {
			if ((tc.flags & (TC_SPARSE | TC_COMPACT | TC_OFFSET16)) != 0) {
				throw new IOException("拒绝" + what + ": " + tc.jadx.typeName
						+ " 的这个配置块使用 sparse/compact/offset16 布局（Android 15 紧凑格式），只读。"
						+ "请用「ARSC 编辑」逐项修改值");
			}
		}

		private static final class EditAt {
			final ArscWriter.Edit e;
			final En en;
			final int poolIdx;

			EditAt(ArscWriter.Edit e, En en, int poolIdx) {
				this.e = e;
				this.en = en;
				this.poolIdx = poolIdx;
			}
		}

		// ----------------------------------------------------------
		// 组装
		// ----------------------------------------------------------

		private byte[] verbatim(ChunkSpan c) {
			int len = (int) (c.end - c.start);
			byte[] b = new byte[len];
			System.arraycopy(raw, (int) c.start, b, 0, len);
			return b;
		}

		private PkgSpan pkgAt(long start) {
			for (PkgSpan p : arsc.packages) {
				if (p.start == start) {
					return p;
				}
			}
			throw new IllegalStateException("package span not found at " + start);
		}

		private byte[] packageBytes(PkgSpan pkg) throws IOException {
			List<String> kAppends = keyAppends.get(pkg.name);
			boolean keyDirty = kAppends != null && !kAppends.isEmpty();
			byte[] typePool = (pkg.typeStringsStart >= 0)
					? poolBytes(spanOf(pkg.typeStringsStart, pkg.typeStringsEnd), null, poolUtf8(pkg.typeStringsStart))
					: new byte[0];
			byte[] keyPool = (pkg.keyStringsStart >= 0)
					? poolBytes(spanOf(pkg.keyStringsStart, pkg.keyStringsEnd), keyDirty ? kAppends : null, poolUtf8(pkg.keyStringsStart))
					: new byte[0];

			ByteArrayOutputStream inner = new ByteArrayOutputStream();
			for (ChunkSpan c : pkg.chunks) {
				Tc tc = null;
				if (c.type == ArscDefs.RES_TABLE_TYPE_TYPE) {
					for (TypeChunk jt : pkg.typeChunks) {
						if (jt.start == c.start) {
							tc = tcs.get(jt);
							break;
						}
					}
				}
				if (tc == null || !isTouched(tc)) {
					inner.write(verbatim(c));
				} else {
					inner.write(typeChunkBytes(tc));
				}
			}

			int headerSize = pkg.headerSize;
			byte[] hdr = new byte[headerSize];
			System.arraycopy(raw, (int) pkg.start, hdr, 0, headerSize);
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

		private boolean isTouched(Tc tc) {
			return editsAt.containsKey(tc) || addsAt.containsKey(tc) || delsAt.containsKey(tc);
		}

		/**
		 * 重序列化一个有改动的 type chunk：
		 * 头（entryCount/entriesStart/size 重算）+ 偏移数组（重建）+ 条目体（拷贝+补丁/合成）。
		 */
		private byte[] typeChunkBytes(Tc tc) throws IOException {
			List<EditAt> eAt = editsAt.get(tc);
			List<Op> dels = delsAt.get(tc);
			List<Add> adds = addsAt.get(tc);
			int s = (int) tc.span.start;

			Set<Integer> deletedIds = new HashSet<Integer>();
			if (dels != null) {
				for (Op op : dels) {
					EntrySpan es = arsc.spanOf(op.entry);
					deletedIds.add(Integer.valueOf(es.entryId));
				}
			}
			Map<Integer, List<EditAt>> byId = new HashMap<Integer, List<EditAt>>();
			if (eAt != null) {
				for (EditAt a : eAt) {
					Integer k = Integer.valueOf(a.en.id);
					List<EditAt> l = byId.get(k);
					if (l == null) {
						l = new ArrayList<EditAt>();
						byId.put(k, l);
					}
					l.add(a);
				}
			}

			int newCount = tc.entryCount;
			if (adds != null) {
				for (Add a : adds) {
					if (a.newId + 1 > newCount) {
						newCount = a.newId + 1;
					}
				}
			}

			// 原条目字节收集（保持原顺序；被删/被编辑的跳过或补丁）
			ByteArrayOutputStream entries = new ByteArrayOutputStream();
			int[] offs = new int[newCount];
			for (int i = 0; i < newCount; i++) {
				offs[i] = ArscDefs.NO_ENTRY;
			}
			for (int i = 0; i < tc.entryCount; i++) {
				En en = tc.entries.get(Integer.valueOf(i));
				if (en == null || deletedIds.contains(Integer.valueOf(i))) {
					continue; // NO_ENTRY
				}
				offs[i] = entries.size();
				entries.write(patchedEntry(en, byId.get(Integer.valueOf(i))));
			}
			if (adds != null) {
				for (Add a : adds) {
					offs[a.newId] = entries.size();
					entries.write(newEntryBytes(a));
				}
			}

			byte[] entryData = entries.toByteArray();
			int pad = (4 - (entryData.length & 3)) & 3;
			int hdrLen = tc.headerSize;
			int entriesStart = hdrLen + newCount * 4; // 偏移数组恒 4 字节/项（拒绝 offset16 块）
			int total = entriesStart + entryData.length + pad;

			byte[] chunk = new byte[total];
			// 头 + config 原样拷贝
			System.arraycopy(raw, s, chunk, 0, hdrLen);
			ArscDefs.putU32(chunk, 4, total);
			ArscDefs.putU32(chunk, 12, newCount);
			ArscDefs.putU32(chunk, 16, entriesStart);
			for (int i = 0; i < newCount; i++) {
				ArscDefs.putU32(chunk, hdrLen + i * 4, offs[i]);
			}
			System.arraycopy(entryData, 0, chunk, entriesStart, entryData.length);
			for (int i = 0; i < pad; i++) {
				chunk[entriesStart + entryData.length + i] = 0;
			}
			return chunk;
		}

		/** 原条目字节拷贝 + 字段补丁 */
		private byte[] patchedEntry(En en, List<EditAt> list) {
			byte[] b = new byte[en.len];
			System.arraycopy(raw, (int) en.start, b, 0, en.len);
			if (list == null) {
				return b;
			}
			for (EditAt a : list) {
				ArscWriter.Edit e = a.e;
				switch (e.kind) {
					case ArscWriter.Edit.VALUE_STRING:
					case ArscWriter.Edit.VALUE_RAW:
						ArscDefs.putU32(b, (int) (a.en.dataPos - a.en.start), a.poolIdx >= 0 ? a.poolIdx : e.raw);
						break;
					case ArscWriter.Edit.NAME:
						ArscDefs.putU32(b, (int) (a.en.keyPos - a.en.start), a.poolIdx);
						break;
					case ArscWriter.Edit.MAP_STRING:
						ArscDefs.putU32(b, (int) (a.en.mapDataPos.get(e.mapIndex) - a.en.start), a.poolIdx);
						break;
					default:
						break;
				}
			}
			return b;
		}

		/** 合成新条目：regular + Res_value（当前支持 TYPE_STRING 值） */
		private byte[] newEntryBytes(Add a) {
			byte[] b = new byte[16];
			ArscDefs.putU16(b, 0, 8);              // entry size
			ArscDefs.putU16(b, 2, 0);              // flags: 非 complex 非 compact
			ArscDefs.putU32(b, 4, a.keyIdx);       // key
			ArscDefs.putU16(b, 8, 8);              // Res_value.size
			b[10] = 0;                             // res0
			b[11] = ArscDefs.TYPE_STRING;          // dataType
			ArscDefs.putU32(b, 12, a.valIdx);      // data
			return b;
		}

		// ----------------------------------------------------------
		// 字符串池重建（原样保留 + 追加；与 ArscWriter 同算法）
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

			ByteArrayOutputStream data = new ByteArrayOutputStream();
			int[] offs = new int[stringCount + appends.size()];
			int emptySlot = -1;
			for (int i = 0; i < stringCount; i++) {
				int relOff = ArscDefs.u32(raw, start + 28 + i * 4);
				int sStart = start + stringsStartField + relOff;
				if (relOff < 0 || sStart >= end) {
					if (emptySlot < 0) {
						emptySlot = data.size();
						data.write(new byte[utf8 ? 3 : 4], 0, utf8 ? 3 : 4);
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
			byte[] styleData = new byte[0];
			if (styleCount > 0 && stylesStartField > 0 && start + stylesStartField < end) {
				styleData = new byte[end - start - stylesStartField];
				System.arraycopy(raw, start + stylesStartField, styleData, 0, styleData.length);
			}
			for (int i = 0; i < appends.size(); i++) {
				offs[stringCount + i] = data.size();
				byte[] enc = utf8 ? encodeUtf8(appends.get(i)) : encodeUtf16(appends.get(i));
				data.write(enc);
			}

			int newCount = stringCount + appends.size();
			int newStringsStart = 28 + (newCount + styleCount) * 4;
			int dataLen = data.size();
			int pad = (4 - (dataLen & 3)) & 3;
			int newStylesStart = styleCount > 0 ? newStringsStart + dataLen + pad : 0;
			int total = newStringsStart + dataLen + pad + styleData.length;
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
			if (styleCount > 0) {
				int oldStyleOffStart = start + 28 + stringCount * 4;
				System.arraycopy(raw, oldStyleOffStart, pool, 28 + newCount * 4, styleCount * 4);
			}
			System.arraycopy(data.toByteArray(), 0, pool, newStringsStart, dataLen);
			for (int i = 0; i < pad; i++) {
				pool[newStringsStart + dataLen + i] = 0;
			}
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

		private static byte[] encodeUtf8(String s) throws IOException {
			byte[] bytes = s.getBytes("UTF-8");
			ByteArrayOutputStream bo = new ByteArrayOutputStream(bytes.length + 8);
			writeLen8(bo, s.length());
			writeLen8(bo, bytes.length);
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
}
