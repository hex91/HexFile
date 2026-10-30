package com.hex91.file;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 多格式压缩包浏览界面（对标 MT 的压缩包查看器）
 * 支持：ZIP / 7z / TAR(.gz) / RAR 的浏览、进入子目录、解压全部、解压单条。
 * dex/arsc/smali 专属菜单仅在 ZIP 格式（含 APK）下出现。
 */
public class ZipBrowserActivity extends BaseActivity {

    public static final String EXTRA_ZIP = "zip";
    public static final String EXTRA_DEST = "dest";

    private File archiveFile;
    private ArchiveUtil.Archive archive;
    private String format = "ZIP";
    private File dest;
    private String prefix = "";

    private TextView titleView;
    private ListView list;
    private ZipAdapter adapter;
    private Button btnExtract;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_zip);

        Intent it = getIntent();
        archiveFile = new File(it.getStringExtra(EXTRA_ZIP));
        String d = it.getStringExtra(EXTRA_DEST);
        dest = (d != null && new File(d).isDirectory()) ? new File(d) : archiveFile.getParentFile();

        titleView = (TextView) findViewById(R.id.zip_title);
        list = (ListView) findViewById(R.id.zip_list);
        btnExtract = (Button) findViewById(R.id.zip_extract);

        try {
            archive = ArchiveUtil.open(archiveFile);
            format = archive.format();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开压缩包: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        findViewById(R.id.zip_up).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { goUp(); }
        });
        findViewById(R.id.zip_close).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        btnExtract.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { extractAll(); }
        });

        adapter = new ZipAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                ArchiveUtil.Entry e = adapter.get(i);
                if (e.dir) {
                    prefix = e.full;
                    load();
                } else {
                    extractEntry(e);
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> av, View v, int i, long id) {
                showEntryMenu(adapter.get(i));
                return true;
            }
        });
        load();
    }

    @Override
    protected void onDestroy() {
        if (archive != null) {
            try { archive.close(); } catch (IOException ignored) {}
        }
        super.onDestroy();
    }

    private void load() {
        try {
            List<ArchiveUtil.Entry> entries = archive.listChildren(prefix);
            adapter.set(entries);
            String inner = prefix.isEmpty() ? "/" : "/" + prefix;
            titleView.setText(archiveFile.getName() + "  [" + format + "]\n内部路径: " + inner
                    + "  (" + entries.size() + " 项)");
        } catch (Exception e) {
            Toast.makeText(this, "读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void goUp() {
        if (prefix.isEmpty()) return;
        String up = prefix.substring(0, prefix.length() - 1); // 去掉末尾 '/'
        int idx = up.lastIndexOf('/');
        prefix = (idx < 0) ? "" : up.substring(0, idx + 1);
        load();
    }

    private void extractAll() {
        final File[] targets = {dest, archiveFile.getParentFile()};
        String[] labels = {
                "解压到: " + dest.getPath(),
                "解压到压缩包同目录: " + archiveFile.getParentFile().getPath()
        };
        new AlertDialog.Builder(this)
                .setTitle("解压全部 (" + format + ")")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        try {
                            archive.extractAll(targets[which]);
                            toast("已解压全部 →\n" + targets[which].getPath());
                        } catch (Exception e) {
                            toast("解压失败: " + e.getMessage());
                        }
                    }
                })
                .show();
    }

    private void extractEntry(ArchiveUtil.Entry e) {
        try {
            archive.extractEntry(e.full, dest);
            toast("已解压: " + e.name + "\n→ " + dest.getPath());
        } catch (Exception ex) {
            toast("解压失败: " + ex.getMessage());
        }
    }

    private void showEntryMenu(final ArchiveUtil.Entry e) {
        final boolean isDex = !e.dir && e.name.toLowerCase().endsWith(".dex");
        final boolean isArsc = !e.dir && e.name.toLowerCase().endsWith(".arsc");
        // dex/arsc/smali 专属操作仅在 ZIP 格式下可用（Dex/Arsc 编辑器要求输入是 ZIP/APK）
        final boolean zipMode = "ZIP".equals(format);
        java.util.List<String> opList = new java.util.ArrayList<String>();
        final java.util.List<Integer> actList = new java.util.ArrayList<Integer>();
        if (zipMode && isDex) { opList.add("Dex 编辑器++"); actList.add(0); }
        if (zipMode && isArsc) { opList.add("ARSC 编辑器++"); actList.add(3); }
        opList.add("解压此条目"); actList.add(1);
        if (zipMode && isDex) { opList.add("反编译为 smali"); actList.add(2); }
        opList.add("解压全部"); actList.add(4);
        new AlertDialog.Builder(this)
                .setTitle(e.name + (isDex ? "  (dex)" : isArsc ? "  (arsc)" : ""))
                .setItems(opList.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        switch (actList.get(which)) {
                            case 0:
                                Intent i = new Intent(ZipBrowserActivity.this, DexEditorActivity.class);
                                i.putExtra(DexEditorActivity.EXTRA_APK, archiveFile.getAbsolutePath());
                                i.putExtra(DexEditorActivity.EXTRA_DEXES, new String[]{e.full});
                                startActivity(i);
                                break;
                            case 1: extractEntry(e); break;
                            case 2: decompileDex(e); break;
                            case 3:
                                Intent j = new Intent(ZipBrowserActivity.this, ArscEditorActivity.class);
                                j.putExtra(ArscEditorActivity.EXTRA_APK, archiveFile.getAbsolutePath());
                                j.putExtra(ArscEditorActivity.EXTRA_ENTRY, e.full);
                                startActivity(j);
                                break;
                            case 4: extractAll(); break;
                        }
                    }
                })
                .show();
    }

    private void decompileDex(final ArchiveUtil.Entry e) {
        final File cache = getCacheDir();
        try {
            archive.extractEntry(e.full, cache);
        } catch (Exception ex) {
            toast("提取 dex 到缓存失败: " + ex.getMessage());
            return;
        }
        final File dexFile = new File(cache, e.name);
        if (!dexFile.exists()) {
            toast("提取 dex 到缓存失败");
            return;
        }
        String apkName = archiveFile.getName();
        int dot = apkName.lastIndexOf('.');
        if (dot > 0) apkName = apkName.substring(0, dot);
        String dexName = e.name;
        dot = dexName.lastIndexOf('.');
        if (dot > 0) dexName = dexName.substring(0, dot);
        final File outDir = new File(
                android.os.Environment.getExternalStorageDirectory(),
                "HexFile/Smali/" + apkName + "/" + dexName);

        toast("反编译中…（大型 dex 可能需要较长时间）");
        new Thread(new Runnable() {
            public void run() {
                final String err = SmaliTool.decompile(dexFile, outDir, 4);
                dexFile.delete(); // 清理缓存
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (err == null) {
                            toast("已生成 smali →\n" + outDir.getPath());
                            Intent i = new Intent(ZipBrowserActivity.this, MainActivity.class);
                            i.putExtra("dir", outDir.getAbsolutePath());
                            startActivity(i);   // MainActivity singleTop：onNewIntent 跳转目录
                        } else {
                            toast("反编译失败: " + err);
                        }
                    }
                });
            }
        }, "baksmali").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
