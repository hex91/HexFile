package com.hex91.file;

import java.util.List;
import java.util.Map;

import jadx.core.xmlgen.entry.RawNamedValue;
import jadx.core.xmlgen.entry.RawValue;
import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * Display + input parsing for ARSC entry values (presentation only, no binary parsing).
 */
public final class ArscValues {

	private ArscValues() {
	}

	private static final String[] DIMENSION_UNITS = { "px", "dip", "sp", "pt", "in", "mm" };
	private static final String[] FRACTION_UNITS = { "%", "%p" };
	// AOSP ResourceTypes.cpp: MANTISSA_MULT = 1/2^8, then /2^7, /2^15, /2^23
	private static final double[] RADIX_MULTS = {
			1.0 / (1 << 8),
			1.0 / (1 << 15),
			1.0 / (1 << 23),
			1.0 / (1 << 31)
	};

	public static String resIdStr(int id) {
		return String.format("0x%08x", id);
	}

	// ------------------------------------------------------------
	// display
	// ------------------------------------------------------------

	public static String valueDisplay(ArscFile a, ResourceEntry e) {
		if (e.getNamedValues() != null) {
			return bagDisplay(a, e);
		}
		RawValue v = e.getSimpleValue();
		if (v == null) {
			return "";
		}
		int t = v.getDataType();
		int d = v.getData();
		switch (t) {
			case ArscDefs.TYPE_STRING:
				return a.globalPool.get(d);
			case ArscDefs.TYPE_REFERENCE:
			case ArscDefs.TYPE_ATTRIBUTE:
			case ArscDefs.TYPE_DYNAMIC_REFERENCE:
			case ArscDefs.TYPE_DYNAMIC_ATTRIBUTE:
				return "@" + resIdStr(d) + nameSuffix(a, d);
			case ArscDefs.TYPE_INT_DEC:
				return String.valueOf(d);
			case ArscDefs.TYPE_INT_HEX:
				return "0x" + Integer.toHexString(d);
			case ArscDefs.TYPE_INT_BOOLEAN:
				return d != 0 ? "true" : "false";
			case ArscDefs.TYPE_INT_COLOR_ARGB8:
				return String.format("#%08x", d);
			case ArscDefs.TYPE_INT_COLOR_RGB8:
				return String.format("#%06x", d & 0xFFFFFF);
			case ArscDefs.TYPE_INT_COLOR_ARGB4:
				return String.format("#%04x", d & 0xFFFF);
			case ArscDefs.TYPE_INT_COLOR_RGB4:
				return String.format("#%03x", d & 0xFFF);
			case ArscDefs.TYPE_FLOAT:
				return String.valueOf(Float.intBitsToFloat(d));
			case ArscDefs.TYPE_DIMENSION:
				return complexStr(d, true);
			case ArscDefs.TYPE_FRACTION:
				return complexStr(d, false);
			case ArscDefs.TYPE_NULL:
				return "@null";
			default:
				return "0x" + Integer.toHexString(d);
		}
	}

	private static String nameSuffix(ArscFile a, int id) {
		Map<Integer, String> names = a.storage.getResourcesNames();
		String n = names.get(Integer.valueOf(id));
		return n == null ? "" : " (" + n + ")";
	}

	public static String bagDisplay(ArscFile a, ResourceEntry e) {
		StringBuilder sb = new StringBuilder();
		sb.append("(bag ");
		if (e.getParentRef() != 0) {
			sb.append("parent=").append(resIdStr(e.getParentRef())).append(' ');
		}
		sb.append(e.getNamedValues().size()).append(" 项)");
		int shown = 0;
		for (RawNamedValue nv : e.getNamedValues()) {
			if (shown >= 4) {
				sb.append(" …");
				break;
			}
			String n = a.globalPool.get(nv.getNameRef());
			sb.append('\n').append(n == null ? ("#" + nv.getNameRef()) : n)
					.append(" = ").append(rawDisplay(a, nv.getRawValue()));
			shown++;
		}
		return sb.toString();
	}

	public static String rawDisplay(ArscFile a, RawValue v) {
		int t = v.getDataType();
		int d = v.getData();
		switch (t) {
			case ArscDefs.TYPE_STRING:
				return a.globalPool.get(d);
			case ArscDefs.TYPE_REFERENCE:
			case ArscDefs.TYPE_DYNAMIC_REFERENCE:
				return "@" + resIdStr(d);
			case ArscDefs.TYPE_INT_BOOLEAN:
				return d != 0 ? "true" : "false";
			case ArscDefs.TYPE_INT_DEC:
				return String.valueOf(d);
			case ArscDefs.TYPE_INT_HEX:
				return "0x" + Integer.toHexString(d);
			case ArscDefs.TYPE_INT_COLOR_ARGB8:
				return String.format("#%08x", d);
			case ArscDefs.TYPE_FLOAT:
				return String.valueOf(Float.intBitsToFloat(d));
			default:
				return "0x" + Integer.toHexString(d) + " (t=0x" + Integer.toHexString(t) + ")";
		}
	}

	public static String namedValueName(ArscFile a, RawNamedValue nv) {
		String n = a.globalPool.get(nv.getNameRef());
		return n == null ? ("#" + nv.getNameRef()) : n;
	}

	private static String complexStr(int data, boolean isDimension) {
		int unit = data & 0xf;
		int radix = (data >> 4) & 0x3;
		int mantissa = (data << 8) >> 8; // sign-extended 24-bit
		double value = mantissa * RADIX_MULTS[radix];
		String unitStr;
		if (isDimension) {
			unitStr = unit < DIMENSION_UNITS.length ? DIMENSION_UNITS[unit] : ("unit" + unit);
		} else {
			unitStr = unit < FRACTION_UNITS.length ? FRACTION_UNITS[unit] : ("unit" + unit);
		}
		return trimNum(value) + unitStr;
	}

	private static String trimNum(double v) {
		if (v == Math.rint(v)) {
			return String.valueOf((long) v);
		}
		return String.valueOf(v);
	}

	// ------------------------------------------------------------
	// input parsing (returns null when the input is not acceptable)
	// ------------------------------------------------------------

	/** Parse user text into a raw int for the given data type; null = unsupported/invalid. */
	public static Integer parseInput(int dataType, String input, ArscFile a) {
		String s = input.trim();
		try {
			switch (dataType) {
				case ArscDefs.TYPE_STRING:
					return null; // handled by the caller (string edit)
				case ArscDefs.TYPE_REFERENCE:
				case ArscDefs.TYPE_DYNAMIC_REFERENCE:
				case ArscDefs.TYPE_ATTRIBUTE:
				case ArscDefs.TYPE_DYNAMIC_ATTRIBUTE:
					return parseRef(s, a);
				case ArscDefs.TYPE_INT_DEC:
					return Integer.valueOf((int) Long.parseLong(s));
				case ArscDefs.TYPE_INT_HEX:
					return Integer.valueOf((int) Long.parseLong(s.replace("0x", "").replace("0X", ""), 16));
				case ArscDefs.TYPE_INT_BOOLEAN:
					if (s.equalsIgnoreCase("true") || s.equals("1")) return Integer.valueOf(1);
					if (s.equalsIgnoreCase("false") || s.equals("0")) return Integer.valueOf(0);
					return null;
				case ArscDefs.TYPE_INT_COLOR_ARGB8:
				case ArscDefs.TYPE_INT_COLOR_RGB8:
					return parseColor(s, 8);
				case ArscDefs.TYPE_INT_COLOR_ARGB4:
				case ArscDefs.TYPE_INT_COLOR_RGB4:
					return parseColor(s, 4);
				case ArscDefs.TYPE_FLOAT:
					return Integer.valueOf(Float.floatToIntBits(Float.parseFloat(s)));
				default:
					return null;
			}
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Integer parseRef(String s, ArscFile a) {
		String t = s.startsWith("@") ? s.substring(1) : s;
		Map<Integer, String> names = a.storage.getResourcesNames();
		if (t.startsWith("0x") || t.startsWith("0X")) {
			return Integer.valueOf((int) Long.parseLong(t.substring(2), 16));
		}
		// @type/name
		int slash = t.indexOf('/');
		if (slash > 0) {
			String type = t.substring(0, slash);
			String key = t.substring(slash + 1);
			for (Map.Entry<Integer, String> en : names.entrySet()) {
				if (en.getValue().equals(type + '/' + key)) {
					return en.getKey();
				}
			}
		}
		return null;
	}

	private static Integer parseColor(String s, int digits) {
		String h = s.startsWith("#") ? s.substring(1) : s;
		if (h.length() != digits) {
			return null;
		}
		return Integer.valueOf((int) Long.parseLong(h, 16));
	}

	/** True when the value of this entry can be edited through the dialog. */
	public static boolean editable(ResourceEntry e) {
		if (e.getNamedValues() != null) {
			return true; // bag: individual named values are editable
		}
		RawValue v = e.getSimpleValue();
		if (v == null) {
			return false;
		}
		switch (v.getDataType()) {
			case ArscDefs.TYPE_STRING:
			case ArscDefs.TYPE_REFERENCE:
			case ArscDefs.TYPE_ATTRIBUTE:
			case ArscDefs.TYPE_DYNAMIC_REFERENCE:
			case ArscDefs.TYPE_DYNAMIC_ATTRIBUTE:
			case ArscDefs.TYPE_INT_DEC:
			case ArscDefs.TYPE_INT_HEX:
			case ArscDefs.TYPE_INT_BOOLEAN:
			case ArscDefs.TYPE_INT_COLOR_ARGB8:
			case ArscDefs.TYPE_INT_COLOR_RGB8:
			case ArscDefs.TYPE_INT_COLOR_ARGB4:
			case ArscDefs.TYPE_INT_COLOR_RGB4:
			case ArscDefs.TYPE_FLOAT:
				return true;
			default:
				return false;
		}
	}

	public static boolean isStringValue(RawValue v) {
		return v != null && v.getDataType() == ArscDefs.TYPE_STRING;
	}

	public static List<RawNamedValue> namedValues(ResourceEntry e) {
		return e.getNamedValues();
	}

	public static int stringIndexOf(List<String> pool, String s) {
		for (int i = 0; i < pool.size(); i++) {
			String p = pool.get(i);
			if (p != null && p.equals(s)) {
				return i;
			}
		}
		return -1;
	}

	public static Map<Integer, String> idNameMap(ArscFile a) {
		return a.storage.getResourcesNames();
	}
}
