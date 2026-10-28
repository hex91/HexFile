package com.hex91.file;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jadx.core.xmlgen.BinaryXMLStrings;
import jadx.core.xmlgen.ResTableBinaryParser;
import jadx.core.xmlgen.ResTableBinaryParser.ChunkSpan;
import jadx.core.xmlgen.ResTableBinaryParser.EntrySpan;
import jadx.core.xmlgen.ResTableBinaryParser.PkgSpan;
import jadx.core.xmlgen.ResTableBinaryParser.TypeChunk;
import jadx.core.xmlgen.ResourceStorage;
import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * ARSC (resources.arsc) file model built on the vendored jadx binary parser.
 *
 * <p>Pure Java (no Android APIs) so it can be unit-tested on a desktop JVM.
 * Holds the original bytes plus decoded entries; edits are applied by {@link ArscWriter}.
 */
public final class ArscFile {

	public byte[] raw;
	public BinaryXMLStrings globalPool;
	public ResourceStorage storage;
	public List<ChunkSpan> topChunks;
	public List<PkgSpan> packages;
	private final ResTableBinaryParser parser;

	/** reverse lookup caches for pool de-duplication when editing */
	private List<String> globalStrings;
	private final Map<Integer, List<String>> pkgKeyStrings = new HashMap<Integer, List<String>>();

	private ArscFile(ResTableBinaryParser parser) {
		this.parser = parser;
		this.raw = parser.getRaw();
		this.globalPool = parser.getStrings();
		this.storage = parser.getResStorage();
		this.topChunks = parser.topChunks;
		this.packages = parser.packages;
	}

	public static ArscFile parse(byte[] data) throws IOException {
		ResTableBinaryParser p = new ResTableBinaryParser();
		p.decode(data);
		return new ArscFile(p);
	}

	public static ArscFile parse(InputStream in) throws IOException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		byte[] buf = new byte[65536];
		int n;
		while ((n = in.read(buf)) > 0) {
			bo.write(buf, 0, n);
		}
		in.close();
		return parse(bo.toByteArray());
	}

	public static ArscFile parse(File f) throws IOException {
		FileInputStream in = new FileInputStream(f);
		try {
			return parse(in);
		} finally {
			in.close();
		}
	}

	public EntrySpan spanOf(ResourceEntry entry) {
		return parser.getEntrySpan(entry);
	}

	public TypeChunk typeChunkOf(ResourceEntry entry) {
		EntrySpan es = spanOf(entry);
		return es == null ? null : es.typeChunk;
	}

	public PkgSpan pkgOf(ResourceEntry entry) {
		for (PkgSpan p : packages) {
			if (p.name.equals(entry.getPkgName())) {
				return p;
			}
		}
		return packages.isEmpty() ? null : packages.get(0);
	}

	public String appPackage() {
		return storage.getAppPackage();
	}

	public int entryCount() {
		return storage.size();
	}

	// ------------------------------------------------------------
	// string pool helpers (used by the writer for append/dedup)
	// ------------------------------------------------------------

	/** All strings of the global value pool (index = position). */
	public List<String> globalStrings() {
		if (globalStrings == null) {
			globalStrings = new ArrayList<String>();
			if (globalPool != null) {
				int n = globalPool.size();
				for (int i = 0; i < n; i++) {
					globalStrings.add(globalPool.get(i));
				}
			}
		}
		return globalStrings;
	}

	public int globalPoolSize() {
		return globalPool == null ? 0 : globalPool.size();
	}

	/** All key strings of a package's key pool (index = position). */
	public List<String> keyStrings(String pkgName) {
		for (PkgSpan p : packages) {
			if (p.name.equals(pkgName)) {
				Integer k = Integer.valueOf(p.id);
				List<String> cached = pkgKeyStrings.get(k);
				if (cached == null) {
					cached = new ArrayList<String>();
					BinaryXMLStrings pool = keyPoolOf(p);
					if (pool != null) {
						int n = pool.size();
						for (int i = 0; i < n; i++) {
							cached.add(pool.get(i));
						}
					}
					pkgKeyStrings.put(k, cached);
				}
				return cached;
			}
		}
		return new ArrayList<String>();
	}

	public BinaryXMLStrings keyPoolOf(PkgSpan p) {
		return p.keyStrings;
	}

	public BinaryXMLStrings typePoolOf(PkgSpan p) {
		return p.typeStrings;
	}

	/** Raw span of the global value string pool (first RES_STRING_POOL_TYPE chunk). */
	public ChunkSpan globalPoolSpan() {
		for (ChunkSpan c : topChunks) {
			if (c.type == ArscDefs.RES_STRING_POOL_TYPE) {
				return c;
			}
		}
		return null;
	}

	public boolean globalPoolUtf8() {
		ChunkSpan c = globalPoolSpan();
		if (c == null) {
			return false;
		}
		int flags = ArscDefs.u32(raw, (int) c.start + 16);
		return (flags & ArscDefs.UTF8_FLAG) != 0;
	}
}
