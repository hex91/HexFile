package com.hex91.file.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.hex91.file.apk.DirectInstaller;
import com.hex91.file.apk.InstallConfirm;
import com.hex91.file.apk.SplitInstaller;

/**
 * v2.13.0（F-03）：安装的会话结果接收器；v2.121.0 起同时服务单文件与分包两条通道。
 *
 * <p>PackageInstaller 的 commit 结果只能通过 {@code PendingIntent} 送回来，
 * 所以必须有个组件存在。这里按 action 分发：{@code DIRECT_INSTALL_RESULT} 交给
 * {@link DirectInstaller}，其余交给 {@link SplitInstaller}。真正的状态翻译与回调
 * 都在 apk 层，接收器只活几百毫秒，不该持有判断逻辑。</p>
 *
 * <p>单文件通道里，若界面已销毁（{@code toUi=false}），由本类补一条通知——
 * 「装没装上」不能因为用户退出了页面就消失。分包通道的通知仍在 SplitInstaller 内，
 * 两条路径的通知渠道不同，不会互相覆盖。</p>
 *
 * <p>清单里声明 {@code exported="false"}：PendingIntent 是我们自己建、由系统发出的，
 * 显式组件 + 自建 PendingIntent 不需要导出；导出反而让任何应用都能伪造「安装成功」。</p>
 */
public class InstallResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        if (DirectInstaller.ACTION_RESULT.equals(intent.getAction())) {
            DirectInstaller.Result r = DirectInstaller.deliver(ctx, intent);
            if (r == null) return;
            if (r.pendingUserAction) {
                if (!InstallConfirm.launch(ctx, r.confirm)) {
                    DirectInstaller.Result failed = DirectInstaller.confirmationFailed(null);
                    if (failed != null && !failed.toUi) {
                        InstallNotifier.notify(ctx, failed.code, failed.detail);
                    }
                }
                return;
            }
            if (!r.toUi) InstallNotifier.notify(ctx, r.code, r.detail);
            return;
        }
        SplitInstaller.deliver(ctx, intent);
    }
}
