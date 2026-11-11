# -*- coding: utf-8 -*-
"""
dexcheck.py —— 校验 dex 里引用的外部方法在目标 jar 里是否真的存在。

背景（v1.93.0）：v1.91.0 的播放器在真机上一点就闪退：

    java.lang.NoSuchMethodError: No virtual method buildOrThrow()Lcom/google/common/collect/ImmutableMap;
      at androidx.media3.exoplayer.audio.AudioCapabilities.<clinit>

原因是 Media3 1.4.1 用 Guava 31+ 编译（ImmutableMap.Builder.buildOrThrow 是 31.0 新增），
而工程 libs/ 里躺的是 Guava 27.1（smbj 的传递依赖）。javac 阶段完全看不出来 ——
Media3 的 class 是预编译的，javac 不核对它引用的方法在不在 classpath 上，
只有运行时第一次执行到那条 invoke 指令才会炸。

这层检查做的是「二进制兼容性」：抽出 dex 里所有 method_id，
凡是引用这批 jar 的，就去 jar 里核对「名字 + 描述符」是否存在（含父类与接口继承）。

用法：
    python tools/dexcheck.py build/dexchk/classes*.dex libs/*.jar
"""

import glob
import struct
import sys
import zipfile


# ---------------------------------------------------------------- dex 解析

def _uleb128(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


def _mutf8(buf, off):
    _, off = _uleb128(buf, off)
    end = buf.index(b'\x00', off)
    return buf[off:end].decode('utf-8', 'replace')


def read_dex(path):
    buf = open(path, 'rb').read()
    if buf[:4] != b'dex\n':
        raise ValueError('not a dex: ' + path)

    (string_ids_size, string_ids_off,
     type_ids_size, type_ids_off,
     proto_ids_size, proto_ids_off) = struct.unpack_from('<6I', buf, 0x38)
    (method_ids_size, method_ids_off) = struct.unpack_from('<2I', buf, 0x58)

    strings = []
    for i in range(string_ids_size):
        off = struct.unpack_from('<I', buf, string_ids_off + 4 * i)[0]
        strings.append(_mutf8(buf, off))

    types = [strings[struct.unpack_from('<I', buf, type_ids_off + 4 * i)[0]]
             for i in range(type_ids_size)]

    protos = []
    for i in range(proto_ids_size):
        _, ret_idx, params_off = struct.unpack_from('<3I', buf, proto_ids_off + 12 * i)
        params = []
        if params_off:
            n = struct.unpack_from('<I', buf, params_off)[0]
            for k in range(n):
                params.append(types[struct.unpack_from('<H', buf, params_off + 4 + 2 * k)[0]])
        protos.append((types[ret_idx], params))

    out = []
    for i in range(method_ids_size):
        class_idx, proto_idx, name_idx = struct.unpack_from('<HHI', buf, method_ids_off + 8 * i)
        ret, params = protos[proto_idx]
        out.append((types[class_idx], strings[name_idx], '(' + ''.join(params) + ')' + ret))
    return out


# ---------------------------------------------------------------- class 解析

CP_SIZES = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
            15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}


def class_info(data):
    """返回 (this, super, interfaces, methods)。失败返回 None"""
    if len(data) < 10 or data[:4] != b'\xca\xfe\xba\xbe':
        return None
    cp_count = struct.unpack_from('>H', data, 8)[0]
    off = 10
    cp = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = data[off]
        off += 1
        if tag == 1:
            ln = struct.unpack_from('>H', data, off)[0]
            off += 2
            cp[i] = data[off:off + ln].decode('utf-8', 'replace')
            off += ln
        else:
            sz = CP_SIZES.get(tag)
            if sz is None:
                return None
            if tag == 7:
                cp[i] = ('class', struct.unpack_from('>H', data, off)[0])
            off += sz
        i += 1

    def cls_name(idx):
        v = cp[idx]
        if isinstance(v, tuple):
            return cp[v[1]]
        return v

    off += 2                                     # access_flags
    this_idx, super_idx = struct.unpack_from('>HH', data, off)
    off += 4
    ifc = struct.unpack_from('>H', data, off)[0]
    off += 2
    itfs = [cls_name(struct.unpack_from('>H', data, off + 2 * k)[0]) for k in range(ifc)]
    off += 2 * ifc

    def skip_members(off, want_names):
        cnt = struct.unpack_from('>H', data, off)[0]
        off += 2
        out = []
        for _ in range(cnt):
            _, n_idx, d_idx = struct.unpack_from('>HHH', data, off)
            off += 6
            if want_names:
                out.append((cp[n_idx], cp[d_idx]))
            attrs = struct.unpack_from('>H', data, off)[0]
            off += 2
            for _ in range(attrs):
                ln = struct.unpack_from('>I', data, off + 2)[0]
                off += 6 + ln
        return off, out

    off, _ = skip_members(off, False)            # fields
    off, methods = skip_members(off, True)       # methods
    sup = 'L' + cls_name(super_idx) + ';' if super_idx else None
    return cls_name(this_idx), sup, ['L' + x + ';' for x in itfs], set(methods)


def jar_index(jars):
    index = {}
    for j in jars:
        try:
            z = zipfile.ZipFile(j)
        except Exception:
            continue
        for n in z.namelist():
            if not n.endswith('.class'):
                continue
            try:
                info = class_info(z.read(n))
            except Exception:
                info = None
            if info:
                index['L' + n[:-6] + ';'] = info
    return index


# java/lang/Object 的方法集是固定的。别的框架类（Enum/Number…）不展开 ——
# 它们的方法太多，写死容易漏；判为「未知」宁可不报，也不要刷屏假阳性。
OBJECT_METHODS = {
    ('equals', '(Ljava/lang/Object;)Z'),
    ('hashCode', '()I'),
    ('toString', '()Ljava/lang/String;'),
    ('getClass', '()Ljava/lang/Class;'),
    ('clone', '()Ljava/lang/Object;'),
    ('finalize', '()V'),
    ('notify', '()V'),
    ('notifyAll', '()V'),
    ('wait', '()V'),
    ('wait', '(J)V'),
    ('wait', '(JI)V'),
}


def lookup(index, cls, name, desc, seen=None):
    """
    沿父类与接口链查方法。三态返回：
      True    找到了
      False   链上能查的类都查完，确实没有
      None    链断在框架类（java/lang/Enum 之类不在本批 jar 里）—— 无法判定，不算缺失
    """
    if cls in ('Ljava/lang/Object;',):
        return (name, desc) in OBJECT_METHODS
    if seen is None:
        seen = set()
    if cls in seen:
        return None
    seen.add(cls)
    if cls not in index:
        return None
    _, sup, itfs, methods = index[cls]
    if (name, desc) in methods:
        return True
    results = []
    if sup:
        results.append(lookup(index, sup, name, desc, seen))
    for itf in itfs:
        results.append(lookup(index, itf, name, desc, seen))
    if any(r is True for r in results):
        return True
    if results and all(r is False for r in results):
        return False
    if not results:
        return False
    return None


def main():
    args = sys.argv[1:]
    dexes = [a for a in args if a.endswith('.dex')]
    jars = [a for a in args if not a.endswith('.dex')]
    if not dexes:
        dexes = sorted(glob.glob('build/dexchk/classes*.dex'))

    print('dex: %d 个, jar: %d 个' % (len(dexes), len(jars)))
    idx = jar_index(jars)
    print('jar 内类数: %d' % len(idx))

    refs = set()
    for d in dexes:
        for cls, name, desc in read_dex(d):
            refs.add((cls, name, desc))
    print('dex 引用方法数: %d' % len(refs))

    missing = []
    checked = 0
    for cls, name, desc in sorted(refs):
        if cls not in idx:
            continue
        if name in ('<init>', '<clinit>'):
            continue
        # d8 desugar 会把 lambda / 方法引用合成成新方法（lambda$xxx$0 / $r8$lambda$xxx），
        # 这些在 jar 里当然不存在 —— 是编译器产物，不是缺失
        if 'lambda$' in name:
            continue
        checked += 1
        if lookup(idx, cls, name, desc) is False:
            missing.append((cls, name, desc))

    print('可校验引用: %d, 缺失: %d' % (checked, len(missing)))
    for cls, name, desc in missing[:100]:
        print('  MISSING %s -> %s%s' % (cls, name, desc))
    return 1 if missing else 0


if __name__ == '__main__':
    sys.exit(main())
