#!/usr/bin/env python3
# 纯标准库 + openssl 独立校验：按 WinZip AES (AE-2) 规范解析 MTClone/Zip4j 生成的
# AES 加密 ZIP，独立派生密钥、校验 HMAC、AES-CTR 解密并比对原始内容。
# 不依赖任何 Python 加密库（PBKDF2/HMAC 用 stdlib.hashlib，AES-CTR 用系统 openssl）。
# 这是“标准工具能否打开该 AES 包”的权威独立证明（不同于 Zip4j 自读回路）。
import os, struct, hashlib, hmac, subprocess, sys, tempfile, zlib

BASE = os.path.join("test", "_zipout")
PWD = b"secret123"
OPENSSL = "openssl"

EXPECT = {
    "readme.txt": b"MTClone Zip4j AES test\n",
    "sub/payload.bin": bytes(range(256)) * 2,   # 512 字节
    "small.dat": b"x",
}

# strength(1/2/3) -> (keyLen, saltLen, authLen)
PARAMS = {1: (16, 8, 10), 2: (24, 12, 12), 3: (32, 16, 14)}


def parse_aes_zip(path):
    """手动解析 zip 本地头，返回 [(name, actualMethod, strength, salt, pwver, ct, auth)]。"""
    with open(path, "rb") as f:
        data = f.read()
    entries = []
    off = 0
    n = len(data)
    while off + 4 <= n:
        sig = data[off:off + 4]
        if sig != b"PK\x03\x04":
            break
        (ver, flags, method, mtime, mdate, crc, csize, usize,
         nlen, elen) = struct.unpack("<HHHHHIIIHH", data[off + 4:off + 30])
        name = data[off + 30:off + 30 + nlen]
        extra = data[off + 30 + nlen:off + 30 + nlen + elen]
        body = data[off + 30 + nlen + elen: off + 30 + nlen + elen + csize]
        if method == 99:  # AES
            # 解析 AES extra (0x9901)
            ei = 0
            a_strength = None
            a_actual = None
            while ei + 4 <= len(extra):
                hid, hsz = struct.unpack("<HH", extra[ei:ei + 4])
                hdata = extra[ei + 4:ei + 4 + hsz]
                if hid == 0x9901:
                    # vendorid(2) vendorver(2) strength(1) actualmethod(2)
                    a_strength = hdata[4]
                    a_actual = struct.unpack("<H", hdata[5:7])[0]
                ei += 4 + hsz
            keyLen, saltLen, authLen = PARAMS[a_strength]
            salt = body[:saltLen]
            pwver = body[saltLen:saltLen + 2]
            ct = body[saltLen + 2: len(body) - authLen]
            auth = body[len(body) - authLen:]
            entries.append((name.decode("utf-8"), a_actual, a_strength,
                           salt, pwver, ct, auth, authLen))
        else:
            # 非加密条目（理论上不应出现在纯 AES 包），跳过
            entries.append((name.decode("utf-8"), method, None,
                           b"", b"", body, b"", 0))
        off += 30 + nlen + elen + csize
    return entries


def aes_ctr_decrypt(key, ct):
    iv = bytes(15) + bytes([1])  # 初始 counter block: 15 字节 0 + 1
    p = subprocess.run([OPENSSL, "enc", "-aes-%d-ctr" % (len(key) * 8),
                        "-d", "-K", key.hex(), "-iv", iv.hex()],
                       input=ct, capture_output=True)
    if p.returncode != 0:
        raise RuntimeError("openssl aes-ctr failed: " + p.stderr.decode())
    return p.stdout


def verify_aes():
    zp = os.path.join(BASE, "sample_aes256.zip")
    if not os.path.exists(zp):
        print("  FAIL AES zip 不存在"); return False
    try:
        entries = parse_aes_zip(zp)
        aes_entries = [e for e in entries if e[2] is not None]
        if not aes_entries:
            print("  FAIL 未发现 AES 条目"); return False
        got = {}
        for (name, actual, strength, salt, pwver, ct, auth, authLen) in aes_entries:
            keyLen, saltLen, _ = PARAMS[strength]
            dk = hashlib.pbkdf2_hmac("sha1", PWD, salt, 1000, 2 * keyLen + 2)
            enc_key = dk[:keyLen]
            auth_key = dk[keyLen:2 * keyLen]
            exp_pwver = dk[2 * keyLen:2 * keyLen + 2]
            if exp_pwver != pwver:
                print("  FAIL %s pwVer 不匹配 (密钥派生不一致)" % name); return False
            mac = hmac.new(auth_key, pwver + ct, hashlib.sha1).digest()[:authLen]
            if mac != auth:
                print("  FAIL %s HMAC 校验失败 (数据损坏或密码错误)" % name); return False
            plain = aes_ctr_decrypt(enc_key, ct)
            if actual == 8:  # DEFLATE
                plain = zlib.decompress(plain, -15)
            got[name] = plain
        exp = EXPECT
        if set(got.keys()) != set(exp.keys()):
            print("  FAIL AES 条目不匹配:", set(got.keys()) ^ set(exp.keys())); return False
        for k in exp:
            if got[k] != exp[k]:
                print("  FAIL AES 内容不一致:", k); return False
        print("  PASS AES-256 zip：stdlib+openssl 独立解密成功，HMAC 校验通过，内容一致")
        print("        => 任何合规 AE-2 工具（7-Zip / WinRAR / ZArchiver）均可打开")
        return True
    except Exception as e:
        print("  FAIL AES zip 解析/解密异常:", repr(e)); return False


if __name__ == "__main__":
    ok = verify_aes()
    print("VERIFY_AES:", "ALL PASS" if ok else "FAILED")
    sys.exit(0 if ok else 1)
