package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.MainActivity;
import com.hex91.file.apk.ApkEditSession;
import com.hex91.file.apk.ArchiveExtract;
import com.hex91.file.apk.ArchiveSearch;
import com.hex91.file.apk.ArchiveUtil;
import com.hex91.file.apk.SmaliTool;
import com.hex91.file.fs.WorkDir;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
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
    private Button btnExtract, btnUp;

    /**
     * v2.16.0（F-21）：非 null 表示当前列表是搜索结果，不是某个目录。
     *
     * <p>搜索和目录浏览共用同一份列表与同一个适配器，用一个字段区分两种模式，
     * 比开第二个 Activity 省事：点击、长按、解压全部都能原地复用。</p>
     */
    private String query;
    private ArchiveSearch.Result searchResult;
    /** 搜索线程正在跑（挡住点击与被 onResume 重载） */
    private volatile boolean searching;
    /** Activity 已销毁：后台线程回来时不要再碰 View */
    private volatile boolean dead;

    /**
     * 是否正在后台整包解压（v2.13.1 F-11）。
     * 解压线程与「点条目 / 刷新列表」共用同一个 Archive 对象，并发访问没有任何保证；
     * 这个标志在解压期间把交互挡掉，比给 Archive 加锁简单也更不容易漏。
     */
    private volatile boolean extracting;

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
        btnUp = (Button) findViewById(R.id.zip_up);

        try {
            archive = ArchiveUtil.open(archiveFile);
            format = archive.format();
        } catch (Exception e) {
            Toast.makeText(this, s(R.string.zip_open_fail, e.getMessage()), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        btnUp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { goUp(); }
        });
        findViewById(R.id.zip_search).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { promptSearch(); }
        });
        findViewById(R.id.zip_close).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        btnExtract.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { extractAll(); }
        });
        // v1.76.0：底栏三个系统按钮 → 胶囊（解压全部为主操作）
        UiDecor.barButton(btnUp, this, UiDecor.KIND_SOFT, 12.5f);
        UiDecor.barButton((Button) findViewById(R.id.zip_search), this, UiDecor.KIND_SOFT, 12.5f);
        UiDecor.barButton(btnExtract, this, UiDecor.KIND_PRIMARY, 12.5f);
        UiDecor.barButton((Button) findViewById(R.id.zip_close), this, UiDecor.KIND_SOFT, 12.5f);
        UiDecor.gap((Button) findViewById(R.id.zip_extract), this, 4f);
        UiDecor.gap((Button) findViewById(R.id.zip_close), this, 4f);

        adapter = new ZipAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                if (extracting || searching) { toast(getString(R.string.zip_busy)); return; }
                ArchiveUtil.Entry e = adapter.get(i);
                if (e.dir) {
                    // 从搜索结果点进目录：退出搜索模式，回到以该目录为根的浏览
                    prefix = e.full;
                    query = null;
                    searchResult = null;
                    load();
                } else {
                    extractEntry(e);
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> av, View v, int i, long id) {
                if (extracting || searching) { toast(getString(R.string.zip_busy)); return true; }
                showEntryMenu(adapter.get(i));
                return true;
            }
        });
        load();
    }

    @Override
    public void finish() {
        // v1.66.0：退出 APK 浏览时，有未签名修改版 → 统一弹窗（不签名/内置签名文件/自有签名文件）
        if (archiveFile != null && ApkEditSession.isApk(archiveFile)
                && ApkEditSession.needsSignPrompt(archiveFile)) {
            SignPromptActivity.launch(this, archiveFile);
        }
        super.finish();
    }

    @Override
    protected void onDestroy() {
        dead = true;                 // 搜索线程可能还在跑，回来后不许再碰 View
        if (archive != null) {
            try { archive.close(); } catch (IOException ignored) {}
        }
        super.onDestroy();
    }

    private void load() {
        if (query != null) {          // 搜索模式由 renderSearch 负责，避免把结果冲掉
            renderSearch();
            return;
        }
        try {
            List<ArchiveUtil.Entry> entries = archive.listChildren(prefix);
            adapter.setShowPath(false);
            adapter.set(entries);
            btnUp.setText(R.string.zip_up);
            String inner = prefix.isEmpty() ? "/" : "/" + prefix;
            titleView.setText(s(R.string.zip_title, archiveFile.getName(), format, inner, entries.size()));
        } catch (Exception e) {
            Toast.makeText(this, s(R.string.zip_read_fail, e.getMessage()), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /** v1.66.9：从编辑器（如 Dex 编辑器写回）返回时重新加载列表——原地模型下包内容可能已变 */
    @Override
    protected void onResume() {
        super.onResume();
        // 解压/搜索中不重载：会与后台线程抢同一个 Archive（F-11 / F-21）。
        // 搜索态也不重载：load() 会走 renderSearch()，那是白跑一次重绘；
        // 包内容若在编辑器里改过，点一次「搜索」就是最新的。
        if (archive == null || archiveFile == null || extracting || searching) return;
        if (query != null) return;
        load();
    }

    private void goUp() {
        if (query != null) {          // 搜索态下「上级」= 返回原目录
            query = null;
            searchResult = null;
            load();
            return;
        }
        if (prefix.isEmpty()) return;
        String up = prefix.substring(0, prefix.length() - 1); // 去掉末尾 '/'
        int idx = up.lastIndexOf('/');
        prefix = (idx < 0) ? "" : up.substring(0, idx + 1);
        load();
    }

    private void extractAll() {
        if (extracting || searching) { toast(getString(R.string.zip_busy)); return; }
        final File[] targets = {dest, archiveFile.getParentFile()};
        String[] labels = {
                s(R.string.zip_extract_to, dest.getPath()),
                s(R.string.zip_extract_to_same, archiveFile.getParentFile().getPath())
        };
        new HfDialogBuilder(this)
                .setTitle(s(R.string.zip_extract_all_fmt, format))
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        startExtract(targets[which]);
                    }
                })
                .show();
    }

    /**
     * 后台解压 + 横向进度条（v2.13.1，F-11）。
     *
     * <p>原来直接在 UI 线程里 {@code archive.extractAll(dest)}：解一个几百 MB 的包就是
     * 十几秒的 ANR，而且没有任何中断手段。现在按条目推进度，取消在每个条目边界生效。</p>
     *
     * <p>进度条 {@code setCancelable(false)} + 一个显式「取消」按钮：返回键把进度条关掉
     * 会让用户以为解压停了，实际线程还在跑 —— 那种「静默完成」比多一个按钮更困惑。</p>
     */
    private void startExtract(final File target) {
        final boolean[] cancel = {false};
        extracting = true;
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setTitle(s(R.string.zip_extract_all_fmt, format));
        pd.setMessage(target.getPath());
        pd.setMax(100);
        pd.setProgress(0);
        pd.setCancelable(false);
        pd.show();
        pd.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, s(R.string.common_cancel),
                new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        cancel[0] = true;
                        pd.setMessage(ZipBrowserActivity.this.s(R.string.zip_stopping));
                    }
                });
        new Thread(new Runnable() {
            public void run() {
                ArchiveExtract.Result r = ArchiveExtract.run(archive, target,
                        new ArchiveExtract.Listener() {
                            public void onProgress(final int done, final int total, String name) {
                                if (total <= 0) return;
                                final int pct = (int) (done * 100L / total);
                                final String show = name == null ? "" : name;
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        if (!pd.isShowing()) return;
                                        pd.setProgress(pct);
                                        pd.setMessage(ZipBrowserActivity.this.s(R.string.zip_progress, done, total, show));
                                    }
                                });
                            }

                            public boolean cancelled() {
                                return cancel[0];
                            }
                        });
                final ArchiveExtract.Result fr = r;
                runOnUiThread(new Runnable() {
                    public void run() {
                        extracting = false;
                        dismissQuietly(pd);
                        if (fr.error != null) {
                            toast(ZipBrowserActivity.this.s(R.string.zip_extract_fail_partial, fr.done, fr.error));
                        } else if (fr.cancelled) {
                            toast(ZipBrowserActivity.this.s(R.string.zip_extract_cancelled, fr.done, fr.total));
                        } else {
                            toast(ZipBrowserActivity.this.s(R.string.zip_extract_done, fr.done, target.getPath()));
                        }
                    }
                });
            }
        }, "zip-extract").start();
    }

    private void extractEntry(ArchiveUtil.Entry e) {
        try {
            archive.extractEntry(e.full, dest);
            // 用完整条目名：搜索结果的短名可能同名多行，只报短名分不清解的是哪个
            toast(getString(R.string.zip_extracted_one, e.full, dest.getPath()));
        } catch (Exception ex) {
            toast(s(R.string.zip_extract_fail, ex.getMessage()));
        }
    }

    /**
     * v2.16.0（F-21）：包内搜索。
     *
     * <p>为什么搜整个包而不是当前目录：用户找 {@code AndroidManifest.xml} 时，绝大多数情况
     * 并不知道它在不了几层下的哪个目录；只搜当前层等于让他先自己找到目录再来搜。</p>
     */
    private void promptSearch() {
        if (extracting || searching) { toast(getString(R.string.zip_busy)); return; }
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        et.setHint(R.string.zip_search_hint);
        if (query != null) et.setText(query);     // 再搜一次时把上次的词带出来好改
        new HfDialogBuilder(this)
                .setTitle(R.string.zip_search)
                .setMessage(R.string.zip_search_msg)
                .setView(et)
                .setPositiveButton(R.string.zip_search,
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                startSearch(et.getText().toString());
                            }
                        })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /**
     * 后台执行搜索。
     *
     * <p>必须离开 UI 线程：ZIP/RAR/7z 的条目表在打开时已解析好，取一次是毫秒级；
     * 但 TAR 没有索引，{@code listAll} 等于把整个包顺序读一遍，几百 MB 就是几秒。
     * 同一份代码不能有两种线程模型，所以统一放后台。</p>
     */
    private void startSearch(final String raw) {
        final String q = ArchiveSearch.normalize(raw);
        if (q.isEmpty()) { toast(getString(R.string.zip_search_empty_query)); return; }
        searching = true;
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.zip_searching));
        // 与解压一致：关掉对话框不代表停下了，那就不给关
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            public void run() {
                ArchiveSearch.Result got = null;
                String err = null;
                try {
                    got = ArchiveSearch.filter(archive.listAll(), q, ArchiveSearch.MAX_HITS);
                } catch (Throwable t) {
                    err = shortMsg(ZipBrowserActivity.this, t);
                }
                final ArchiveSearch.Result fr = got;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        searching = false;
                        dismissQuietly(pd);
                        if (dead || isFinishing()) return;
                        if (fe != null) {
                            toast(getString(R.string.zip_search_failed, fe));
                            return;
                        }
                        query = q;
                        searchResult = fr;
                        renderSearch();
                    }
                });
            }
        }, "zip-search").start();
    }

    /** 把搜索结果画进列表：完整路径 + 「命中多少条 / 是否截断」 */
    private void renderSearch() {
        ArchiveSearch.Result r = searchResult;
        if (r == null) { query = null; load(); return; }
        adapter.setShowPath(true);          // 必须在 set() 之前：set 里会 notifyDataSetChanged
        adapter.set(r.items);
        btnUp.setText(R.string.zip_search_back);
        String head;
        if (r.total == 0) head = getString(R.string.zip_search_none, query);
        else if (r.truncated) head = getString(R.string.zip_search_truncated, query, r.total, r.items.size());
        else head = getString(R.string.zip_search_done, query, r.total);
        titleView.setText(getString(R.string.zip_title_search, archiveFile.getName(), format, head));
    }

    /** 关进度条：解压与搜索共用一份，免得同一个 try/catch 写两遍 */
    private void dismissQuietly(ProgressDialog pd) {
        if (pd == null || !pd.isShowing()) return;
        try {
            pd.dismiss();
        } catch (Throwable t) {
            toast(getString(R.string.zip_dialog_dismiss_failed, String.valueOf(t)));
        }
    }

    /** 异常压成一行（ArchiveUtil 里那份是包内可见，这里跨包拿不到） */
    private static String shortMsg(android.content.Context c, Throwable t) {
        String m = t == null ? null : t.getMessage();
        if (m == null || m.isEmpty()) m = t == null ? c.getString(R.string.zip_unknown_error) : t.getClass().getSimpleName();
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    private void showEntryMenu(final ArchiveUtil.Entry e) {
        final boolean isDex = !e.dir && e.name.toLowerCase().endsWith(".dex");
        final boolean isArsc = !e.dir && e.name.toLowerCase().endsWith(".arsc");
        // dex/arsc/smali 专属操作仅在 ZIP 格式下可用（Dex/Arsc 编辑器要求输入是 ZIP/APK）
        final boolean zipMode = "ZIP".equals(format);
        java.util.List<String> opList = new java.util.ArrayList<String>();
        final java.util.List<Integer> actList = new java.util.ArrayList<Integer>();
        if (zipMode && isDex) { opList.add(s(R.string.zip_op_dex_editor)); actList.add(0); }
        if (zipMode && isArsc) { opList.add(s(R.string.zip_op_arsc_edit)); actList.add(3); }
        if (zipMode && isArsc) { opList.add(s(R.string.zip_op_arscpp_edit)); actList.add(5); }
        opList.add(s(R.string.zip_op_extract_one)); actList.add(1);
        if (zipMode && isDex) { opList.add(s(R.string.zip_op_decompile)); actList.add(2); }
        opList.add(s(R.string.zip_extract_all)); actList.add(4);
        new HfDialogBuilder(this)
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
                            case 5:
                                Intent k = new Intent(ZipBrowserActivity.this, ArscPlusActivity.class);
                                k.putExtra(ArscPlusActivity.EXTRA_APK, archiveFile.getAbsolutePath());
                                k.putExtra(ArscPlusActivity.EXTRA_ENTRY, e.full);
                                startActivity(k);
                                break;
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
            toast(s(R.string.zip_dex_cache_fail, ex.getMessage()));
            return;
        }
        final File dexFile = new File(cache, e.name);
        if (!dexFile.exists()) {
            toast(s(R.string.zip_dex_cache_fail_plain));
            return;
        }
        String apkName = archiveFile.getName();
        int dot = apkName.lastIndexOf('.');
        if (dot > 0) apkName = apkName.substring(0, dot);
        String dexName = e.name;
        dot = dexName.lastIndexOf('.');
        if (dot > 0) dexName = dexName.substring(0, dot);
        final File outDir = new File(WorkDir.base(), "Smali/" + apkName + "/" + dexName);

        toast(s(R.string.zip_decompiling));
        new Thread(new Runnable() {
            public void run() {
                final String err = SmaliTool.decompile(dexFile, outDir, 4);
                dexFile.delete(); // 清理缓存
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (err == null) {
                            toast(ZipBrowserActivity.this.s(R.string.zip_smali_done, outDir.getPath()));
                            Intent i = new Intent(ZipBrowserActivity.this, MainActivity.class);
                            i.putExtra("dir", outDir.getAbsolutePath());
                            startActivity(i);   // MainActivity singleTop：onNewIntent 跳转目录
                        } else {
                            toast(ZipBrowserActivity.this.s(R.string.zip_decompile_fail,
                                    SmaliReport.err(ZipBrowserActivity.this, err)));
                        }
                    }
                });
            }
        }, "baksmali").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private String s(int id) { return getString(id); }
    private String s(int id, Object... args) { return getString(id, args); }
}
