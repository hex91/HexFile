package com.hex91.file;

import com.hex91.file.R;
import com.hex91.file.fs.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.99.3：独立的任务中心。
 *
 * <p><b>为什么从弹窗改成页面</b>：弹窗里最多看四五条，任务一多就得在里面滚；
 * 而且弹窗的生命周期绑在主界面上 —— 用户切走再回来，弹窗没了，进度也就看不见了。
 * 任务本身是进程级的，放在独立页面里更自然：可以随时进来、看完整历史、逐条重试。</p>
 *
 * <p><b>刷新策略</b>：引擎回调是「有变化就通知」，但一个任务在跑时每秒会来好几次；
 * 页面用 400ms 轮询 + 签名比较，签名没变就不 {@code notifyDataSetChanged()} ——
 * 已完成的任务躺着不动时，列表完全不重绘。</p>
 *
 * <p>行渲染与主界面共用 {@link TaskRows}，不各写一份。</p>
 */
public class TaskCenterActivity extends BaseActivity {

    private static final long TICK_MS = 400L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<FileTask> data = new ArrayList<FileTask>();
    private TaskAdapter adapter;
    private TextView empty, title;
    private String lastSig = "";

    private final Runnable tick = new Runnable() {
        public void run() {
            reload();
            ui.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_task_center);

        title = (TextView) findViewById(R.id.tv_title);
        empty = (TextView) findViewById(R.id.tv_empty);
        ListView lv = (ListView) findViewById(R.id.list_tasks);
        adapter = new TaskAdapter();
        lv.setAdapter(adapter);

        // 顶栏按钮沿用回收站的胶囊样式：accent 顶栏上必须用「栏上」版本的配色
        Button clearDone = (Button) findViewById(R.id.btn_clear_done);
        UiDecor.barButtonOnBar(clearDone, this, UiDecor.KIND_SOFT, 11.5f);
        UiDecor.gap(clearDone, this, 4f);
        clearDone.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = 0;
                for (FileTask t : FileTaskEngine.get().tasks()) if (t.finished()) n++;
                if (n == 0) { toast("没有已结束的任务"); return; }
                FileTaskEngine.get().clearFinished();
                toast("已清理 " + n + " 条记录");
                reload(true);
            }
        });

        Button cancelAll = (Button) findViewById(R.id.btn_cancel_all);
        UiDecor.barButtonOnBar(cancelAll, this, UiDecor.KIND_DANGER, 11.5f);
        cancelAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!FileTaskEngine.get().hasActive()) { toast("没有进行中的任务"); return; }
                new HfDialogBuilder(TaskCenterActivity.this)
                        .setTitle("取消全部任务")
                        .setMessage("将停止所有排队与进行中的任务，已经复制完成的部分会保留。")
                        .setPositiveButton("全部取消", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                FileTaskEngine.get().cancelAll();
                                reload(true);
                            }
                        })
                        .setNegativeButton("继续", null)
                        .show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);      // 页面不可见就别再定时刷新（任务本身在引擎线程里跑）
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void reload() { reload(false); }

    /**
     * @param force true = 无条件重绘（刚做过清理/取消，签名可能没变但内容已不同）
     */
    private void reload(boolean force) {
        List<FileTask> list = FileTaskEngine.get().tasks();
        StringBuilder sig = new StringBuilder();
        for (FileTask t : list) {
            sig.append(t.id).append(':').append(t.state).append(':')
                    .append(t.percent()).append(':').append(t.doneFiles).append(';');
        }
        String s = sig.toString();
        if (!force && s.equals(lastSig)) return;   // 没有任何变化，不重绘
        lastSig = s;

        data.clear();
        data.addAll(list);
        adapter.notifyDataSetChanged();

        int active = 0;
        for (FileTask t : list) if (t.cancelable()) active++;
        title.setText(active > 0 ? "文件任务 · " + active + " 个进行中" : "文件任务");
        empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        if (list.isEmpty()) {
            empty.setText("暂无任务\n\n复制 / 移动 / 压缩时会在这里显示进度，可随时取消或重试失败的项。");
        }
    }

    private class TaskAdapter extends BaseAdapter {
        public int getCount() { return data.size(); }

        public Object getItem(int i) { return data.get(i); }

        public long getItemId(int i) { return data.get(i).id; }

        public View getView(int i, View convert, ViewGroup parent) {
            // 最新在最上面；ListView 只给可见行调 getView，重建一行的成本可以忽略
            FileTask t = data.get(data.size() - 1 - i);
            return TaskRows.build(TaskCenterActivity.this, t);
        }
    }
}
