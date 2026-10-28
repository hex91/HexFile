.class public Lbin/mtclone/Sample;
.super Ljava/lang/Object;
.source "Sample.java"

# 这是一个示例 smali 文件，用于测试行号与语法高亮
.field private count:I

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    const/4 v0, 0x0
    iput v0, p0, Lbin/mtclone/Sample;->count:I
    return-void
.end method

.method public add(II)I
    .registers 5
    .param p1, "a"    # I
    .param p2, "b"    # I
    add-int v0, p1, p2
    const-string v1, "result="
    invoke-static {v1, v0}, Landroid/util/Log;->d(Ljava/lang/String;I)I
    iget v1, p0, Lbin/mtclone/Sample;->count:I
    add-int v1, v1, v0
    iput v1, p0, Lbin/mtclone/Sample;->count:I
    if-ge v0, v1, :cond_ok
    const/16 v2, 0xff
    goto :goto_end
:cond_ok
    const/4 v2, 0x1
:goto_end
    return v0
.end method

.method private loop()V
    .registers 3
    const/4 v0, 0x0
    :goto_loop
    if-lt v0, p0, :cond_exit
    add-int/lit8 v0, v0, 0x1
    goto :goto_loop
    :cond_exit
    return-void
.end method
