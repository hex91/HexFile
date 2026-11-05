package com.hex91.file;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 迷你文件共享 Provider（零 AndroidX FileProvider 依赖）。
 * URI 形如 content://com.hex91.file.files/storage/emulated/0/Download/x.apk，
 * 仅授权方带 FLAG_GRANT_READ_URI_PERMISSION 时接收方才能读取。
 * 用于「安装 APK」功能向系统安装器提供文件。
 */
public class ApkFileProvider extends ContentProvider {

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = new File(uri.getPath());
        if (!f.isFile()) throw new FileNotFoundException(uri.getPath());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        // v1.72.0：分享面板/接收方会用这里查类型，按扩展名给具体 MIME，
        // 否则微信、QQ 这类只看类型的应用不会把自己列进候选
        return MimeUtil.of(uri.getPath());
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }

    @Override
    public Uri insert(Uri uri, ContentValues v) { return null; }

    @Override
    public int delete(Uri uri, String s, String[] a) { return 0; }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
