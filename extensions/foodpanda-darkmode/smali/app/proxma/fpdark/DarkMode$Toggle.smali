.class public final Lapp/proxma/fpdark/DarkMode$Toggle;
.super Ljava/lang/Object;
.source "DarkMode.smali"
.implements Landroid/widget/CompoundButton$OnCheckedChangeListener;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

# Persist, lock the row against double taps, then restart shortly after so the tick animation shows.
.method public onCheckedChanged(Landroid/widget/CompoundButton;Z)V
    .registers 7
    invoke-virtual {p1}, Landroid/view/View;->getContext()Landroid/content/Context;
    move-result-object v0
    invoke-static {v0, p2}, Lapp/proxma/fpdark/DarkMode;->set(Landroid/content/Context;Z)V
    const/4 v1, 0x0
    invoke-virtual {p1, v1}, Landroid/view/View;->setEnabled(Z)V
    new-instance v1, Lapp/proxma/fpdark/DarkMode$Restart;
    invoke-direct {v1, v0}, Lapp/proxma/fpdark/DarkMode$Restart;-><init>(Landroid/content/Context;)V
    const-wide/16 v2, 0x15e
    invoke-virtual {p1, v1, v2, v3}, Landroid/view/View;->postDelayed(Ljava/lang/Runnable;J)Z
    return-void
.end method
