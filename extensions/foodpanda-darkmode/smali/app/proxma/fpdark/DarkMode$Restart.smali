.class public final Lapp/proxma/fpdark/DarkMode$Restart;
.super Ljava/lang/Object;
.source "DarkMode.smali"
.implements Ljava/lang/Runnable;

.field private final ctx:Landroid/content/Context;

.method public constructor <init>(Landroid/content/Context;)V
    .registers 2
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lapp/proxma/fpdark/DarkMode$Restart;->ctx:Landroid/content/Context;
    return-void
.end method

.method public run()V
    .registers 2
    iget-object v0, p0, Lapp/proxma/fpdark/DarkMode$Restart;->ctx:Landroid/content/Context;
    invoke-static {v0}, Lapp/proxma/fpdark/DarkMode;->restart(Landroid/content/Context;)V
    return-void
.end method
