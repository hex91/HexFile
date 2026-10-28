import io

p = 'src/bin/mtclone/ZipBrowserActivity.java'
s = io.open(p, encoding='utf-8').read()

start = s.find('private void showEntryMenu')
assert start >= 0, "showEntryMenu not found"
end = s.find('private void decompileDex', start)
assert end > start, "decompileDex not found"

new_block = '''    private void showEntryMenu(final ZipUtil.Entry e) {
        final boolean isDex = !e.dir && e.name.toLowerCase().endsWith(".dex");
        final boolean isArsc = !e.dir && e.name.toLowerCase().endsWith(".arsc");
        java.util.List<String> opList = new java.util.ArrayList<String>();
        final java.util.List<Integer> actList = new java.util.ArrayList<Integer>();
        if (isDex) { opList.add("Dex 编辑器++"); actList.add(0); }
        if (isArsc) { opList.add("ARSC 编辑器++"); actList.add(3); }
        opList.add("解压此条目"); actList.add(1);
        if (isDex) { opList.add("反编译为 smali"); actList.add(2); }
        opList.add("解压全部"); actList.add(4);
        new AlertDialog.Builder(this)
                .setTitle(e.name + (isDex ? "  (dex)" : isArsc ? "  (arsc)" : ""))
                .setItems(opList.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        switch (actList.get(which)) {
                            case 0:
                                Intent i = new Intent(ZipBrowserActivity.this, DexEditorActivity.class);
                                i.putExtra(DexEditorActivity.EXTRA_APK, zip.getAbsolutePath());
                                i.putExtra(DexEditorActivity.EXTRA_DEXES, new String[]{e.full});
                                startActivity(i);
                                break;
                            case 1: extractEntry(e); break;
                            case 2: decompileDex(e); break;
                            case 3:
                                Intent j = new Intent(ZipBrowserActivity.this, ArscEditorActivity.class);
                                j.putExtra(ArscEditorActivity.EXTRA_APK, zip.getAbsolutePath());
                                j.putExtra(ArscEditorActivity.EXTRA_ENTRY, e.full);
                                startActivity(j);
                                break;
                            case 4: extractAll(); break;
                        }
                    }
                })
                .show();
    }

    '''
s = s[:start] + new_block + s[end:]
io.open(p, 'w', encoding='utf-8').write(s)
print("ZipBrowser wired OK")
