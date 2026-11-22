package com.hex91.file.ui;

import com.hex91.file.R;
import com.hex91.file.fs.FileOps;
import com.hex91.file.sched.ScheduleCodec;
import com.hex91.file.sched.ScheduleItem;
import com.hex91.file.sched.ScheduleReceiver;
import com.hex91.file.sched.ScheduleRule;
import com.hex91.file.sched.ScheduleRunner;
import com.hex91.file.sched.ScheduleRunState;
import com.hex91.file.sched.ScheduleStore;
import com.hex91.file.sched.VolumeRoots;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务列表（v2.17.0，F-23）。
 *
 * <p>规则输入就是一段短文本（{@code daily 23:00} / {@code weekly 1,3 07:30}），
 * 不做「小时下拉 + 分钟下拉 + 星期多选」那种表单：三个控件拼起来看着更友好，但用户想改成
 * 「每周一三五」时要在两排控件之间来回点，而一段文本改一个数字就能做到。</p>
 *
 * <p>校验失败不关对话框（用 {@code setOnShowListener} 覆写确定按钮），否则用户输入的内容
 * 会随对话框一起消失，只剩一句「规则看不懂」。</p>
 */
public class ScheduleActivity extends BaseActivity {

    /** 可选：从别处带一个目录进来，新建时预填 */
    public static final String EXTRA_DIR = "dir";

    private final List<ScheduleItem> data = new ArrayList<ScheduleItem>();
    private Adapter adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_schedule);

        empty = (TextView) findViewById(R.id.sc_empty);
        ListView lv = (ListView) findViewById(R.id.sc_list);
        adapter = new Adapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> av, View v, int i, long id) {
                showOps(data.get(i));
            }
        });

        android.widget.Button btnNew = (android.widget.Button) findViewById(R.id.sc_new);
        UiDecor.barButtonOnBar(btnNew, this, UiDecor.KIND_PRIMARY, 11.5f);
        UiDecor.gap(btnNew, this, 4f);
        btnNew.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { edit(null); }
        });

        android.widget.Button btnClose = (android.widget.Button) findViewById(R.id.sc_close);
        UiDecor.barButtonOnBar(btnClose, this, UiDecor.KIND_SOFT, 11.5f);
        UiDecor.gap(btnClose, this, 4f);
        btnClose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });

        // 进页面顺手重排一次：应用可能刚被系统清理过闹钟（自启动限制、强制停止）
        ScheduleStore.scheduleNext(this);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        // R-41：上一次执行是被系统杀掉的。这里只说「没跑完」，不谎报成功，
        // 也不自动重跑 —— 清空/压缩都不是幂等操作，重跑比不跑更危险。
        if (ScheduleRunState.takeInterrupted(this)) {
            Toast.makeText(this, R.string.sched_interrupted, Toast.LENGTH_LONG).show();
        }
    }

    private void reload() {
        data.clear();
        data.addAll(ScheduleStore.load(this));
        adapter.notifyDataSetChanged();
        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- 单条操作

    private void showOps(final ScheduleItem it) {
        String last = it.lastResult == null || it.lastResult.isEmpty()
                ? getString(R.string.sched_never) : it.lastResult;
        String title = getString(ScheduleReceiver.actionLabel(it.action))
                + " · " + it.rule + "\n" + getString(R.string.sched_last_short, last);

        String[] ops = new String[]{
                it.enabled ? getString(R.string.sched_menu_disable) : getString(R.string.sched_menu_enable),
                getString(R.string.sched_menu_run),
                getString(R.string.sched_menu_edit),
                getString(R.string.sched_menu_delete)
        };
        new HfDialogBuilder(this)
                .setTitle(title)
                .setItems(ops, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) toggle(it);
                        else if (which == 1) runNow(it);
                        else if (which == 2) edit(it);
                        else confirmDelete(it);
                    }
                })
                .show();
    }

    private void toggle(ScheduleItem it) {
        List<ScheduleItem> list = ScheduleStore.load(this);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == it.id) list.get(i).enabled = !it.enabled;
        }
        ScheduleStore.saveAndReschedule(this, list);
        reload();
    }

    private void confirmDelete(final ScheduleItem it) {
        new HfDialogBuilder(this)
                .setTitle(R.string.sched_menu_delete)
                .setMessage(getString(R.string.sched_delete_msg,
                        getString(ScheduleReceiver.actionLabel(it.action)) + " " + it.target))
                .setPositiveButton(R.string.sched_menu_delete,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                List<ScheduleItem> list = ScheduleStore.load(ScheduleActivity.this);
                                for (int i = list.size() - 1; i >= 0; i--) {
                                    if (list.get(i).id == it.id) list.remove(i);
                                }
                                ScheduleStore.saveAndReschedule(ScheduleActivity.this, list);
                                reload();
                            }
                        })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** 立即执行一次：执行结果按真实执行时间记进 lastRun（不影响「这一轮跑过没有」的判断） */
    private void runNow(final ScheduleItem it) {
        toast(getString(R.string.sched_running));
        new Thread(new Runnable() {
            public void run() {
                // R-42：执行前先收集真实卷根，让 isDangerous 的注入比对生效
                VolumeRoots.of(ScheduleActivity.this);
                final ScheduleRunner.Result r = ScheduleRunner.run(it);
                runOnUiThread(new Runnable() {
                    public void run() {
                        List<ScheduleItem> list = ScheduleStore.load(ScheduleActivity.this);
                        for (int i = 0; i < list.size(); i++) {
                            ScheduleItem x = list.get(i);
                            if (x.id != it.id) continue;
                            x.lastRun = System.currentTimeMillis();
                            x.lastResult = r.ok()
                                    ? getString(R.string.sched_result_ok, r.done)
                                    : getString(R.string.sched_result_err,
                                            com.hex91.file.sched.SchedText.errorText(
                                                    ScheduleActivity.this, r));
                        }
                        ScheduleStore.saveAndReschedule(ScheduleActivity.this, list);
                        reload();
                        toast(r.ok() ? getString(R.string.sched_run_ok, r.done)
                                : getString(R.string.sched_run_err,
                                        com.hex91.file.sched.SchedText.errorText(
                                                ScheduleActivity.this, r)));
                    }
                });
            }
        }, "sched-run").start();
    }

    // ---------------------------------------------------------------- 新建 / 编辑

    private void edit(final ScheduleItem src) {
        final boolean isNew = src == null;
        final ScheduleItem work = isNew ? new ScheduleItem() : src.copy();
        if (isNew) {
            String d = getIntent().getStringExtra(EXTRA_DIR);
            if (d != null && !d.isEmpty()) work.target = d;
        }

        int pad = ToolUi.dp(this, 16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, 0, pad, 0);

        box.addView(label(R.string.sched_label_rule));
        final EditText etRule = new EditText(this);
        etRule.setSingleLine(true);
        etRule.setHint(R.string.sched_rule_hint);
        etRule.setText(work.rule);
        box.addView(etRule);

        box.addView(label(R.string.sched_label_action));
        final Spinner sp = new Spinner(this);
        final String[] acts = {
                ScheduleItem.ACTION_CLEAN, ScheduleItem.ACTION_ZIP, ScheduleItem.ACTION_EXPIRE
        };
        final String[] names = {
                getString(R.string.sched_action_clean),
                getString(R.string.sched_action_zip),
                getString(R.string.sched_action_expire)
        };
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        for (int i = 0; i < acts.length; i++) {
            if (acts[i].equals(work.action)) sp.setSelection(i);
        }
        box.addView(sp);

        box.addView(label(R.string.sched_label_target));
        final EditText etTarget = new EditText(this);
        etTarget.setSingleLine(true);
        etTarget.setHint(R.string.sched_target_hint);
        etTarget.setText(work.target);
        box.addView(etTarget);

        final TextView lbDays = label(R.string.sched_label_days);
        final EditText etDays = new EditText(this);
        etDays.setSingleLine(true);
        etDays.setInputType(InputType.TYPE_CLASS_NUMBER);
        etDays.setText(String.valueOf(work.param <= 0 ? 7 : work.param));
        box.addView(lbDays);
        box.addView(etDays);

        // 只有「清理旧文件」需要天数：一直显示会让另两种动作看起来也能配天数
        final Runnable syncDays = new Runnable() {
            public void run() {
                boolean exp = ScheduleItem.ACTION_EXPIRE.equals(acts[sp.getSelectedItemPosition()]);
                lbDays.setVisibility(exp ? View.VISIBLE : View.GONE);
                etDays.setVisibility(exp ? View.VISIBLE : View.GONE);
            }
        };
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { syncDays.run(); }
            public void onNothingSelected(AdapterView<?> p) { syncDays.run(); }
        });
        syncDays.run();

        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        AlertDialog.Builder b = new HfDialogBuilder(this);
        b.setTitle(isNew ? R.string.sched_dialog_new : R.string.sched_dialog_edit);
        b.setView(sc);
        b.setPositiveButton(R.string.sched_save, null);
        b.setNegativeButton(R.string.common_cancel, null);
        final AlertDialog dlg = b.create();
        // 覆写确定按钮：校验失败时只提示、不关对话框，输入内容不会丢
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dlg.getButton(DialogInterface.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                String rule = etRule.getText().toString().trim();
                                if (!ScheduleRule.valid(rule)) {
                                    toast(getString(R.string.sched_rule_invalid));
                                    return;
                                }
                                String target = etTarget.getText().toString().trim();
                                if (target.isEmpty() || !new File(target).isDirectory()) {
                                    toast(getString(R.string.sched_target_invalid));
                                    return;
                                }
                                // R-42：保存时就按真实卷根判一次，/storage/emulated/0 与可移动卷根都要拦
                                if (ScheduleRunner.isDangerous(target, VolumeRoots.of(ScheduleActivity.this))) {
                                    toast(getString(R.string.sched_target_dangerous));
                                    return;
                                }
                                work.rule = rule;
                                work.action = acts[sp.getSelectedItemPosition()];
                                work.target = target;
                                if (ScheduleItem.ACTION_EXPIRE.equals(work.action)) {
                                    int days;
                                    try {
                                        days = Integer.parseInt(etDays.getText().toString().trim());
                                    } catch (NumberFormatException e) {
                                        days = 0;
                                    }
                                    if (days <= 0) {
                                        toast(getString(R.string.sched_days_invalid));
                                        return;
                                    }
                                    work.param = days;
                                }
                                save(work, isNew);
                                dlg.dismiss();
                                reload();
                                toast(getString(R.string.sched_saved));
                            }
                        });
            }
        });
        dlg.show();
    }

    private void save(ScheduleItem work, boolean isNew) {
        List<ScheduleItem> list = ScheduleStore.load(this);
        if (isNew) {
            work.id = ScheduleCodec.nextId(list);
            list.add(work);
        } else {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id != work.id) continue;
                list.set(i, work);
                break;
            }
        }
        ScheduleStore.saveAndReschedule(this, list);
    }

    private TextView label(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(12f);
        t.setTextColor(ThemeManager.textSub(this));
        t.setPadding(0, ToolUi.dp(this, 10), 0, 0);
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- 列表

    private class Adapter extends BaseAdapter {
        public int getCount() { return data.size(); }
        public Object getItem(int i) { return data.get(i); }
        public long getItemId(int i) { return data.get(i).id; }

        public View getView(int i, View convert, ViewGroup parent) {
            if (convert == null) {
                convert = android.view.LayoutInflater.from(ScheduleActivity.this)
                        .inflate(R.layout.item_result, parent, false);
            }
            ScheduleItem it = data.get(i);
            TextView name = (TextView) convert.findViewById(R.id.name);
            TextView detail = (TextView) convert.findViewById(R.id.detail);

            String leaf = it.target == null ? "" : it.target;
            int slash = leaf.lastIndexOf('/');
            if (slash >= 0 && slash < leaf.length() - 1) leaf = leaf.substring(slash + 1);
            name.setText(getString(ScheduleReceiver.actionLabel(it.action)) + " · " + leaf);

            StringBuilder d = new StringBuilder(it.rule);
            long next = it.enabled ? ScheduleRule.nextRun(it.rule, System.currentTimeMillis()) : -1;
            d.append(" · ");
            if (!it.enabled) d.append(getString(R.string.sched_off));
            else if (next > 0) d.append(getString(R.string.sched_next, FileOps.formatTime(next)));
            else d.append(getString(R.string.sched_rule_invalid_short));
            if (it.lastResult != null && !it.lastResult.isEmpty()) {
                d.append(" · ").append(it.lastResult);
            }
            detail.setText(d.toString());
            // 停用的整行压暗，扫一眼就能分出哪些在跑
            convert.setAlpha(it.enabled ? 1f : 0.55f);
            return convert;
        }
    }
}
