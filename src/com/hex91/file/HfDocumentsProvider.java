package com.hex91.file;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * v1.84.0：把本应用暴露给系统的 Storage Access Framework。
 *
 * <p>作用：装了这个应用后，系统「文件」应用的侧边栏会多出 HexFile 这一项；
 * 别的应用（微信、QQ、邮件、编辑器）点「打开文件 / 选择位置」时，也能直接浏览这里的目录，
 * 不必先把文件导到别处。这是文件管理器的「被集成」能力，属于 P2 的第一项。</p>
 *
 * <h3>documentId 就是绝对路径</h3>
 * 不维护 id→文件 映射表，进程被杀后 id 依然有效，查询也不用额外内存。
 * 代价是<b>必须校验</b>：docId 由外部应用提供，可以伪造 {@code /data/data/...}，
 * 所以每次解析都过 {@link DocPath#withinAny} 限定在允许根内。
 *
 * <h3>为什么不暴露 /</h3>
 * 有 root 能读全盘，但把根目录交给 provider 等于把整个设备交给任何能拉起 SAF 的应用。
 * 这里只放「内部存储」和工作目录两个根，root 能力仍留在应用内。
 */
public class HfDocumentsProvider extends DocumentsProvider {

    /** 与 AndroidManifest 中 android:authorities 必须一致 */
    public static final String AUTHORITY = "com.hex91.file.documents";

    /** 设置页开关的偏好（与 WorkDir 共用文件） */
    public static final String PREF = WorkDir.PREF;
    public static final String KEY_VISIBLE = "docs_visible";

    private static final String ROOT_STORAGE = "storage";
    private static final String ROOT_WORK = "work";

    /** 单目录最多列这么多（防止 rootfs 场景把系统卡死） */
    private static final int MAX_CHILDREN = 5000;
    /** 搜索最多返回 + 最大深度 */
    private static final int MAX_SEARCH = 300;
    private static final int MAX_SEARCH_DEPTH = 6;
    /** 缩略图只处理这么大的图片，再大就交回系统走原图 */
    private static final long MAX_THUMB_BYTES = 24L * 1024 * 1024;

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES};
    private static final String[] DEFAULT_DOC_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_SUMMARY};

    @Override
    public boolean onCreate() {
        // provider 可能先于任何 Activity 起来，工作目录得在这里初始化一次
        WorkDir.init(getContext());
        return true;
    }

    /** 设置页切换「系统文件选择器可见」后调用，让系统丢掉已缓存的根列表 */
    public static void notifyRootsChanged(Context c) {
        if (c == null) return;
        try {
            c.getContentResolver().notifyChange(
                    DocumentsContract.buildRootsUri(AUTHORITY), null);
        } catch (Throwable ignored) {
        }
    }

    static boolean isVisible(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY_VISIBLE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------------------ roots

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(
                projection != null ? projection : DEFAULT_ROOT_PROJECTION);

        if (!isVisible(getContext())) return c;      // 用户关了开关 → 系统看不到任何根

        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) {
            addRoot(c, ROOT_STORAGE, "内部存储", "手机内置存储", ext,
                    R.mipmap.ic_launcher);
        }
        File work = WorkDir.base();
        if (work != null && (work.exists() || work.mkdirs())) {
            addRoot(c, ROOT_WORK, "HexFile 工作目录", WorkDir.path(), work,
                    R.mipmap.ic_launcher);
        }
        return c;
    }

    private void addRoot(MatrixCursor c, String rootId, String title, String summary,
                         File dir, int icon) {
        int flags = Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD;
        if (dir.canWrite()) flags |= Root.FLAG_SUPPORTS_CREATE;
        flags |= Root.FLAG_SUPPORTS_SEARCH;

        c.newRow()
                .add(Root.COLUMN_ROOT_ID, rootId)
                .add(Root.COLUMN_DOCUMENT_ID, dir.getAbsolutePath())
                .add(Root.COLUMN_TITLE, title)
                .add(Root.COLUMN_SUMMARY, summary)
                .add(Root.COLUMN_ICON, icon)
                .add(Root.COLUMN_FLAGS, flags)
                .add(Root.COLUMN_MIME_TYPES, "*/*")
                .add(Root.COLUMN_AVAILABLE_BYTES, dir.getUsableSpace());
    }

    // --------------------------------------------------------------- document

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        File f = resolve(documentId);
        MatrixCursor c = new MatrixCursor(
                projection != null ? projection : DEFAULT_DOC_PROJECTION);
        includeFile(c, f, rootTitleOf(documentId));
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
                                      String sortOrder) throws FileNotFoundException {
        File parent = resolve(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException(parentDocumentId);

        MatrixCursor c = new MatrixCursor(
                projection != null ? projection : DEFAULT_DOC_PROJECTION);
        File[] kids = parent.listFiles();
        if (kids == null) return c;      // 无权限 / 已被删除

        List<File> list = new ArrayList<File>(Arrays.asList(kids));
        Collections.sort(list, new Comparator<File>() {
            public int compare(File a, File b) {
                return DocPath.compare(a.isDirectory(), a.getName(),
                        b.isDirectory(), b.getName());
            }
        });
        int n = 0;
        for (File f : list) {
            if (n++ >= MAX_CHILDREN) break;
            includeFile(c, f, null);
        }
        return c;
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection)
            throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(
                projection != null ? projection : DEFAULT_DOC_PROJECTION);
        String q = query == null ? "" : query.toLowerCase();
        File root = rootDir(rootId);
        if (root == null) return c;

        List<File> hits = new ArrayList<File>();
        search(root, q, 0, hits);
        for (File f : hits) includeFile(c, f, null);
        return c;
    }

    private void search(File dir, String q, int depth, List<File> out) {
        if (dir == null || depth > MAX_SEARCH_DEPTH || out.size() >= MAX_SEARCH) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (out.size() >= MAX_SEARCH) return;
            String n = f.getName();
            if (n.toLowerCase().contains(q)) out.add(f);
            if (f.isDirectory()) search(f, q, depth + 1, out);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File f = resolve(documentId);
        return f.isDirectory() ? Document.MIME_TYPE_DIR : MimeUtil.of(f.getName());
    }

    /** 把一条记录写进游标（provider 的所有查询最终都走这里）；rootTitle 非 null 表示这是个根 */
    private void includeFile(MatrixCursor c, File f, String rootTitle) {
        boolean isRoot = rootTitle != null;
        String docId = f.getAbsolutePath();
        boolean dir = f.isDirectory();
        boolean writable = f.canWrite();

        int flags = 0;
        if (!isRoot) {
            if (writable) flags |= Document.FLAG_SUPPORTS_WRITE
                    | Document.FLAG_SUPPORTS_RENAME
                    | Document.FLAG_SUPPORTS_COPY
                    | Document.FLAG_SUPPORTS_MOVE;
            flags |= Document.FLAG_SUPPORTS_DELETE;
        }
        if (dir) {
            if (writable) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
            flags |= Document.FLAG_DIR_PREFERS_LAST_MODIFIED;
        } else if (isImage(f.getName())) {
            flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }

        // 根节点的名字不能用 File.getName()：/storage/emulated/0 会显示成「0」
        String name = isRoot ? rootTitle : f.getName();
        if (name == null || name.isEmpty()) name = "/";

        MatrixCursor.RowBuilder row = c.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, docId)
                .add(Document.COLUMN_DISPLAY_NAME, name)
                .add(Document.COLUMN_MIME_TYPE,
                        dir ? Document.MIME_TYPE_DIR : MimeUtil.of(f.getName()))
                .add(Document.COLUMN_FLAGS, flags)
                .add(Document.COLUMN_LAST_MODIFIED, f.lastModified());
        if (dir) {
            row.add(Document.COLUMN_SIZE, 0)
                    .add(Document.COLUMN_SUMMARY, "文件夹");
        } else {
            long len = f.length();
            row.add(Document.COLUMN_SIZE, len)
                    .add(Document.COLUMN_SUMMARY, FileOps.formatSize(len));
        }
    }

    // ----------------------------------------------------------------- 打开读

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
                                             CancellationSignal signal)
            throws FileNotFoundException {
        File f = resolve(documentId);
        if (!f.exists()) throw new FileNotFoundException(documentId);
        try {
            return ParcelFileDescriptor.open(f, parseMode(mode));
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            FileNotFoundException e = new FileNotFoundException(
                    documentId + " (" + t.getClass().getSimpleName() + ")");
            e.initCause(t);
            throw e;
        }
    }

    /** SAF mode 字符串 → ParcelFileDescriptor 打开标志（解析逻辑在 {@link DocPath#modeOf} 里单测） */
    static int parseMode(String mode) {
        switch (DocPath.modeOf(mode)) {
            case DocPath.M_WRITE:
                return ParcelFileDescriptor.MODE_WRITE_ONLY
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE;
            case DocPath.M_APPEND:
                return ParcelFileDescriptor.MODE_WRITE_ONLY
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_APPEND;
            case DocPath.M_RW:
                return ParcelFileDescriptor.MODE_READ_WRITE;
            case DocPath.M_RWT:
                return ParcelFileDescriptor.MODE_READ_WRITE
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE;
            default:
                return ParcelFileDescriptor.MODE_READ_ONLY;
        }
    }

    /**
     * 图片缩略图：系统文件选择器里能直接看到预览。
     * 失败一律返回 null（协议允许「没有缩略图」），不让一张坏图把整个列表拖垮。
     */
    @Override
    public android.content.res.AssetFileDescriptor openDocumentThumbnail(
            String documentId, Point sizeHint, CancellationSignal signal)
            throws FileNotFoundException {
        final File f = resolve(documentId);
        if (!isImage(f.getName()) || f.length() > MAX_THUMB_BYTES) return null;
        final int max = sizeHint == null ? 256 : Math.max(64, Math.max(sizeHint.x, sizeHint.y));

        Bitmap bm;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= max || probe.outHeight / (sample * 2) >= max) {
                sample *= 2;
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            bm = BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
        } catch (Throwable t) {
            return null;
        }
        if (bm == null) return null;

        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            final ParcelFileDescriptor writeEnd = pipe[1];
            final Bitmap out = scale(bm, max);
            new Thread(new Runnable() {
                public void run() {
                    try {
                        FileOutputStream os = new FileOutputStream(writeEnd.getFileDescriptor());
                        out.compress(Bitmap.CompressFormat.PNG, 100, os);
                        os.flush();
                        os.close();
                    } catch (Throwable ignored) {
                    } finally {
                        try {
                            writeEnd.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, "doc-thumb").start();
            return new android.content.res.AssetFileDescriptor(pipe[0], 0,
                    android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap scale(Bitmap src, int max) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        float k = Math.min((float) max / w, (float) max / h);
        if (k >= 1f) return src;
        return Bitmap.createScaledBitmap(src, Math.max(1, (int) (w * k)),
                Math.max(1, (int) (h * k)), true);
    }

    // -------------------------------------------------------------- 写操作族

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = resolve(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException(parentDocumentId);
        String name = DocPath.sanitize(displayName);
        if (name == null) throw new FileNotFoundException("非法文件名");

        File target = new File(parent, uniqueName(parent, name));
        boolean ok;
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            ok = target.mkdirs();
        } else {
            try {
                ok = target.createNewFile();
            } catch (Throwable t) {
                ok = false;
            }
        }
        if (!ok) throw new FileNotFoundException("创建失败：" + target.getAbsolutePath());
        return target.getAbsolutePath();
    }

    /** 同名时追加 (1)(2)…，避免系统 UI 重复点击建同名文件直接失败 */
    private static String uniqueName(File parent, String name) {
        if (!new File(parent, name).exists()) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String n = base + " (" + i + ")" + ext;
            if (!new File(parent, n).exists()) return n;
        }
        return base + "_" + System.currentTimeMillis() + ext;
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File f = resolve(documentId);
        if (isRootDoc(documentId)) throw new FileNotFoundException("不能删除根目录");
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null && kids.length > 0) {
                throw new FileNotFoundException("目录非空，请先清空");
            }
        }
        if (!f.delete()) throw new FileNotFoundException("删除失败：" + documentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName)
            throws FileNotFoundException {
        File f = resolve(documentId);
        if (isRootDoc(documentId)) throw new FileNotFoundException("不能重命名根目录");
        String name = DocPath.sanitize(displayName);
        if (name == null) throw new FileNotFoundException("非法文件名");
        if (name.equals(f.getName())) return f.getAbsolutePath();

        File target = new File(f.getParentFile(), name);
        if (target.exists()) throw new FileNotFoundException("同名文件已存在");
        if (!f.renameTo(target)) throw new FileNotFoundException("重命名失败");
        return target.getAbsolutePath();
    }

    @Override
    public String copyDocument(String sourceDocumentId, String targetParentDocumentId)
            throws FileNotFoundException {
        File src = resolve(sourceDocumentId);
        File parent = resolve(targetParentDocumentId);
        File target = new File(parent, uniqueName(parent, src.getName()));
        if (!copyRecursive(src, target)) {
            throw new FileNotFoundException("复制失败：" + sourceDocumentId);
        }
        return target.getAbsolutePath();
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId,
                               String targetParentDocumentId) throws FileNotFoundException {
        File src = resolve(sourceDocumentId);
        File parent = resolve(targetParentDocumentId);
        File target = new File(parent, src.getName());
        if (target.exists()) throw new FileNotFoundException("目标已存在同名项");
        if (src.renameTo(target)) return target.getAbsolutePath();

        // 跨分区 rename 会失败 → 复制后删除源
        if (!copyRecursive(src, target)) throw new FileNotFoundException("移动失败");
        deleteRecursive(src);
        return target.getAbsolutePath();
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        String p = DocPath.normalize(parentDocumentId);
        String d = DocPath.normalize(documentId);
        if (p == null || d == null) return false;
        return DocPath.within(p, d);
    }

    // ----------------------------------------------------------------- 内部

    /**
     * docId → File，并强制限定在允许根内。
     * 这里必须拒绝越界：docId 完全由调用方提供。
     */
    private File resolve(String documentId) throws FileNotFoundException {
        String p = DocPath.normalize(documentId);
        if (p == null) throw new FileNotFoundException("非法路径：" + documentId);
        if (!DocPath.withinAny(allowedRoots(), p)) {
            throw new FileNotFoundException("超出允许范围：" + documentId);
        }
        return new File(p);
    }

    private List<String> allowedRoots() {
        List<String> roots = new ArrayList<String>(4);
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) add(roots, ext.getAbsolutePath());
        add(roots, "/sdcard");          // 兼容用符号链接路径写出来的 id
        add(roots, "/storage/self/primary");
        File work = WorkDir.base();
        if (work != null) add(roots, work.getAbsolutePath());
        return roots;
    }

    private static void add(List<String> roots, String path) {
        String n = DocPath.normalize(path);
        if (n != null && !roots.contains(n)) roots.add(n);
    }

    private boolean isRootDoc(String documentId) {
        return rootTitleOf(documentId) != null;
    }

    /** docId 是某个根时返回它的展示名，否则 null */
    private String rootTitleOf(String documentId) {
        String p = DocPath.normalize(documentId);
        if (p == null) return null;
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && p.equals(DocPath.normalize(ext.getAbsolutePath()))) return "内部存储";
        if (p.equals(DocPath.normalize(WorkDir.path()))) return "HexFile 工作目录";
        return null;
    }

    private File rootDir(String rootId) {
        if (ROOT_WORK.equals(rootId)) return WorkDir.base();
        return Environment.getExternalStorageDirectory();
    }

    private static boolean isImage(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp");
    }

    private static boolean copyRecursive(File src, File dst) {
        // v1.99.1（S-1 同类）：src.isDirectory() 会跟随链接 —— 复制一个链接目录等于把目标
        // 整棵树复制一份（链接成环时直接爆栈）。这里按原样复刻链接本身；
        // 目标文件系统不支持链接（FAT32 等）就跳过这一条，不判整单失败。
        if (LinkUtil.isLink(src)) {
            TreeOps.SYSTEM.copyLink(src, dst);
            return true;
        }
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) return false;
            File[] kids = src.listFiles();
            if (kids == null) return false;
            for (File k : kids) {
                if (!copyRecursive(k, new File(dst, k.getName()))) return false;
            }
            return true;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Throwable t) {
            dst.delete();
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * v1.97.0：改为走 {@link TreeOps#delete}。SAF 是「别的应用能直接调」的入口，
     * 原来跟进软链接意味着：任何应用只要拿到一个链接目录的 document id，
     * 就能借本应用的权限把链接目标删掉（本应用多是 root/存储权限全开的状态）。
     */
    private static void deleteRecursive(File f) {
        TreeOps.delete(f);
    }
}
