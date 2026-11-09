package com.hex91.file;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 局域网快传用到的设备信息与网段探测（v1.74.0）。
 *
 * <p>把 Build / Settings / NetworkInterface 这些 Android 相关但无状态的东西集中在这里，
 * 让 {@link LanNode} 只关心收发流程。</p>
 */
public final class LanNet {

    private static final String PREFS = "lan_share";
    private static final String KEY_ID = "device_id";

    private LanNet() {
    }

    /** 本机展示名：优先「厂商 型号」，去掉重复词（小米手机常见 "Xiaomi Xiaomi 13"） */
    public static String deviceName() {
        String brand = Build.BRAND == null ? "" : Build.BRAND.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.length() == 0) model = "Android";
        if (brand.length() == 0) return model;
        String b = brand.substring(0, 1).toUpperCase() + brand.substring(1);
        if (model.toLowerCase().startsWith(brand.toLowerCase())) return model;
        return b + " " + model;
    }

    /**
     * 稳定设备 ID：优先 ANDROID_ID（同设备同签名稳定），拿不到时生成 UUID 持久化。
     * 不能用 IP 当 ID——切换 Wi-Fi 就变，列表里会留下幽灵设备。
     */
    public static String selfId(Context c) {
        String id = null;
        try {
            id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }
        if (id != null && id.length() >= 6) return LanProtocol.normalizeId("a" + id);
        String saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ID, null);
        if (saved != null && saved.length() > 0) return saved;
        String gen = LanProtocol.normalizeId("u" + UUID.randomUUID().toString().replace("-", ""));
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ID, gen).apply();
        return gen;
    }

    /** 主 IPv4 地址（热点/多网段时优先 192.168/10./172.16-31 这类私网地址） */
    public static String localIp() {
        String fallback = null;
        try {
            List<NetworkInterface> nis = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : nis) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String h = a.getHostAddress();
                    if (h == null) continue;
                    if (h.startsWith("192.168.") || h.startsWith("10.")
                            || h.startsWith("172.16.") || h.startsWith("172.17.")
                            || h.startsWith("172.18.") || h.startsWith("172.19.")
                            || h.startsWith("172.2") || h.startsWith("172.30.")
                            || h.startsWith("172.31.")) {
                        return h;
                    }
                    if (fallback == null) fallback = h;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback == null ? "" : fallback;
    }

    /**
     * 所有可能的广播地址：每个网卡的定向广播 + 受限广播 255.255.255.255。
     *
     * <p>只发 255.255.255.255 在部分路由器/ROM 上会被丢，只发定向广播又可能因为网卡没
     * 报 broadcast 而漏掉，所以两者都发一遍，去重。</p>
     */
    public static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<InetAddress>();
        try {
            List<NetworkInterface> nis = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : nis) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b)) out.add(b);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            InetAddress all = InetAddress.getByName("255.255.255.255");
            if (!out.contains(all)) out.add(all);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 组播锁：部分 ROM 在息屏/省电时会过滤广播，持有锁能显著提高发现成功率 */
    public static WifiManager.MulticastLock acquireMulticast(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiManager.MulticastLock lock = wm.createMulticastLock("HexFile:lan");
            lock.setReferenceCounted(false);
            lock.acquire();
            return lock;
        } catch (Throwable t) {
            return null;
        }
    }

    public static WifiManager.WifiLock acquireWifi(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiManager.WifiLock lock =
                    wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HexFile:lan-tx");
            lock.setReferenceCounted(false);
            lock.acquire();
            return lock;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 统一释放（MulticastLock/WifiLock 都可能为 null） */
    public static void releaseQuietly(Object lock) {
        try {
            if (lock instanceof WifiManager.MulticastLock) {
                ((WifiManager.MulticastLock) lock).release();
            } else if (lock instanceof WifiManager.WifiLock) {
                ((WifiManager.WifiLock) lock).release();
            }
        } catch (Throwable ignored) {
        }
    }
}
