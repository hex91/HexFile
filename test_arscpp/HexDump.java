import java.io.*;

public class HexDump {
    public static void main(String[] a) throws Exception {
        byte[] d = readAll(a[0]);
        int p = Integer.decode(a[1]);
        int len = Integer.decode(a[2]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x ", d[p + i] & 0xFF));
            if ((i + 1) % 16 == 0) sb.append("\n");
        }
        System.out.println("@0x" + Integer.toHexString(p) + ":\n" + sb);
    }
    static byte[] readAll(String f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
