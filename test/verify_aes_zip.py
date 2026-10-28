#!/usr/bin/env python3
# 独立交叉验证 MTClone 生成的 WinZip AES (AE-2) zip：
#   python(stdlib PBKDF2-HMAC-SHA1 + zip 结构解析 + zlib) + openssl(aes-256-ctr 解密)
# 不依赖生产端任何 Java 代码，验证生成格式是否可被标准工具链打开。
import hashlib, hmac, zlib, struct, subprocess, sys, os

ZIP = "test/aes_sample.zip"
SRC = "test/aes_src"
PW = "secret123"
OPENSSL = "openssl"

def read_short(b, o): return b[o] | (b[o+1] << 8)
def read_int(b, o): return b[o] | (b[o+1]<<8) | (b[o+2]<<16) | (b[o+3]<<24)

def main():
    blob = open(ZIP, "rb").read()
    # EOCD
    eocd = blob.rfind(b'PK\x05\x06')
    assert eocd >= 0, "no EOCD"
    cd_off = read_int(blob, eocd + 16)
    p = cd_off
    fails = 0
    while p + 4 <= len(blob) and blob[p:p+4] == b'PK\x01\x02':
        method = read_short(blob, p + 10)
        comp_size = read_int(blob, p + 20)
        name_len = read_short(blob, p + 28)
        extra_len = read_short(blob, p + 30)
        comment_len = read_short(blob, p + 32)
        local_off = read_int(blob, p + 42)
        name = blob[p+46:p+46+name_len].decode("utf-8")
        extra = blob[p+46+name_len:p+46+name_len+extra_len]
        # parse AES extra
        actual_method, salt_len, key_len, auth_len = 8, 16, 32, 14
        q = 0
        while q + 4 <= len(extra):
            eid = read_short(extra, q); esz = read_short(extra, q+2)
            if eid == 0x9901:
                strength = extra[q+4+4]
                actual_method = read_short(extra, q+4+5)
                salt_len = 8 if strength==1 else (12 if strength==2 else 16)
                key_len  = 16 if strength==1 else (24 if strength==2 else 32)
                auth_len = 10 if strength==1 else (12 if strength==2 else 14)
                break
            q += 4 + esz
        if name.endswith("/"):
            p += 46 + name_len + extra_len + comment_len
            continue
        ln = read_short(blob, local_off + 26)
        le = read_short(blob, local_off + 28)
        ds = local_off + 30 + ln + le
        data = blob[ds:ds+comp_size]
        salt = data[0:salt_len]
        pv = data[salt_len:salt_len+2]
        ct = data[salt_len+2:len(data)-auth_len]
        auth = data[len(data)-auth_len:]
        dk = hashlib.pbkdf2_hmac("sha1", PW.encode("utf-8"), salt, 1000, 2*key_len+2)
        enc_key = dk[:key_len]; auth_key = dk[key_len:2*key_len]; pv_exp = dk[2*key_len:2*key_len+2]
        ok_pv = (pv == pv_exp)
        mac = hmac.new(auth_key, pv + ct, hashlib.sha1)
        ok_auth = (mac.digest()[:auth_len] == auth)
        print(f"  [diag {name}] pv_hex={pv.hex()} pv_exp_hex={pv_exp.hex()} ok_pv={ok_pv} ok_auth={ok_auth}")
        iv = b'\x00'*15 + b'\x01'
        ctmp = "test/_ct.bin"; ctmp2 = "test/_comp.bin"
        open(ctmp, "wb").write(ct)
        r = subprocess.run([OPENSSL, "enc", "-aes-256-ctr", "-d",
                            "-K", enc_key.hex(), "-iv", iv.hex(),
                            "-in", ctmp, "-out", ctmp2, "-nopad"],
                           capture_output=True)
        if r.returncode != 0:
            print(f"  FAIL {name}: openssl error {r.stderr.decode(errors='replace')[:80]}")
            fails += 1
            p += 46 + name_len + extra_len + comment_len
            continue
        comp = open(ctmp2, "rb").read()
        try:
            raw = zlib.decompress(comp, -15) if actual_method == 8 else comp
        except Exception as e:
            print(f"  FAIL {name}: zlib error {e} (comp head={comp[:8].hex()})")
            fails += 1
            p += 46 + name_len + extra_len + comment_len
            continue
        src = os.path.join(SRC, name)
        exp = open(src, "rb").read() if os.path.exists(src) else b""
        ok_content = (raw == exp)
        status = "OK" if (ok_pv and ok_auth and ok_content) else "FAIL"
        if status == "FAIL": fails += 1
        print(f"  {status} {name}: pv={ok_pv} auth={ok_auth} content={ok_content} (src={len(exp)}B dec={len(raw)}B)")
        p += 46 + name_len + extra_len + comment_len
    print("VERIFY:", "ALL PASS" if fails == 0 else f"{fails} FAIL")
    sys.exit(1 if fails else 0)

if __name__ == "__main__":
    main()
