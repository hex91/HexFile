import com.hex91.file.FormatUtil;
import com.hex91.file.FormatUtil.FormatException;

/** FormatUtil 桌面验证：JSON 美化/压缩/异常/字面量保留、XML 缩进、代码 beautify */
public class FormatTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        json();
        xml();
        code();
        detect();
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------- JSON
    static void json() {
        eq("{\"a\":1}",
            "{\n  \"a\": 1\n}",
            FormatUtil.FMT_JSON, "json 简单对象");

        eq("{\"a\":1,\"b\":[1,2,{\"c\":true}],\"d\":null}",
            "{\n  \"a\": 1,\n  \"b\": [\n    1,\n    2,\n    {\n      \"c\": true\n    }\n  ],\n  \"d\": null\n}",
            FormatUtil.FMT_JSON, "json 嵌套");

        eq("{}", "{}", FormatUtil.FMT_JSON, "json 空对象");
        eq("[]", "[]", FormatUtil.FMT_JSON, "json 空数组");
        eq("{\"a\":{},\"b\":[]}", "{\n  \"a\": {},\n  \"b\": []\n}", FormatUtil.FMT_JSON, "json 嵌套空容器");

        // 压缩
        eq("{\n  \"a\": 1,\n  \"b\": [ 1, 2 ]\n}", "{\"a\":1,\"b\":[1,2]}",
            FormatUtil.FMT_JSON_MIN, "json 压缩");
        eq("{\"a\":\"x, y\"}", "{\"a\":\"x, y\"}", FormatUtil.FMT_JSON_MIN, "json 压缩保字符串内逗号");

        // 数字字面量保留（org.json 会把 1.0/长整数转 double 丢精度，自写 parser 必须原样）
        eq("{\"a\":1.0,\"b\":1e10,\"c\":12345678901234567890,\"d\":-0.5}",
            "{\n  \"a\": 1.0,\n  \"b\": 1e10,\n  \"c\": 12345678901234567890,\n  \"d\": -0.5\n}",
            FormatUtil.FMT_JSON, "json 字面量保留");

        // 字符串转义原样
        eq("{\"a\":\"c:\\\\temp\\\\x\\\"y\"}",
            "{\n  \"a\": \"c:\\\\temp\\\\x\\\"y\"\n}",
            FormatUtil.FMT_JSON, "json 转义原样");

        // 尾随逗号宽容
        eq("{\"a\":1,}", "{\n  \"a\": 1\n}", FormatUtil.FMT_JSON, "json 尾随逗号");
        eq("[1,2,]", "[\n  1,\n  2\n]", FormatUtil.FMT_JSON, "json 数组尾随逗号");

        // 异常场景
        err("{\"a\":1", FormatUtil.FMT_JSON, "json 未闭合");
        err("{\"a\" 1}", FormatUtil.FMT_JSON, "json 缺冒号");
        err("{\"a\":1} x", FormatUtil.FMT_JSON, "json 尾随内容");
        err("{a:1}", FormatUtil.FMT_JSON, "json 键无引号");
        err("{\"a\":tru}", FormatUtil.FMT_JSON, "json 残缺字面量");
        err("{\"a\":01x}", FormatUtil.FMT_JSON, "json 残缺数字");
        err("{\"a\":\"unclosed}", FormatUtil.FMT_JSON, "json 字符串未闭合");
        err("", FormatUtil.FMT_JSON, "json 空输入");
    }

    // ---------------------------------------------------------- XML
    static void xml() {
        String r1 = FormatUtil.formatXml("<root><a>1</a><b x=\"2\"/></root>");
        eqc(r1, "<root>\n  <a>1</a>\n  <b x=\"2\"/>\n</root>\n", "xml 嵌套缩进");

        String r2 = FormatUtil.formatXml("<a>\n\n\n  <b>  text  </b>\n</a>");
        eqc(r2, "<a>\n  <b>text</b>\n</a>\n", "xml 文本节点 trim");

        String r3 = FormatUtil.formatXml("<a><!-- c --><b><![CDATA[ <x> ]]></b></a>");
        eqc(r3, "<a>\n  <!-- c -->\n  <b>\n    <![CDATA[ <x> ]]>\n  </b>\n</a>\n", "xml 注释/CDATA");

        String r4 = FormatUtil.formatXml("<?xml version=\"1.0\"?><!DOCTYPE a><a/>");
        eqc(r4, "<?xml version=\"1.0\"?>\n<!DOCTYPE a>\n<a/>\n", "xml 声明/DOCTYPE");

        String r5 = FormatUtil.formatXml("<html><body><ul><li><a href=\"#\">x</a></li></ul></body></html>");
        eqc(r5, "<html>\n  <body>\n    <ul>\n      <li>\n        <a href=\"#\">x</a>\n      </li>\n    </ul>\n  </body>\n</html>\n",
            "html 行内标签不缩进");

        // 内容零损：所有非空白字符必须原序保留
        String src = "<manifest xmlns:android=\"http://x\"><application android:label=\"L\"><activity/></application></manifest>";
        String out = FormatUtil.formatXml(src);
        String stripped = out.replaceAll("[\\s]+", "");
        eqc(stripped, src.replaceAll("[\\s]+", ""), "xml 非空白内容零损");
    }

    // ---------------------------------------------------------- CODE
    static void code() {
        String r1 = FormatUtil.formatCode("public class A{int x=1;void f(){if(x>0){x=2;}}}");
        eqc(r1,
            "public class A {\n" +
            "    int x=1;\n" +
            "    void f() {\n" +
            "        if(x>0) {\n" +
            "            x=2;\n" +
            "        }\n" +
            "    }\n" +
            "}\n", "java 基础缩进");

        // 字符串内的 ; { } 绝不触发规则
        String r2 = FormatUtil.formatCode("class A{String s=\"a;b{c}\";}");
        eqc(r2, "class A {\n    String s=\"a;b{c}\";\n}\n", "java 字符串保护");

        // 注释内的 } 不缩减进
        String r3 = FormatUtil.formatCode("class A{\n// } not a brace\n/* } neither */\nint x;\n}");
        eqc(r3, "class A {\n    // } not a brace\n    /* } neither */\n    int x;\n}\n", "java 注释保护");

        // for(;;) 分号不换行
        String r4 = FormatUtil.formatCode("for(int i=0;i<10;i++){sum+=i;}");
        eqc(r4, "for(int i=0;i<10;i++) {\n    sum+=i;\n}\n", "java for 分号");

        // } else { 续排
        String r5 = FormatUtil.formatCode("if(a){x=1;}else{y=2;}");
        eqc(r5, "if(a) {\n    x=1;\n} else {\n    y=2;\n}\n", "java } else {");

        // case/default
        String r6 = FormatUtil.formatCode("switch(x){case 1:f();break;default:g();}");
        eqc(r6, "switch(x) {\n    case 1:\n    f();\n    break;\n    default:\n    g();\n}\n", "java case/default");

        // 连续空行折叠
        String r7 = FormatUtil.formatCode("class A{\n\n\n\n    int x;\n\n\n}");
        eqc(r7, "class A {\n\n    int x;\n\n}\n", "java 空行折叠");

        // 行内逗号不换行（方法参数/调用参数）
        String r8 = FormatUtil.formatCode("void f(int a,int b){g(a,b);}");
        eqc(r8, "void f(int a,int b) {\n    g(a,b);\n}\n", "java 逗号处理");

        // 内容零损（去空白后字符序列不变）
        String src = "class T{String s=\"a;{}//x\";void m(){f(\";{\");}}";
        String stripped = FormatUtil.formatCode(src).replaceAll("[\\s]+", "");
        eqc(stripped, src.replaceAll("[\\s]+", ""), "java 内容零损");
    }

    // ---------------------------------------------------------- detect
    static void detect() {
        eqi(FormatUtil.detectFormat("a.json", ""), FormatUtil.FMT_JSON, "detect json 后缀");
        eqi(FormatUtil.detectFormat("a.xml", ""), FormatUtil.FMT_XML, "detect xml 后缀");
        eqi(FormatUtil.detectFormat("a.html", ""), FormatUtil.FMT_XML, "detect html 后缀");
        eqi(FormatUtil.detectFormat("A.java", ""), FormatUtil.FMT_CODE, "detect java 后缀");
        eqi(FormatUtil.detectFormat("a.cpp", ""), FormatUtil.FMT_CODE, "detect cpp 后缀");
        eqi(FormatUtil.detectFormat("a.smali", ""), FormatUtil.FMT_CODE, "detect smali 后缀");
        eqi(FormatUtil.detectFormat("noext", "{\"a\":1}"), FormatUtil.FMT_JSON, "detect json 内容");
        eqi(FormatUtil.detectFormat("noext", "  <root/>"), FormatUtil.FMT_XML, "detect xml 内容");
        eqi(FormatUtil.detectFormat("a.txt", "hello"), 0, "detect 无法识别");
    }

    // ---------------------------------------------------------- helpers
    static void eq(String in, String expect, int mode, String name) {
        try {
            String out = FormatUtil.format(in, mode);
            if (expect.equals(out)) ok(name);
            else bad(name, "expect:\n" + expect + "\nactual:\n" + out);
        } catch (Exception e) {
            bad(name, "exception: " + e);
        }
    }

    static void eqc(String actual, String expect, String name) {
        if (expect.equals(actual)) ok(name);
        else bad(name, "expect:\n" + expect + "\nactual:\n" + actual);
    }

    static void err(String in, int mode, String name) {
        try {
            FormatUtil.format(in, mode);
            bad(name, "expected FormatException but got success");
        } catch (FormatException e) {
            ok(name);
        } catch (Exception e) {
            bad(name, "wrong exception: " + e);
        }
    }

    static void eqi(int actual, int expect, String name) {
        if (actual == expect) ok(name);
        else bad(name, "expect " + expect + " actual " + actual);
    }

    static void ok(String name) { pass++; System.out.println("PASS " + name); }

    static void bad(String name, String msg) {
        fail++;
        System.out.println("FAIL " + name + " -> " + msg);
    }
}
