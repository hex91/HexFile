package com.hex91.file.ui;

import com.hex91.file.*;
import com.hex91.file.R;
import com.hex91.file.fs.FileOps;
import com.hex91.file.fs.FileTask;
import com.hex91.file.fs.FileTaskEngine;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v1.99.3：任务行的唯一渲染实现。
 *
 * <p>原来这段代码只在主界面的弹窗里（{@code MainActivity.taskRow}）。抽出独立任务中心后
 * 两处要画一模一样的东西，复制一份必然走形（改了一处忘了另一处），所以收在这里。</p>
 *
 * <p>行内动作直接驱动引擎：取消按 id、重试按 id —— 界面不持有 {@link FileTask} 的引用做事，
 * 任务可能已经被引擎的淘汰策略从列表里移除。</p>
 */
final class TaskRows {

    private TaskRows() {}

    static View build(final Context c, final FileTask t) {
        // 外层 wrapper 是必须的：ListView 在 setupChild 里把 child 的 LayoutParams 强转成
        // AbsListView.LayoutParams，直接返回带 LinearLayout.LayoutParams 的卡片会
        // ClassCastException（一进任务中心就崩）。wrapper 承担列表的布局参数，
        // 卡片保留自己的 bottomMargin —— AbsListView.LayoutParams 没有 margin 概念。
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout card = UiDecor.card(c);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = UiDecor.dp(c, 8);
        card.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(c);
        name.setText(t.title);
        name.setTextSize(13f);
        name.setTextColor(ThemeManager.text(c));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView state = new TextView(c);
        state.setText(t.stateText());
        state.setTextSize(11.5f);
        boolean bad = t.finished() && t.state != FileTask.DONE;
        state.setTextColor(bad ? ThemeManager.err(c) : ThemeManager.accent(c));
        top.addView(state);
        card.addView(top);

        android.widget.ProgressBar pb = new android.widget.ProgressBar(
                c, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(t.percent());
        // 必须带 theme 解析：进度条颜色是 ?attr/hfAccent，无主题的 getDrawable 会解析失败
        pb.setProgressDrawable(c.getResources().getDrawable(R.drawable.hf_task_progress, c.getTheme()));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiDecor.dp(c, 4));
        plp.topMargin = UiDecor.dp(c, 8);
        card.addView(pb, plp);

        LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        TextView info = new TextView(c);
        info.setText(detail(t));
        info.setTextSize(11f);
        info.setTextColor(ThemeManager.textSub(c));
        info.setPadding(0, UiDecor.dp(c, 7), UiDecor.dp(c, 6), 0);
        foot.addView(info, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (t.cancelable()) {
            TextView cancel = UiDecor.action(c, "✕", "取消", ThemeManager.err(c));
            cancel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { FileTaskEngine.get().cancel(t.id); }
            });
            foot.addView(cancel);
        } else if (t.finished() && t.state != FileTask.DONE
                && t.srcs != null && !t.srcs.isEmpty()) {
            // 失败 / 部分失败 / 取消过的任务一键重来 —— 源和目标都还在任务里，
            // 不需要用户再选一遍（大目录尤其折腾）
            TextView again = UiDecor.action(c, "↻", "重试", ThemeManager.accent(c));
            again.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    FileTask nt = FileTaskEngine.get().retry(t.id);
                    if (nt == null) {
                        Toast.makeText(c, "无法重试：原始来源已不可用",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        TaskService.start(c);
                        Toast.makeText(c, "已重新提交", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            foot.addView(again);
        }
        card.addView(foot);
        wrap.addView(card);
        return wrap;
    }

    /** 两行明细：字节/文件数（运行中加速度），失败原因，最后一行目标路径 */
    static String detail(FileTask t) {
        StringBuilder sb = new StringBuilder();
        sb.append(FileOps.formatSize(t.doneBytes)).append(" / ")
                .append(FileOps.formatSize(t.totalBytes))
                .append(" · ").append(t.doneFiles).append('/').append(t.totalFiles).append(" 个文件");
        if (t.state == FileTask.RUNNING) {
            long sp = t.speed(System.currentTimeMillis());
            if (sp > 0) sb.append(" · ").append(FileOps.formatSize(sp)).append("/s");
        }
        if (t.message != null && t.finished() && t.state != FileTask.DONE) {
            sb.append('\n').append(t.message);
        }
        sb.append('\n').append(t.dstPath);
        return sb.toString();
    }
}
