package jadx.core.xmlgen;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.core.xmlgen.entry.EntryConfig;
import jadx.core.xmlgen.entry.RawNamedValue;
import jadx.core.xmlgen.entry.RawValue;
import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * resources.arsc binary table parser.
 *
 * <p>Derived from jadx (https://github.com/skylot/jadx), Apache License 2.0.
 * HexFile modifications (marked "HexFile mod"):
 * <ul>
 *   <li>dropped JadxDecompiler/RootNode deobfuscation integration - always uses raw resource names</li>
 *   <li>dropped decodeFiles()/XML generation (not needed for editing)</li>
 *   <li>records raw byte spans of every chunk/entry/pool to support byte-exact write-back</li>
 *   <li>Java 8 source compatibility (no var)</li>
 * </ul>
 */
public class ResTableBinaryParser extends CommonBinaryParser {
	private static final Logger LOG = LoggerFactory.getLogger(ResTableBinaryParser.class);

	// ============================================================
	// HexFile mod: raw span model for byte-exact write-back
	// ============================================================

	/** A top-level or package-level chunk span (absolute file offsets). */
	public static final class ChunkSpan {
		public final int type;
		public final long start;
		public final long end;

		public ChunkSpan(int type, long start, long end) {
			this.type = type;
			this.start = start;
			this.end = end;
		}
	}

	/** One type chunk (0x0201) with its entry spans by entry index. */
	public static final class TypeChunk {
		public final long start;
		public final long end;
		public final int typeId;
		public final String typeName;
		public final HashMap<Integer, EntrySpan> entries = new HashMap<>();

		public TypeChunk(long start, long end, int typeId, String typeName) {
			this.start = start;
			this.end = end;
			this.typeId = typeId;
			this.typeName = typeName;
		}
	}

	/**
	 * Span of one resource entry inside its type chunk.
	 * dataPos/keyPos are absolute file offsets of the 4-byte (2-byte for compact key) fields
	 * that the writer patches when the user edits a value or renames a key.
	 */
	public static final class EntrySpan {
		public final int entryId;
		public final long start;
		public final long end;
		public final long dataPos; // simple/compact value data field, -1 if none
		public final long keyPos; // 4-byte key index field, -1 if none
		public final long compactKeyPos; // 2-byte key in size field (compact entries), -1 if none
		public final boolean complex;
		public final List<Long> mapDataPoses; // complex entries: data field of each named value
		public final TypeChunk typeChunk;

		public EntrySpan(int entryId, long start, long end, long dataPos, long keyPos,
				long compactKeyPos, boolean complex, List<Long> mapDataPoses, TypeChunk typeChunk) {
			this.entryId = entryId;
			this.start = start;
			this.end = end;
			this.dataPos = dataPos;
			this.keyPos = keyPos;
			this.compactKeyPos = compactKeyPos;
			this.complex = complex;
			this.mapDataPoses = mapDataPoses;
			this.typeChunk = typeChunk;
		}
	}

	/** Package chunk span: raw header, both string pools and the ordered inner chunks. */
	public static final class PkgSpan {
		public final long start;
		public final long end;
		public final int headerSize;
		public final int id;
		public final String name;
		public long typeStringsStart = -1;
		public long typeStringsEnd = -1;
		public long keyStringsStart = -1;
		public long keyStringsEnd = -1;
		public BinaryXMLStrings typeStrings; // HexFile mod: parsed pool objects
		public BinaryXMLStrings keyStrings; // HexFile mod: parsed pool objects
		public final List<ChunkSpan> chunks = new ArrayList<>();
		public final List<TypeChunk> typeChunks = new ArrayList<>();

		public PkgSpan(long start, long end, int headerSize, int id, String name) {
			this.start = start;
			this.end = end;
			this.headerSize = headerSize;
			this.id = id;
			this.name = name;
		}
	}

	private static final class PackageChunk {
		private final int id;
		private final String name;
		private final BinaryXMLStrings typeStrings;
		private final BinaryXMLStrings keyStrings;

		private PackageChunk(int id, String name, BinaryXMLStrings typeStrings, BinaryXMLStrings keyStrings) {
			this.id = id;
			this.name = name;
			this.typeStrings = typeStrings;
			this.keyStrings = keyStrings;
		}

		public int getId() {
			return id;
		}

		public String getName() {
			return name;
		}

		public BinaryXMLStrings getTypeStrings() {
			return typeStrings;
		}

		public BinaryXMLStrings getKeyStrings() {
			return keyStrings;
		}
	}

	// HexFile mod: original file bytes + recorded spans (for write-back)
	private byte[] raw;
	public final List<ChunkSpan> topChunks = new ArrayList<>();
	public final List<PkgSpan> packages = new ArrayList<>();
	private final IdentityHashMap<ResourceEntry, EntrySpan> entrySpans = new IdentityHashMap<>();
	private PkgSpan curPkg;
	private TypeChunk curType;

	private ResourceStorage resStorage;
	private BinaryXMLStrings strings;
	private String baseFileName = "";

	public ResTableBinaryParser() {
	}

	public void setBaseFileName(String fileName) {
		this.baseFileName = fileName;
	}

	public String getBaseFileName() {
		return baseFileName;
	}

	/** HexFile mod: parse from a byte[] (kept in memory for raw write-back). */
	public void decode(byte[] data) throws IOException {
		this.raw = data;
		decode(new ByteArrayInputStream(data));
	}

	public void decode(InputStream inputStream) throws IOException {
		long start = System.currentTimeMillis();
		is = new ParserStream(new java.io.BufferedInputStream(inputStream, 32768));
		resStorage = new ResourceStorage();
		decodeTableChunk();
		resStorage.finish();
		if (LOG.isDebugEnabled()) {
			LOG.debug("Resource table parsed: size: {}, time: {}ms",
					resStorage.size(), System.currentTimeMillis() - start);
		}
	}

	/** HexFile mod: entry -> raw span lookup used by the writer. */
	public EntrySpan getEntrySpan(ResourceEntry entry) {
		return entrySpans.get(entry);
	}

	void decodeTableChunk() throws IOException {
		is.checkInt16(RES_TABLE_TYPE, "Not a table chunk");
		is.checkInt16(0x000c, "Unexpected table header size");
		int size = is.readInt32();
		int pkgCount = is.readInt32();

		int pkgNum = 0;
		while (is.getPos() < size) {
			long chuckStart = is.getPos();
			int type = is.readInt16();
			int headerSize = is.readInt16();
			long chunkSize = is.readUInt32();
			long chunkEnd = chuckStart + chunkSize;
			topChunks.add(new ChunkSpan(type, chuckStart, chunkEnd)); // HexFile mod
			switch (type) {
				case RES_NULL_TYPE:
					// skip
					break;

				case RES_STRING_POOL_TYPE:
					strings = parseStringPoolNoSize(chuckStart, chunkEnd);
					break;

				case RES_TABLE_PACKAGE_TYPE:
					parsePackage(chuckStart, headerSize, chunkEnd);
					pkgNum++;
					break;
			}
			is.skipToPos(chunkEnd, "Skip to table chunk end");
		}
		if (pkgNum != pkgCount) {
			LOG.warn("Unexpected package chunks, read: {}, expected: {}", pkgNum, pkgCount);
		}
	}

	private void parsePackage(long pkgChunkStart, int headerSize, long pkgChunkEnd) throws IOException {
		if (headerSize < 0x011c) {
			die("Package header size too small");
			return;
		}
		int id = is.readInt32();
		String name = is.readString16Fixed(128);
		long typeStringsOffset = pkgChunkStart + is.readInt32();
		int lastPublicType = is.readInt32();
		long keyStringsOffset = pkgChunkStart + is.readInt32();
		int lastPublicKey = is.readInt32();
		if (headerSize >= 0x0120) {
			int typeIdOffset = is.readInt32();
		}
		is.skipToPos(pkgChunkStart + headerSize, "package header end");

		PkgSpan pkgSpan = new PkgSpan(pkgChunkStart, pkgChunkEnd, headerSize, id, name); // HexFile mod
		packages.add(pkgSpan);
		curPkg = pkgSpan;

		BinaryXMLStrings typeStrings = null;
		if (typeStringsOffset != 0) {
			is.skipToPos(typeStringsOffset, "Expected typeStrings string pool");
			pkgSpan.typeStringsStart = is.getPos(); // HexFile mod
			typeStrings = parseStringPool();
			pkgSpan.typeStringsEnd = is.getPos(); // HexFile mod
		}
		BinaryXMLStrings keyStrings = null;
		if (keyStringsOffset != 0) {
			is.skipToPos(keyStringsOffset, "Expected keyStrings string pool");
			pkgSpan.keyStringsStart = is.getPos(); // HexFile mod
			keyStrings = parseStringPool();
			pkgSpan.keyStringsEnd = is.getPos(); // HexFile mod
		}
		pkgSpan.typeStrings = typeStrings; // HexFile mod
		pkgSpan.keyStrings = keyStrings; // HexFile mod

		PackageChunk pkg = new PackageChunk(id, name, typeStrings, keyStrings);
		resStorage.setAppPackage(name);

		while (is.getPos() < pkgChunkEnd) {
			long chunkStart = is.getPos();
			int type = is.readInt16();
			LOG.trace("res package chunk start at {} type {}", chunkStart, type);
			switch (type) {
				case RES_NULL_TYPE:
					LOG.info("Null chunk type encountered at offset {}", chunkStart);
					break;
				case RES_TABLE_TYPE_TYPE: // 0x0201
					parseTypeChunk(chunkStart, pkg);
					break;
				case RES_TABLE_TYPE_SPEC_TYPE: // 0x0202
					parseTypeSpecChunk(chunkStart);
					break;
				case RES_TABLE_TYPE_LIBRARY: // 0x0203
					parseLibraryTypeChunk(chunkStart);
					break;
				case RES_TABLE_TYPE_OVERLAY: // 0x0204
					parseOverlayTypeChunk(chunkStart);
					break;
				case RES_TABLE_TYPE_OVERLAY_POLICY: // 0x0205
					throw new IOException(
							String.format("Encountered unsupported chunk type RES_TABLE_TYPE_OVERLAY_POLICY at offset 0x%x ", chunkStart));
				case RES_TABLE_TYPE_STAGED_ALIAS: // 0x0206
					parseStagedAliasChunk(chunkStart);
					break;
				default:
					LOG.warn("Unknown chunk type {} encountered at offset {}", type, chunkStart);
			}
		}
		curPkg = null; // HexFile mod
	}

	@SuppressWarnings("unused")
	private void parseTypeSpecChunk(long chunkStart) throws IOException {
		is.checkInt16(0x0010, "Unexpected type spec header size");
		int chunkSize = is.readInt32();
		long expectedEndPos = chunkStart + chunkSize;
		curPkg.chunks.add(new ChunkSpan(RES_TABLE_TYPE_SPEC_TYPE, chunkStart, expectedEndPos)); // HexFile mod

		int id = is.readInt8();
		is.skip(3);
		int entryCount = is.readInt32();
		for (int i = 0; i < entryCount; i++) {
			int entryFlag = is.readInt32();
		}
		if (is.getPos() != expectedEndPos) {
			throw new IOException(String.format("Error reading type spec chunk at offset 0x%x", chunkStart));
		}
	}

	private void parseLibraryTypeChunk(long chunkStart) throws IOException {
		LOG.trace("parsing library type chunk starting at offset {}", chunkStart);
		is.checkInt16(12, "Unexpected header size");
		int chunkSize = is.readInt32();
		long expectedEndPos = chunkStart + chunkSize;
		curPkg.chunks.add(new ChunkSpan(RES_TABLE_TYPE_LIBRARY, chunkStart, expectedEndPos)); // HexFile mod
		int count = is.readInt32();
		for (int i = 0; i < count; i++) {
			int packageId = is.readInt32();
			String packageName = is.readString16Fixed(128);
			LOG.info("Found resource shared library {}, pkgId: {}", packageName, packageId);
			if (is.getPos() > expectedEndPos) {
				throw new IOException("reading after chunk end");
			}
		}
		if (is.getPos() != expectedEndPos) {
			throw new IOException(String.format("Error reading library chunk at offset 0x%x", chunkStart));
		}
	}

	/**
	 * Parse an <code>ResTable_type</code> (except for the 2 bytes <code>uint16_t</code>
	 * from <code>ResChunk_header</code>).
	 * <p>
	 * See: <a href=
	 * "https://github.com/aosp-mirror/platform_frameworks_base/blob/master/libs/androidfw/include/androidfw/ResourceTypes.h">ResourceTypes.h</a>
	 */
	private void parseTypeChunk(long start, PackageChunk pkg) throws IOException {
		/* int headerSize = */
		is.readInt16();
		/* int size = */
		long chunkSize = is.readUInt32();
		long chunkEnd = start + chunkSize;
		is.mark((int) chunkSize);

		// The type identifier this chunk is holding. Type IDs start at 1 (corresponding
		// to the value of the type bits in a resource identifier). 0 is invalid.
		int typeId = is.readInt8();
		String typeName = pkg.getTypeStrings().get(typeId - 1);

		int flags = is.readInt8();
		boolean isSparse = (flags & FLAG_SPARSE) != 0;
		boolean isOffset16 = (flags & FLAG_OFFSET16) != 0;

		is.readInt16(); // ignore reserved value - should be zero but in some apps it is not zero; see #2402
		int entryCount = is.readInt32();
		long entriesStart = start + is.readInt32();

		EntryConfig config = parseConfig();

		if (config.isInvalid) {
			LOG.warn("Invalid config flags detected: {}{}", typeName, config.getQualifiers());
		}

		List<EntryOffset> offsets = new ArrayList<>(entryCount);
		if (isSparse) {
			for (int i = 0; i < entryCount; i++) {
				int idx = is.readInt16();
				int offset = is.readInt16() * 4; // The offset in ResTable_sparseTypeEntry::offset is stored divided by 4.
				offsets.add(new EntryOffset(idx, offset));
			}
		} else if (isOffset16) {
			for (int i = 0; i < entryCount; i++) {
				int offset = is.readInt16();
				if (offset != 0xFFFF) {
					offsets.add(new EntryOffset(i, offset * 4));
				}
			}
		} else {
			for (int i = 0; i < entryCount; i++) {
				offsets.add(new EntryOffset(i, is.readInt32()));
			}
		}
		is.skipToPos(entriesStart, "Failed to skip to entries start");

		TypeChunk tc = new TypeChunk(start, chunkEnd, typeId, typeName); // HexFile mod
		curPkg.chunks.add(new ChunkSpan(RES_TABLE_TYPE_TYPE, start, chunkEnd)); // HexFile mod
		curPkg.typeChunks.add(tc); // HexFile mod
		curType = tc; // HexFile mod

		int ignoredEoc = 0; // ignored entries because they are located after end of chunk
		Set<Integer> processedIndices = new HashSet<>(offsets.size() * 2);
		for (EntryOffset entryOffset : offsets) {
			int offset = entryOffset.getOffset();
			if (offset == NO_ENTRY) {
				continue;
			}
			int index = entryOffset.getIdx();
			if (isSparse && !processedIndices.add(index)) {
				// Sometimes sparse type chunks contain multiple entries with the same index.
				// If we have processed the index once, we assume can ignore other entries with the same index.
				continue;
			}
			long entryStartOffset = entriesStart + offset;
			if (entryStartOffset >= chunkEnd) {
				// Certain resource obfuscated apps like com.facebook.orca have more entries defined
				// than actually fit into the chunk size -> ignore this entry
				ignoredEoc++;
				// LOG.debug("Pos is after chunk end: {} end {}", entryStartOffset, chunkEnd);
				continue;
			}
			if (entryStartOffset < is.getPos()) {
				// workaround for issue #2343: if the entryStartOffset is located before our current position
				is.reset();
			}
			is.skipToPos(entryStartOffset, "Expected start of entry " + index);
			parseEntry(pkg, typeId, index, config.getQualifiers());
		}
		if (ignoredEoc > 0) {
			// invalid = data offset is after the chunk end
			LOG.warn("{} entries of type {} has been ignored (invalid offset)", ignoredEoc, typeName);
		}
		is.skipToPos(chunkEnd, "End of chunk");
		curType = null; // HexFile mod
	}

	private static final class EntryOffset {
		private final int idx;
		private final int offset;

		private EntryOffset(int idx, int offset) {
			this.idx = idx;
			this.offset = offset;
		}

		public int getIdx() {
			return idx;
		}

		public int getOffset() {
			return offset;
		}

		@Override
		public String toString() {
			return "EntryOffset[idx=" + idx + ", offset=" + offset + ']';
		}
	}

	private void parseOverlayTypeChunk(long chunkStart) throws IOException {
		LOG.trace("parsing overlay type chunk starting at offset {}", chunkStart);
		// read ResTable_overlayable_header
		/* headerSize = */
		is.readInt16(); // usually 1032 bytes
		int chunkSize = is.readInt32(); // e.g. 1056 bytes
		long expectedEndPos = chunkStart + chunkSize;
		curPkg.chunks.add(new ChunkSpan(RES_TABLE_TYPE_OVERLAY, chunkStart, expectedEndPos)); // HexFile mod
		String name = is.readString16Fixed(256); // 512 bytes
		String actor = is.readString16Fixed(256); // 512 bytes
		LOG.trace("Overlay header data: name={} actor={}", name, actor);
		// skip: ResTable_overlayable_policy_header + ResTable_ref * x
		is.skipToPos(expectedEndPos, "overlay chunk end");
	}

	private void parseStagedAliasChunk(long chunkStart) throws IOException {
		LOG.trace("parsing staged alias chunk starting at offset {}", chunkStart);
		// read ResTable_staged_alias_header
		/* headerSize = */
		is.readInt16();
		int chunkSize = is.readInt32();
		long expectedEndPos = chunkStart + chunkSize;
		curPkg.chunks.add(new ChunkSpan(RES_TABLE_TYPE_STAGED_ALIAS, chunkStart, expectedEndPos)); // HexFile mod
		int count = is.readInt32();

		for (int i = 0; i < count; i++) {
			// read ResTable_staged_alias_entry
			int stagedResId = is.readInt32();
			int finalizedResId = is.readInt32();
			LOG.debug("Staged alias: stagedResId {} finalizedResId {}", stagedResId, finalizedResId);
		}
		is.skipToPos(expectedEndPos, "staged alias chunk end");
	}

	private void parseEntry(PackageChunk pkg, int typeId, int index, String config) throws IOException {
		long entryStart = is.getPos(); // HexFile mod
		int size = is.readInt16();
		int flags = is.readInt16();
		boolean isComplex = (flags & FLAG_COMPLEX) != 0;
		boolean isCompact = (flags & FLAG_COMPACT) != 0;

		int key = isCompact ? size : is.readInt32();
		if (key == -1) {
			// HexFile mod: record header-only span (no value to patch)
			recordSpan(index, entryStart, is.getPos(), -1, -1, -1, false, null, null);
			return;
		}

		// resourceID as defined in AOSP make_resid()
		int resId = pkg.getId() << 24 | typeId << 16 | index;
		String typeName = pkg.getTypeStrings().get(typeId - 1);
		String origKeyName = pkg.getKeyStrings().get(key);

		ResourceEntry newResEntry = buildResourceEntry(pkg, config, resId, typeName, origKeyName);
		if (isCompact) {
			int dataType = flags >> 8;
			long dataPos = is.getPos(); // HexFile mod
			int data = is.readInt32();
			newResEntry.setSimpleValue(new RawValue(dataType, data));
			// compact entry: u16 size(holds key) + u16 flags + u32 data = 8 bytes
			recordSpan(index, entryStart, entryStart + 8, dataPos, -1, entryStart, false, null, newResEntry);
		} else if (isComplex || size == 16) {
			int parentRef = is.readInt32();
			int count = is.readInt32();
			newResEntry.setParentRef(parentRef);
			List<RawNamedValue> values = new ArrayList<>(count);
			List<Long> mapDataPoses = new ArrayList<>(count); // HexFile mod
			for (int i = 0; i < count; i++) {
				long mapStart = is.getPos(); // HexFile mod
				int nameRef = is.readInt32();
				values.add(new RawNamedValue(nameRef, parseValue()));
				mapDataPoses.add(mapStart + 8); // HexFile mod: data field of the map entry
			}
			newResEntry.setNamedValues(values);
			recordSpan(index, entryStart, is.getPos(), -1, entryStart + 4, -1, true, mapDataPoses, newResEntry);
		} else {
			long valueStart = is.getPos(); // HexFile mod
			newResEntry.setSimpleValue(parseValue());
			recordSpan(index, entryStart, is.getPos(), valueStart + 4, entryStart + 4, -1, false, null, newResEntry);
		}
	}

	// HexFile mod: attach the raw span to the parsed entry
	private void recordSpan(int index, long start, long end, long dataPos, long keyPos,
			long compactKeyPos, boolean complex, List<Long> mapDataPoses, ResourceEntry entry) {
		if (entry == null || curType == null) {
			return;
		}
		EntrySpan es = new EntrySpan(index, start, end, dataPos, keyPos, compactKeyPos,
				complex, mapDataPoses, curType);
		curType.entries.put(index, es);
		entrySpans.put(entry, es);
	}

	private ResourceEntry buildResourceEntry(PackageChunk pkg, String config, int resId, String typeName, String origKeyName) {
		// HexFile mod: deobfuscation removed - always keep the original resource name
		ResourceEntry newResEntry = new ResourceEntry(resId, pkg.getName(), typeName, origKeyName, config);
		resStorage.add(newResEntry);
		return newResEntry;
	}

	private RawNamedValue parseValueMap() throws IOException {
		int nameRef = is.readInt32();
		return new RawNamedValue(nameRef, parseValue());
	}

	private RawValue parseValue() throws IOException {
		is.checkInt16(8, "value size");
		is.checkInt8(0, "value res0 not 0");
		int dataType = is.readInt8();
		int data = is.readInt32();
		return new RawValue(dataType, data);
	}

	private EntryConfig parseConfig() throws IOException {
		long start = is.getPos();
		int size = is.readInt32();
		if (size < 4) {
			throw new IOException("Config size < 4");
		}

		// Android zero fill this structure and only read the data present
		byte[] configData = new byte[Math.max(52, size - 4)];
		is.readFully(configData, 0, size - 4);
		ParserStream configIs = new ParserStream(new ByteArrayInputStream(configData));

		short mcc = (short) configIs.readInt16();
		short mnc = (short) configIs.readInt16();

		char[] language = unpackLocaleOrRegion((byte) configIs.readInt8(), (byte) configIs.readInt8(), 'a');
		char[] country = unpackLocaleOrRegion((byte) configIs.readInt8(), (byte) configIs.readInt8(), '0');

		byte orientation = (byte) configIs.readInt8();
		byte touchscreen = (byte) configIs.readInt8();
		int density = configIs.readInt16();

		byte keyboard = (byte) configIs.readInt8();
		byte navigation = (byte) configIs.readInt8();
		byte inputFlags = (byte) configIs.readInt8();
		byte grammaticalInflection = (byte) configIs.readInt8();

		short screenWidth = (short) configIs.readInt16();
		short screenHeight = (short) configIs.readInt16();

		short sdkVersion = (short) configIs.readInt16();
		configIs.readInt16(); // minorVersion must always be 0

		byte screenLayout = (byte) configIs.readInt8();
		byte uiMode = (byte) configIs.readInt8();
		short smallestScreenWidthDp = (short) configIs.readInt16();
		short screenWidthDp = (short) configIs.readInt16();
		short screenHeightDp = (short) configIs.readInt16();

		char[] localeScript = readScriptOrVariantChar(4, configIs).toCharArray();
		char[] localeVariant = readScriptOrVariantChar(8, configIs).toCharArray();

		byte screenLayout2 = (byte) configIs.readInt8();
		byte colorMode = (byte) configIs.readInt8();
		configIs.readInt16(); // reserved padding

		is.checkPos(start + size, "Config skip trailing bytes");

		return new EntryConfig(mcc, mnc, language, country,
				orientation, touchscreen, density, keyboard, navigation,
				inputFlags, grammaticalInflection, screenWidth, screenHeight, sdkVersion,
				screenLayout, uiMode, smallestScreenWidthDp, screenWidthDp,
				screenHeightDp,
				localeScript.length == 0 ? null : localeScript,
				localeVariant.length == 0 ? null : localeVariant,
				screenLayout2,
				colorMode, false, size);
	}

	private char[] unpackLocaleOrRegion(byte in0, byte in1, char base) {
		// check high bit, if so we have a packed 3 letter code
		if (((in0 >> 7) & 1) == 1) {
			int first = in1 & 0x1F;
			int second = ((in1 & 0xE0) >> 5) + ((in0 & 0x03) << 3);
			int third = (in0 & 0x7C) >> 2;

			// since this function handles languages & regions, we add the value(s) to the base char
			// which is usually 'a' or '0' depending on language and region.
			return new char[] { (char) (first + base), (char) (second + base), (char) (third + base) };
		}
		return new char[] { (char) in0, (char) in1 };
	}

	private String readScriptOrVariantChar(int length) throws IOException {
		return readScriptOrVariantChar(length, is);
	}

	private static String readScriptOrVariantChar(int length, ParserStream ps) throws IOException {
		long start = ps.getPos();
		StringBuilder sb = new StringBuilder(16);
		for (int i = 0; i < length; i++) {
			short ch = (short) ps.readInt8();
			if (ch == 0) {
				break;
			}
			sb.append((char) ch);
		}
		ps.skipToPos(start + length, "readScriptOrVariantChar");
		return sb.toString();
	}

	public ResourceStorage getResStorage() {
		return resStorage;
	}

	public BinaryXMLStrings getStrings() {
		return strings;
	}

	public byte[] getRaw() {
		return raw;
	}
}
