# Runtime half of the Simosa auto-claim: per-account, per-day latch, the jittered one-shot schedule,
# and the PROXMA_CLAIM logcat lines. Nothing here is user-visible.
#
# Latch: SharedPreferences file "proxma_simosa", key proxma_claim_<sha256(msisdn)[0..12]>_<yyyyMMdd>
# (Asia/Karachi day), int bitmask 1 = ladder, 2 = check-in. It is committed before a claim fires,
# and older proxma_claim_* keys are dropped on each write.
.class public final Lapp/proxma/simosa/AutoClaim;
.super Ljava/lang/Object;

.field private static final scheduled:Ljava/util/concurrent/ConcurrentHashMap;
.field private static final pending:Ljava/util/concurrent/ConcurrentHashMap;

.method static constructor <clinit>()V
    .registers 1
    new-instance v0, Ljava/util/concurrent/ConcurrentHashMap;
    invoke-direct {v0}, Ljava/util/concurrent/ConcurrentHashMap;-><init>()V
    sput-object v0, Lapp/proxma/simosa/AutoClaim;->scheduled:Ljava/util/concurrent/ConcurrentHashMap;
    new-instance v0, Ljava/util/concurrent/ConcurrentHashMap;
    invoke-direct {v0}, Ljava/util/concurrent/ConcurrentHashMap;-><init>()V
    sput-object v0, Lapp/proxma/simosa/AutoClaim;->pending:Ljava/util/concurrent/ConcurrentHashMap;
    return-void
.end method

.method public static log(Ljava/lang/String;Ljava/lang/String;)V
    .registers 4
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v1, " "
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    const-string v1, "PROXMA_CLAIM"
    invoke-static {v1, v0}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    return-void
.end method

.method private static bit(Ljava/lang/String;)I
    .registers 2
    const-string v0, "ladder"
    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :checkin
    const/4 v0, 0x1
    return v0
    :checkin
    const/4 v0, 0x2
    return v0
.end method

.method private static prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    .registers 3
    const-string v0, "proxma_simosa"
    const/4 v1, 0x0
    invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
    move-result-object v0
    return-object v0
.end method

# proxma_claim_<msisdn-hash>_<yyyyMMdd>, or null when no account is signed in.
.method private static key(Landroid/content/Context;)Ljava/lang/String;
    .registers 8
    const/4 v6, 0x0
    if-eqz p0, :none
    :try_start
    sget-object v0, Lcom/jazz/jazzworld/data/manager/DataManager;->Companion:Lcom/jazz/jazzworld/data/manager/DataManager$Companion;
    invoke-virtual {v0}, Lcom/jazz/jazzworld/data/manager/DataManager$Companion;->getInstance()Lcom/jazz/jazzworld/data/manager/DataManager;
    move-result-object v0
    if-eqz v0, :none
    invoke-virtual {v0, p0}, Lcom/jazz/jazzworld/data/manager/DataManager;->getUserData(Landroid/content/Context;)Lcom/jazz/jazzworld/data/manager/UserDataModel;
    move-result-object v0
    if-eqz v0, :none
    invoke-virtual {v0}, Lcom/jazz/jazzworld/data/manager/UserDataModel;->getMsisdn()Ljava/lang/String;
    move-result-object v0
    if-eqz v0, :none
    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z
    move-result v1
    if-nez v1, :none
    const-string v1, "SHA-256"
    invoke-static {v1}, Ljava/security/MessageDigest;->getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;
    move-result-object v1
    const-string v2, "UTF-8"
    invoke-virtual {v0, v2}, Ljava/lang/String;->getBytes(Ljava/lang/String;)[B
    move-result-object v0
    invoke-virtual {v1, v0}, Ljava/security/MessageDigest;->digest([B)[B
    move-result-object v0
    new-instance v1, Ljava/lang/StringBuilder;
    const-string v2, "proxma_claim_"
    invoke-direct {v1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    const/4 v2, 0x0
    :hex
    const/4 v3, 0x6
    if-ge v2, v3, :hex_done
    aget-byte v3, v0, v2
    and-int/lit16 v3, v3, 0xff
    add-int/lit16 v3, v3, 0x100
    invoke-static {v3}, Ljava/lang/Integer;->toHexString(I)Ljava/lang/String;
    move-result-object v3
    const/4 v4, 0x1
    invoke-virtual {v3, v4}, Ljava/lang/String;->substring(I)Ljava/lang/String;
    move-result-object v3
    invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    add-int/lit8 v2, v2, 0x1
    goto :hex
    :hex_done
    const-string v2, "_"
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    new-instance v2, Ljava/text/SimpleDateFormat;
    const-string v3, "yyyyMMdd"
    sget-object v4, Ljava/util/Locale;->US:Ljava/util/Locale;
    invoke-direct {v2, v3, v4}, Ljava/text/SimpleDateFormat;-><init>(Ljava/lang/String;Ljava/util/Locale;)V
    const-string v3, "Asia/Karachi"
    invoke-static {v3}, Ljava/util/TimeZone;->getTimeZone(Ljava/lang/String;)Ljava/util/TimeZone;
    move-result-object v3
    invoke-virtual {v2, v3}, Ljava/text/DateFormat;->setTimeZone(Ljava/util/TimeZone;)V
    new-instance v3, Ljava/util/Date;
    invoke-direct {v3}, Ljava/util/Date;-><init>()V
    invoke-virtual {v2, v3}, Ljava/text/DateFormat;->format(Ljava/util/Date;)Ljava/lang/String;
    move-result-object v2
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :none
    :none
    return-object v6
.end method

# Returns the skip reason ("skip no-account" / "skip latched"), or null when a claim may go ahead.
.method public static check(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;
    .registers 5
    invoke-static {p0}, Lapp/proxma/simosa/AutoClaim;->key(Landroid/content/Context;)Ljava/lang/String;
    move-result-object v0
    if-nez v0, :have_key
    const-string v0, "skip no-account"
    return-object v0
    :have_key
    invoke-static {p0}, Lapp/proxma/simosa/AutoClaim;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object v1
    const/4 v2, 0x0
    invoke-interface {v1, v0, v2}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I
    move-result v1
    invoke-static {p1}, Lapp/proxma/simosa/AutoClaim;->bit(Ljava/lang/String;)I
    move-result v2
    and-int/2addr v1, v2
    if-eqz v1, :free
    const-string v0, "skip latched"
    return-object v0
    :free
    const/4 v0, 0x0
    return-object v0
.end method

# Sets today's bit for `kind`, synchronously (commit), before the claim is fired.
.method public static latch(Landroid/content/Context;Ljava/lang/String;)V
    .registers 8
    invoke-static {p0}, Lapp/proxma/simosa/AutoClaim;->key(Landroid/content/Context;)Ljava/lang/String;
    move-result-object v0
    if-nez v0, :have_key
    return-void
    :have_key
    invoke-static {p0}, Lapp/proxma/simosa/AutoClaim;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object v1
    invoke-interface {v1}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v2
    invoke-interface {v1}, Landroid/content/SharedPreferences;->getAll()Ljava/util/Map;
    move-result-object v3
    invoke-interface {v3}, Ljava/util/Map;->keySet()Ljava/util/Set;
    move-result-object v3
    invoke-interface {v3}, Ljava/util/Set;->iterator()Ljava/util/Iterator;
    move-result-object v3
    :stale
    invoke-interface {v3}, Ljava/util/Iterator;->hasNext()Z
    move-result v4
    if-eqz v4, :stale_done
    invoke-interface {v3}, Ljava/util/Iterator;->next()Ljava/lang/Object;
    move-result-object v4
    check-cast v4, Ljava/lang/String;
    const-string v5, "proxma_claim_"
    invoke-virtual {v4, v5}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v5
    if-eqz v5, :stale
    invoke-virtual {v4, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v5
    if-nez v5, :stale
    invoke-interface {v2, v4}, Landroid/content/SharedPreferences$Editor;->remove(Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;
    goto :stale
    :stale_done
    const/4 v3, 0x0
    invoke-interface {v1, v0, v3}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I
    move-result v3
    invoke-static {p1}, Lapp/proxma/simosa/AutoClaim;->bit(Ljava/lang/String;)I
    move-result v4
    or-int/2addr v3, v4
    invoke-interface {v2, v0, v3}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;
    invoke-interface {v2}, Landroid/content/SharedPreferences$Editor;->commit()Z
    return-void
.end method

# Entry point from the ViewModel hooks: one jittered (2-6 s) attempt per kind, launched on the
# ViewModel's viewModelScope. Repeated state responses while one is waiting are ignored.
.method public static schedule(Lapp/proxma/simosa/ClaimTarget;Ljava/lang/String;)V
    .registers 9
    :try_start
    invoke-interface {p0}, Lapp/proxma/simosa/ClaimTarget;->proxmaContext()Landroid/content/Context;
    move-result-object v0
    invoke-static {v0, p1}, Lapp/proxma/simosa/AutoClaim;->check(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    if-eqz v1, :go
    invoke-static {p1, v1}, Lapp/proxma/simosa/AutoClaim;->log(Ljava/lang/String;Ljava/lang/String;)V
    return-void
    :go
    sget-object v1, Lapp/proxma/simosa/AutoClaim;->scheduled:Ljava/util/concurrent/ConcurrentHashMap;
    invoke-virtual {v1, p1, p1}, Ljava/util/concurrent/ConcurrentHashMap;->putIfAbsent(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v1
    if-eqz v1, :fresh
    return-void
    :fresh
    invoke-static {}, Ljava/util/concurrent/ThreadLocalRandom;->current()Ljava/util/concurrent/ThreadLocalRandom;
    move-result-object v1
    const/16 v2, 0xfa1
    invoke-virtual {v1, v2}, Ljava/util/concurrent/ThreadLocalRandom;->nextInt(I)I
    move-result v1
    add-int/lit16 v1, v1, 0x7d0
    int-to-long v2, v1
    new-instance v4, Ljava/lang/StringBuilder;
    const-string v5, "scheduled delay="
    invoke-direct {v4, v5}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    invoke-virtual {v4, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    const-string v5, "ms"
    invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v4
    invoke-static {p1, v4}, Lapp/proxma/simosa/AutoClaim;->log(Ljava/lang/String;Ljava/lang/String;)V
    new-instance v4, Lapp/proxma/simosa/DelayedClaim;
    invoke-direct {v4, p0, p1, v2, v3}, Lapp/proxma/simosa/DelayedClaim;-><init>(Lapp/proxma/simosa/ClaimTarget;Ljava/lang/String;J)V
    invoke-interface {p0, v4}, Lapp/proxma/simosa/ClaimTarget;->proxmaLaunch(Lkotlin/jvm/functions/Function2;)V
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :failed
    return-void
    :failed
    move-exception v0
    invoke-static {p1}, Lapp/proxma/simosa/AutoClaim;->unschedule(Ljava/lang/String;)V
    new-instance v1, Ljava/lang/StringBuilder;
    const-string v2, "error schedule "
    invoke-direct {v1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    invoke-static {p1, v1}, Lapp/proxma/simosa/AutoClaim;->log(Ljava/lang/String;Ljava/lang/String;)V
    return-void
.end method

.method public static unschedule(Ljava/lang/String;)V
    .registers 2
    sget-object v0, Lapp/proxma/simosa/AutoClaim;->scheduled:Ljava/util/concurrent/ConcurrentHashMap;
    invoke-virtual {v0, p0}, Ljava/util/concurrent/ConcurrentHashMap;->remove(Ljava/lang/Object;)Ljava/lang/Object;
    return-void
.end method

# Called by proxmaFire() after the latch is written, right before the app's own claim call.
.method public static fired(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    .registers 6
    sget-object v0, Lapp/proxma/simosa/AutoClaim;->pending:Ljava/util/concurrent/ConcurrentHashMap;
    const-string v1, "auto"
    invoke-virtual {v0, p0, v1}, Ljava/util/concurrent/ConcurrentHashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    new-instance v0, Ljava/lang/StringBuilder;
    const-string v1, "fire day="
    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v1, " status="
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    invoke-static {p0, v0}, Lapp/proxma/simosa/AutoClaim;->log(Ljava/lang/String;Ljava/lang/String;)V
    return-void
.end method

# The user's own tap went through the patched check-in path: latch today so the automatic attempt
# does not repeat it, and report its result.
.method public static manual(Landroid/content/Context;Ljava/lang/String;)V
    .registers 4
    invoke-static {p0, p1}, Lapp/proxma/simosa/AutoClaim;->latch(Landroid/content/Context;Ljava/lang/String;)V
    sget-object v0, Lapp/proxma/simosa/AutoClaim;->pending:Ljava/util/concurrent/ConcurrentHashMap;
    const-string v1, "manual"
    invoke-virtual {v0, p1, v1}, Ljava/util/concurrent/ConcurrentHashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    const-string v0, "fire manual-tap"
    invoke-static {p1, v0}, Lapp/proxma/simosa/AutoClaim;->log(Ljava/lang/String;Ljava/lang/String;)V
    return-void
.end method

# Logs "<kind> success|error <auto|manual> <detail>" once per fired attempt; null when none pending.
.method private static result(Ljava/lang/String;ZLjava/lang/String;)V
    .registers 6
    sget-object v0, Lapp/proxma/simosa/AutoClaim;->pending:Ljava/util/concurrent/ConcurrentHashMap;
    invoke-virtual {v0, p0}, Ljava/util/concurrent/ConcurrentHashMap;->remove(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v0
    if-nez v0, :was_pending
    return-void
    :was_pending
    new-instance v1, Ljava/lang/StringBuilder;
    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
    if-eqz p1, :error
    const-string v2, "success "
    goto :append
    :error
    const-string v2, "error "
    :append
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;
    const-string v2, " "
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    invoke-static {p0, v1}, Lapp/proxma/simosa/AutoClaim;->log(Ljava/lang/String;Ljava/lang/String;)V
    return-void
.end method

# Hooked at the top of the ladder's OnDailyRewardSubscriptionListeners.onDailyRewardSubscriptionSuccess.
.method public static ladderSuccess(Ljava/lang/Object;)V
    .registers 5
    const-string v1, ""
    :try_start
    instance-of v0, p0, Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardResponse;
    if-eqz v0, :emit
    check-cast p0, Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardResponse;
    invoke-virtual {p0}, Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardResponse;->getData()Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/Data;
    move-result-object v0
    if-eqz v0, :emit
    new-instance v2, Ljava/lang/StringBuilder;
    const-string v3, "day="
    invoke-direct {v2, v3}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    invoke-virtual {v0}, Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/Data;->getCurrentDay()Ljava/lang/String;
    move-result-object v3
    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v3, " status="
    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/Data;->getCurrentStatus()Ljava/lang/String;
    move-result-object v3
    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v3, " claimed="
    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/Data;->getClaimed()Ljava/lang/String;
    move-result-object v3
    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :emit
    :emit
    const-string v0, "ladder"
    const/4 v2, 0x1
    invoke-static {v0, v2, v1}, Lapp/proxma/simosa/AutoClaim;->result(Ljava/lang/String;ZLjava/lang/String;)V
    return-void
.end method

# Hooked at the top of onDailyRewardSubscriptionFailed(errorCode, message).
.method public static ladderFailed(Ljava/lang/String;Ljava/lang/String;)V
    .registers 5
    new-instance v0, Ljava/lang/StringBuilder;
    const-string v1, "code="
    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v1, " msg="
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    const-string v1, "ladder"
    const/4 v2, 0x0
    invoke-static {v1, v2, v0}, Lapp/proxma/simosa/AutoClaim;->result(Ljava/lang/String;ZLjava/lang/String;)V
    return-void
.end method

# Hooked at the top of the check-in result collector (ApiResources Loading / Success / Failure).
.method public static checkinResult(Ljava/lang/Object;)V
    .registers 5
    if-nez p0, :have
    return-void
    :have
    invoke-virtual {p0}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
    move-result-object v0
    invoke-virtual {v0}, Ljava/lang/Class;->getSimpleName()Ljava/lang/String;
    move-result-object v0
    const-string v1, "Loading"
    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :settled
    return-void
    :settled
    const-string v1, "Success"
    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    const-string v2, "checkin"
    if-eqz v1, :failed
    const-string v3, ""
    invoke-static {v2, v1, v3}, Lapp/proxma/simosa/AutoClaim;->result(Ljava/lang/String;ZLjava/lang/String;)V
    return-void
    :failed
    invoke-virtual {p0}, Ljava/lang/Object;->toString()Ljava/lang/String;
    move-result-object v3
    invoke-static {v2, v1, v3}, Lapp/proxma/simosa/AutoClaim;->result(Ljava/lang/String;ZLjava/lang/String;)V
    return-void
.end method
