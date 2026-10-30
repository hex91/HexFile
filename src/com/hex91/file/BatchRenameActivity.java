package com.hex91.file;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量重命名（v1.30.0）：三种规则 + 实时预览 + 冲突检测 + 两阶段重命名。
 * 入口：MainActivity 多选栏「重命名」按钮，传入 EXTRA_PATHS（选中文件绝对路径列表）。
 */
public class BatchRenameActivity extends BaseActivity {

    public static final String EXTRA_PATHS = "paths";

    private List<File> files = new ArrayList<File>();
    private List<Row> rows = new ArrayList<Row>();
    private RowAdapter adapter;

    private TextView title, warn, applyBtn;
    private ListView list;
    private RadioGroup modeGroup;
    private RadioButton rbNum, rbRep, rbExt;
    private LinearLayout panelNum, panelRep, panelExt;
    private EditText etPrefix, etSuffix, etStart, etStep, etDigits, etFind, etRepl, etNewExt;
    private CheckBox cbKeepNum, cbRegex, cbCi, cbKeepRep, cbOnlyExt;

    static class Row {
        File file;
        String oldName;
        String newName;
        boolean conflict;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_rename);

        ArrayList<String> paths = getIntent().getStringArrayListExtra(EXTRA_PATHS);
        if (paths == null) paths = new ArrayList<String>();
        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) files.add(f);
        }

        title = (TextView) findViewById(R.id.br_title);
        title.setText("批量重命名 (" + files.size() + " 项)");

        warn = (TextView) findViewById(R.id.br_warn);
        applyBtn = (TextView) findViewById(R.id.br_apply);
        list = (ListView) findViewById(R.id.br_list);

        modeGroup = (RadioGroup) findViewById(R.id.br_mode);
        rbNum = (RadioButton) findViewById(R.id.rb_num);
        rbRep = (RadioButton) findViewById(R.id.rb_rep);
        rbExt = (RadioButton) findViewById(R.id.rb_ext);
        panelNum = (LinearLayout) findViewById(R.id.panel_num);
        panelRep = (LinearLayout) findViewById(R.id.panel_rep);
        panelExt = (LinearLayout) findViewById(R.id.panel_ext);

        etPrefix = (EditText) findViewById(R.id.et_prefix);
        etSuffix = (EditText) findViewById(R.id.et_suffix);
        etStart = (EditText) findViewById(R.id.et_start);
        etStep = (EditText) findViewById(R.id.et_step);
        etDigits = (EditText) findViewById(R.id.et_digits);
        etFind = (EditText) findViewById(R.id.et_find);
        etRepl = (EditText) findViewById(R.id.et_repl);
        etNewExt = (EditText) findViewById(R.id.et_newext);
        cbKeepNum = (CheckBox) findViewById(R.id.cb_keepnum);
        cbRegex = (CheckBox) findViewById(R.id.cb_regex);
        cbCi = (CheckBox) findViewById(R.id.cb_ci);
        cbKeepRep = (CheckBox) findViewById(R.id.cb_keeprep);
        cbOnlyExt = (CheckBox) findViewById(R.id.cb_onlyext);

        // 模式切换显隐面板
        modeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                panelNum.setVisibility(rbNum.isChecked() ? View.VISIBLE : View.GONE);
                panelRep.setVisibility(rbRep.isChecked() ? View.VISIBLE : View.GONE);
                panelExt.setVisibility(rbExt.isChecked() ? View.VISIBLE : View.GONE);
                refresh();
            }
        });

        // 输入变化实时预览
        TextWatcher tw = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { refresh(); }
            public void afterTextChanged(Editable e) {}
        };
        etPrefix.addTextChangedListener(tw);
        etSuffix.addTextChangedListener(tw);
        etStart.addTextChangedListener(tw);
        etStep.addTextChangedListener(tw);
        etDigits.addTextChangedListener(tw);
        etFind.addTextChangedListener(tw);
        etRepl.addTextChangedListener(tw);
        etNewExt.addTextChangedListener(tw);
        CompoundButton.OnCheckedChangeListener cw = new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean c) { refresh(); }
        };
        cbKeepNum.setOnCheckedChangeListener(cw);
        cbRegex.setOnCheckedChangeListener(cw);
        cbCi.setOnCheckedChangeListener(cw);
        cbKeepRep.setOnCheckedChangeListener(cw);
        cbOnlyExt.setOnCheckedChangeListener(cw);

        adapter = new RowAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.br_close).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.br_cancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        applyBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { apply(); }
        });

        refresh();
    }

    private int currentMode() {
        if (rbRep.isChecked()) return 1;
        if (rbExt.isChecked()) return 2;
        return 0;
    }

    private int safeInt(EditText et, String def) {
        try {
            String s = et.getText().toString().trim();
            if (s.isEmpty()) s = def;
            return Integer.parseInt(s);
        } catch (Exception e) {
            try { return Integer.parseInt(def); } catch (Exception e2) { return 0; }
        }
    }

    private String compute(File f, int mode) {
        if (mode == 0) {
            return BatchRenameCore.modeNumber(f.getName(), etPrefix.getText().toString(),
                    safeInt(etStart, "1"), safeInt(etStep, "1"), safeInt(etDigits, "3"),
                    etSuffix.getText().toString(), cbKeepNum.isChecked(), files.indexOf(f));
        } else if (mode == 1) {
            return BatchRenameCore.modeReplace(f.getName(), etFind.getText().toString(),
                    etRepl.getText().toString(), cbRegex.isChecked(), cbCi.isChecked(),
                    cbKeepRep.isChecked());
        } else {
            return BatchRenameCore.modeExt(f.getName(), etNewExt.getText().toString(),
                    cbOnlyExt.isChecked());
        }
    }

    private void refresh() {
        int mode = currentMode();
        rows.clear();
        List<String> newNames = new ArrayList<String>();
        for (File f : files) {
            String nn = compute(f, mode);
            newNames.add(nn);
            Row r = new Row();
            r.file = f;
            r.oldName = f.getName();
            r.newName = nn;
            rows.add(r);
        }
        boolean[] cf = BatchRenameCore.detectConflicts(files, newNames);
        int c = 0;
        for (int i = 0; i < cf.length; i++) {
            rows.get(i).conflict = cf[i];
            if (cf[i]) c++;
        }
        adapter.notifyDataSetChanged();
        if (c > 0) {
            warn.setVisibility(View.VISIBLE);
            warn.setText("存在 " + c + " 个冲突（重名或已存在文件），请调整规则后再执行");
            applyBtn.setEnabled(false);
        } else {
            warn.setVisibility(View.GONE);
            applyBtn.setEnabled(true);
        }
    }

    private void apply() {
        int mode = currentMode();
        List<String> newNames = new ArrayList<String>();
        for (File f : files) newNames.add(compute(f, mode));
        boolean[] cf = BatchRenameCore.detectConflicts(files, newNames);
        for (boolean b : cf) {
            if (b) { toast("存在冲突，无法执行"); return; }
        }
        File dir = files.get(0).getParentFile();

        // 阶段一：全部移到唯一临时名，避免 a→b、b→c 顺序覆盖
        List<File> temps = new ArrayList<File>();
        boolean phase1 = true;
        for (int i = 0; i < files.size(); i++) {
            File src = files.get(i);
            File tmp = uniqueTemp(dir, i);
            if (!src.renameTo(tmp)) { phase1 = false; break; }
            temps.add(tmp);
        }
        if (!phase1) {
            for (int i = 0; i < temps.size(); i++) temps.get(i).renameTo(files.get(i));
            toast("重命名失败：无法移动部分文件（已回滚）");
            refresh();
            return;
        }

        // 阶段二：临时名 → 目标名
        int done = 0;
        for (int i = 0; i < temps.size(); i++) {
            File tgt = new File(dir, newNames.get(i));
            if (temps.get(i).renameTo(tgt)) done++;
            else temps.get(i).renameTo(files.get(i)); // 失败回退原名
        }
        toast("已重命名 " + done + "/" + files.size() + " 项");
        setResult(RESULT_OK);
        finish();
    }

    private File uniqueTemp(File dir, int i) {
        File t = new File(dir, ".mtbr_" + System.nanoTime() + "_" + i);
        while (t.exists()) {
            t = new File(dir, ".mtbr_" + System.nanoTime() + "_" + i + "_" + (int) (Math.random() * 100000));
        }
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() { return rows.size(); }
        @Override
        public Object getItem(int i) { return rows.get(i); }
        @Override
        public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            LinearLayout ll;
            TextView a, b;
            if (v == null) {
                ll = new LinearLayout(BatchRenameActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setPadding(12, 8, 12, 8);
                a = new TextView(BatchRenameActivity.this);
                a.setTextSize(13);
                a.setTextColor(ThemeManager.textSub(BatchRenameActivity.this));
                b = new TextView(BatchRenameActivity.this);
                b.setTextSize(15);
                ll.addView(a);
                ll.addView(b);
            } else {
                ll = (LinearLayout) v;
                a = (TextView) ll.getChildAt(0);
                b = (TextView) ll.getChildAt(1);
            }
            Row r = rows.get(i);
            a.setText(r.oldName);
            b.setText("→ " + (r.newName.isEmpty() ? "(空名·冲突)" : r.newName));
            b.setTextColor(r.conflict
                    ? ThemeManager.err(BatchRenameActivity.this)
                    : ThemeManager.text(BatchRenameActivity.this));
            return ll;
        }
    }
}
