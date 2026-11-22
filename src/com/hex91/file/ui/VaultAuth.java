package com.hex91.file.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.hex91.file.R;
import com.hex91.file.fs.VaultBio;

/**
 * 指纹/面容快捷解锁的认证入口（v2.113.0，F-25）。
 *
 * <p>平台 API 28 才有 {@code android.hardware.biometrics.BiometricPrompt}，而工程 minSdk 是 21。
 * 所以真正碰那个类的代码全部关在 {@link Api28} 这个嵌套类里：低版本设备只会执行到
 * {@link #available} 的版本判断，<b>永远不会加载</b> {@link Api28}，也就不会在类校验阶段
 * 撞上「这个类不存在」。</p>
 *
 * <p>低版本（21–27）不给入口，直接走口令。理由见 {@link VaultBio#deviceReady}：
 * 那几版没有 BiometricPrompt，而 KeyguardManager 的设备凭证确认不会被 KeyStore 认作
 * 有效认证，做出来也是个点了必然失败的按钮。</p>
 */
public final class VaultAuth {

    public interface Cb {
        /** 认证通过。之后由调用方解封主密钥 —— 这里不碰密钥，避免把两层职责混在一起 */
        void onOk();

        /** 认证没通过；{@code reason == null} 表示用户主动取消，不必提示 */
        void onFail(String reason);
    }

    private VaultAuth() {
    }

    /** 当前设备 + 当前版本是否支持指纹快捷解锁（不代表已经开启，见 {@link VaultBio#exists}） */
    public static boolean available(Context c) {
        return Build.VERSION.SDK_INT >= 28 && VaultBio.deviceReady(c);
    }

    public static void prompt(Activity a, Cb cb) {
        if (a == null || cb == null) return;
        if (Build.VERSION.SDK_INT < 28 || !VaultBio.deviceReady(a)) {
            postFail(cb, a.getString(R.string.vault_bio_unsupported));
            return;
        }
        try {
            Api28.prompt(a, cb);
        } catch (Throwable t) {
            // 部分 ROM 的 BiometricPrompt 会在没有硬件时抛，不能让「点一下指纹」变成崩溃
            postFail(cb, String.valueOf(t.getMessage()));
        }
    }

    private static void postFail(final Cb cb, final String reason) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                cb.onFail(reason);
            }
        });
    }

    /** 只有 API 28+ 会加载这个类，所以可以在这里直接引用 BiometricPrompt */
    private static final class Api28 {

        static void prompt(final Activity a, final Cb cb) {
            final Handler ui = new Handler(Looper.getMainLooper());
            android.hardware.biometrics.BiometricPrompt bp =
                    new android.hardware.biometrics.BiometricPrompt.Builder(a)
                            .setTitle(a.getString(R.string.vault_bio_title))
                            .setSubtitle(a.getString(R.string.vault_bio_sub))
                            .setNegativeButton(a.getString(R.string.common_cancel),
                                    a.getMainExecutor(),
                                    new android.content.DialogInterface.OnClickListener() {
                                        public void onClick(android.content.DialogInterface d, int w) {
                                            ui.post(new Runnable() {
                                                public void run() {
                                                    cb.onFail(null);   // 主动取消：静默退出
                                                }
                                            });
                                        }
                                    })
                            .build();
            bp.authenticate(new android.os.CancellationSignal(), a.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                            ui.post(new Runnable() {
                                public void run() {
                                    cb.onOk();
                                }
                            });
                        }

                        public void onAuthenticationError(final int code, CharSequence msg) {
                            final String m = msg == null ? String.valueOf(code) : msg.toString();
                            ui.post(new Runnable() {
                                public void run() {
                                    cb.onFail(m);
                                }
                            });
                        }

                        public void onAuthenticationFailed() {
                            // 单次比对失败：系统仍停在认证界面让用户重试，这里不回调，
                            // 否则每按错一次就闪一次错误提示
                        }
                    });
        }
    }
}
