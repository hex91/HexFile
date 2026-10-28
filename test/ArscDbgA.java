import com.hex91.file.ArscFile;
import com.hex91.file.ArscValues;
import java.io.File;
import jadx.core.xmlgen.entry.ResourceEntry;

public class ArscDbgA {
    public static void main(String[] args) throws Exception {
        ArscFile a = ArscFile.parse(new File(args[0]));
        int n = 0;
        for (ResourceEntry e : a.storage.getResources()) {
            try {
                ArscValues.valueDisplay(a, e);
                n++;
            } catch (Throwable t) {
                System.out.println("CRASH at entry id=0x" + Integer.toHexString(e.getId())
                        + " type=" + e.getTypeName() + " key=" + e.getKeyName()
                        + " config=" + e.getConfig());
                t.printStackTrace(System.out);
                return;
            }
        }
        System.out.println("all " + n + " entries displayed OK");
    }
}
