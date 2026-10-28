package jadx.core.xmlgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jadx.core.xmlgen.entry.ResourceEntry;

/**
 * In-memory storage of decoded resource entries.
 *
 * <p>Derived from jadx (https://github.com/skylot/jadx), Apache License 2.0.
 * HexFile modification: dropped the IJadxSecurity dependency (entry-name validation is only
 * relevant when jadx writes resource files to disk; the HexFile editor never uses entry names
 * as file paths, so a lightweight inline check is enough).
 */
public class ResourceStorage {
	// HexFile mod: method references replaced with anonymous classes
	// (android.jar bootclasspath + -source 8 has no LambdaMetafactory)
	private static final Comparator<ResourceEntry> RES_ENTRY_NAME_COMPARATOR =
			new Comparator<ResourceEntry>() {
				public int compare(ResourceEntry a, ResourceEntry b) {
					int c = String.valueOf(a.getConfig()).compareTo(String.valueOf(b.getConfig()));
					if (c != 0) {
						return c;
					}
					c = String.valueOf(a.getTypeName()).compareTo(String.valueOf(b.getTypeName()));
					if (c != 0) {
						return c;
					}
					return String.valueOf(a.getKeyName()).compareTo(String.valueOf(b.getKeyName()));
				}
			};

	private final List<ResourceEntry> list = new ArrayList<>();

	private String appPackage;

	/**
	 * Names in one config and type must be unique
	 */
	private final Map<ResourceEntry, ResourceEntry> uniqNameEntries = new TreeMap<>(RES_ENTRY_NAME_COMPARATOR);

	/**
	 * Preserve same name for same id across different configs
	 */
	private final Map<Integer, String> renames = new HashMap<>();

	public void add(ResourceEntry resEntry) {
		list.add(resEntry);
		uniqNameEntries.put(resEntry, resEntry);
	}

	public void replace(ResourceEntry prevResEntry, ResourceEntry newResEntry) {
		int idx = list.indexOf(prevResEntry);
		if (idx != -1) {
			list.set(idx, newResEntry);
		}
		// don't remove from unique names so old name stays occupied
	}

	public void addRename(ResourceEntry entry) {
		addRename(entry.getId(), entry.getKeyName());
	}

	public void addRename(int id, String keyName) {
		renames.put(id, keyName);
	}

	public String getRename(int id) {
		return renames.get(id);
	}

	public ResourceEntry searchEntryWithSameName(ResourceEntry resourceEntry) {
		return uniqNameEntries.get(resourceEntry);
	}

	public void finish() {
		list.sort(new Comparator<ResourceEntry>() {
			public int compare(ResourceEntry a, ResourceEntry b) {
				return Integer.compare(a.getId(), b.getId());
			}
		});
		uniqNameEntries.clear();
		renames.clear();
	}

	public int size() {
		return list.size();
	}

	public Iterable<ResourceEntry> getResources() {
		return list;
	}

	public String getAppPackage() {
		return appPackage;
	}

	public void setAppPackage(String appPackage) {
		this.appPackage = appPackage;
	}

	public Map<Integer, String> getResourcesNames() {
		Map<Integer, String> map = new HashMap<>();
		for (ResourceEntry entry : list) {
			map.put(entry.getId(), entry.getTypeName() + '/' + entry.getKeyName());
		}
		return map;
	}
}
