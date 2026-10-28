#!/usr/bin/env python3
# 独立跨工具校验：用 pyzipper（AES）与标准库 zipfile（传统 ZipCrypto）
# 打开 MTClone/Zip4j 生成的加密 ZIP，验证内容一致 + 加密方式正确。
# 这是“标准工具能否打开”的权威验证（非自实现回路）。
import os, sys, shutil, zipfile, tempfile

try:
    import pyzipper
except ImportError:
    print("pyzipper 未安装：pip install pyzipper")
    sys.exit(2)

BASE = os.path.join("test", "_zipout")
PWD = b"secret123"

EXPECT = {
    "readme.txt": b"MTClone Zip4j AES test\n",
    "sub/payload.bin": bytes(range(256)) * 2,  # 512 bytes, 与 Java payload[i]=(byte)i 等价
    "small.dat": b"x",
}

def list_files(d):
    out = {}
    for root, _, files in os.walk(d):
        for fn in files:
            p = os.path.join(root, fn)
            rel = os.path.relpath(p, d).replace(os.sep, "/")
            with open(p, "rb") as f:
                out[rel] = f.read()
    return out

def expected_map():
    return EXPECT

def verify_aes():
    zp = os.path.join(BASE, "sample_aes256.zip")
    if not os.path.exists(zp):
        print("  FAIL AES zip 不存在"); return False
    tmp = tempfile.mkdtemp()
    try:
        with pyzipper.AESZipFile(zp) as z:
            z.extractall(tmp, pwd=PWD)
        got = list_files(tmp)
        exp = expected_map()
        if set(got.keys()) != set(exp.keys()):
            print("  FAIL AES 条目不匹配:", set(got.keys()) ^ set(exp.keys())); return False
        for k in exp:
            if got[k] != exp[k]:
                print("  FAIL AES 内容不一致:", k); return False
        # 确认确为 AES 加密（非传统 ZipCrypto）：标准库 zipfile 应无法打开
        is_aes = True
        try:
            with zipfile.ZipFile(zp) as z2:
                z2.extractall(tempfile.mkdtemp(), pwd=PWD)
            is_aes = False
        except Exception:
            pass
        print("  PASS AES-256 zip：pyzipper(AE-2 参考实现)成功解压，内容一致，确为 AES 加密 =", is_aes)
        return True
    except Exception as e:
        print("  FAIL AES zip 读取异常:", repr(e)); return False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

def verify_zipcrypto():
    zp = os.path.join(BASE, "sample_zipcrypto.zip")
    if not os.path.exists(zp):
        print("  FAIL ZipCrypto zip 不存在"); return False
    tmp = tempfile.mkdtemp()
    try:
        # 标准库 zipfile 仅支持传统 ZipCrypto —— 能成功解压即证明系统/Windows 可直接打开
        with zipfile.ZipFile(zp) as z:
            z.extractall(tmp, pwd=PWD)
        got = list_files(tmp)
        exp = expected_map()
        if set(got.keys()) != set(exp.keys()):
            print("  FAIL ZipCrypto 条目不匹配"); return False
        for k in exp:
            if got[k] != exp[k]:
                print("  FAIL ZipCrypto 内容不一致:", k); return False
        print("  PASS ZipCrypto zip：标准 zipfile 成功解压，内容一致（系统/Windows 可直接打开）")
        return True
    except Exception as e:
        print("  FAIL ZipCrypto zip 读取异常:", repr(e)); return False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

def negative_aes_not_traditional():
    # 标准库 zipfile 应无法解 AES 条目（证明它是 AES 而非传统 ZipCrypto）
    zp = os.path.join(BASE, "sample_aes256.zip")
    try:
        with zipfile.ZipFile(zp) as z:
            z.extractall(tempfile.mkdtemp(), pwd=PWD)
        print("  WARN：标准 zipfile 居然解开了 AES 包（疑为传统加密）")
        return False
    except Exception:
        print("  PASS AES 包：标准 zipfile 无法解（确为 AES，非传统）")
        return True

if __name__ == "__main__":
    ok = True
    print("== 独立跨工具校验（pyzipper / 标准 zipfile）==")
    ok &= verify_aes()
    ok &= verify_zipcrypto()
    ok &= negative_aes_not_traditional()
    print("VERIFY:", "ALL PASS" if ok else "FAILED")
    sys.exit(0 if ok else 1)
