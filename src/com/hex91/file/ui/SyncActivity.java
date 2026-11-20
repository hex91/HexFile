package com.hex91.file.ui;

import com.hex91.file.utils.SyncPlan;
import com.hex91.file.R;
import com.hex91.file.net.NetFs;
import com.hex91.file.net.NetFsFactory;
import com.hex91.file.net.SyncRunner;
import com.hex91.file.fs.WorkDir;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v1.87.0 云同步页（P2 收尾）。
 *
 * <p>流程刻意做成「先预览、再执行」：同步是不可逆的写操作，
 * 必须让用户先看到「会上传 12、下载 3、冲突 1」再决定。直接开跑等于赌博。</p>
 *
 * <p>连接每次操作现开现关：WebDAV 长连接在手机网络切换后会失效，
 * 复用反而更容易出「看起来连上了其实早断了」的怪问题。</p>
 */
public class SyncActivity extends BaseActivity {

    private static final String PREFS = "netstorage";
    private static final String KEY_SERVERS = "servers";

    private final List<JSONObject> servers = new ArrayList<JSONObject>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();

    private int idx = -1;
    private File localDir;
    private int mode = SyncPlan.MODE_BOTH;
    private volatile SyncPlan.Plan plan;
    private volatile boolean busy;

    private TextView rowServer, rowLocal, rowMode, tvPlan, tvLog, tvStatus;
    private EditText etRemote;
    private ProgressBar progress;
    private Button btnPreview, btnStart;
    private long lastUiAt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_sync);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });

        rowServer = (TextView) findViewById(R.id.row_server);
        rowLocal = (TextView) findViewById(R.id.row_local);
        rowMode = (TextView) findViewById(R.id.row_mode);
        tvPlan = (TextView) findViewById(R.id.tv_plan);
        tvLog = (TextView) findViewById(R.id.tv_log);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        etRemote = (EditText) findViewById(R.id.et_remote);
        progress = (ProgressBar) findViewById(R.id.progress);
        btnPreview = (Button) findViewById(R.id.btn_preview);
        btnStart = (Button) findViewById(R.id.btn_start);

        UiDecor.barButton(btnPreview, this, UiDecor.KIND_SOFT, 13f);
        UiDecor.barButton(btnStart, this, UiDecor.KIND_PRIMARY, 13f);

        loadServers();
        localDir = WorkDir.base();

        rowServer.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickServer(); }
        });
        rowLocal.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickLocal(); }
        });
        rowMode.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickMode(); }
        });
        btnPreview.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { preview(); }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (busy) {
                    cancel.set(true);
                    tvStatus.setText(s(R.string.sy_cancelling));
                } else {
                    startSync();
                }
            }
        });

        render();
    }

    @Override
    protected void onDestroy() {
        cancel.set(true);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 数据

    private void loadServers() {
        servers.clear();
        try {
            JSONArray arr = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_SERVERS, "[]"));
            for (int i = 0; i < arr.length(); i++) servers.add(arr.getJSONObject(i));
        } catch (Throwable ignored) {
        }
        idx = servers.isEmpty() ? -1 : 0;
    }

    private void render() {
        rowServer.setText(s(R.string.sy_row_server) + (idx < 0 ? s(R.string.sy_server_unselected)
                : NetFsFactory.describe(SyncActivity.this, servers.get(idx))));
        rowLocal.setText(s(R.string.sy_row_local) + (localDir == null ? s(R.string.sy_unselected)
                : localDir.getAbsolutePath()));
        rowMode.setText(s(R.string.sy_row_mode) + modeName());
    }

    private String modeName() {
        if (mode == SyncPlan.MODE_UP) return s(R.string.sy_mode_up);
        if (mode == SyncPlan.MODE_DOWN) return s(R.string.sy_mode_down);
        return s(R.string.sy_mode_both);
    }

    private void pickServer() {
        if (servers.isEmpty()) {
            toast(s(R.string.sy_no_server));
            return;
        }
        final String[] items = new String[servers.size()];
        for (int i = 0; i < servers.size(); i++) items[i] = NetFsFactory.describe(SyncActivity.this, servers.get(i));
        new HfDialogBuilder(this)
                .setTitle(s(R.string.sy_pick_server))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        idx = which;
                        plan = null;
                        tvPlan.setText("");
                        render();
                    }
                })
                .setNegativeButton(s(R.string.common_cancel), null)
                .show();
    }

    private void pickLocal() {
        DirPickerDialog.show(this, localDir == null ? WorkDir.base() : localDir,
                new DirPickerDialog.OnPick() {
                    public void onPick(File dir) {
                        localDir = dir;
                        plan = null;
                        tvPlan.setText("");
                        render();
                    }
                });
    }

    private void pickMode() {
        final String[] items = {s(R.string.sy_mode_up), s(R.string.sy_mode_down), s(R.string.sy_mode_both_short)};
        new HfDialogBuilder(this)
                .setTitle(s(R.string.sy_direction))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        mode = which == 0 ? SyncPlan.MODE_UP
                                : which == 1 ? SyncPlan.MODE_DOWN : SyncPlan.MODE_BOTH;
                        plan = null;
                        tvPlan.setText("");
                        render();
                    }
                })
                .setNegativeButton(s(R.string.common_cancel), null)
                .show();
    }

    private String remote() {
        String s = etRemote.getText().toString().trim();
        return s.isEmpty() ? "/" : s;
    }

    // ------------------------------------------------------------------ 预览

    private void preview() {
        if (busy) { toast(s(R.string.sy_busy)); return; }
        if (!checkReady()) return;
        final JSONObject cfg = servers.get(idx);
        setBusy(true, s(R.string.sy_comparing));
        new Thread(new Runnable() {
            public void run() {
                NetFs fs = null;
                try {
                    fs = NetFsFactory.open(SyncActivity.this, cfg);
                    final SyncPlan.Plan p = SyncRunner.preview(fs, remote(), localDir, mode);
                    ui.post(new Runnable() {
                        public void run() {
                            plan = p;
                            tvPlan.setText(p.work() == 0
                                    ? s(R.string.sy_no_diff) + UtilText.syncSummary(SyncActivity.this, p)
                                    : UtilText.syncSummary(SyncActivity.this, p));
                            if (p.work() == 0) tvStatus.setText(s(R.string.sy_no_sync));
                        }
                    });
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        public void run() {
                            plan = null;
                            tvPlan.setText(s(R.string.sy_preview_fail, msg(SyncActivity.this, e)));
                        }
                    });
                } finally {
                    if (fs != null) fs.close();
                    ui.post(new Runnable() {
                        public void run() { setBusy(false, null); }
                    });
                }
            }
        }, "sync-preview").start();
    }

    // ------------------------------------------------------------------ 执行

    private void startSync() {
        if (!checkReady()) return;
        final SyncPlan.Plan p = plan;
        if (p == null) { toast(s(R.string.sy_need_preview)); return; }
        if (p.work() == 0) { toast(s(R.string.sy_no_files)); return; }

        final JSONObject cfg = servers.get(idx);
        cancel.set(false);
        busy = true;
        btnStart.setText(s(R.string.common_cancel));
        btnPreview.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        tvLog.setText("");
        tvStatus.setText(s(R.string.sy_starting, p.work()));

        new Thread(new Runnable() {
            public void run() {
                NetFs fs = null;
                try {
                    fs = NetFsFactory.open(SyncActivity.this, cfg);
                    SyncRunner.run(fs, remote(), localDir, p, new SyncRunner.Callback() {
                        public void onProgress(final int done, final int total, final String path) {
                            // 节流：网络传输时回调很密，每 120ms 刷一次足够
                            long now = System.currentTimeMillis();
                            if (now - lastUiAt < 120 && done < total) return;
                            lastUiAt = now;
                            ui.post(new Runnable() {
                                public void run() {
                                    if (total > 0) progress.setProgress(done * 100 / total);
                                    tvStatus.setText(SyncActivity.this.s(R.string.sy_progress, done, total, path));
                                }
                            });
                        }

                        public void onLog(final String msg) {
                            ui.post(new Runnable() {
                                public void run() {
                                    String cur = tvLog.getText().toString();
                                    if (cur.length() > 4000) cur = cur.substring(cur.length() - 3000);
                                    tvLog.setText(cur + msg + "\n");
                                }
                            });
                        }

                        public void onFinish(final SyncRunner.Report r) {
                            ui.post(new Runnable() {
                                public void run() {
                                    tvStatus.setText(SyncText.summaryText(SyncActivity.this, r));
                                    progress.setProgress(100);
                                    if (!r.errors.isEmpty()) {
                                        StringBuilder sb = new StringBuilder(SyncActivity.this.s(R.string.sy_fail_detail));
                                        for (int i = 0; i < Math.min(8, r.errors.size()); i++) {
                                            sb.append(SyncActivity.this.s(R.string.sy_bullet)).append(r.errors.get(i)).append('\n');
                                        }
                                        tvLog.setText(tvLog.getText().toString() + sb.toString());
                                    }
                                    // 完成后计划作废：再点开始应重新预览
                                    plan = null;
                                }
                            });
                        }
                    }, cancel);
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        public void run() { tvStatus.setText(SyncActivity.this.s(R.string.sy_sync_fail, msg(SyncActivity.this, e))); }
                    });
                } finally {
                    if (fs != null) fs.close();
                    ui.post(new Runnable() {
                        public void run() {
                            busy = false;
                            btnStart.setText(SyncActivity.this.s(R.string.sy_start));
                            btnPreview.setEnabled(true);
                        }
                    });
                }
            }
        }, "sync-run").start();
    }

    // ------------------------------------------------------------------ 杂项

    private boolean checkReady() {
        if (idx < 0 || idx >= servers.size()) { toast(s(R.string.sy_pick_server_first)); return false; }
        if (localDir == null || !localDir.isDirectory()) { toast(s(R.string.sy_pick_local)); return false; }
        String r = remote();
        if (r.contains("..")) { toast(s(R.string.sy_remote_dotdot)); return false; }
        return true;
    }

    private void setBusy(boolean b, String status) {
        busy = b;
        btnPreview.setEnabled(!b);
        btnStart.setEnabled(!b);
        if (status != null) tvStatus.setText(status);
        if (!b) progress.setVisibility(View.GONE);
    }

    private static String msg(android.content.Context c, Throwable e) {
        if (e instanceof com.hex91.file.net.NetFsError) return NetText.errorText(c, e);
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String s(int id) { return getString(id); }
    private String s(int id, Object... args) { return getString(id, args); }
}
