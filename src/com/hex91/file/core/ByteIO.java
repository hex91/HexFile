package com.hex91.file.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * v2.4.0（O-19）：整读字节的小工具。
 *
 * <p><b>为什么单开一个类</b>：{@code MainActivity.readAllBytes} 被
 * {@code ArchiveController} 调了 5 处（压缩包条目读取、写回前取原内容），
 * 而 {@code MainActivity} 是 4,600 行的宿主壳 —— controller 反向依赖它这件事，
 * 一半的耦合量就出在这种「一个纯函数长在了 Activity 里」的地方。</p>
 *
 * <p>纯 JDK，不进 {@code android.*}，桌面单测可直接用；流版本顺手一起收进来，
 * 免得各处再写一遍「读到底部拼 byte[]」的循环。</p>
 */
public final class ByteIO {

    private static final int BUF = 64 * 1024;

    private ByteIO() {}

    /** 整读一个文件；文件不存在/无权限直接抛 IOException（调用方自己拼提示） */
    public static byte[] readAll(File f) throws IOException {
        if (f == null) throw new IOException("文件为空");
        FileInputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            closeQuietly(in);
        }
    }

    /** 整读一个流（不关闭传入的流，由调用方负责） */
    public static byte[] readAll(InputStream in) throws IOException {
        if (in == null) throw new IOException("流为空");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[BUF];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 读已成功，关不掉不影响结果
        }
    }
}
