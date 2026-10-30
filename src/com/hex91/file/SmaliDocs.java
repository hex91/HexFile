package com.hex91.file;

/**
 * v1.61.0 Smali 语法知识库（纯静态数据，供 {@link SmaliDocActivity} 渲染）。
 *
 * <p>每条目四列：{分类, 语法, 中文解析, 示例}。解析行在 UI 中以 "# " 注释形式
 * 展示在红色语法下方；示例为等宽代码块。新增条目直接往 {@link #E} 追加一行即可，
 * 分类顺序由 {@link #CATS} 控制（未列出的分类自动排到最后）。</p>
 *
 * <p>语法约定：vx/vy 表示寄存器占位（v=本地寄存器，p=参数寄存器），
 * 角标 /from16 /16 /jumbo 表示索引宽度。内容参照 AOSP dalvik-bytecode 规范。</p>
 */
public final class SmaliDocs {

    private SmaliDocs() {}

    /** 分类显示顺序（其余分类按首次出现顺序排在后面）。 */
    public static final String[] CATS = {
            "基础概念", "伪指令与结构", "数据移动", "常量加载", "返回",
            "算术与逻辑", "比较", "跳转与分支", "数组", "字段操作",
            "方法调用", "对象与类型", "类型转换", "其他指令"
    };

    /**
     * 条目表。每行 4 列：分类 / 语法 / 中文解析（多行用 \n）/ 示例（多行用 \n）。
     */
    public static final String[][] E = {

        // ================= 基础概念 =================
        {"基础概念", "vN / pN（寄存器命名）",
            "# smali 中寄存器两种写法：\n"
          + "#   vN —— 本地寄存器，v0、v1、v2…\n"
          + "#   pN —— 方法参数寄存器，p0、p1…（非 static 方法 p0=this）\n"
          + "# 两者是同一片寄存器的不同视角：pN = v(寄存器总数-参数个数+N)。\n"
          + "# .registers 声明总数；.locals 只声明本地个数（参数另算）。",
            ".method public foo(I)V\n"
          + "    .locals 2        # 本地寄存器 v0、v1\n"
          + "                     # 参数 p0=this，p1=int 入参\n"
          + "    ..."},
        {"基础概念", "寄存器索引宽度（4bit/8bit/16bit）",
            "# Dalvik 指令中寄存器编号按指令格式占用不同位数：\n"
          + "#   无后缀        —— 目的/源都是 4bit，寄存器号 ≤ 15（v0~v15）\n"
          + "#   /from16       —— 目的 8bit（≤255），源 16bit（≤65535）\n"
          + "#   /16           —— 目的与源都是 16bit（≤65535）\n"
          + "# 例：move/from16 vx, vy —— vy 可以到 64k，vx 必须 ≤ 255。\n"
          + "# 常量字面量宽度同理：const/4（4bit 有符号）、const/16、const、const/high16。",
            "# 高编号寄存器访问需要宽索引版本：\n"
          + "move/from16 v0, v256   # 源 v256 超过 4bit，必须用 /from16\n"
          + "move/16     v300, v301 # 两个都超 8bit，用 /16"},
        {"基础概念", "类型描述符",
            "# 字段/方法签名里的类型缩写：\n"
          + "#   V void   Z boolean  B byte  S short  C char\n"
          + "#   I int    J long     F float D double\n"
          + "#   L包名/类名; —— 对象类型，分号结尾\n"
          + "#   [I / [Ljava/lang/String; —— 数组（每维一个 [）",
            "# 例：\n"
          + "field name:Ljava/lang/String;\n"
          + "field flags:[I          # int[]\n"
          + "field grid:[[I          # int[][]"},
        {"基础概念", "方法签名",
            "# 格式：name(参数类型连写)返回类型\n"
          + "# 参数类型紧挨着写，无分隔符；返回类型在右括号后。",
            "# String.substring(int, int) → String\n"
          + "method public substring(II)Ljava/lang/String;\n"
          + "\n"
          + "# int[] Arrays.copyOf(int[], int)\n"
          + "method static copyOf([II)[I"},

        // ================= 伪指令与结构 =================
        {"伪指令与结构", ".class",
            "# 声明当前文件对应的类。文件通常为 类名.smali。",
            ".class public final Lcom/hex91/file/Main;\n"
          + ".class public interface abstract Lcom/x/Callback;"},
        {"伪指令与结构", ".super",
            "# 声明直接父类。必须紧跟 .class。",
            ".class public Lcom/x/MyApp;\n"
          + ".super Landroid/app/Application;"},
        {"伪指令与结构", ".source",
            "# 源文件名调试信息，可省略。",
            ".source \"Main.java\""},
        {"伪指令与结构", ".field",
            "# 声明字段。格式：.field <修饰> 名称:类型 [静态初值]\n"
          + "# 静态初值仅 final static 编译期常量可带（=value）。",
            ".field private mName:Ljava/lang/String;\n"
          + ".field public static final TAG:Ljava/lang/String; = \"HexFile\"\n"
          + ".field volatile count:I"},
        {"伪指令与结构", ".method / .end method",
            "# 方法体包裹。格式：.method <修饰> 名称(参数)返回值\n"
          + "# 内部用 .registers 或 .locals 开头。",
            ".method public getName()Ljava/lang/String;\n"
          + "    .locals 1\n"
          + "    iget-object v0, p0, Lcom/x/User;->name:Ljava/lang/String;\n"
          + "    return-object v0\n"
          + ".end method"},
        {"伪指令与结构", ".registers / .locals",
            "# .registers N —— 寄存器总数（本地+参数）\n"
          + "# .locals  N   —— 仅本地寄存器个数，参数自动追加在高位\n"
          + "# 二选一；长整型/双精度占 2 个寄存器（v3 的 wide 用 v3-v4）。",
            ".method static add(II)I\n"
          + "    .locals 0      # 参数 p0,p1 即够用，无本地\n"
          + "    add-int v0, p0, p1\n"
          + "    return v0\n"
          + ".end method"},
        {"伪指令与结构", ":label（标签）",
            "# 跳转目标，冒号开头。常配合 goto/if/switch 使用。\n"
          + "# 命名习惯：:cond_N 条件分支，:goto_N 跳转，:sswitch_data_N 等。",
            "    if-eqz v0, :cond_0\n"
          + "    const/4 v1, 0x1\n"
          + "    goto :goto_0\n"
          + "    :cond_0\n"
          + "    const/4 v1, 0x0\n"
          + "    :goto_0\n"
          + "    return v1"},
        {"伪指令与结构", ".annotation / .end annotation",
            "# 注解声明，附着于类/字段/方法。",
            "    .annotation runtime Ljava/lang/Override;\n"
          + "    .end annotation\n"
          + "\n"
          + "    .annotation system Landroid/annotation/SuppressLint;\n"
          + "        value = {\"BatteryLife\"}\n"
          + "    .end annotation"},
        {"伪指令与结构", ".param / .parameter",
            "# 参数名调试信息（baksmali 默认会还原）。",
            ".method public setName(Ljava/lang/String;)V\n"
          + "    .param p1, \"name\"    # Ljava/lang/String;\n"
          + "    ..."},
        {"伪指令与结构", ".line",
            "# 行号调试信息，对应 Java 源码行，崩溃栈用它定位。",
            "    .line 42\n"
          + "    invoke-virtual {p0}, Lcom/x/A;->run()V"},
        {"伪指令与结构", ".packed-switch / .sparse-switch",
            "# switch 数据表：packed 连续值，sparse 任意值。\n"
          + "# 结构：表标签(目标列表) 与 数据段标签 成对出现。",
            "    packed-switch p1, :pswitch_data_0\n"
          + "    ...\n"
          + "    :pswitch_0\n"
          + "    ...\n"
          + "    :pswitch_data_0\n"
          + "    .packed-switch 0x0\n"
          + "        :pswitch_0\n"
          + "        :pswitch_1\n"
          + "    .end packed-switch"},
        {"伪指令与结构", ".array-data",
            "# fill-array-data 引用的数组初值表，元素宽度由指令目标类型决定。",
            "    fill-array-data v0, :arr_0\n"
          + "    ...\n"
          + "    :arr_0\n"
          + "    .array-data 4     # 每元素 4 字节(int)\n"
          + "        0x1 0x2 0x3 0x4\n"
          + "    .end array-data"},
        {"伪指令与结构", ".enum",
            "# 枚举字段引用简写，等价于 sget-object + 常量表项。",
            "    sget-object v0, Lcom/x/Color;->RED:Lcom/x/Color;\n"
          + "    # baksmali 有时会显示为：\n"
          + "    .enum Lcom/x/Color;->RED:Lcom/x/Color;"},

        // ================= 数据移动 =================
        {"数据移动", "move vx, vy",
            "# 把 vy（4bit 索引，≤v15）的 32bit 值移动到 vx。\n"
          + "# 仅用于 int/float 等非对象 32bit 值。",
            "    move v0, v1        # v0 = v1"},
        {"数据移动", "move/from16 vx, vy",
            "# 16bit 宽索引版 move：vy 源寄存器可达 64k（v0~v65535），\n"
          + "# 但目的 vx 仍是 8bit（v0~v255）。用于把高编号寄存器的值\n"
          + "# 搬到低编号寄存器。",
            "    move/from16 v0, v100   # v0 = v100（源超 8bit，必须 /from16）"},
        {"数据移动", "move/16 vx, vy",
            "# 目的与源都是 16bit 索引（均 ≤65535）的 move。",
            "    move/16 v300, v301"},
        {"数据移动", "move-wide vx, vy",
            "# 移动 64bit 值（long/double），占用 vx-vx+1 与 vy-vy+1 两个寄存器。",
            "    move-wide v0, v2      # v0~v1 = v2~v3"},
        {"数据移动", "move-wide/from16 vx, vy",
            "# 宽值移动 + 16bit 源索引（源 ≤65535，目的 ≤255）。",
            "    move-wide/from16 v0, v100"},
        {"数据移动", "move-wide/16 vx, vy",
            "# 宽值移动，目的与源都是 16bit 索引。",
            "    move-wide/16 v300, v301"},
        {"数据移动", "move-object vx, vy",
            "# 移动对象引用（4bit 索引）。",
            "    move-object v0, v1   # v0 = v1（对象引用）"},
        {"数据移动", "move-object/from16 vx, vy",
            "# 对象移动 + 16bit 源索引。方法调用传参取回结果等场景常见。",
            "    move-result-object v0\n"
          + "    move-object/from16 v1, v0"},
        {"数据移动", "move-object/16 vx, vy",
            "# 对象移动，目的源均 16bit 索引。",
            "    move-object/16 v300, v301"},
        {"数据移动", "move-result vx",
            "# 取上一条 invoke 指令返回的 32bit 非对象值（int/float）。\n"
          + "# 必须紧跟 invoke-* 之后。",
            "    invoke-virtual {v1}, Ljava/lang/String;->length()I\n"
          + "    move-result v0      # v0 = s.length()"},
        {"数据移动", "move-result-wide vx",
            "# 取上条 invoke 的 64bit 返回值（long/double），占 vx-vx+1。",
            "    invoke-virtual {v1}, Ljava/io/File;->lastModified()J\n"
          + "    move-result-wide v0"},
        {"数据移动", "move-result-object vx",
            "# 取上条 invoke 返回的对象引用。也用于 new-instance 之后。",
            "    new-instance v0, Ljava/lang/StringBuilder;\n"
          + "    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V\n"
          + "    move-result-object v0"},
        {"数据移动", "move-exception vx",
            "# catch 块内取抛出的异常对象，必须是 trap 块首条指令。",
            "    try_start_0:\n"
          + "    ...\n"
          + "    try_end_0:\n"
          + "    catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0\n"
          + "    :catch_0\n"
          + "    move-exception v0   # v0 = 被捕获的异常"},

        // ================= 常量加载 =================
        {"常量加载", "const/4 vx, lit4",
            "# 4bit 有符号立即数（-8~7）装入 vx。",
            "    const/4 v0, 0x1     # v0 = 1"},
        {"常量加载", "const/16 vx, lit16",
            "# 16bit 有符号立即数（-32768~32767）。",
            "    const/16 v0, 0x100  # v0 = 256"},
        {"常量加载", "const vx, lit32",
            "# 32bit 完整立即数。",
            "    const v0, 0x7fffffff"},
        {"常量加载", "const/high16 vx, lit16hi",
            "# 高 16bit 立即数，低 16bit 补 0。用于 >32767 且低 16bit 为 0 的 int，\n"
          + "# 以及 float 位模式（如 0x3f800000=1.0f）。",
            "    const/high16 v0, 0x10000   # v0 = 65536\n"
          + "    const/high16 v1, 0x3f800000 # v1 = 1.0f"},
        {"常量加载", "const-wide/16 vx, lit16",
            "# 16bit 符号扩展为 64bit long，占 vx-vx+1。",
            "    const-wide/16 v0, 0x64  # v0~v1 = 100L"},
        {"常量加载", "const-wide/32 vx, lit32",
            "# 32bit 符号扩展为 64bit long。",
            "    const-wide/32 v0, 0x12345678"},
        {"常量加载", "const-wide vx, lit64",
            "# 完整 64bit 立即数。",
            "    const-wide v0, 0x1122334455667788L"},
        {"常量加载", "const-wide/high16 vx, lit16hi",
            "# 高 16bit 的 64bit 常量（低 48bit 补 0），double 位模式常用。",
            "    const-wide/high16 v0, 0x4059000000000000L  # 100.0"},
        {"常量加载", "const-string vx, \"str\"",
            "# 装入字符串常量引用（索引 16bit，方法内常量表）。",
            "    const-string v0, \"hello\"\n"
          + "    return-object v0"},
        {"常量加载", "const-string/jumbo vx, \"str\"",
            "# 32bit 字符串索引版（应用的字符串 >65536 条时用）。",
            "    const-string/jumbo v0, \"very large pool string\""},
        {"常量加载", "const-class vx, type",
            "# 装入类的 Class 对象引用。",
            "    const-class v0, Lcom/x/User;\n"
          + "    invoke-virtual {v0}, Ljava/lang/Class;->getName()Ljava/lang/String;\n"
          + "    move-result-object v1"},

        // ================= 返回 =================
        {"返回", "return-void",
            "# 方法返回，无返回值。",
            "    return-void"},
        {"返回", "return vx",
            "# 返回 32bit 非对象值（int/float/short/boolean/byte/char）。",
            "    return v0"},
        {"返回", "return-wide vx",
            "# 返回 64bit 值（long/double），vx-vx+1。",
            "    return-wide v0"},
        {"返回", "return-object vx",
            "# 返回对象引用。",
            "    return-object v0"},

        // ================= 算术与逻辑 =================
        {"算术与逻辑", "add-int vx, vy, vz",
            "# 整数加法：vx = vy + vz。",
            "    add-int v0, v1, v2"},
        {"算术与逻辑", "add-int/2addr vx, vy",
            "# 双寄存器版：vx = vx + vy。省指令宽度，最常见。",
            "    add-int/2addr v0, v1"},
        {"算术与逻辑", "add-int/lit16 vx, vy, lit16",
            "# vx = vy + 16bit 有符号立即数。",
            "    add-int/lit16 v0, v1, 0x10"},
        {"算术与逻辑", "add-int/lit8 vx, vy, lit8",
            "# vx = vy + 8bit 立即数。",
            "    add-int/lit8 v0, v1, 0x1"},
        {"算术与逻辑", "sub-int / sub-int/2addr",
            "# 整数减法。同族：sub-int/lit16、sub-int/lit8。",
            "    sub-int v0, v1, v2\n"
          + "    sub-int/2addr v0, v1"},
        {"算术与逻辑", "mul-int / div-int / rem-int",
            "# 乘 / 除 / 取余。div 遇 0 抛 ArithmeticException。\n"
          + "# 同样有 /2addr /lit16 /lit8 变体。",
            "    mul-int v0, v1, v2\n"
          + "    div-int v3, v1, v2\n"
          + "    rem-int v4, v1, v2"},
        {"算术与逻辑", "and-int / or-int / xor-int",
            "# 按位与/或/异或（含 /2addr /lit16 /lit8 变体）。",
            "    and-int/lit8 v0, v1, 0xff   # 取低 8bit\n"
          + "    or-int v2, v0, v1\n"
          + "    xor-int v3, v2, v2          # 自异或清零"},
        {"算术与逻辑", "shl-int / shr-int / ushr-int",
            "# 移位：左移 / 算术右移(带符号) / 逻辑右移(补零)。\n"
          + "# 移位量取立即数或寄存器低 5bit。",
            "    shl-int/lit8 v0, v1, 0x1    # v0 = v1 << 1\n"
          + "    shr-int/lit8 v2, v1, 0x1f   # 取符号位\n"
          + "    ushr-int v3, v1, v4"},
        {"算术与逻辑", "add-long / sub-long / mul-long / div-long / rem-long",
            "# 64bit 整数运算，操作数占两个寄存器。",
            "    add-long v0, v2, v4     # v0~v1 = v2~v3 + v4~v5\n"
          + "    sub-long/2addr v0, v2"},
        {"算术与逻辑", "and-long / or-long / xor-long / shl-long / shr-long / ushr-long",
            "# 64bit 位运算族。",
            "    or-long v0, v2, v4\n"
          + "    shl-long/2addr v0, v2"},
        {"算术与逻辑", "add-float / sub-float / mul-float / div-float / rem-float",
            "# 32bit 浮点运算（IEEE754）。",
            "    add-float v0, v1, v2\n"
          + "    div-float/2addr v0, v1"},
        {"算术与逻辑", "add-double / sub-double / mul-double / div-double / rem-double",
            "# 64bit 双精度运算，操作数占两个寄存器。",
            "    add-double v0, v2, v4\n"
          + "    mul-double/2addr v0, v2"},
        {"算术与逻辑", "neg-int vx, vy",
            "# 取负：vx = -vy。",
            "    neg-int v0, v1"},
        {"算术与逻辑", "not-int vx, vy",
            "# 按位取反：vx = ~vy。",
            "    not-int v0, v1"},
        {"算术与逻辑", "neg-long / neg-float / neg-double",
            "# 64bit 整数 / 32bit / 64bit 浮点取负。",
            "    neg-long v0, v2\n"
          + "    neg-float v0, v1"},
        {"算术与逻辑", "int-to-long vx, vy",
            "# int 符号扩展为 long，结果占 vx-vx+1。",
            "    int-to-long v0, v2"},
        {"算术与逻辑", "long-to-int vx, vy",
            "# long 截断为 int（取低 32bit）。",
            "    long-to-int v0, v2"},

        // ================= 比较 =================
        {"比较", "cmp-long vx, vy, vz",
            "# long 比较：vx = vy>vz?1 : vy<vz?-1 : 0。",
            "    cmp-long v0, v2, v4"},
        {"比较", "cmpl-float vx, vy, vz",
            "# float 比较（NaN 时返回 -1）。vx = vy>vz?1 : vy<vz?-1 : 0。",
            "    cmpl-float v0, v1, v2"},
        {"比较", "cmpg-float vx, vy, vz",
            "# float 比较（NaN 时返回 1）。与 cmpl 只差 NaN 处理方向。",
            "    cmpg-float v0, v1, v2"},
        {"比较", "cmpl-double / cmpg-double",
            "# double 版本，语义同 float 两个方向。",
            "    cmpl-double v0, v2, v4\n"
          + "    cmpg-double v0, v2, v4"},

        // ================= 跳转与分支 =================
        {"跳转与分支", "goto / goto/16 / goto/32",
            "# 无条件跳转到标签。偏移宽度 8/16/32bit。",
            "    goto :goto_0\n"
          + "    goto/16 :goto_far\n"
          + "    goto/32 :goto_far_far"},
        {"跳转与分支", "if-eq vx, vy, :label",
            "# 相等跳转：vx == vy 时跳（对象比较用 if-eq 也可，等价引用比较）。",
            "    if-eq v0, v1, :cond_same"},
        {"跳转与分支", "if-ne vx, vy, :label",
            "# 不等跳转：vx != vy。",
            "    if-ne v0, v1, :cond_diff"},
        {"跳转与分支", "if-lt / if-ge / if-gt / if-le",
            "# 有符号整数比较跳转：\n"
          + "#   if-lt 小于(<)  if-ge 大于等于(≥)\n"
          + "#   if-gt 大于(>)  if-le 小于等于(≤)",
            "    if-lt v0, v1, :cond_less\n"
          + "    if-ge v0, v1, :cond_ge\n"
          + "    if-gt v0, v1, :cond_gt\n"
          + "    if-le v0, v1, :cond_le"},
        {"跳转与分支", "if-eqz vx, :label",
            "# vx == 0 跳转。对象引用与 null 比较、boolean 判断都编译成它。",
            "    if-eqz v0, :cond_is_null\n"
          + "\n"
          + "# 常见模式：判空\n"
          + "    if-eqz p1, :cond_null_check"},
        {"跳转与分支", "if-nez vx, :label",
            "# vx != 0 跳转。等价 Java 的 if (x != null) / if (flag)。",
            "    if-nez v0, :cond_not_null"},
        {"跳转与分支", "if-ltz / if-gez / if-gtz / if-lez",
            "# 与 0 比较：小于零 / 大于等于零 / 大于零 / 小于等于零。",
            "    if-ltz v0, :cond_neg     # v0 < 0\n"
          + "    if-gez v0, :cond_non_neg # v0 >= 0\n"
          + "    if-gtz v0, :cond_pos     # v0 > 0\n"
          + "    if-lez v0, :cond_npos    # v0 <= 0"},
        {"跳转与分支", "packed-switch vx, :label",
            "# 连续值 switch。跳转表按首值偏移定位，数据段用 .packed-switch 定义。",
            "    packed-switch v0, :pswitch_0\n"
          + "    :pswitch_data_0\n"
          + "    .packed-switch 0x1\n"
          + "        :pswitch_1 :pswitch_2 :pswitch_3\n"
          + "    .end packed-switch"},
        {"跳转与分支", "sparse-switch vx, :label",
            "# 稀疏值 switch（key 无需连续）。数据段 key→目标 成对列出。",
            "    sparse-switch v0, :sswitch_0\n"
          + "    :sswitch_data_0\n"
          + "    .sparse-switch\n"
          + "        0x1 -> :sswitch_1\n"
          + "        0x64 -> :sswitch_2\n"
          + "    .end sparse-switch"},

        // ================= 数组 =================
        {"数组", "array-length vx, vy",
            "# vx = vy 指向数组的长度。",
            "    array-length v0, v1"},
        {"数组", "new-array vx, vy, type",
            "# 新建数组：vy=长度，vx=引用。类型是数组描述符。",
            "    const/16 v0, 0xa\n"
          + "    new-array v1, v0, [I   # new int[10]"},
        {"数组", "new-array/jumbo vx, vy, type",
            "# 32bit 类型索引版 new-array。",
            "    new-array/jumbo v1, v0, [Ljava/lang/Object;"},
        {"数组", "filled-new-array {vN..vM}, type",
            "# 用寄存器组初始化并新建数组（元素为寄存器现值）。\n"
          + "# 结果需 move-result-object 取回。",
            "    filled-new-array {v0, v1, v2}, [I\n"
          + "    move-result-object v3"},
        {"数组", "filled-new-array/range {vN..vM}, type",
            "# 寄存器连续区间版 filled-new-array（>4 个元素必须用 range）。",
            "    filled-new-array/range {v0..v9}, [I\n"
          + "    move-result-object v10"},
        {"数组", "fill-array-data vx, :label",
            "# 用 .array-data 表批量填充数组内容。",
            "    fill-array-data v0, :arr\n"
          + "    :arr\n"
          + "    .array-data 1\n"
          + "        0x0 0x1 0x2 0x3\n"
          + "    .end array-data"},
        {"数组", "aget vx, vy, vz",
            "# 读数组元素：vx = vy[vz]。类型族：\n"
          + "#   aget（int/float）aget-wide（long/double）aget-object\n"
          + "#   aget-boolean aget-byte aget-char aget-short",
            "    aget v0, v1, v2          # v0 = arr[v2]\n"
          + "    aget-object v3, v1, v2\n"
          + "    aget-wide v4, v1, v2"},
        {"数组", "aput vx, vy, vz",
            "# 写数组元素：vy[vz] = vx。类型族同 aget。",
            "    aput v0, v1, v2          # arr[v2] = v0\n"
          + "    aput-object v0, v1, v2\n"
          + "    aput-boolean v0, v1, v2"},

        // ================= 字段操作 =================
        {"字段操作", "iget vx, vy, field",
            "# 读实例字段：vx = vy->field。族：iget iget-wide\n"
          + "# iget-object iget-boolean iget-byte iget-char iget-short",
            "    iget v0, p0, Lcom/x/User;->age:I\n"
          + "    iget-object v0, p0, Lcom/x/User;->name:Ljava/lang/String;"},
        {"字段操作", "iput vx, vy, field",
            "# 写实例字段：vy->field = vx。族同 iget（iput-object 最常用）。",
            "    iput v0, p0, Lcom/x/User;->age:I\n"
          + "    iput-object v0, p0, Lcom/x/User;->name:Ljava/lang/String;"},
        {"字段操作", "sget vx, field",
            "# 读静态字段：vx = Class.field。族同 iget 命名（sget-wide 等）。",
            "    sget-object v0, Landroid/os/Build;->MODEL:Ljava/lang/String;\n"
          + "    sget v0, Lcom/x/Cfg;->count:I"},
        {"字段操作", "sput vx, field",
            "# 写静态字段：Class.field = vx。族同 iget 命名。",
            "    sput v0, Lcom/x/Cfg;->count:I\n"
          + "    sput-object v0, Lcom/x/Cfg;->sName:Ljava/lang/String;"},
        {"字段操作", "字段引用格式 Class->field:type",
            "# 完整写法：L包/类;->字段名:类型。与伪指令 .field 声明对应。",
            "Lcom/hex91/file/FileOps;->TAG:Ljava/lang/String;\n"
          + "Lcom/x/A;->mList:Ljava/util/List;"},

        // ================= 方法调用 =================
        {"方法调用", "invoke-virtual {vx..}, method",
            "# 调用虚方法（普通实例方法，运行时按对象分派）。\n"
          + "# 参数寄存器列表：第一个是接收者 this。",
            "    invoke-virtual {p0}, Lcom/x/A;->run()V\n"
          + "    invoke-virtual {v0, v1}, Ljava/lang/String;->length()I\n"
          + "    move-result v1"},
        {"方法调用", "invoke-virtual/range {vN..vM}, method",
            "# 参数寄存器连续区间版（参数 >5 个必须用 range）。",
            "    invoke-virtual/range {v0..v5}, Lcom/x/A;->many(IIIII)V"},
        {"方法调用", "invoke-super {vx..}, method",
            "# 调用父类方法（override 里 super.xxx()）。",
            "    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V"},
        {"方法调用", "invoke-direct {vx..}, method",
            "# 直接调用（不走虚分派）：构造器 <init> 与 private 方法。",
            "    new-instance v0, Ljava/io/File;\n"
          + "    invoke-direct {v0, v1, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V"},
        {"方法调用", "invoke-static {vx..}, method",
            "# 调用静态方法（无 this，参数全是实参）。",
            "    invoke-static {v0}, Lcom/hex91/file/FileOps;->fmtSize(J)Ljava/lang/String;\n"
          + "    move-result-object v1"},
        {"方法调用", "invoke-static/range {vN..vM}, method",
            "# 静态调用连续寄存器区间版。",
            "    invoke-static/range {v0..v3}, Lcom/x/U;->f(IIII)V"},
        {"方法调用", "invoke-interface {vx..}, method",
            "# 调用接口方法（itable 分派）。族：/range。",
            "    invoke-interface {v0}, Ljava/lang/Runnable;->run()V\n"
          + "    invoke-interface/range {v0..v2}, Lcom/x/Cb;->on(II)V"},
        {"方法调用", "构造器 <init> 调用模板",
            "# Java 的 new = new-instance + invoke-direct <init> 两步，\n"
          + "# 返回引用要 move-result-object 重新取一次。",
            "    new-instance v0, Ljava/lang/StringBuilder;\n"
          + "    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V\n"
          + "    move-result-object v0\n"
          + "    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
          + "    move-result-object v0"},

        // ================= 对象与类型 =================
        {"对象与类型", "new-instance vx, type",
            "# 分配对象（不调用构造器）。必须再 invoke-direct <init>。",
            "    new-instance v0, Lcom/x/User;"},
        {"对象与类型", "check-cast vx, type",
            "# 强制类型转换，失败抛 ClassCastException。",
            "    check-cast v0, Ljava/lang/String;\n"
          + "    check-cast v0, [Ljava/lang/Object;"},
        {"对象与类型", "instance-of vx, vy, type",
            "# 类型判断：vx = (vy instanceof type) ? true : false。",
            "    instance-of v0, v1, Ljava/lang/String;\n"
          + "    if-eqz v0, :cond_not_string"},
        {"对象与类型", "monitor-enter vx",
            "# 进入对象监视器（synchronized 块开始）。",
            "    monitor-enter v0\n"
          + "    ...\n"
          + "    monitor-exit v0"},
        {"对象与类型", "monitor-exit vx",
            "# 退出监视器。异常路径也必须释放（配合 catch-all）。",
            "    :try_start_0\n"
          + "    monitor-enter v0\n"
          + "    ...\n"
          + "    monitor-exit v0\n"
          + "    :try_end_0\n"
          + "    catch-all {:try_start_0 .. :try_end_0} :catch_0"},
        {"对象与类型", "throw vx",
            "# 抛出 vx 指向的异常对象。",
            "    new-instance v0, Ljava/lang/IllegalStateException;\n"
          + "    invoke-direct {v0}, Ljava/lang/IllegalStateException;-><init>()V\n"
          + "    throw v0"},

        // ================= 类型转换 =================
        {"类型转换", "int-to-byte / int-to-char / int-to-short",
            "# int 截断为 8/16bit 类型（保留低 bit）。",
            "    int-to-byte v0, v1\n"
          + "    int-to-char v0, v1\n"
          + "    int-to-short v0, v1"},
        {"类型转换", "int-to-float / int-to-double",
            "# int 转浮点（32bit / 64bit）。",
            "    int-to-float v0, v1\n"
          + "    int-to-double v0, v2    # 结果占 v0~v1"},
        {"类型转换", "int-to-long vx, vy",
            "# int 符号扩展到 64bit。",
            "    int-to-long v0, v2"},
        {"类型转换", "long-to-int / long-to-float / long-to-double",
            "# long 转换族。",
            "    long-to-int v0, v2\n"
          + "    long-to-double v0, v2"},
        {"类型转换", "float-to-int / float-to-long / float-to-double",
            "# float 转换族（向零截断，NaN→0）。",
            "    float-to-int v0, v1\n"
          + "    float-to-long v0, v1"},
        {"类型转换", "double-to-int / double-to-long / double-to-float",
            "# double 转换族。",
            "    double-to-int v0, v2\n"
          + "    double-to-long v0, v2\n"
          + "    double-to-float v0, v2"},

        // ================= 其他指令 =================
        {"其他指令", "nop",
            "# 空操作，占位对齐用。",
            "    nop"},
        {"其他指令", "catch / catch-all 指令块",
            "# try-catch 声明：紧跟 try_end 标签之后写 catch 行。\n"
          + "# catch-all 捕获一切（finally 展开用），目标块首条通常 move-exception。",
            "    :try_start_0\n"
          + "    div-int v0, v1, v2\n"
          + "    :try_end_0\n"
          + "    catch Ljava/lang/ArithmeticException; {:try_start_0 .. :try_end_0} :catch_0\n"
          + "    catch-all {:try_start_0 .. :try_end_0} :catch_1\n"
          + "    return-void\n"
          + "    :catch_0\n"
          + "    move-exception v3\n"
          + "    return-void"},
        {"其他指令", "execute-inline",
            "# 内联优化指令（优化器产物，手写 smali 一般不出现）。",
            "    execute-inline {v0}, 0x1"},
        {"其他指令", "常用调试技巧",
            "# 1) 看不懂方法时先找 invoke-* 与 move-result 链——还原调用关系\n"
          + "# 2) const-string 往往暴露关键逻辑（URL、key、log）\n"
          + "# 3) 改 smali 只需动寄存器和跳转标签，别改 .registers 计数\n"
          + "# 4) 64k 问题：单个 dex 方法数上限 65536（multidex 拆分）\n"
          + "# 5) 本 App 的 dex 编辑器（DexEditorActivity）可直接改 smali 回编译",
            "# 修改返回值示例：让校验方法恒返回 true\n"
          + "method public check()Z\n"
          + "    const/4 v0, 0x1   # 原逻辑整体替换\n"
          + "    return v0"}
    };
}
