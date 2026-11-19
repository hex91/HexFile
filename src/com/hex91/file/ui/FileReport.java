package com.hex91.file.ui;

import android.content.Context;

import com.hex91.file.R;
import com.hex91.file.apk.FileInspect;
import com.hex91.file.apk.KeyStoreUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 「文件解析」报告的文案层 —— v2.32.0（F-14/19）。
 *
 * <p>报告原来在 {@link FileInspect#describe} / {@code signatureInfo} 里拼中文，而 apk 层
 * 既没有 {@code Context} 也不该知道语言。拆法：apk 层只解析（{@code parseElf} / {@code magicName}
 * / {@code typeCode} / {@code magicDescTag} …），报告文本在这一层按资源拼。</p>
 *
 * <p>格式对齐全靠等宽字体，所以模板里的换行、缩进、`%-18s` 这类对齐都留在代码里，
 * 只有标签与说明句走资源 —— 译文再长也不会破坏列宽（`String.format` 按字符数对齐）。</p>
 */
public final class FileReport {

    private FileReport() {
    }

    private static String s(Context c, int id) {
        return c.getString(id);
    }

    private static String s(Context c, int id, Object... args) {
        return c.getString(id, args);
    }

    // ============================================================ 类型行

    /** 「魔数/类型」这一行的值：类型码 → 文案，附带扩展名细化 */
    public static String typeText(Context c, byte[] d, String fileName) {
        String code = FileInspect.typeCode(d, fileName);
        if ("APK".equals(code)) return s(c, R.string.fi_k_apk);
        if ("JAR".equals(code)) return s(c, R.string.fi_k_jar);
        if ("AAB".equals(code)) return s(c, R.string.fi_k_aab);
        if ("PKCS12".equals(code)) return s(c, R.string.fi_k_pkcs12);
        if ("X509".equals(code)) return s(c, R.string.fi_k_x509);
        if ("DER".equals(code)) return s(c, R.string.fi_k_der);
        if ("JKS_MISMATCH".equals(code)) return s(c, R.string.fi_k_jks_mismatch);
        if ("BKS_MISMATCH".equals(code)) return s(c, R.string.fi_k_bks_mismatch);
        if ("UNKNOWN".equals(code)) return s(c, R.string.fi_k_unknown);
        String desc = descText(c, FileInspect.magicDescTag(d));
        return desc == null ? code : s(c, R.string.fi_type_join, code, desc);
    }

    /** 说明码 → 说明句；码为空返回 null */
    private static String descText(Context c, String tag) {
        if (tag == null) return null;
        if ("ELF".equals(tag)) return s(c, R.string.fi_t_elf);
        if ("ZIP".equals(tag)) return s(c, R.string.fi_t_zip);
        if ("ZIP_EMPTY".equals(tag)) return s(c, R.string.fi_t_zip_empty);
        if ("ZIP_SPLIT".equals(tag)) return s(c, R.string.fi_t_zip_split);
        if ("DEX".equals(tag)) return s(c, R.string.fi_t_dex);
        if ("JKS".equals(tag)) return s(c, R.string.fi_t_jks);
        if ("BKS".equals(tag)) return s(c, R.string.fi_t_bks);
        if ("BKS_OLD".equals(tag)) return s(c, R.string.fi_t_bks_old);
        if ("DER".equals(tag)) return s(c, R.string.fi_t_der);
        if ("ARSC".equals(tag)) return s(c, R.string.fi_t_arsc);
        if ("AXML".equals(tag)) return s(c, R.string.fi_t_axml);
        if ("PNG".equals(tag)) return s(c, R.string.fi_t_png);
        if ("JPEG".equals(tag)) return s(c, R.string.fi_t_jpeg);
        if ("GIF".equals(tag)) return s(c, R.string.fi_t_gif);
        if ("RIFF".equals(tag)) return s(c, R.string.fi_t_riff);
        if ("GZIP".equals(tag)) return s(c, R.string.fi_t_gzip);
        if ("XZ".equals(tag)) return s(c, R.string.fi_t_xz);
        if ("ZSTD".equals(tag)) return s(c, R.string.fi_t_zstd);
        if ("7Z".equals(tag)) return s(c, R.string.fi_t_7z);
        if ("RAR".equals(tag)) return s(c, R.string.fi_t_rar);
        if ("PDF".equals(tag)) return s(c, R.string.fi_t_pdf);
        if ("CLASS".equals(tag)) return s(c, R.string.fi_t_class);
        if ("PE".equals(tag)) return s(c, R.string.fi_t_pe);
        if ("WASM".equals(tag)) return s(c, R.string.fi_t_wasm);
        if ("TTF".equals(tag)) return s(c, R.string.fi_t_ttf);
        if ("OTF".equals(tag)) return s(c, R.string.fi_t_otf);
        if ("TAR".equals(tag)) return s(c, R.string.fi_t_tar);
        if ("SQLITE".equals(tag)) return s(c, R.string.fi_t_sqlite);
        return null;
    }

    /** ELF e_type 的注解（REL 可重定位 / EXEC 可执行 …） */
    private static String elfTypeText(Context c, FileInspect.Elf e) {
        String code = e.typeCode();
        if ("NONE".equals(code)) return s(c, R.string.fi_elf_none);
        if ("REL".equals(code)) return s(c, R.string.fi_elf_rel);
        if ("EXEC".equals(code)) return s(c, R.string.fi_elf_exec);
        if ("DYN".equals(code)) return s(c, R.string.fi_elf_dyn);
        if ("CORE".equals(code)) return s(c, R.string.fi_elf_core);
        return code;
    }

    /** 机器名：只有「ARM」需要补「32 位」的注解 */
    private static String machineText(Context c, FileInspect.Elf e) {
        String name = e.machineName();
        return "ARM".equals(name) ? s(c, R.string.fi_machine_arm) : name;
    }

    // ============================================================ 报告正文

    /** 完整的「文件解析」文本（HEX 编辑器的信息面板用） */
    public static String describe(Context c, byte[] data, String fileName, long realSize) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(s(c, R.string.fi_name, fileName)).append('\n');
        String size = s(c, R.string.fi_size, realSize);
        if (data != null && realSize > data.length) size += s(c, R.string.fi_size_partial, data.length);
        sb.append(size).append('\n');
        if (data == null || data.length == 0) {
            sb.append('\n').append(s(c, R.string.fi_empty));
            return sb.toString();
        }
        sb.append(s(c, R.string.fi_head, FileInspect.hexHead(data, 16))).append('\n');
        sb.append(s(c, R.string.fi_type, typeText(c, data, fileName))).append('\n');
        String dex = FileInspect.dexVersion(data);
        if (dex != null) sb.append(s(c, R.string.fi_dex_ver, dex)).append('\n');
        int jks = FileInspect.jksVersion(data);
        if (jks > 0) {
            sb.append(s(c, R.string.fi_jks_ver, jks / 100, jks % 100)).append('\n');
        }

        FileInspect.Elf elf = FileInspect.parseElf(data);
        if (elf != null) {
            sb.append('\n').append(s(c, R.string.fi_elf_head)).append('\n');
            sb.append(s(c, R.string.fi_bits, elf.bits,
                    s(c, elf.little ? R.string.fi_le : R.string.fi_be))).append('\n');
            sb.append(s(c, R.string.fi_kind, elfTypeText(c, elf))).append('\n');
            sb.append(s(c, R.string.fi_machine, machineText(c, elf))).append('\n');
            sb.append(s(c, R.string.fi_entry, Long.toHexString(elf.entry))).append('\n');
            sb.append(s(c, R.string.fi_phdr, elf.phnum, Long.toHexString(elf.phoff))).append('\n');
            sb.append(s(c, R.string.fi_shdr, elf.shnum, Long.toHexString(elf.shoff),
                    elf.shentsize)).append('\n');
            sb.append(s(c, R.string.fi_flags, Integer.toHexString(elf.flags))).append('\n');
            if (elf.comment != null && elf.comment.length() > 0) {
                sb.append(s(c, R.string.fi_comment, elf.comment)).append('\n');
            }
            sb.append('\n').append(s(c, R.string.fi_sec_head)).append('\n');
            int shown = 0;
            for (FileInspect.Section sec : elf.sections) {
                if (sec.name == null || sec.name.length() == 0) {
                    if (sec.type == 0) continue;          // 跳过 NULL 节
                }
                if (shown >= 40) {
                    sb.append(s(c, R.string.fi_sec_more, elf.sections.size())).append('\n');
                    break;
                }
                // 表格按字符数对齐（等宽字体），标签只在「匿名」与「超出范围」两处走资源
                sb.append(String.format(Locale.US, "%-18s %-10s %-3s 0x%08x  %8d  %s",
                        sec.name.length() == 0 ? s(c, R.string.fi_sec_anon) : sec.name,
                        sec.typeName(), sec.flagsText(),
                        sec.addr, sec.size,
                        sec.outside ? s(c, R.string.fi_sec_outside) : ""));
                sb.append('\n');
                shown++;
            }
            if (elf.truncated) sb.append(s(c, R.string.fi_sec_trunc)).append('\n');
        } else if ("ARSC".equals(FileInspect.magicName(data))) {
            sb.append('\n').append(s(c, R.string.fi_arsc_hint)).append('\n');
        } else if ("AXML".equals(FileInspect.magicName(data))) {
            sb.append('\n').append(s(c, R.string.fi_axml_hint)).append('\n');
        }
        return sb.toString();
    }

    /**
     * ZIP/APK 的签名信息：META-INF 下的签名块 + 证书主题/有效期。
     * 需要真实文件（内存切片读不到中央目录）。
     */
    public static String signatureInfo(Context c, File f) {
        StringBuilder sb = new StringBuilder();
        ZipFile zf = null;
        try {
            zf = new ZipFile(f);
            List<String> blocks = new ArrayList<String>();
            List<String> certs = new ArrayList<String>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String n = ze.getName().toUpperCase(Locale.US);
                if (!n.startsWith("META-INF/")) continue;
                if (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC") || n.endsWith(".SF")) {
                    blocks.add(s(c, R.string.fi_block_item, ze.getName(), ze.getSize()));
                    if (!n.endsWith(".SF")) {
                        InputStream in = zf.getInputStream(ze);
                        try {
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int r;
                            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                            // 注意：APK v1 的 .RSA/.DSA/.EC 是 PKCS#7 SignedData（不是裸证书），
                            // 必须用 generateCertificates 解析，generateCertificate 会报 "Too short"
                            CertificateFactory cf = CertificateFactory.getInstance("X.509");
                            java.util.Collection<? extends java.security.cert.Certificate> chain =
                                    cf.generateCertificates(
                                            new java.io.ByteArrayInputStream(bo.toByteArray()));
                            for (java.security.cert.Certificate cc : chain) {
                                if (!(cc instanceof X509Certificate)) continue;
                                X509Certificate x = (X509Certificate) cc;
                                certs.add(s(c, R.string.fi_cert_subject, x.getSubjectDN().getName())
                                        + "\n" + s(c, R.string.fi_cert_issuer, x.getIssuerDN().getName())
                                        + "\n" + s(c, R.string.fi_cert_valid,
                                                x.getNotBefore(), x.getNotAfter())
                                        + "\n" + s(c, R.string.fi_cert_alg, x.getSigAlgName())
                                        + "\n" + s(c, R.string.fi_cert_sha,
                                                KeyStoreUtil.fingerprint(x, "SHA-256")));
                            }
                        } finally {
                            in.close();
                        }
                    }
                }
            }
            if (blocks.isEmpty()) {
                sb.append(s(c, R.string.fi_sig_none)).append('\n');
            } else {
                sb.append(s(c, R.string.fi_sig_blocks)).append('\n');
                for (String b : blocks) sb.append(b).append('\n');
            }
            for (String cert : certs) {
                sb.append('\n').append(s(c, R.string.fi_cert_head)).append('\n').append(cert).append('\n');
            }
        } catch (Throwable t) {
            sb.append(s(c, R.string.fi_sig_fail, String.valueOf(t.getMessage()))).append('\n');
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
        return sb.toString();
    }
}
