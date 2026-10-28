package bin.HexFile;

import com.hex91.file.HexCore;
import com.hex91.file.HexCore.Type;

public class HexCoreTest {
    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) { pass++; }
        else { fail++; System.out.println("FAIL: " + name); }
    }

    static boolean eq(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    public static void main(String[] args) {
        // hexToBytes
        check("hex DEADBEEF", eq(HexCore.hexToBytes("DEADBEEF"), new byte[]{(byte)0xDE,(byte)0xAD,(byte)0xBE,(byte)0xEF}));
        check("hex spaced", eq(HexCore.hexToBytes("de ad be ef"), new byte[]{(byte)0xDE,(byte)0xAD,(byte)0xBE,(byte)0xEF}));
        try { HexCore.hexToBytes("ABC"); check("hex odd throws", false); }
        catch (IllegalArgumentException e) { check("hex odd throws", true); }

        // INT32 LE / BE
        check("int32 LE 1", eq(HexCore.patternFor(Type.INT32, "1", false), new byte[]{1,0,0,0}));
        check("int32 BE 1", eq(HexCore.patternFor(Type.INT32, "1", true), new byte[]{0,0,0,1}));
        check("int32 LE 0x12345678", eq(HexCore.patternFor(Type.INT32, "305419896", false), new byte[]{0x78,0x56,0x34,0x12}));

        // UINT16 LE
        check("uint16 LE 258", eq(HexCore.patternFor(Type.UINT16, "258", false), new byte[]{0x02,0x01}));
        check("uint16 BE 258", eq(HexCore.patternFor(Type.UINT16, "258", true), new byte[]{0x01,0x02}));

        // INT8 signed
        check("int8 -1", eq(HexCore.patternFor(Type.INT8, "-1", false), new byte[]{(byte)0xFF}));
        try { HexCore.patternFor(Type.UINT8, "-1", false); check("uint8 neg throws", false); }
        catch (IllegalArgumentException e) { check("uint8 neg throws", true); }

        // FLOAT32
        check("float32 BE 1.0", eq(HexCore.patternFor(Type.FLOAT32, "1.0", true), new byte[]{0x3F, (byte)0x80, 0x00, 0x00}));
        check("float32 LE 1.0", eq(HexCore.patternFor(Type.FLOAT32, "1.0", false), new byte[]{0x00, 0x00, (byte)0x80, 0x3F}));

        // FLOAT64
        byte[] d64be = HexCore.patternFor(Type.FLOAT64, "1.0", true);
        check("float64 BE len", d64be.length == 8);
        check("float64 BE 1.0", eq(d64be, new byte[]{0x3F,(byte)0xF0,0,0,0,0,0,0}));

        // UINT64
        check("uint64 LE max", eq(HexCore.patternFor(Type.UINT64, "18446744073709551615", false),
                new byte[]{(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF}));
        check("uint64 BE 256", eq(HexCore.patternFor(Type.UINT64, "256", true),
                new byte[]{0,0,0,0,0,0,1,0}));

        // TEXT (UTF-8)
        check("text hello", eq(HexCore.patternFor(Type.TEXT, "AB", false), new byte[]{'A','B'}));

        // findAll basic
        byte[] data = HexCore.hexToBytes("DEADBEEFDEADBEEF00112233DEADBEEF");
        int[] m1 = HexCore.findAll(data, HexCore.hexToBytes("DEADBEEF"));
        check("findAll count 3", m1.length == 3);
        check("findAll first 0", m1[0] == 0);
        check("findAll second 4", m1[1] == 4);
        check("findAll third 12", m1[2] == 12);

        // findAll none
        check("findAll none", HexCore.findAll(data, HexCore.hexToBytes("FFFFFFFF")).length == 0);

        // findAll with KMP overlap (pattern AAAA(2B) in AAAAAAAA(4B) -> 0,1,2)
        byte[] d2 = HexCore.hexToBytes("AAAAAAAA");
        int[] m2 = HexCore.findAll(d2, HexCore.hexToBytes("AAAA"));
        check("findAll overlap 3", m2.length == 3 && m2[0]==0 && m2[1]==1 && m2[2]==2);

        // numeric search
        byte[] d3 = HexCore.hexToBytes("000000010000000200000003");
        int[] m3 = HexCore.findAll(d3, HexCore.patternFor(Type.INT32, "2", true));
        check("int32 BE search", m3.length == 1 && m3[0] == 4);

        // patch
        byte[] d4 = HexCore.hexToBytes("00112233");
        HexCore.patch(d4, 1, HexCore.hexToBytes("AB"));
        check("patch mid", eq(d4, HexCore.hexToBytes("00AB2233")));
        try { HexCore.patch(d4, 3, HexCore.hexToBytes("AABB")); check("patch oob throws", false); }
        catch (IllegalArgumentException e) { check("patch oob throws", true); }

        // bytesToHex
        check("bytesToHex", HexCore.bytesToHex(d4, 0, d4.length).equals("00 AB 22 33"));

        // ===== v1.32.0 增强逻辑 =====
        // formatCode 进制宽度
        check("fmt hex", HexCore.formatCode(0x0A, HexCore.BASE_HEX).equals("0A"));
        check("fmt dec", HexCore.formatCode(255, HexCore.BASE_DEC).equals("255"));
        check("fmt oct", HexCore.formatCode(63, HexCore.BASE_OCT).equals("077"));
        check("fmt bin", HexCore.formatCode(10, HexCore.BASE_BIN).equals("00001010"));
        check("codeWidth bin", HexCore.codeWidth(HexCore.BASE_BIN) == 8);
        check("byteStart hex col8", HexCore.byteStart(8, HexCore.BASE_HEX) == 35);

        // spliceInsert / removeRange
        byte[] s0 = HexCore.hexToBytes("AABBCCDD");
        byte[] s1 = HexCore.spliceInsert(s0, 2, HexCore.hexToBytes("EEFF"));
        check("spliceInsert", eq(s1, HexCore.hexToBytes("AA BB EE FF CC DD")));
        byte[] s2 = HexCore.removeRange(s1, 2, 2);
        check("removeRange == orig", eq(s2, s0));
        try { HexCore.spliceInsert(s0, 99, new byte[]{1}); check("splice oob throws", false); }
        catch (IllegalArgumentException e) { check("splice oob throws", true); }

        // EditAction undo/redo: overwrite (old/new 等长)
        byte[] d5 = HexCore.hexToBytes("0011223344");
        byte[] old5 = HexCore.copyRange(d5, 2, 3); // "11"（1 字节）
        HexCore.EditAction ow = new HexCore.EditAction(false, 2, old5, HexCore.hexToBytes("AB"));
        byte[] d5u = HexCore.undoData(HexCore.redoData(d5, ow), ow); // redo then undo -> back to d5
        check("overwrite undo/redo", eq(d5u, d5));
        byte[] d5r = HexCore.redoData(d5, ow); // overwrite 偏移2处的 22 -> AB
        check("overwrite redo", eq(d5r, HexCore.hexToBytes("00 11 AB 33 44")));

        // EditAction undo/redo: insert
        byte[] d6 = HexCore.hexToBytes("0011");
        HexCore.EditAction ins = new HexCore.EditAction(true, 1, null, HexCore.hexToBytes("AABB"));
        byte[] d6i = HexCore.redoData(d6, ins); // insert at 1 -> 00 AABB 11
        check("insert redo", eq(d6i, HexCore.hexToBytes("00 AA BB 11")));
        byte[] d6u = HexCore.undoData(d6i, ins); // undo insert -> 0011
        check("insert undo", eq(d6u, d6));

        // copyRange
        check("copyRange swap", eq(HexCore.copyRange(d5, 4, 2), HexCore.hexToBytes("2233")));

        System.out.println("HexCoreTest: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
