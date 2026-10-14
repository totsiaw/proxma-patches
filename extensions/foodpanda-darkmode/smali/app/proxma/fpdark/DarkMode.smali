.class public final Lapp/proxma/fpdark/DarkMode;
.super Ljava/lang/Object;
.source "DarkMode.smali"

# foodpanda dark-mode switch, merged into the app by "Force dark mode" (FoodpandaDarkPaletteDexPatch).
# `on` is read by every rewritten Lm7g getter and by the Lq7g rating-gradient stops. It is loaded once
# per process from SharedPreferences "fp_dark_mode"/"fp_dark_mode" (default TRUE: dark stays the
# shipped behaviour) and changed only by the toggle row on the settings screen, which then restarts
# the app because the Bento theme objects are built once and nothing recomposes on a static flag.

.field public static on:Z

.method static constructor <clinit>()V
    .registers 1
    invoke-static {}, Lapp/proxma/fpdark/DarkMode;->load()Z
    move-result v0
    sput-boolean v0, Lapp/proxma/fpdark/DarkMode;->on:Z
    return-void
.end method

.method private constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

# Platform prefs via the base ContextImpl, bypassing App.getSharedPreferences' async wrapper.
.method private static prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    .registers 3
    invoke-virtual {p0}, Landroid/content/Context;->getApplicationContext()Landroid/content/Context;
    move-result-object v0
    if-nez v0, :have
    move-object v0, p0
    :have
    instance-of v1, v0, Landroid/content/ContextWrapper;
    if-eqz v1, :plain
    move-object v1, v0
    check-cast v1, Landroid/content/ContextWrapper;
    invoke-virtual {v1}, Landroid/content/ContextWrapper;->getBaseContext()Landroid/content/Context;
    move-result-object v1
    if-eqz v1, :plain
    move-object v0, v1
    :plain
    const-string v1, "fp_dark_mode"
    const/4 p0, 0x0
    invoke-virtual {v0, v1, p0}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
    move-result-object v0
    return-object v0
.end method

# Any failure (no Application yet, reflection blocked, I/O) keeps the default: dark ON.
.method private static load()Z
    .registers 5
    const/4 v4, 0x1
    :try_start
    const-string v0, "android.app.ActivityThread"
    invoke-static {v0}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
    move-result-object v0
    const-string v1, "currentApplication"
    const/4 v2, 0x0
    new-array v3, v2, [Ljava/lang/Class;
    invoke-virtual {v0, v1, v3}, Ljava/lang/Class;->getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
    move-result-object v0
    new-array v3, v2, [Ljava/lang/Object;
    const/4 v1, 0x0
    invoke-virtual {v0, v1, v3}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v0
    if-eqz v0, :done
    check-cast v0, Landroid/content/Context;
    invoke-static {v0}, Lapp/proxma/fpdark/DarkMode;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object v0
    const-string v1, "fp_dark_mode"
    invoke-interface {v0, v1, v4}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
    move-result v4
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :done
    :done
    return v4
.end method

.method public static set(Landroid/content/Context;Z)V
    .registers 4
    sput-boolean p1, Lapp/proxma/fpdark/DarkMode;->on:Z
    :try_start
    invoke-static {p0}, Lapp/proxma/fpdark/DarkMode;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object v0
    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v0
    const-string v1, "fp_dark_mode"
    invoke-interface {v0, v1, p1}, Landroid/content/SharedPreferences$Editor;->putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences$Editor;
    move-result-object v0
    invoke-interface {v0}, Landroid/content/SharedPreferences$Editor;->commit()Z
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :done
    :done
    return-void
.end method

# Relaunch the launcher activity in a fresh task and end this process so the theme is rebuilt.
.method public static restart(Landroid/content/Context;)V
    .registers 3
    :try_start
    invoke-virtual {p0}, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;
    move-result-object v0
    invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Landroid/content/pm/PackageManager;->getLaunchIntentForPackage(Ljava/lang/String;)Landroid/content/Intent;
    move-result-object v0
    if-eqz v0, :done
    invoke-virtual {v0}, Landroid/content/Intent;->getComponent()Landroid/content/ComponentName;
    move-result-object v0
    invoke-static {v0}, Landroid/content/Intent;->makeRestartActivityTask(Landroid/content/ComponentName;)Landroid/content/Intent;
    move-result-object v0
    invoke-virtual {p0, v0}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
    invoke-static {}, Ljava/lang/Runtime;->getRuntime()Ljava/lang/Runtime;
    move-result-object v0
    const/4 v1, 0x0
    invoke-virtual {v0, v1}, Ljava/lang/Runtime;->exit(I)V
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :done
    :done
    return-void
.end method

# Lq7g surfaceRatingGradient stops: dark value when on, the shipped light stop otherwise.
.method public static ratingStart(J)J
    .registers 3
    sget-boolean v0, Lapp/proxma/fpdark/DarkMode;->on:Z
    if-eqz v0, :light
    const-wide p0, -0xd4e7e000000000L    # ff2b1820 << 32
    :light
    return-wide p0
.end method

.method public static ratingEnd(J)J
    .registers 3
    sget-boolean v0, Lapp/proxma/fpdark/DarkMode;->on:Z
    if-eqz v0, :light
    const-wide p0, -0xaddac600000000L    # ff52253a << 32
    :light
    return-wide p0
.end method

# Settings screen: append a "Dark mode" checkbox card styled from the first (Language) card, at index 1.
# Never throws — a layout change just means no row.
.method public static attach(Landroid/view/View;)V
    .registers 16
    :try_start
    instance-of v0, p0, Landroid/view/ViewGroup;
    if-eqz v0, :done
    move-object v0, p0
    check-cast v0, Landroid/view/ViewGroup;
    invoke-virtual {v0}, Landroid/view/ViewGroup;->getChildCount()I
    move-result v1
    if-eqz v1, :done
    const/4 v1, 0x0
    invoke-virtual {v0, v1}, Landroid/view/ViewGroup;->getChildAt(I)Landroid/view/View;
    move-result-object v0
    instance-of v2, v0, Landroid/widget/LinearLayout;
    if-eqz v2, :done
    check-cast v0, Landroid/widget/LinearLayout;
    invoke-virtual {v0}, Landroid/view/ViewGroup;->getChildCount()I
    move-result v2
    if-eqz v2, :done
    invoke-virtual {v0, v1}, Landroid/view/ViewGroup;->getChildAt(I)Landroid/view/View;
    move-result-object v2
    instance-of v3, v2, Landroidx/cardview/widget/CardView;
    if-eqz v3, :done
    check-cast v2, Landroidx/cardview/widget/CardView;
    invoke-virtual {p0}, Landroid/view/View;->getContext()Landroid/content/Context;
    move-result-object v3

    new-instance v4, Landroidx/cardview/widget/CardView;
    invoke-direct {v4, v3}, Landroidx/cardview/widget/CardView;-><init>(Landroid/content/Context;)V
    invoke-virtual {v2}, Landroidx/cardview/widget/CardView;->getRadius()F
    move-result v5
    invoke-virtual {v4, v5}, Landroidx/cardview/widget/CardView;->setRadius(F)V
    invoke-virtual {v2}, Landroidx/cardview/widget/CardView;->getCardElevation()F
    move-result v5
    invoke-virtual {v4, v5}, Landroidx/cardview/widget/CardView;->setCardElevation(F)V
    invoke-virtual {v2}, Landroidx/cardview/widget/CardView;->getMaxCardElevation()F
    move-result v5
    invoke-virtual {v4, v5}, Landroidx/cardview/widget/CardView;->setMaxCardElevation(F)V
    invoke-virtual {v2}, Landroidx/cardview/widget/CardView;->getUseCompatPadding()Z
    move-result v5
    invoke-virtual {v4, v5}, Landroidx/cardview/widget/CardView;->setUseCompatPadding(Z)V
    invoke-virtual {v2}, Landroidx/cardview/widget/CardView;->getPreventCornerOverlap()Z
    move-result v5
    invoke-virtual {v4, v5}, Landroidx/cardview/widget/CardView;->setPreventCornerOverlap(Z)V
    invoke-virtual {v2}, Landroidx/cardview/widget/CardView;->getCardBackgroundColor()Landroid/content/res/ColorStateList;
    move-result-object v5
    invoke-virtual {v4, v5}, Landroidx/cardview/widget/CardView;->setCardBackgroundColor(Landroid/content/res/ColorStateList;)V
    invoke-virtual {v2}, Landroid/view/View;->getLayoutParams()Landroid/view/ViewGroup$LayoutParams;
    move-result-object v5
    instance-of v6, v5, Landroid/widget/LinearLayout$LayoutParams;
    if-eqz v6, :nolp
    check-cast v5, Landroid/widget/LinearLayout$LayoutParams;
    new-instance v6, Landroid/widget/LinearLayout$LayoutParams;
    invoke-direct {v6, v5}, Landroid/widget/LinearLayout$LayoutParams;-><init>(Landroid/widget/LinearLayout$LayoutParams;)V
    invoke-virtual {v4, v6}, Landroid/view/View;->setLayoutParams(Landroid/view/ViewGroup$LayoutParams;)V
    :nolp

    new-instance v5, Landroid/widget/LinearLayout;
    invoke-direct {v5, v3}, Landroid/widget/LinearLayout;-><init>(Landroid/content/Context;)V
    const/4 v6, 0x1
    invoke-virtual {v5, v6}, Landroid/widget/LinearLayout;->setOrientation(I)V
    invoke-virtual {v2}, Landroid/view/ViewGroup;->getChildCount()I
    move-result v6
    if-eqz v6, :nopad
    invoke-virtual {v2, v1}, Landroid/view/ViewGroup;->getChildAt(I)Landroid/view/View;
    move-result-object v6
    invoke-virtual {v6}, Landroid/view/View;->getPaddingLeft()I
    move-result v7
    invoke-virtual {v6}, Landroid/view/View;->getPaddingTop()I
    move-result v8
    invoke-virtual {v6}, Landroid/view/View;->getPaddingRight()I
    move-result v9
    invoke-virtual {v6}, Landroid/view/View;->getPaddingBottom()I
    move-result v10
    invoke-virtual {v5, v7, v8, v9, v10}, Landroid/view/View;->setPadding(IIII)V
    :nopad

    new-instance v6, Lcom/deliveryhero/pretty/core/CoreCheckBox;
    const/4 v7, 0x0
    invoke-direct {v6, v3, v7}, Lcom/deliveryhero/pretty/core/CoreCheckBox;-><init>(Landroid/content/Context;Landroid/util/AttributeSet;)V
    const-string v7, "Dark mode"
    invoke-virtual {v6, v7}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
    sget-boolean v7, Lapp/proxma/fpdark/DarkMode;->on:Z
    invoke-virtual {v6, v7}, Landroid/widget/CompoundButton;->setChecked(Z)V
    new-instance v7, Lapp/proxma/fpdark/DarkMode$Toggle;
    invoke-direct {v7}, Lapp/proxma/fpdark/DarkMode$Toggle;-><init>()V
    invoke-virtual {v6, v7}, Landroid/widget/CompoundButton;->setOnCheckedChangeListener(Landroid/widget/CompoundButton$OnCheckedChangeListener;)V
    new-instance v7, Landroid/widget/LinearLayout$LayoutParams;
    const/4 v8, -0x1
    const/4 v9, -0x2
    invoke-direct {v7, v8, v9}, Landroid/widget/LinearLayout$LayoutParams;-><init>(II)V
    invoke-virtual {v5, v6, v7}, Landroid/view/ViewGroup;->addView(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V
    invoke-virtual {v4, v5}, Landroid/view/ViewGroup;->addView(Landroid/view/View;)V
    const/4 v6, 0x1
    invoke-virtual {v0, v4, v6}, Landroid/view/ViewGroup;->addView(Landroid/view/View;I)V
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :done
    :done
    return-void
.end method
