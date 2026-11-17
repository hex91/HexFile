package com.hex91.file.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.hex91.file.apk.SplitInstaller;

/**
 * v2.13.0（F-03）：分包安装的会话结果接收器。
 *
 * <p>PackageInstaller 的 commit 结果只能通过 {@code PendingIntent} 送回来，
 * 所以必须有个组件存在。这里只做转发：真正的状态翻译、通知、界面回调都在
 * {@link SplitInstaller#deliver} 里 —— 接收器只活几百毫秒，不该持有判断逻辑。</p>
 *
 * <p>清单里声明 {@code exported="false"}：PendingIntent 是我们自己建、由系统发出的，
 * 显式组件 + 自建 PendingIntent 不需要导出；导出反而让任何应用都能伪造「安装成功」。</p>
 */
public class InstallResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        SplitInstaller.deliver(ctx, intent);
    }
}
