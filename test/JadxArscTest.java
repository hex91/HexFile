import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.hex91.file.ArscDefs;
import com.hex91.file.ArscFile;
import com.hex91.file.ArscWriter;
import com.hex91.file.ArscWriter.Edit;
import jadx.core.xmlgen.BinaryXMLStrings;
import jadx.core.xmlgen.entry.RawNamedValue;
import jadx.core.xmlgen.entry.RawValue;
import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * Desktop closed-loop test for the jadx-based ARSC engine:
 * parse (4 samples) -> byte-exact round-trip -> string/rename/raw/bag edits -> re-parse verify.
 */
public class JadxArscTest {

	static int pass = 0;
	static int fail = 0;

	static void check(String name, boolean ok) {
		if (ok) {
			pass++;
			System.out.println("PASS  " + name);
		} else {
			fail++;
			System.out.println("FAIL  " + name);
		}
	}

	static byte[] read(File f) throws Exception {
		java.io.FileInputStream in = new java.io.FileInputStream(f);
		byte[] b = new byte[(int) f.length()];
		in.read(b);
		in.close();
		return b;
	}

	static String strVal(ArscFile a, ResourceEntry e) {
		RawValue v = e.getSimpleValue();
		if (v == null) {
			return null;
		}
		if (v.getDataType() == ArscDefs.TYPE_STRING) {
			return a.globalPool.get(v.getData());
		}
		return null;
	}

	static ResourceEntry firstStringEntry(ArscFile a) {
		for (ResourceEntry e : a.storage.getResources()) {
			if (e.getNamedValues() == null && e.getSimpleValue() != null
					&& e.getSimpleValue().getDataType() == ArscDefs.TYPE_STRING) {
				return e;
			}
		}
		return null;
	}

	static ResourceEntry firstRawEntry(ArscFile a) {
		for (ResourceEntry e : a.storage.getResources()) {
			if (e.getNamedValues() == null && e.getSimpleValue() != null
					&& e.getSimpleValue().getDataType() != ArscDefs.TYPE_STRING) {
				return e;
			}
		}
		return null;
	}

	static ResourceEntry firstBagEntry(ArscFile a) {
		for (ResourceEntry e : a.storage.getResources()) {
			if (e.getNamedValues() != null) {
				for (RawNamedValue nv : e.getNamedValues()) {
					if (nv.getRawValue().getDataType() == ArscDefs.TYPE_STRING) {
						return e;
					}
				}
			}
		}
		return null;
	}

	public static void main(String[] args) throws Exception {
		String[] samples = { "test/mt22610.arsc", "test/huangguo.arsc", "test/np.arsc", "test/clone.arsc" };

		for (String path : samples) {
			System.out.println("\n==== " + path + " ====");
			byte[] orig = read(new File(path));
			ArscFile a = ArscFile.parse(orig);
			check("parse: entries > 0", a.entryCount() > 0);
			System.out.println("  entries=" + a.entryCount() + " pkg=" + a.appPackage()
					+ " globalStrings=" + a.globalPoolSize()
					+ " utf8=" + a.globalPoolUtf8()
					+ " pkgs=" + a.packages.size());

			// ---- 1. round-trip byte identical ----
			byte[] rt = ArscWriter.write(a, new ArrayList<Edit>());
			check("round-trip byte identical", java.util.Arrays.equals(orig, rt));

			// ---- 2. edit string value ----
			ResourceEntry se = firstStringEntry(a);
			if (se != null) {
				String oldVal = strVal(a, se);
				String newVal = "MTCLONE_TEST_" + System.nanoTime();
				List<Edit> edits = new ArrayList<Edit>();
				edits.add(new Edit(se, newVal));
				byte[] out = ArscWriter.write(a, edits);
				check("edit string: output differs", !java.util.Arrays.equals(orig, out));

				ArscFile b = ArscFile.parse(out);
				ResourceEntry se2 = findById(b, se.getId(), se.getConfig());
				check("edit string: new value readable", se2 != null && newVal.equals(strVal(b, se2)));
				check("edit string: entry count unchanged", b.entryCount() == a.entryCount());
				// all other string entries keep their values
				int diffCount = 0;
				for (ResourceEntry e : b.storage.getResources()) {
					ResourceEntry e0 = findById(a, e.getId(), e.getConfig());
					if (e0 == null) {
						continue;
					}
					String v0 = strVal(a, e0);
					String v1 = strVal(b, e);
					if (v0 != null && !v0.equals(v1)) {
						diffCount++;
					}
				}
				check("edit string: only target changed (diff=" + diffCount + ")", diffCount == 1);

				// ---- 3. rename key ----
				String newName = "HexFile_renamed_key";
				List<Edit> re = new ArrayList<Edit>();
				re.add(new Edit(se, newName, true));
				byte[] out2 = ArscWriter.write(a, re);
				ArscFile c = ArscFile.parse(out2);
				ResourceEntry se3 = findById(c, se.getId(), se.getConfig());
				check("rename: new key name readable", se3 != null && newName.equals(se3.getKeyName()));
				check("rename: entry count unchanged", c.entryCount() == a.entryCount());

				// ---- 4. multi-edit (value + rename in one pass) ----
				List<Edit> multi = new ArrayList<Edit>();
				multi.add(new Edit(se, newVal + "_m"));
				multi.add(new Edit(se, newName + "_m", true));
				byte[] out3 = ArscWriter.write(a, multi);
				ArscFile d = ArscFile.parse(out3);
				ResourceEntry se4 = findById(d, se.getId(), se.getConfig());
				check("multi-edit: value+rename both applied",
						se4 != null && (newVal + "_m").equals(strVal(d, se4))
								&& (newName + "_m").equals(se4.getKeyName()));

				// ---- 5. raw (non-string) value edit ----
				ResourceEntry re0 = firstRawEntry(a);
				if (re0 != null) {
					int oldRaw = re0.getSimpleValue().getData();
					int newRaw = oldRaw ^ 0x2A;
					List<Edit> rawEdits = new ArrayList<Edit>();
					rawEdits.add(new Edit(re0, newRaw));
					byte[] out4 = ArscWriter.write(a, rawEdits);
					ArscFile e2 = ArscFile.parse(out4);
					ResourceEntry re2 = findById(e2, re0.getId(), re0.getConfig());
					check("raw edit: new data applied", re2 != null
							&& re2.getSimpleValue().getData() == newRaw);
				} else {
					System.out.println("SKIP  raw edit (no non-string entry)");
				}

				// ---- 6. bag named string edit ----
				ResourceEntry be = firstBagEntry(a);
				if (be != null) {
					int mapIdx = -1;
					for (int i = 0; i < be.getNamedValues().size(); i++) {
						if (be.getNamedValues().get(i).getRawValue().getDataType() == ArscDefs.TYPE_STRING) {
							mapIdx = i;
							break;
						}
					}
					String bagNew = "MTCLONE_BAG_" + System.nanoTime();
					List<Edit> bagEdits = new ArrayList<Edit>();
					bagEdits.add(new Edit(be, mapIdx, bagNew));
					byte[] out5 = ArscWriter.write(a, bagEdits);
					ArscFile f2 = ArscFile.parse(out5);
					ResourceEntry be2 = findById(f2, be.getId(), be.getConfig());
					boolean bagOk = be2 != null && be2.getNamedValues() != null
							&& mapIdx < be2.getNamedValues().size()
							&& bagNew.equals(f2.globalPool.get(
									be2.getNamedValues().get(mapIdx).getRawValue().getData()));
					check("bag edit: named string applied", bagOk);
				} else {
					System.out.println("SKIP  bag edit (no complex entry)");
				}

				// ---- 7. second round-trip of edited file is stable ----
				ArscFile g = ArscFile.parse(out);
				byte[] rt2 = ArscWriter.write(g, new ArrayList<Edit>());
				check("edited file round-trips stable", java.util.Arrays.equals(out, rt2));

				System.out.println("  (sample done, old value was: " + oldVal + ")");
			} else {
				System.out.println("SKIP  no string entry found");
			}
		}

		System.out.println("\n==== " + pass + " passed, " + fail + " failed ====");
		if (fail > 0) {
			System.exit(1);
		}
	}

	static ResourceEntry findById(ArscFile a, int id, String config) {
		for (ResourceEntry e : a.storage.getResources()) {
			if (e.getId() == id && String.valueOf(e.getConfig()).equals(String.valueOf(config))) {
				return e;
			}
		}
		return null;
	}
}
