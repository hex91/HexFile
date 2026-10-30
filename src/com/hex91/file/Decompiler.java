package com.hex91.file;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 反编译引擎封装（v1.63.0）：<b>smali → dex → dex2jar（.class）→ CFR（.java）</b>。
 *
 * <p>两个引擎都不进主 dex（主 dex 方法数已接近 64K 上限），而是以 dex 插件形式随 assets
 * 分发，首次使用时释放到私有目录并通过 {@link dalvik.system.DexClassLoader} 按需加载，
 * 全部走反射调用：</p>
 * <ul>
 *   <li><b>dex2jar 2.4.28</b>（Apache 2.0）：{@code Dex2jar.from(byte[]).doTranslate(ByteArrayOutputStream)}
 *       —— 用 ByteArrayOutputStream 重载可绕开 {@code java.nio.file.Path}，minSdk 21 也能跑。</li>
 *   <li><b>CFR 0.152</b>（MIT）：{@code org.benf.cfr.reader.Main.main(String[])}，
 *       单 class 输入 + {@code --extraclasspath} 提供工程级类型信息。</li>
 * </ul>
 *
 * <p>注意：dex2jar 的 {@code doTranslate(ByteArrayOutputStream)} 输出的<b>不是 zip</b>，
 * 而是自定义的长度前缀流 {@code [nameLen:4][name][dataLen:4][data]…}，
 * 由 {@link #writeJar} 组装成真正的 jar。</p>
 */
public final class Decompiler {

    private static final String DIR = "decompile";
    private static final String D2J_ZIP = "d2j-plugin.zip";
    private static final String CFR_ZIP = "cfr-plugin.zip";

    private static volatile dalvik.system.DexClassLoader sCl;
    private static String sErr;

    private Decompiler() {}

    /** 释放并加载插件。成功返回 null，失败返回错误信息。 */
    public static synchronized String ensure(Context ctx) {
        if (sCl != null) return null;
        if (sErr != null) return sErr;
        try {
            File base = new File(ctx.getFilesDir(), DIR);
            base.mkdirs();
            File d2j = new File(base, "d2j.dex");
            File cfr = new File(base, "cfr.dex");
            unpack(ctx, D2J_ZIP, d2j);
            unpack(ctx, CFR_ZIP, cfr);
            File opt = new File(ctx.getCodeCacheDir(), "dec_odex");
            opt.mkdirs();
            sCl = new dalvik.system.DexClassLoader(
                    d2j.getAbsolutePath() + File.pathSeparator + cfr.getAbsolutePath(),
                    opt.getAbsolutePath(), null, Decompiler.class.getClassLoader());
            // 预热：确认两个入口类都在
            sCl.loadClass("com.googlecode.d2j.dex.Dex2jar");
            sCl.loadClass("org.benf.cfr.reader.Main");
            sErr = null;
            return null;
        } catch (Throwable t) {
            sErr = err(t);
            return sErr;
        }
    }

    /** dex → jar（内含 .class）。成功返回 null。 */
    public static String dexToJar(File dex, File outJar) {
        try {
            if (sCl == null) return "反编译插件未加载（应先调用 ensure）";
            byte[] dexBytes = readAll(dex);
            Class<?> c = sCl.loadClass("com.googlecode.d2j.dex.Dex2jar");
            Object inst = c.getMethod("from", byte[].class).invoke(null, dexBytes);
            try {
                inst = c.getMethod("topoLogicalSort").invoke(inst);
            } catch (Throwable ignore) {
                // 老版本无此无参重载，忽略
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            c.getMethod("doTranslate", ByteArrayOutputStream.class).invoke(inst, baos);
            writeJar(baos.toByteArray(), outJar);
            return null;
        } catch (Throwable t) {
            return err(t);
        }
    }

    /**
     * CFR 反编译单个 .class，<b>直接返回 Java 源码字符串</b>。
     *
     * <p>不用 {@code org.benf.cfr.reader.Main.main} 的原因：它内部会调用 {@code System.exit}，
     * 在 Android 上等于自杀进程（真机实测：Activity 直接消失回到主界面）。
     * 这里改用官方 API {@code CfrDriver.Builder}，并用动态代理实现 OutputSinkFactory
     * ——把 STRING sink 的源码接住，既不落盘也不触发 exit。</p>
     *
     * @param in        输入 .class
     * @param classpath 工程级 jar（提供跨类类型信息），可为 null
     * @param extra     追加选项（key/value 成对，名字不带 --）
     */
    public static String decompileSource(File in, File classpath, String... extra) throws Exception {
        if (sCl == null) throw new Exception("反编译插件未加载（应先调用 ensure）");
        final ClassLoader cl = sCl;
        final Class<?> sinkFactoryCls = cl.loadClass("org.benf.cfr.reader.api.OutputSinkFactory");
        final Class<?> sinkCls = cl.loadClass("org.benf.cfr.reader.api.OutputSinkFactory$Sink");
        final Class<?> sinkTypeCls = cl.loadClass("org.benf.cfr.reader.api.OutputSinkFactory$SinkType");
        final Class<?> sinkClassCls = cl.loadClass("org.benf.cfr.reader.api.OutputSinkFactory$SinkClass");

        final Object stJava = enumOf(sinkTypeCls, "JAVA");
        final Object scString = enumOf(sinkClassCls, "STRING");
        final StringBuilder srcBuf = new StringBuilder();
        final StringBuilder errs = new StringBuilder();

        // Sink：JAVA 累积源码，EXCEPTION 收集错误信息，其余丢弃
        java.lang.reflect.InvocationHandler sinkHandler =
                new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        if ("write".equals(m.getName()) && args != null && args.length > 0
                                && args[0] != null) {
                            if (errs.length() == 0 && args[0].toString().startsWith("Exception"))
                                errs.append(args[0]);
                        }
                        return null;
                    }
                };
        final java.lang.reflect.InvocationHandler javaSinkHandler =
                new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        if ("write".equals(m.getName()) && args != null && args.length > 0
                                && args[0] != null) {
                            srcBuf.append(args[0].toString());
                        }
                        return null;
                    }
                };

        Object sinkFactory = java.lang.reflect.Proxy.newProxyInstance(
                cl, new Class<?>[]{sinkFactoryCls}, new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args)
                            throws Throwable {
                        String n = m.getName();
                        if ("getSupportedSinks".equals(n)) {
                            Object type = args[0];
                            java.util.Collection<?> avail = (java.util.Collection<?>) args[1];
                            if (type == stJava && avail.contains(scString))
                                return java.util.Collections.singletonList(scString);
                            return java.util.Collections.emptyList();
                        }
                        if ("getSink".equals(n)) {
                            return java.lang.reflect.Proxy.newProxyInstance(cl,
                                    new Class<?>[]{sinkCls},
                                    args[0] == stJava ? javaSinkHandler : sinkHandler);
                        }
                        return null;
                    }
                });

        java.util.Map<String, String> opts = new java.util.HashMap<String, String>();
        // 可读性优先：去掉字节码注释与冗长提示；修复混淆代码常见的非法标识符与类型冲突
        opts.put("comments", "false");
        opts.put("silent", "true");
        opts.put("renameillegalidents", "true");
        opts.put("recovertypeclash", "true");
        opts.put("removeboilerplate", "true");
        if (classpath != null && classpath.exists()) opts.put("extraclasspath", classpath.getAbsolutePath());
        if (extra != null) {
            for (int i = 0; i + 1 < extra.length; i += 2) opts.put(extra[i], extra[i + 1]);
        }

        Class<?> builderCls = cl.loadClass("org.benf.cfr.reader.api.CfrDriver$Builder");
        Object builder = builderCls.getConstructor().newInstance();
        builder = builderCls.getMethod("withOutputSink", sinkFactoryCls).invoke(builder, sinkFactory);
        builder = builderCls.getMethod("withOptions", java.util.Map.class).invoke(builder, opts);
        Object driver = builderCls.getMethod("build").invoke(builder);
        Class<?> driverCls = cl.loadClass("org.benf.cfr.reader.api.CfrDriver");
        java.util.List<String> inputs = new java.util.ArrayList<String>();
        inputs.add(in.getAbsolutePath());
        driverCls.getMethod("analyse", java.util.List.class).invoke(driver, inputs);

        if (srcBuf.length() == 0) {
            throw new Exception("CFR 未产出源码"
                    + (errs.length() > 0 ? "：" + errs : "（该类可能无方法体或不受支持）"));
        }
        return srcBuf.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumOf(Class<?> cls, String name) throws Exception {
        return Enum.valueOf((Class<Enum>) cls, name);
    }

    /** 把 dex2jar 的长度前缀流组装成 zip。 */
    private static void writeJar(byte[] raw, File outJar) throws Exception {
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(raw));
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outJar));
        try {
            int n = 0;
            while (din.available() > 0) {
                int nl = din.readInt();
                if (nl < 0 || nl > 65536) throw new java.io.IOException("异常的条目名长度: " + nl);
                byte[] nb = new byte[nl];
                din.readFully(nb);
                String name = new String(nb, "UTF-8");
                int dl = din.readInt();
                if (dl < 0 || dl > 64 * 1024 * 1024) throw new java.io.IOException("异常的条目长度: " + dl);
                byte[] data = new byte[dl];
                din.readFully(data);
                zos.putNextEntry(new ZipEntry(name.endsWith(".class") ? name : name + ".class"));
                zos.write(data);
                zos.closeEntry();
                n++;
            }
            if (n == 0) throw new java.io.IOException("dex2jar 未产出任何类");
        } finally {
            try { zos.finish(); } catch (Throwable ignore) {}
            zos.close();
        }
    }

    private static void unpack(Context ctx, String asset, File out) throws Exception {
        if (out.exists() && out.length() > 0) return;
        InputStream is = ctx.getAssets().open(asset);
        try {
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(is);
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().endsWith("classes.dex")) {
                    FileOutputStream fos = new FileOutputStream(out);
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = zis.read(buf)) > 0) fos.write(buf, 0, r);
                    fos.close();
                    break;
                }
            }
            zis.close();
        } finally {
            is.close();
        }
        if (!out.exists() || out.length() == 0) throw new java.io.IOException("插件释放失败: " + asset);
    }

    static byte[] readAll(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        DataInputStream in = new DataInputStream(new java.io.FileInputStream(f));
        try {
            in.readFully(b);
        } finally {
            in.close();
        }
        return b;
    }

    /** 递归展开异常链，便于定位反射调用的真实失败原因。 */
    static String err(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int d = 0;
        while (c != null && d++ < 6) {
            if (sb.length() > 0) sb.append("\n  因: ");
            sb.append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
            Throwable nx = c.getCause();
            if (nx == c) break;
            c = nx;
        }
        return sb.toString();
    }
}
