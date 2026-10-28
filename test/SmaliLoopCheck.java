import java.nio.file.*;
import java.util.concurrent.*;

/** 纯 Java 复刻 SyntaxHighlight.smali 扫描逻辑（span 改为计数），验证死循环根因与修复闭环。
 *  用法：java SmaliLoopCheck <file> [old|new]   —— old=修复前(应超时)，new=修复后(应正常返回) */
public class SmaliLoopCheck {
    static int spanCount = 0;

    static final String[] OPCODE_PREFIXES = {
        "invoke","const","return","if-","goto","move","new","iget","iput",
        "sget","sput","aget","aput","add","sub","mul","div","rem","neg",
        "not","and","or-","xor","shl","shr","ushr","int-to","long-to",
        "float-to","double-to","check-cast","instance-of","throw","monitor-",
        "fill-array","packed-switch","sparse-switch","cmp","nop","execute"
    };
    static boolean isOpcode(String w){ for(String p:OPCODE_PREFIXES) if(w.startsWith(p)) return true; return false; }

    // 复刻 smali()：fixed=true 对应修复后（标签从 i+1 扫），false 对应修复前（从 i 扫 → 死循环）
    static void smali(String text, boolean fixed){
        int len=text.length(), i=0;
        while(i<len){
            char c=text.charAt(i);
            if(c=='#'){ int e=lineEnd(text,i); i=e+1; continue; }
            if(c=='"'){ int e=i+1; while(e<len&&text.charAt(e)!='"'){ if(text.charAt(e)=='\\'&&e+1<len)e++; e++; } i=e+1; continue; }
            if(c==':'&&(i==0||text.charAt(i-1)==' '||text.charAt(i-1)=='\t'||text.charAt(i-1)=='\n')){
                int e=tokenEnd(text, fixed? i+1 : i);   // 关键差异点
                spanCount++; i=e; continue;
            }
            if(isWordStart(c)){
                int e=tokenEnd(text,i); String w=text.substring(i,e);
                if(w.startsWith(".")) spanCount++;
                else if(w.equals("v")||w.equals("p")){ i=e; continue; }
                else if((w.charAt(0)=='v'||w.charAt(0)=='p')&&w.length()>1&&isDigits(w.substring(1))) spanCount++;
                else if(w.startsWith("L")&&w.endsWith(";")) spanCount++;
                else if(isOpcode(w)) spanCount++;
                else if(isNumberLiteral(w)) spanCount++;
                i=e; continue;
            }
            i++;
        }
    }
    static int lineEnd(String t,int from){ int e=t.indexOf('\n',from); return e<0? t.length()-1 : e-1; }
    static int tokenEnd(String t,int from){ int i=from; while(i<t.length()){ char c=t.charAt(i);
        if(Character.isLetterOrDigit(c)||c=='_'||c=='-'||c=='$'||c=='/'||c=='.') i++; else break; } return i; }
    static boolean isWordStart(char c){ return Character.isLetter(c)||c=='.'||c=='_'; }
    static boolean isDigits(String s){ if(s.isEmpty())return false; for(int i=0;i<s.length();i++) if(!Character.isDigit(s.charAt(i)))return false; return true; }
    static boolean isNumberLiteral(String w){ if(w.isEmpty())return false; int s=w.charAt(0)=='-'?1:0; if(s==w.length())return false;
        if(w.regionMatches(s,"0x",0,2)) return s+2<w.length()&&isDigits(w.substring(s+2)); return isDigits(w.substring(s)); }

    public static void main(String[] a) throws Exception {
        String text=new String(Files.readAllBytes(Paths.get(a[0])),"UTF-8");
        boolean fixed = !("old".equals(a.length>1?a[1]:"new"));
        System.out.println("mode="+(fixed?"NEW(修复后)":"OLD(修复前)")+"  len="+text.length());
        ExecutorService ex=Executors.newSingleThreadExecutor();
        Future<?> f=ex.submit(()->smali(text, fixed));
        try{ f.get(5,TimeUnit.SECONDS); System.out.println("OK  扫描完成，span 数="+spanCount); }
        catch(TimeoutException t){ System.out.println("TIMEOUT/死循环：smali 扫描在 5s 内未返回（证实 OLD 逻辑卡死）"); f.cancel(true); System.exit(0); }
        ex.shutdownNow();
    }
}
