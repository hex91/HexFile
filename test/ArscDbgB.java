import com.hex91.file.ArscFile;
import java.io.File;
import jadx.core.xmlgen.entry.ResourceEntry;

public class ArscDbgB {
    public static void main(String[] args) throws Exception {
        ArscFile a = ArscFile.parse(new File(args[0]));
        int found = 0;
        for (ResourceEntry e : a.storage.getResources()) {
            if ("abc_action_bar_up_description".equals(e.getKeyName())) {
                String v = ArscValuesHolder.display(a, e);
                System.out.println("config=[" + e.getConfig() + "] value=" + v);
                if (v != null && v.contains("MT")) found++;
            }
        }
        System.out.println("edited values found: " + found);
    }
}
class ArscValuesHolder {
    static String display(bin.HexFile.ArscFile a, ResourceEntry e) {
        return bin.HexFile.ArscValues.valueDisplay(a, e);
    }
}
