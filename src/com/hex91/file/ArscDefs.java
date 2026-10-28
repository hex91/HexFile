package com.hex91.file;

/**
 * resources.arsc structure constants (AOSP ResourceTypes.h) and little-endian helpers.
 * Values mirror jadx's ParserConstants; duplicated here so the writer stays independent
 * of the vendored package visibility.
 */
public final class ArscDefs {

	private ArscDefs() {
	}

	// chunk types
	public static final int RES_NULL_TYPE = 0x0000;
	public static final int RES_STRING_POOL_TYPE = 0x0001;
	public static final int RES_TABLE_TYPE = 0x0002;
	public static final int RES_TABLE_PACKAGE_TYPE = 0x0200;
	public static final int RES_TABLE_TYPE_TYPE = 0x0201;
	public static final int RES_TABLE_TYPE_SPEC_TYPE = 0x0202;
	public static final int RES_TABLE_TYPE_LIBRARY = 0x0203;
	public static final int RES_TABLE_TYPE_OVERLAY = 0x0204;
	public static final int RES_TABLE_TYPE_STAGED_ALIAS = 0x0206;

	// string pool flags
	public static final int SORTED_FLAG = 1;
	public static final int UTF8_FLAG = 1 << 8;

	// value data types
	public static final int TYPE_NULL = 0x00;
	public static final int TYPE_REFERENCE = 0x01;
	public static final int TYPE_ATTRIBUTE = 0x02;
	public static final int TYPE_STRING = 0x03;
	public static final int TYPE_FLOAT = 0x04;
	public static final int TYPE_DIMENSION = 0x05;
	public static final int TYPE_FRACTION = 0x06;
	public static final int TYPE_DYNAMIC_REFERENCE = 0x07;
	public static final int TYPE_DYNAMIC_ATTRIBUTE = 0x08;
	public static final int TYPE_INT_DEC = 0x10;
	public static final int TYPE_INT_HEX = 0x11;
	public static final int TYPE_INT_BOOLEAN = 0x12;
	public static final int TYPE_INT_COLOR_ARGB8 = 0x1c;
	public static final int TYPE_INT_COLOR_RGB8 = 0x1d;
	public static final int TYPE_INT_COLOR_ARGB4 = 0x1e;
	public static final int TYPE_INT_COLOR_RGB4 = 0x1f;

	// entry flags
	public static final int FLAG_COMPLEX = 0x0001;
	public static final int FLAG_COMPACT = 0x0008;

	public static final int NO_ENTRY = 0xFFFFFFFF;

	// package header field offsets (relative to package chunk start)
	public static final int PKG_TYPESTRINGS_OFF = 268;
	public static final int PKG_KEYSTRINGS_OFF = 276;

	// ------------------------------------------------------------
	// little-endian helpers
	// ------------------------------------------------------------

	public static int u16(byte[] d, int p) {
		return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
	}

	public static int u32(byte[] d, int p) {
		return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8)
				| ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24);
	}

	public static void putU16(byte[] d, int p, int v) {
		d[p] = (byte) v;
		d[p + 1] = (byte) (v >>> 8);
	}

	public static void putU32(byte[] d, int p, int v) {
		d[p] = (byte) v;
		d[p + 1] = (byte) (v >>> 8);
		d[p + 2] = (byte) (v >>> 16);
		d[p + 3] = (byte) (v >>> 24);
	}
}
