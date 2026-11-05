package com.hex91.file;

/**
 * 扩展名 → MIME 类型（v1.72.0）。
 *
 * 用途：分享文件时决定系统分享面板里哪些应用能接收，以及接收方通过
 * ContentResolver.getType() 查询时的返回值。猜不准的类型统一给
 * application/octet-stream，接收方仍能收到文件，只是可能不在推荐列表里。
 */
public final class MimeUtil {

    private MimeUtil() {
    }

    public static String of(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        String e = dot > 0 ? n.substring(dot + 1) : "";
        if (e.equals("apk")) return "application/vnd.android.package-archive";
        if (e.equals("png")) return "image/png";
        if (e.equals("jpg") || e.equals("jpeg")) return "image/jpeg";
        if (e.equals("gif")) return "image/gif";
        if (e.equals("webp")) return "image/webp";
        if (e.equals("bmp")) return "image/bmp";
        if (e.equals("svg")) return "image/svg+xml";
        if (e.equals("mp4")) return "video/mp4";
        if (e.equals("mkv")) return "video/x-matroska";
        if (e.equals("mp3")) return "audio/mpeg";
        if (e.equals("wav")) return "audio/wav";
        if (e.equals("pdf")) return "application/pdf";
        if (e.equals("txt") || e.equals("log") || e.equals("md") || e.equals("ini")) return "text/plain";
        if (e.equals("json")) return "application/json";
        if (e.equals("xml")) return "text/xml";
        if (e.equals("html") || e.equals("htm")) return "text/html";
        if (e.equals("js") || e.equals("java") || e.equals("smali")) return "text/plain";
        if (e.equals("zip")) return "application/zip";
        if (e.equals("rar")) return "application/x-rar-compressed";
        if (e.equals("7z")) return "application/x-7z-compressed";
        if (e.equals("tar")) return "application/x-tar";
        if (e.equals("gz")) return "application/gzip";
        if (e.equals("ttf")) return "font/ttf";
        if (e.equals("otf")) return "font/otf";
        if (e.equals("dex")) return "application/x-dex";
        if (e.equals("so")) return "application/x-sharedlib";
        return "application/octet-stream";
    }
}
