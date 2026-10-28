import java.util.*;
import java.util.regex.*;

public class FindLogicTest {
    static boolean optCase, optWord, optRegex, regexError;

    static List<int[]> findMatches(String text, String key) {
        List<int[]> res = new ArrayList<>();
        if (key.isEmpty()) return res;
        if (optRegex) {
            int flags = optCase ? 0 : Pattern.CASE_INSENSITIVE;
            try {
                Pattern p = Pattern.compile(key, flags);
                Matcher m = p.matcher(text);
                while (m.find()) {
                    int s = m.start(), e = m.end();
                    if (optWord && !isWholeWord(text, s, e)) continue;
                    res.add(new int[]{s, e});
                }
            } catch (PatternSyntaxException ex) { regexError = true; }
            return res;
        }
        if (optCase) {
            int idx = 0;
            while ((idx = text.indexOf(key, idx)) >= 0) {
                int e = idx + key.length();
                if (optWord && !isWholeWord(text, idx, e)) { idx = e; continue; }
                res.add(new int[]{idx, e}); idx = e;
            }
        } else {
            String lt = text.toLowerCase(), lk = key.toLowerCase();
            int idx = 0;
            while ((idx = lt.indexOf(lk, idx)) >= 0) {
                int e = idx + key.length();
                if (optWord && !isWholeWord(text, idx, e)) { idx = e; continue; }
                res.add(new int[]{idx, e}); idx = e;
            }
        }
        return res;
    }
    static boolean isWholeWord(String t, int s, int e) {
        boolean b = (s == 0) || !Character.isLetterOrDigit(t.charAt(s - 1));
        boolean a = (e >= t.length()) || !Character.isLetterOrDigit(t.charAt(e));
        return b && a;
    }
    static String replaceAll(String text, String key, String repl) {
        if (optRegex) {
            int flags = optCase ? 0 : Pattern.CASE_INSENSITIVE;
            return Pattern.compile(key, flags).matcher(text).replaceAll(repl);
        }
        List<int[]> m = findMatches(text, key);
        StringBuilder sb = new StringBuilder(text);
        for (int i = m.size() - 1; i >= 0; i--) sb.replace(m.get(i)[0], m.get(i)[1], repl);
        return sb.toString();
    }

    static int pass=0, fail=0;
    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("PASS "+name); }
        else { fail++; System.out.println("FAIL "+name); }
    }

    public static void main(String[] a) {
        // 1. plain case-insensitive
        optCase=optWord=optRegex=false; regexError=false;
        List<int[]> r = findMatches("Hello hello HELLO", "hello");
        check("ci count=3", r.size()==3);
        // 2. case-sensitive
        optCase=true; r=findMatches("Hello hello HELLO","hello");
        check("cs count=1", r.size()==1);
        // 3. whole word
        optCase=optWord=true; r=findMatches("hello hellox world","hello");
        check("word count=1", r.size()==1);
        // 4. regex
        optCase=optWord=false; optRegex=true; r=findMatches("abc123 def456","\\d+");
        check("regex digits=2", r.size()==2);
        // 5. regex error
        r=findMatches("abc","("); check("regex error flag", regexError && r.isEmpty());
        // 6. regex replaceAll with group
        optRegex=true; regexError=false;
        String out=replaceAll("a1 b2 c3","([a-z])(\\d)","$1_$2");
        check("regex repl", out.equals("a_1 b_2 c_3"));
        // 7. plain replaceAll
        optRegex=false; out=replaceAll("foo bar foo","foo","XXX");
        check("plain repl", out.equals("XXX bar XXX"));
        // 8. whole word replaceAll no touch substring
        optWord=true; out=replaceAll("foo foobar foo","foo","X");
        check("word repl", out.equals("X foobar X"));
        // 9. case-sensitive replaceAll
        optWord=false; optCase=true; out=replaceAll("Abc abc","abc","Z");
        check("cs repl", out.equals("Abc Z"));

        System.out.println("\n==== "+pass+" passed, "+fail+" failed ====");
        if (fail>0) System.exit(1);
    }
}
