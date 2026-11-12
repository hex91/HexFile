import com.hex91.file.core.HexCore;

/**
 * Hex 编辑器排版回归（v1.68.0）：列头与行文本必须严格对齐
 * —— 对不齐就会出现「列号写在 A 位置、字节在 B 位置」的错位观感（旧版正是如此）。
 */
public class HexLayoutTest {

    static int pass = 0, fail = 0;

    static void check(boolean cond, String msg) {
        if (cond) { pass++; System.out.println("PASS  " + msg); }
        else { fail++; System.out.println("FAIL  " + msg); }
    }

    public static void main(String[] args) {
        final int ROW = 16;
        final int HEX = HexCore.BASE_HEX, BIN = HexCore.BASE_BIN;
        final int DEC = HexCore.BASE_DEC;

        // ---- 偏移列与首字节列 ----
        check(HexCore.byteStart(0, HEX) == 10, "第 0 字节起始于第 10 列（8 偏移 + 2 空格）");
        check(HexCore.byteStart(8, HEX) == 35, "第 8 字节前多一个分组空格（35）");
        check(HexCore.byteStart(15, HEX) == 56, "第 15 字节起始列 56");

        // ---- 文本列 ----
        int col = HexCore.textColumn(HEX, ROW);
        check(col == 60, "HEX 文本列 = 60（实际 " + col + "）");
        check(HexCore.textColumn(BIN, ROW) == 156, "BIN 文本列 = 156");
        check(HexCore.textColumn(HEX, 8) == 35, "8 字节行文本列 = 35（无分组空格）");

        // ---- 列头与行文本对齐（核心断言）----
        String head = HexCore.headerLine(HEX, ROW);
        check(head.length() == 60 + 16, "列头总长 = 76（实际 " + head.length() + "）");
        check(head.indexOf("0123456789ABCDEF") == col, "列头文本列与 rowLine 文本列一致");
        check(head.startsWith("OFFSET"), "列头以 OFFSET 开头");

        byte[] d = "HelloHexFileTestData0123456789".getBytes();
        String row0 = HexCore.rowLine(d, 0, HEX, ROW, "HelloHexFileTest");
        check(row0.indexOf("HelloHexFileTest") == col, "第 0 行文本落在文本列");
        check(row0.length() == head.length(), "行宽与列头一致（" + row0.length() + "）");
        check(row0.startsWith("00000000"), "偏移为 8 位大写十六进制");
        // 行首 16 字节内容：48 65 6C 6C 6F 48 65 78 46 69 6C 65 54 65 73 74
        check(row0.contains("48 65 6C 6C 6F 48 65 78"), "字节区格式正确");
        check(row0.charAt(34) == ' ', "第 8 列前有分组空格");

        String row1 = HexCore.rowLine(d, 1, HEX, ROW, "Data");
        check(row1.startsWith("00000010"), "第 1 行偏移为 00000010");
        check(row1.indexOf("Data") == col, "第 1 行文本同样对齐");

        // ---- 末行不足 16 字节：用空格补齐，文本列不左移 ----
        byte[] shortData = new byte[4];
        String rowS = HexCore.rowLine(shortData, 0, HEX, ROW, "AAAA");
        check(rowS.indexOf("AAAA") == col, "不足一行时文本列不移动");
        check(rowS.length() == head.length(), "不足一行时行宽仍与列头一致");

        // ---- 空数据 / null 不崩 ----
        check(HexCore.rowLine(null, 0, HEX, ROW, null).length() == head.length(), "空数据显示空行且等宽");

        // ---- 其它进制的行宽 ----
        check(HexCore.rowLine(d, 0, BIN, ROW, "x").indexOf("x") == HexCore.textColumn(BIN, ROW),
                "BIN 行文本对齐");
        check(HexCore.rowLine(d, 0, DEC, ROW, "x").indexOf("x") == HexCore.textColumn(DEC, ROW),
                "DEC 行文本对齐");
        check(HexCore.headerLine(BIN, ROW).length() > 0, "非 HEX 进制列头非空");

        // ---- v1.68.1 点击反算列：字符下标 ↔ 字节列 必须自洽 ----
        boolean roundTrip = true;
        for (int c = 0; c < ROW; c++) {
            int st = HexCore.byteStart(c, HEX);
            if (HexCore.columnAt(st, HEX, ROW) != c) {
                roundTrip = false;
                System.out.println("      列 " + c + " 起点 " + st + " 反算为 "
                        + HexCore.columnAt(st, HEX, ROW));
            }
            // 该字节的第二位十六进制也应落在同一列
            if (HexCore.columnAt(st + 1, HEX, ROW) != c) {
                roundTrip = false;
                System.out.println("      列 " + c + " 第二位反算失败");
            }
        }
        check(roundTrip, "字节区：任一列的首位/第二位字符都反算回同一列");

        boolean textOk = true;
        for (int c = 0; c < ROW; c++) {
            if (HexCore.columnAt(col + c, HEX, ROW) != c) textOk = false;
        }
        check(textOk, "文本列：第 n 个字符反算为第 n 字节");

        check(HexCore.columnAt(0, HEX, ROW) == 0, "点偏移区归到第 0 字节");
        check(HexCore.columnAt(9, HEX, ROW) == 0, "点偏移与字节间的空格归到第 0 字节");
        check(HexCore.columnAt(9999, HEX, ROW) == 15, "超出右边界收敛到最后一列");
        check(HexCore.columnAt(-5, HEX, ROW) == 0, "负下标收敛到第 0 列");
        check(HexCore.columnAt(HexCore.byteStart(3, BIN), BIN, ROW) == 3, "BIN 进制同样可反算");

        // ---- v1.68.2 手机端 8 字节/行：列头、行、反算全部对齐 ----
        final int R8 = 8;
        String h8 = HexCore.headerLine(HEX, R8);
        int c8 = HexCore.textColumn(HEX, R8);
        check(c8 == 35, "8 字节行文本列 = 35（实际 " + c8 + "）");
        check(h8.length() == c8 + R8, "8 字节列头总长 = 43（实际 " + h8.length() + "）");
        check(h8.charAt(c8) == '0', "8 字节列头文本列从 0 开始");
        String r8 = HexCore.rowLine(d, 0, HEX, R8, "HelloHex");
        check(r8.length() == h8.length(), "8 字节行宽与列头一致（" + r8.length() + "）");
        check(r8.indexOf("HelloHex") == c8, "8 字节行文本落在文本列");
        check(r8.contains("48 65 6C 6C 6F 48 65 78"), "8 字节行字节区格式正确");
        boolean rt8 = true;
        for (int c = 0; c < R8; c++) {
            if (HexCore.columnAt(c8 + c, HEX, R8) != c) rt8 = false;
            if (HexCore.columnAt(HexCore.byteStart(c, HEX), HEX, R8) != c) rt8 = false;
        }
        check(rt8, "8 字节行：字节区与文本列都能反算回本列");
        check(HexCore.columnAt(9999, HEX, R8) == 7, "8 字节行右越界收敛到第 7 列");

        // ---- v1.68.2 文本列特殊符号：控制字符点，其余按 Latin-1 原样 ----
        byte[] sym = new byte[]{0x00, 0x1F, 0x20, 0x41, 0x7E, 0x7F, (byte) 0xB0, (byte) 0xE9};
        String ts = HexCore.textRow(sym, 0, sym.length);
        check(ts.length() == 8, "textRow 长度恒等于字节数");
        check(ts.charAt(0) == '.' && ts.charAt(1) == '.', "控制字符 00/1F 显示为点");
        check(ts.charAt(2) == ' ' && ts.charAt(3) == 'A', "可打印 ASCII 原样显示");
        check(ts.charAt(4) == '~', "0x7E 显示为 ~");
        check(ts.charAt(5) == '.', "0x7F(DEL) 显示为点");
        check(ts.charAt(6) == 0xB0, "0xB0 显示为 °（不再点掉）");
        check(ts.charAt(7) == 0xE9, "0xE9 显示为 é（不再点掉）");

        System.out.println();
        System.out.println("RESULT  pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
