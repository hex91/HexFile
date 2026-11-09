package com.hex91.file;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

/**
 * v1.76.0：统一美化的 AlertDialog.Builder。
 *
 * <p>用法与系统 {@code AlertDialog.Builder} 完全一致，只把 {@code new AlertDialog.Builder(ctx)}
 * 换成 {@code new HfDialogBuilder(ctx)} 即可：</p>
 *
 * <pre>new HfDialogBuilder(this).setTitle("删除").setMessage("不可恢复")
 *         .setPositiveButton("删除", null).setNegativeButton("取消", null).show();</pre>
 *
 * <p>关键在于只重写 {@link #create()}：{@code Builder.show()} 内部是虚调用 create()，
 * 所以即便链式调用里 setTitle 等方法仍返回父类型 {@code AlertDialog.Builder}，
 * 最后一步 show() 依然会走到这里——不需要把二十多个 setter 全部重写一遍。</p>
 *
 * <p>标题/正文/按钮这些子 View 要等 {@code AlertDialog.onCreate} 里 installContent
 * 之后才存在，因此内容美化挂在 OnShowListener 上；面板底色（windowBackground）
 * 在 create() 里先设一次，避免弹窗首帧闪一下系统方角。</p>
 */
public class HfDialogBuilder extends AlertDialog.Builder {

    public HfDialogBuilder(Context context) {
        super(context);
    }

    public HfDialogBuilder(Context context, int themeResId) {
        super(context, themeResId);
    }

    @Override
    public AlertDialog create() {
        final AlertDialog d = super.create();
        UiDecor.polishDialog(d);
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                UiDecor.polishDialog(d);
            }
        });
        return d;
    }
}
