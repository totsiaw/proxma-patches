.class public final Lmorphe/Notify;
.super Ljava/lang/Object;


.field private static appCtx:Landroid/content/Context;

.field private static armed:Z
.field public static lastLog:Ljava/lang/String;
.field public static sSession:Ljava/lang/String;
.field public static sCheckin:Ljava/lang/String;


.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method


.method public static init(Landroid/content/Context;)V
    .registers 2

    if-eqz p0, :done

    invoke-virtual {p0}, Landroid/content/Context;->getApplicationContext()Landroid/content/Context;

    move-result-object v0

    if-eqz v0, :use

    move-object v0, p0

    :use
    sput-object v0, Lmorphe/Notify;->appCtx:Landroid/content/Context;

    :done
    return-void
.end method


.method public static claimed()V
    .registers 12

    const-string v0, "SUCCESS_CALLBACK"

    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V

    invoke-static {}, Lmorphe/Notify;->markClaimed()V

    sget-object v0, Lmorphe/Notify;->appCtx:Landroid/content/Context;

    if-eqz v0, :ret

    const v1, 0x7f120252

    invoke-virtual {v0, v1}, Landroid/content/Context;->getString(I)Ljava/lang/String;

    move-result-object v1

    const/4 v2, 0x1

    invoke-static {v0, v1, v2}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;

    move-result-object v2

    invoke-virtual {v2}, Landroid/widget/Toast;->show()V

    sget v6, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v7, 0x21

    if-lt v6, v7, :perm_ok

    const-string v8, "android.permission.POST_NOTIFICATIONS"

    invoke-virtual {v0, v8}, Landroid/content/Context;->checkSelfPermission(Ljava/lang/String;)I

    move-result v8

    if-nez v8, :ret

    :perm_ok
    :try_start
    invoke-virtual {v0}, Landroid/content/Context;->getApplicationInfo()Landroid/content/pm/ApplicationInfo;

    move-result-object v2

    iget v3, v2, Landroid/content/pm/ApplicationInfo;->icon:I

    if-eqz v3, :ret

    invoke-virtual {v0}, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;

    move-result-object v4

    invoke-virtual {v2, v4}, Landroid/content/pm/PackageItemInfo;->loadLabel(Landroid/content/pm/PackageManager;)Ljava/lang/CharSequence;

    move-result-object v5

    const-string v4, "notification"

    invoke-virtual {v0, v4}, Landroid/content/Context;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;

    move-result-object v4

    check-cast v4, Landroid/app/NotificationManager;

    if-eqz v4, :ret

    sget v6, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v7, 0x1a

    if-lt v6, v7, :legacy

    const-string v10, "morphe_claim"

    new-instance v6, Landroid/app/NotificationChannel;

    const/4 v7, 0x3

    invoke-direct {v6, v10, v5, v7}, Landroid/app/NotificationChannel;-><init>(Ljava/lang/String;Ljava/lang/CharSequence;I)V

    invoke-virtual {v4, v6}, Landroid/app/NotificationManager;->createNotificationChannel(Landroid/app/NotificationChannel;)V

    new-instance v6, Landroid/app/Notification$Builder;

    invoke-direct {v6, v0, v10}, Landroid/app/Notification$Builder;-><init>(Landroid/content/Context;Ljava/lang/String;)V

    goto :build

    :legacy
    new-instance v6, Landroid/app/Notification$Builder;

    invoke-direct {v6, v0}, Landroid/app/Notification$Builder;-><init>(Landroid/content/Context;)V

    :build
    invoke-virtual {v6, v3}, Landroid/app/Notification$Builder;->setSmallIcon(I)Landroid/app/Notification$Builder;

    move-result-object v6

    invoke-virtual {v6, v5}, Landroid/app/Notification$Builder;->setContentTitle(Ljava/lang/CharSequence;)Landroid/app/Notification$Builder;

    move-result-object v6

    invoke-virtual {v6, v1}, Landroid/app/Notification$Builder;->setContentText(Ljava/lang/CharSequence;)Landroid/app/Notification$Builder;

    move-result-object v6

    const/4 v7, 0x1

    invoke-virtual {v6, v7}, Landroid/app/Notification$Builder;->setAutoCancel(Z)Landroid/app/Notification$Builder;

    move-result-object v6

    invoke-virtual {v6}, Landroid/app/Notification$Builder;->build()Landroid/app/Notification;

    move-result-object v6

    const/16 v7, 0x3e9

    invoke-virtual {v4, v7, v6}, Landroid/app/NotificationManager;->notify(ILandroid/app/Notification;)V

    :try_end
    goto :ret

    :catch
    move-exception v0

    :ret
    return-void
.catch Ljava/lang/Throwable; {:try_start .. :try_end} :catch
.end method



.method public static failed(Ljava/lang/String;Ljava/lang/String;)V
    .registers 5

    sget-object v0, Lmorphe/Notify;->appCtx:Landroid/content/Context;

    if-eqz v0, :ret

    invoke-static {}, Lmorphe/Notify;->clearAttempted()V

    move-object v1, p1

    if-eqz v1, :have

    move-object v1, p0

    :have
    if-eqz v1, :ret

    const-string v2, "Claim failed: "

    invoke-static {v2}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;

    move-result-object v2

    invoke-static {v2, v1}, Landroid/text/TextUtils;->concat(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/CharSequence;

    move-result-object v1

    const/4 v2, 0x1

    invoke-static {v0, v1, v2}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;

    move-result-object v0

    invoke-virtual {v0}, Landroid/widget/Toast;->show()V

    :ret
    return-void
.end method


.method public static log(Ljava/lang/String;)V
    .registers 2

    const-string v0, "PROXMA_CLAIM"

    sput-object p0, Lmorphe/Notify;->lastLog:Ljava/lang/String;
    invoke-static {v0, p0}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    return-void
.end method


.method public static dayKey()I
    .registers 4

    invoke-static {}, Ljava/util/Calendar;->getInstance()Ljava/util/Calendar;

    move-result-object v0

    const/4 v1, 0x1

    invoke-virtual {v0, v1}, Ljava/util/Calendar;->get(I)I

    move-result v1

    const/4 v2, 0x6

    invoke-virtual {v0, v2}, Ljava/util/Calendar;->get(I)I

    move-result v2

    mul-int/lit16 v1, v1, 0x3e8

    add-int/2addr v1, v2

    return v1
.end method


.method private static prefs()Landroid/content/SharedPreferences;
    .registers 3

    sget-object v0, Lmorphe/Notify;->appCtx:Landroid/content/Context;

    if-eqz v0, :null

    const-string v1, "proxma_claim"

    const/4 v2, 0x0

    invoke-virtual {v0, v1, v2}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;

    move-result-object v0

    return-object v0

    :null
    const/4 v0, 0x0

    return-object v0
.end method


.method public static isClaimedToday()Z
    .registers 4

    invoke-static {}, Lmorphe/Notify;->prefs()Landroid/content/SharedPreferences;

    move-result-object v0

    if-eqz v0, :no

    const-string v1, "claimed_day"

    const/4 v2, 0x0

    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I

    move-result v0

    invoke-static {}, Lmorphe/Notify;->dayKey()I

    move-result v1

    if-ne v0, v1, :no

    const/4 v0, 0x1

    return v0

    :no
    const/4 v0, 0x0

    return v0
.end method


.method public static markClaimed()V
    .registers 4

    invoke-static {}, Lmorphe/Notify;->prefs()Landroid/content/SharedPreferences;

    move-result-object v0

    if-eqz v0, :done

    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object v1

    const-string v2, "claimed_day"

    invoke-static {}, Lmorphe/Notify;->dayKey()I

    move-result v3

    invoke-interface {v1, v2, v3}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;

    move-result-object v1

    invoke-interface {v1}, Landroid/content/SharedPreferences$Editor;->apply()V

    :done
    return-void
.end method


.method public static isAttemptedToday()Z
    .registers 4

    invoke-static {}, Lmorphe/Notify;->prefs()Landroid/content/SharedPreferences;

    move-result-object v0

    if-eqz v0, :no

    const-string v1, "attempted_day"

    const/4 v2, 0x0

    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I

    move-result v0

    invoke-static {}, Lmorphe/Notify;->dayKey()I

    move-result v1

    if-ne v0, v1, :no

    const/4 v0, 0x1

    return v0

    :no
    const/4 v0, 0x0

    return v0
.end method


.method public static markAttempted()V
    .registers 4

    invoke-static {}, Lmorphe/Notify;->prefs()Landroid/content/SharedPreferences;

    move-result-object v0

    if-eqz v0, :done

    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object v1

    const-string v2, "attempted_day"

    invoke-static {}, Lmorphe/Notify;->dayKey()I

    move-result v3

    invoke-interface {v1, v2, v3}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;

    move-result-object v1

    invoke-interface {v1}, Landroid/content/SharedPreferences$Editor;->apply()V

    :done
    return-void
.end method


.method public static clearAttempted()V
    .registers 4

    invoke-static {}, Lmorphe/Notify;->prefs()Landroid/content/SharedPreferences;

    move-result-object v0

    if-eqz v0, :done

    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object v1

    const-string v2, "attempted_day"

    const/4 v3, 0x0

    invoke-interface {v1, v2, v3}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;

    move-result-object v1

    invoke-interface {v1}, Landroid/content/SharedPreferences$Editor;->apply()V

    :done
    return-void
.end method


.method public static notifyClaimed(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    .registers 16

    sget-object v0, Lmorphe/Notify;->appCtx:Landroid/content/Context;
    if-eqz v0, :ret

    new-instance v1, Ljava/lang/StringBuilder;
    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
    const-string v2, "Day "
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    move-result-object v1

    if-nez p0, :day_ok
    const-string p0, "?"
    :day_ok
    invoke-virtual {v1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    move-result-object v1

    const-string v2, " claimed: "
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    move-result-object v1

    if-nez p1, :val_ok
    const-string p1, "reward"
    :val_ok
    invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    move-result-object v1

    const-string v2, " "
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    move-result-object v1

    if-nez p2, :unit_ok
    const-string p2, ""
    :unit_ok
    invoke-virtual {v1, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    move-result-object v1

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v3

    const-string v1, "NOTIFY_CLAIMED "
    invoke-virtual {v1, v3}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    invoke-static {v1}, Lmorphe/Notify;->log(Ljava/lang/String;)V

    sget v4, Landroid/os/Build$VERSION;->SDK_INT:I
    const/16 v5, 0x21
    if-lt v4, v5, :perm_ok

    const-string v4, "android.permission.POST_NOTIFICATIONS"
    invoke-virtual {v0, v4}, Landroid/content/Context;->checkSelfPermission(Ljava/lang/String;)I
    move-result v4
    if-nez v4, :perm_ok
    return-void

    :perm_ok
    :try_start
    invoke-virtual {v0}, Landroid/content/Context;->getApplicationInfo()Landroid/content/pm/ApplicationInfo;
    move-result-object v4

    iget v5, v4, Landroid/content/pm/ApplicationInfo;->icon:I
    if-eqz v5, :ret2

    invoke-virtual {v0}, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;
    move-result-object v6

    invoke-virtual {v4, v6}, Landroid/content/pm/PackageItemInfo;->loadLabel(Landroid/content/pm/PackageManager;)Ljava/lang/CharSequence;
    move-result-object v7

    const-string v8, "notification"
    invoke-virtual {v0, v8}, Landroid/content/Context;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;
    move-result-object v8
    check-cast v8, Landroid/app/NotificationManager;
    if-eqz v8, :ret2

    const-string v9, "morphe_claim"
    sget v10, Landroid/os/Build$VERSION;->SDK_INT:I
    const/16 v11, 0x1a
    if-lt v10, v11, :legacy

    new-instance v10, Landroid/app/NotificationChannel;
    const/4 v11, 0x3
    invoke-direct {v10, v9, v7, v11}, Landroid/app/NotificationChannel;-><init>(Ljava/lang/String;Ljava/lang/CharSequence;I)V
    invoke-virtual {v8, v10}, Landroid/app/NotificationManager;->createNotificationChannel(Landroid/app/NotificationChannel;)V

    new-instance v10, Landroid/app/Notification$Builder;
    invoke-direct {v10, v0, v9}, Landroid/app/Notification$Builder;-><init>(Landroid/content/Context;Ljava/lang/String;)V
    goto :build

    :legacy
    new-instance v10, Landroid/app/Notification$Builder;
    invoke-direct {v10, v0}, Landroid/app/Notification$Builder;-><init>(Landroid/content/Context;)V

    :build
    invoke-virtual {v10, v5}, Landroid/app/Notification$Builder;->setSmallIcon(I)Landroid/app/Notification$Builder;
    move-result-object v10
    invoke-virtual {v10, v7}, Landroid/app/Notification$Builder;->setContentTitle(Ljava/lang/CharSequence;)Landroid/app/Notification$Builder;
    move-result-object v10
    invoke-virtual {v10, v3}, Landroid/app/Notification$Builder;->setContentText(Ljava/lang/CharSequence;)Landroid/app/Notification$Builder;
    move-result-object v10
    const/4 v11, 0x1
    invoke-virtual {v10, v11}, Landroid/app/Notification$Builder;->setAutoCancel(Z)Landroid/app/Notification$Builder;
    move-result-object v10
    invoke-virtual {v10}, Landroid/app/Notification$Builder;->build()Landroid/app/Notification;
    move-result-object v10

    const/16 v11, 0x3ea
    invoke-virtual {v8, v11, v10}, Landroid/app/NotificationManager;->notify(ILandroid/app/Notification;)V
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :catch

    :ret2
    return-void

    :catch
    move-exception v0

    :ret
    return-void
.end method


.method public static schedule(Landroid/content/Context;)V
    .registers 8

    :try_start
    invoke-static {p0}, Landroidx/work/WorkManager;->getInstance(Landroid/content/Context;)Landroidx/work/WorkManager;
    move-result-object v0

    new-instance v1, Landroidx/work/OneTimeWorkRequest$Builder;
    const-class v2, Lcom/jazz/jazzworld/presentation/ClaimWorker;
    invoke-direct {v1, v2}, Landroidx/work/OneTimeWorkRequest$Builder;-><init>(Ljava/lang/Class;)V

    new-instance v2, Landroidx/work/Constraints$Builder;
    invoke-direct {v2}, Landroidx/work/Constraints$Builder;-><init>()V
    sget-object v3, Landroidx/work/NetworkType;->CONNECTED:Landroidx/work/NetworkType;
    invoke-virtual {v2, v3}, Landroidx/work/Constraints$Builder;->setRequiredNetworkType(Landroidx/work/NetworkType;)Landroidx/work/Constraints$Builder;
    move-result-object v2
    invoke-virtual {v2}, Landroidx/work/Constraints$Builder;->build()Landroidx/work/Constraints;
    move-result-object v2

    invoke-virtual {v1, v2}, Landroidx/work/WorkRequest$Builder;->setConstraints(Landroidx/work/Constraints;)Landroidx/work/WorkRequest$Builder;
    move-result-object v1

    const-wide/16 v2, 0xf
    sget-object v4, Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;
    invoke-virtual {v1, v2, v3, v4}, Landroidx/work/WorkRequest$Builder;->setInitialDelay(JLjava/util/concurrent/TimeUnit;)Landroidx/work/WorkRequest$Builder;
    move-result-object v1

    invoke-virtual {v1}, Landroidx/work/WorkRequest$Builder;->build()Landroidx/work/WorkRequest;
    move-result-object v1
    check-cast v1, Landroidx/work/OneTimeWorkRequest;

    const-string v2, "proxma_autoclaim"
    sget-object v3, Landroidx/work/ExistingWorkPolicy;->KEEP:Landroidx/work/ExistingWorkPolicy;
    invoke-virtual {v0, v2, v3, v1}, Landroidx/work/WorkManager;->enqueueUniqueWork(Ljava/lang/String;Landroidx/work/ExistingWorkPolicy;Landroidx/work/OneTimeWorkRequest;)Landroidx/work/Operation;

    const-string v0, "SCHEDULED unique=proxma_autoclaim first=15s repeat=10m until claimed"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :catch

    return-void

    :catch
    move-exception v0
    invoke-virtual {v0}, Ljava/lang/Throwable;->toString()Ljava/lang/String;
    move-result-object v1
    const-string v0, "SCHEDULE_FAILED "
    invoke-virtual {v0, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    return-void
.end method


.method public static armIn(Landroid/content/Context;J)V
    .registers 8

    :try_start
    invoke-static {p0}, Landroidx/work/WorkManager;->getInstance(Landroid/content/Context;)Landroidx/work/WorkManager;
    move-result-object v0

    new-instance v1, Landroidx/work/OneTimeWorkRequest$Builder;
    const-class v2, Lcom/jazz/jazzworld/presentation/ClaimWorker;
    invoke-direct {v1, v2}, Landroidx/work/OneTimeWorkRequest$Builder;-><init>(Ljava/lang/Class;)V

    new-instance v2, Landroidx/work/Constraints$Builder;
    invoke-direct {v2}, Landroidx/work/Constraints$Builder;-><init>()V
    sget-object v3, Landroidx/work/NetworkType;->CONNECTED:Landroidx/work/NetworkType;
    invoke-virtual {v2, v3}, Landroidx/work/Constraints$Builder;->setRequiredNetworkType(Landroidx/work/NetworkType;)Landroidx/work/Constraints$Builder;
    move-result-object v2
    invoke-virtual {v2}, Landroidx/work/Constraints$Builder;->build()Landroidx/work/Constraints;
    move-result-object v2

    invoke-virtual {v1, v2}, Landroidx/work/WorkRequest$Builder;->setConstraints(Landroidx/work/Constraints;)Landroidx/work/WorkRequest$Builder;
    move-result-object v1

    sget-object v3, Ljava/util/concurrent/TimeUnit;->MINUTES:Ljava/util/concurrent/TimeUnit;
    invoke-virtual {v1, p1, p2, v3}, Landroidx/work/WorkRequest$Builder;->setInitialDelay(JLjava/util/concurrent/TimeUnit;)Landroidx/work/WorkRequest$Builder;
    move-result-object v1

    invoke-virtual {v1}, Landroidx/work/WorkRequest$Builder;->build()Landroidx/work/WorkRequest;
    move-result-object v1
    check-cast v1, Landroidx/work/OneTimeWorkRequest;

    const-string v2, "proxma_autoclaim"
    sget-object v3, Landroidx/work/ExistingWorkPolicy;->REPLACE:Landroidx/work/ExistingWorkPolicy;
    invoke-virtual {v0, v2, v3, v1}, Landroidx/work/WorkManager;->enqueueUniqueWork(Ljava/lang/String;Landroidx/work/ExistingWorkPolicy;Landroidx/work/OneTimeWorkRequest;)Landroidx/work/Operation;

    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    const-string v2, "CHAIN_NEXT in_min="
    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    move-result-object v0
    invoke-virtual {v0, p1, p2}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
    move-result-object v0
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :catch

    return-void

    :catch
    move-exception v0
    invoke-virtual {v0}, Ljava/lang/Throwable;->toString()Ljava/lang/String;
    move-result-object v1
    const-string v0, "ARM_FAILED "
    invoke-virtual {v0, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    return-void
.end method

.method public static armTomorrow(Landroid/content/Context;)V
    .registers 10

    invoke-static {}, Ljava/util/Calendar;->getInstance()Ljava/util/Calendar;
    move-result-object v0

    const/16 v1, 0xb
    invoke-virtual {v0, v1}, Ljava/util/Calendar;->get(I)I
    move-result v2

    const/16 v1, 0xc
    invoke-virtual {v0, v1}, Ljava/util/Calendar;->get(I)I
    move-result v3

    mul-int/lit8 v2, v2, 0x3c
    add-int/2addr v2, v3
    const/16 v3, 0x5a0
    sub-int v2, v3, v2
    add-int/lit8 v2, v2, 0x23

    int-to-long v4, v2
    invoke-static {p0, v4, v5}, Lmorphe/Notify;->armIn(Landroid/content/Context;J)V

    const-string v0, "CHAIN_TOMORROW armed next 0035"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    return-void
.end method

.method public static bumpAttempt()I
    .registers 6

    invoke-static {}, Lmorphe/Notify;->dayKey()I
    move-result v0

    invoke-static {}, Lmorphe/Notify;->prefs()Landroid/content/SharedPreferences;
    move-result-object v1

    if-eqz v1, :after_day

    const-string v2, "att_day"
    const/4 v3, 0x0
    invoke-interface {v1, v2, v3}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I
    move-result v2

    if-eq v2, v0, :after_day

    invoke-interface {v1}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v2
    const-string v3, "att_day"
    invoke-interface {v2, v3, v0}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;
    const-string v3, "att_n"
    const/4 v4, 0x0
    invoke-interface {v2, v3, v4}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;
    invoke-interface {v2}, Landroid/content/SharedPreferences$Editor;->apply()V

    :after_day
    if-eqz v1, :none

    const-string v2, "att_n"
    const/4 v3, 0x0
    invoke-interface {v1, v2, v3}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I
    move-result v2

    add-int/lit8 v2, v2, 0x1

    invoke-interface {v1}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v3
    const-string v4, "att_n"
    invoke-interface {v3, v4, v2}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;
    invoke-interface {v3}, Landroid/content/SharedPreferences$Editor;->apply()V

    return v2

    :none
    const/4 v2, 0x0
    return v2
.end method

.method public static tooEarlyForLatch()Z
    .registers 4

    invoke-static {}, Ljava/util/Calendar;->getInstance()Ljava/util/Calendar;
    move-result-object v0

    const/16 v1, 0xb
    invoke-virtual {v0, v1}, Ljava/util/Calendar;->get(I)I
    move-result v1

    mul-int/lit8 v1, v1, 0x3c

    const/16 v2, 0xc
    invoke-virtual {v0, v2}, Ljava/util/Calendar;->get(I)I
    move-result v2

    add-int/2addr v1, v2

    const/16 v2, 0x23
    if-ge v1, v2, :past_grace

    const/4 v1, 0x1
    return v1

    :past_grace
    const/4 v1, 0x0
    return v1
.end method


.method public static notifyStatus(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V
    .registers 14

    if-eqz p0, :ns_ret

    :ns_try_start
    invoke-virtual {p0}, Landroid/content/Context;->getApplicationInfo()Landroid/content/pm/ApplicationInfo;
    move-result-object v1
    iget v2, v1, Landroid/content/pm/ApplicationInfo;->icon:I
    if-eqz v2, :ns_ret

    const-string v3, "notification"
    invoke-virtual {p0, v3}, Landroid/content/Context;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;
    move-result-object v3
    check-cast v3, Landroid/app/NotificationManager;
    if-eqz v3, :ns_ret

    const-string v4, "proxma_auto"

    new-instance v5, Landroid/app/NotificationChannel;
    const/4 v6, 0x4
    const-string v7, "Auto Status"
    invoke-direct {v5, v4, v7, v6}, Landroid/app/NotificationChannel;-><init>(Ljava/lang/String;Ljava/lang/CharSequence;I)V
    invoke-virtual {v3, v5}, Landroid/app/NotificationManager;->createNotificationChannel(Landroid/app/NotificationChannel;)V

    new-instance v5, Landroid/app/Notification$Builder;
    invoke-direct {v5, p0, v4}, Landroid/app/Notification$Builder;-><init>(Landroid/content/Context;Ljava/lang/String;)V

    invoke-virtual {v5, v2}, Landroid/app/Notification$Builder;->setSmallIcon(I)Landroid/app/Notification$Builder;
    move-result-object v5
    invoke-virtual {v5, p1}, Landroid/app/Notification$Builder;->setContentTitle(Ljava/lang/CharSequence;)Landroid/app/Notification$Builder;
    move-result-object v5

    new-instance v6, Landroid/app/Notification$BigTextStyle;
    invoke-direct {v6}, Landroid/app/Notification$BigTextStyle;-><init>()V
    invoke-virtual {v6, p2}, Landroid/app/Notification$BigTextStyle;->bigText(Ljava/lang/CharSequence;)Landroid/app/Notification$BigTextStyle;
    move-result-object v6
    invoke-virtual {v5, v6}, Landroid/app/Notification$Builder;->setStyle(Landroid/app/Notification$Style;)Landroid/app/Notification$Builder;
    move-result-object v5

    const/4 v6, 0x1
    invoke-virtual {v5, v6}, Landroid/app/Notification$Builder;->setAutoCancel(Z)Landroid/app/Notification$Builder;
    move-result-object v5
    invoke-virtual {v5}, Landroid/app/Notification$Builder;->build()Landroid/app/Notification;
    move-result-object v5

    const/16 v6, 0x3eb
    invoke-virtual {v3, v6, v5}, Landroid/app/NotificationManager;->notify(ILandroid/app/Notification;)V
    :ns_try_end
    .catch Ljava/lang/Throwable; {:ns_try_start .. :ns_try_end} :ns_catch

    :ns_ret
    return-void

    :ns_catch
    move-exception v0
    return-void
.end method

.method public static setSession(Ljava/lang/String;)V
    .registers 1
    sput-object p0, Lmorphe/Notify;->sSession:Ljava/lang/String;
    return-void
.end method

.method public static setCheckin(Ljava/lang/String;)V
    .registers 1
    sput-object p0, Lmorphe/Notify;->sCheckin:Ljava/lang/String;
    return-void
.end method

.method public static getSession()Ljava/lang/String;
    .registers 1
    sget-object v0, Lmorphe/Notify;->sSession:Ljava/lang/String;
    return-object v0
.end method

.method public static getCheckin()Ljava/lang/String;
    .registers 1
    sget-object v0, Lmorphe/Notify;->sCheckin:Ljava/lang/String;
    return-object v0
.end method
