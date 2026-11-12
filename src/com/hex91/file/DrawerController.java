package com.hex91.file;

import android.content.Intent;
import android.os.Environment;
import android.view.View;
import android.widget.TextView;

import java.io.File;

/**
 * 左侧抽屉（v2.0.0，O-15 刀5）：☰ 打开 / 遮罩关闭 / 16 个入口分发 / 存储与 Root 状态。
 *
 * <p>整段从 MainActivity 搬来，只有三类取值换了来源：{@code findViewById} → {@link Host#find}、
 * {@code this} → {@link Host#ctx()}、{@code startActivity} → {@link Host#open}。
 * 抽屉的两个视图引用（drawer / drawerMask）跟着方法一起搬走，Activity 不再持有。</p>
 */
final class DrawerController {

    interface Host {
        android.content.Context ctx();

        /** activity.findViewById(id) */
        View find(int id);

        Pane active();

        /** push=true 等价原来的两参 loadDir */
        void loadDir(Pane p, File dir, boolean push);

        void toast(String s);

        void showBookmarks();

        void open(Intent i);

        /** runOnUiThread + alive 检查 */
        void ui(Runnable r);
    }

    private final Host host;

    private View drawer, drawerMask;

    DrawerController(Host host) {
        this.host = host;
    }

    /** 返回键用：抽屉是否正在显示（原来在 MainActivity 直接看 drawer 的可见性） */
    boolean isOpen() {
        return drawer != null && drawer.getVisibility() == View.VISIBLE;
    }

    /** 抽屉初始化：☰ 打开 / 遮罩点击关闭 / 菜单项分发 */
    void setupDrawer() {
        drawer = host.find(R.id.drawer);
        drawerMask = host.find(R.id.drawer_mask);
        try {
            String ver = host.ctx().getPackageManager().getPackageInfo(host.ctx().getPackageName(), 0).versionName;
            ((TextView) host.find(R.id.drawer_version)).setText("v" + ver);
        } catch (Exception ignored) {}
        host.find(R.id.btn_drawer).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openDrawer(); }
        });
        drawerMask.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); }
        });
        host.find(R.id.drawer_bookmark).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); host.showBookmarks(); }
        });
        // v1.42.0：本地存储区（根目录 / 内部存储）
        host.find(R.id.drawer_root).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                host.loadDir(host.active(), new File("/"), true);
                if (!RootUtil.isRootAvailable())
                    host.toast("未获取 Root，仅能浏览可读部分（/data 等目录不可见）");
            }
        });
        host.find(R.id.drawer_sd).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                host.loadDir(host.active(), Environment.getExternalStorageDirectory(), true);
            }
        });
        refreshDrawerStorage();
        checkRootAsync();
        host.find(R.id.drawer_trash).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                host.open(new Intent(host.ctx(), TrashActivity.class));
            }
        });
        // v1.45.0：应用管理（已安装应用列表 + 提取安装包）
        host.find(R.id.drawer_apps).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                host.open(new Intent(host.ctx(), AppManagerActivity.class));
            }
        });
        host.find(R.id.drawer_backups).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.54.0：备份管理（列出所有备份，支持已卸载应用还原）
                host.open(new Intent(host.ctx(), BackupManagerActivity.class));
            }
        });
        // v1.68.0：签名管理（导入 / 创建 / 查看 / 重命名 / 删除 / 设为默认）
        host.find(R.id.drawer_signkeys).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                host.open(new Intent(host.ctx(), SignKeyManagerActivity.class));
            }
        });
        // v1.69.0：Activity 记录（无障碍监听页面切换 + 透明浮窗）
        host.find(R.id.drawer_activity_log).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                host.open(new Intent(host.ctx(), ActivityLogActivity.class));
            }
        });
        // v1.70.0：工具箱（编码 / 加解密 / 进制 / 网络 / 时间戳）
        host.find(R.id.drawer_toolbox).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                host.open(new Intent(host.ctx(), ToolboxActivity.class));
            }
        });
        // v1.74.0：局域网快传（雷达扫描同网设备，点光点传文件）
        host.find(R.id.drawer_lan).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                host.open(new Intent(host.ctx(), LanShareActivity.class));
            }
        });
        // v1.91.0：抽屉里的「设置」已删除（⋮ 菜单同一入口足够），见 showMoreMenu
        host.find(R.id.drawer_theme).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.48.0：主题设置页（22 套色板 + 明暗模式）
                host.open(new Intent(host.ctx(), ThemeActivity.class));
            }
        });
        host.find(R.id.drawer_remote).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.50.0：远程管理（局域网 HTTP 服务器 + 浏览器管理手机文件）
                host.open(new Intent(host.ctx(), RemoteActivity.class));
            }
        });
        host.find(R.id.drawer_net).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.56.0：网络存储（SMB/WebDAV 客户端，直连电脑共享）
                host.open(new Intent(host.ctx(), NetStorageActivity.class));
            }
        });
        host.find(R.id.drawer_storage).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.59.0：存储分析（目录占用树下钻 + 大文件清理 + 重复文件查找）
                host.open(new Intent(host.ctx(), StorageAnalysisActivity.class));
            }
        });
        host.find(R.id.drawer_smali).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.61.0：Smali 语法查询（红色语法 + 中文注释解析 + 搜索，数据可扩展）
                host.open(new Intent(host.ctx(), SmaliDocActivity.class));
            }
        });
        host.find(R.id.drawer_java2smali).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.62.0：Java → Smali（nb-javac 编译 + dx 打包 + baksmali 反汇编，全离线）
                host.open(new Intent(host.ctx(), Java2SmaliActivity.class));
            }
        });
        host.find(R.id.drawer_terminal).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeDrawer();
                // v1.49.0：内置终端模拟器，开局即当前浏览目录
                String cwd = null;
                try {
                    File d = host.active().dir;
                    if (d != null) cwd = d.getAbsolutePath();
                } catch (Throwable ignore) {
                }
                if (cwd == null || cwd.isEmpty()) {
                    cwd = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                }
                Intent it = new Intent(host.ctx(), TerminalActivity.class);
                it.putExtra(TerminalActivity.EXTRA_CWD, cwd);
                host.open(it);
            }
        });
    }

    /** v1.42.0：抽屉存储容量统计（根目录 / 内部存储），后台线程 StatFs */
    void refreshDrawerStorage() {
        new Thread(new Runnable() {
            public void run() {
                final String rootStat = StorageStat.statFs("/");
                // 内部存储与根目录同属 /data 分区，数值必然一样；这行只报可用量，避免两行完全重复
                final String sdStat = StorageStat.statFree(Environment.getExternalStorageDirectory().getPath());
                host.ui(new Runnable() {
                    public void run() {
                        ((TextView) host.find(R.id.drawer_root_usage)).setText(rootStat);
                        ((TextView) host.find(R.id.drawer_sd_usage)).setText(sdStat);
                    }
                });
            }
        }, "drawer-storage").start();
    }

    /** v1.42.0：root 检测（su -c id），结果落 RootUtil 缓存并更新抽屉状态行 */
    void checkRootAsync() {
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = RootUtil.detect();
                host.ui(new Runnable() {
                    public void run() {
                        TextView tv = (TextView) host.find(R.id.drawer_rootstat);
                        tv.setText(ok ? "Root: 已授权 ✓" : "Root: 未获取");
                        tv.setTextColor(ok ? 0xFFAED581 : 0xFF90A4AE);
                    }
                });
            }
        }, "root-detect").start();
    }

    void openDrawer() {
        drawerMask.setVisibility(View.VISIBLE);
        drawerMask.setAlpha(0f);
        drawerMask.animate().alpha(1f).setDuration(220).start();
        drawer.setVisibility(View.VISIBLE);
        // GONE 期间宽度为 0，等布局完成后再从屏外滑入
        drawer.post(new Runnable() {
            public void run() {
                drawer.setTranslationX(-drawer.getWidth() - 20);
                drawer.animate().translationX(0).setDuration(220).start();
                // v1.73.1：每次打开回到菜单顶部。此前滚到中间再关掉，
                // 下次打开会停在半截位置，看不到「本地」区和前几个入口。
                View scroll = host.find(R.id.drawer_scroll);
                if (scroll != null) scroll.scrollTo(0, 0);
            }
        });
    }

    void closeDrawer() {
        drawer.animate().translationX(-drawer.getWidth() - 20).setDuration(180)
                .withEndAction(new Runnable() {
                    public void run() {
                        drawer.setVisibility(View.GONE);
                        drawerMask.setVisibility(View.GONE);
                    }
                }).start();
        drawerMask.animate().alpha(0f).setDuration(180).start();
    }
}
