.class public Lcom/jazz/jazzworld/presentation/ClaimWorker;
.super Landroidx/work/Worker;

.field private final ctx:Landroid/content/Context;
.field latch:Ljava/util/concurrent/CountDownLatch;

.method public constructor <init>(Landroid/content/Context;Landroidx/work/WorkerParameters;)V
    .registers 3
    invoke-direct {p0, p1, p2}, Landroidx/work/Worker;-><init>(Landroid/content/Context;Landroidx/work/WorkerParameters;)V
    iput-object p1, p0, Lcom/jazz/jazzworld/presentation/ClaimWorker;->ctx:Landroid/content/Context;
    return-void
.end method

.method public doWork()Landroidx/work/ListenableWorker$Result;
    .registers 12

    invoke-virtual {p0}, Landroidx/work/ListenableWorker;->getApplicationContext()Landroid/content/Context;
    move-result-object v9

    const-string v0, "PROXMA_CLAIM"
    const-string v1, "DOWORK_ENTER"
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    invoke-static {v9}, Lmorphe/Notify;->init(Landroid/content/Context;)V

    invoke-static {}, Lmorphe/Notify;->isClaimedToday()Z
    move-result v0

    if-eqz v0, :run

    const-string v0, "WORKER_SKIP already claimed today"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    invoke-static {v9}, Lcom/jazz/jazzworld/presentation/ClaimWorker;->doCheckIn(Landroid/content/Context;)V
    invoke-static {v9}, Lmorphe/Notify;->armTomorrow(Landroid/content/Context;)V

    invoke-static {}, Landroidx/work/ListenableWorker$Result;->success()Landroidx/work/ListenableWorker$Result;
    move-result-object v0
    const-string v1, "already done"
    const-string v2, "tomorrow ~00:35"
    const-string v3, "Skip"
    invoke-static {v9, v1, v2, v3}, Lcom/jazz/jazzworld/presentation/ClaimWorker;->doNotify(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    return-object v0

    :run
    invoke-static {}, Lmorphe/Notify;->bumpAttempt()I
    move-result v10
    const/16 v0, 0x24
    if-gt v10, v0, :cap
    goto :after_cap

    :cap
    const-string v0, "CHAIN_CAP tries_today=36 stop"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    invoke-static {v9}, Lmorphe/Notify;->armTomorrow(Landroid/content/Context;)V
    invoke-static {}, Landroidx/work/ListenableWorker$Result;->success()Landroidx/work/ListenableWorker$Result;
    move-result-object v0
    return-object v0

    :after_cap
    :try_start
    sget-object v0, LK8/F;->a:LK8/F;
    invoke-virtual {v0, v9}, LK8/F;->Y(Landroid/content/Context;)Lcom/jazz/jazzworld/data/manager/UserDataModel;
    move-result-object v1

    if-nez v1, :have_session

    const-string v0, "NO_SESSION K8.F.Y=null"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    invoke-static {v9}, Lmorphe/Notify;->armTomorrow(Landroid/content/Context;)V
    invoke-static {}, Landroidx/work/ListenableWorker$Result;->success()Landroidx/work/ListenableWorker$Result;
    move-result-object v0
    return-object v0

    :have_session
    sget-object v0, Lcom/jazz/jazzworld/data/manager/DataManager;->Companion:Lcom/jazz/jazzworld/data/manager/DataManager$Companion;
    invoke-virtual {v0}, Lcom/jazz/jazzworld/data/manager/DataManager$Companion;->getInstance()Lcom/jazz/jazzworld/data/manager/DataManager;
    move-result-object v0
    invoke-virtual {v0, v1}, Lcom/jazz/jazzworld/data/manager/DataManager;->setUserData(Lcom/jazz/jazzworld/data/manager/UserDataModel;)V

    invoke-virtual {v9}, Landroid/content/Context;->getApplicationContext()Landroid/content/Context;
    move-result-object v0
    check-cast v0, Lcom/jazz/jazzworld/presentation/Hilt_MyApplication;
    invoke-virtual {v0}, Lcom/jazz/jazzworld/presentation/Hilt_MyApplication;->generatedComponent()Ljava/lang/Object;
    move-result-object v0
    check-cast v0, Lcom/jazz/jazzworld/presentation/a$h;

    iget-object v2, v0, Lcom/jazz/jazzworld/presentation/a$h;->x:LL9/g;
    invoke-interface {v2}, Ljavax/inject/Provider;->get()Ljava/lang/Object;
    move-result-object v2
    check-cast v2, LN9/a;

    iget-object v3, v0, Lcom/jazz/jazzworld/presentation/a$h;->i0:LL9/g;
    invoke-interface {v3}, Ljavax/inject/Provider;->get()Ljava/lang/Object;
    move-result-object v3
    check-cast v3, Lcom/jazz/jazzworld/data/network/genericapis/dailyreward/DailyRewardRemoteDataSource;

    iget-object v4, v0, Lcom/jazz/jazzworld/presentation/a$h;->j0:LL9/g;
    invoke-interface {v4}, Ljavax/inject/Provider;->get()Ljava/lang/Object;
    move-result-object v4
    check-cast v4, Lcom/jazz/jazzworld/data/network/genericapis/dailyreward/DailyRewardSubscribedRemoteDataSource;

    iget-object v5, v0, Lcom/jazz/jazzworld/presentation/a$h;->k0:LL9/g;
    invoke-interface {v5}, Ljavax/inject/Provider;->get()Ljava/lang/Object;
    move-result-object v5
    check-cast v5, Lcom/jazz/jazzworld/data/network/genericapis/dailyreward/DailyRewardClaimedRemoteDataSource;

    invoke-static {v9, v2, v3, v4, v5}, LV7/j;->a(Landroid/content/Context;LN9/a;Lcom/jazz/jazzworld/data/network/genericapis/dailyreward/DailyRewardRemoteDataSource;Lcom/jazz/jazzworld/data/network/genericapis/dailyreward/DailyRewardSubscribedRemoteDataSource;Lcom/jazz/jazzworld/data/network/genericapis/dailyreward/DailyRewardClaimedRemoteDataSource;)LU7/a;
    move-result-object v6
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :catch_setup

    const-string v0, "REPO_OK"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V

    new-instance v7, Ljava/util/concurrent/CountDownLatch;
    const/4 v8, 0x1
    invoke-direct {v7, v8}, Ljava/util/concurrent/CountDownLatch;-><init>(I)V
    iput-object v7, p0, Lcom/jazz/jazzworld/presentation/ClaimWorker;->latch:Ljava/util/concurrent/CountDownLatch;

    new-instance v8, Lcom/jazz/jazzworld/presentation/ClaimWorker$ListCb;
    invoke-direct {v8, p0, v6}, Lcom/jazz/jazzworld/presentation/ClaimWorker$ListCb;-><init>(Lcom/jazz/jazzworld/presentation/ClaimWorker;LU7/a;)V
    invoke-virtual {v6, v8}, LU7/a;->c(Lcom/jazz/jazzworld/data/network/genericapis/dailyreward/OnDailyRewardListListeners;)V

    const-wide/16 v0, 0x3c
    sget-object v2, Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;
    invoke-virtual {v7, v0, v1, v2}, Ljava/util/concurrent/CountDownLatch;->await(JLjava/util/concurrent/TimeUnit;)Z

    # Auto check-in after ladder attempt
    invoke-static {v9}, Lcom/jazz/jazzworld/presentation/ClaimWorker;->doCheckIn(Landroid/content/Context;)V

    invoke-static {}, Lmorphe/Notify;->isClaimedToday()Z
    move-result v0

    if-eqz v0, :not_done

    const-string v0, "WORKER_OK claimed stop"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    invoke-static {v9}, Lmorphe/Notify;->armTomorrow(Landroid/content/Context;)V
    invoke-static {}, Landroidx/work/ListenableWorker$Result;->success()Landroidx/work/ListenableWorker$Result;
    move-result-object v0
    const-string v2, "claimed!"
    const-string v3, "tomorrow ~00:35"
    const-string v4, "Claimed"
    invoke-static {v9, v2, v3, v4}, Lcom/jazz/jazzworld/presentation/ClaimWorker;->doNotify(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    return-object v0

    :not_done
    const-string v0, "WORKER_RETRY not claimed next 10m"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    const-wide/16 v0, 0xa
    invoke-static {v9, v0, v1}, Lmorphe/Notify;->armIn(Landroid/content/Context;J)V
    invoke-static {}, Landroidx/work/ListenableWorker$Result;->success()Landroidx/work/ListenableWorker$Result;
    move-result-object v0
    const-string v2, "pending"
    const-string v3, "~10 min"
    const-string v4, "Retry"
    invoke-static {v9, v2, v3, v4}, Lcom/jazz/jazzworld/presentation/ClaimWorker;->doNotify(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    return-object v0

    :catch_setup
    move-exception v1
    invoke-virtual {v1}, Ljava/lang/Throwable;->toString()Ljava/lang/String;
    move-result-object v2
    const-string v0, "NO_REPO "
    invoke-virtual {v0, v2}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    const-wide/16 v0, 0xa
    invoke-static {v9, v0, v1}, Lmorphe/Notify;->armIn(Landroid/content/Context;J)V
    invoke-static {}, Landroidx/work/ListenableWorker$Result;->success()Landroidx/work/ListenableWorker$Result;
    move-result-object v0
    return-object v0
.end method


.method private static doNotify(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    .registers 10

    :dn_try
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    const-string v1, "Session: "
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-static {}, Lmorphe/Notify;->getSession()Ljava/lang/String;
    move-result-object v2
    if-nez v2, :dn_s_ok
    const-string v2, "?"
    :dn_s_ok
    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v1, "\nClaim: "
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v1, "\nCheckin: "
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-static {}, Lmorphe/Notify;->getCheckin()Ljava/lang/String;
    move-result-object v3
    if-nez v3, :dn_c_ok
    const-string v3, "?"
    :dn_c_ok
    invoke-virtual {v0, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v1, "\nNext: "
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    invoke-static {p0, p3, v0}, Lmorphe/Notify;->notifyStatus(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V
    :dn_try_end
    .catch Ljava/lang/Throwable; {:dn_try .. :dn_try_end} :dn_catch
    return-void

    :dn_catch
    move-exception v0
    return-void
.end method
.method private static doCheckIn(Landroid/content/Context;)V
    .registers 16

    :try_start_checkin

    const-string v0, "none"
    invoke-static {v0}, Lmorphe/Notify;->setSession(Ljava/lang/String;)V
    const-string v0, "n/a"
    invoke-static {v0}, Lmorphe/Notify;->setCheckin(Ljava/lang/String;)V

    # === Day gate ===
    const-string v0, "proxma_claim"
    const/4 v1, 0x0
    invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
    move-result-object v9

    if-nez v9, :have_prefs
    const-string v0, "CHECKIN_ERR no_prefs"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    return-void

    :have_prefs
    invoke-static {}, Lmorphe/Notify;->dayKey()I
    move-result v10

    const-string v0, "checkin_day"
    const/4 v1, 0x0
    invoke-interface {v9, v0, v1}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I
    move-result v0

    if-ne v0, v10, :checkin_not_done
    const-string v0, "CHECKIN_DONE_TODAY"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    const-string v0, "already done"
    invoke-static {v0}, Lmorphe/Notify;->setCheckin(Ljava/lang/String;)V
    return-void

    :checkin_not_done
    const-string v0, "CHECKIN_START"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    const-string v0, "cached"
    invoke-static {v0}, Lmorphe/Notify;->setSession(Ljava/lang/String;)V

    # === Try cached token ===
    sget-object v0, Lcom/jazz/jazzworld/data/network/networkcache/NetworkCacheManager;->INSTANCE:Lcom/jazz/jazzworld/data/network/networkcache/NetworkCacheManager;
    const-class v2, Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardAuthTokenResponse;
    const-string v8, "key_gamification_authToken"
    invoke-static {v8}, LK8/X;->M(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v3
    const-wide v4, 0x7fffffffffffffffL
    const-wide/16 v6, 0x0
    move-object v1, p0
    invoke-virtual/range {v0 .. v7}, Lcom/jazz/jazzworld/data/network/networkcache/NetworkCacheManager;->getCacheData(Landroid/content/Context;Ljava/lang/Class;Ljava/lang/String;JJ)Lcom/jazz/jazzworld/data/network/networkcache/CacheData;
    move-result-object v0

    if-eqz v0, :try_refresh_token

    invoke-virtual {v0}, Lcom/jazz/jazzworld/data/network/networkcache/CacheData;->getData()Ljava/lang/Object;
    move-result-object v0
    if-eqz v0, :try_refresh_token

    check-cast v0, Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardAuthTokenResponse;

    # Save refresh_token and uuid to our prefs for future offline refreshes
    invoke-virtual {v0}, Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardAuthTokenResponse;->getRefresh_token()Ljava/lang/String;
    move-result-object v12
    invoke-virtual {v0}, Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardAuthTokenResponse;->getUuid()Ljava/lang/String;
    move-result-object v13

    if-eqz v12, :skip_save_rt
    if-eqz v13, :skip_save_rt
    invoke-interface {v9}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v14
    const-string v8, "simosa_refresh_token"
    invoke-interface {v14, v8, v12}, Landroid/content/SharedPreferences$Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;
    const-string v8, "simosa_uuid"
    invoke-interface {v14, v8, v13}, Landroid/content/SharedPreferences$Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;
    invoke-interface {v14}, Landroid/content/SharedPreferences$Editor;->apply()V
    :skip_save_rt

    invoke-virtual {v0}, Lcom/jazz/jazzworld/data/appmodels/dailyreward/response/DailyRewardAuthTokenResponse;->resolveAccessToken()Ljava/lang/String;
    move-result-object v1
    if-eqz v1, :try_refresh_token

    # Build Bearer string from cached access_token
    new-instance v2, Ljava/lang/StringBuilder;
    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
    const-string v3, "Bearer "
    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v11
    const/4 v12, 0x1
    goto :do_checkin_request

    # === Refresh token fallback ===
    :try_refresh_token
    const-string v0, "simosa_refresh_token"
    const/4 v1, 0x0
    invoke-interface {v9, v0, v1}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v12

    const-string v0, "simosa_uuid"
    invoke-interface {v9, v0, v1}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v13

    if-eqz v12, :no_refresh_token
    if-eqz v13, :no_refresh_token

    # Build JSON body
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    const-string v1, "{\"refresh_token\":\""
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0, v12}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v1, "\",\"uuid\":\""
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0, v13}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v1, "\"}"
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v14

    # POST to auth/refresh
    const-string v0, "https://api-prod-dailycheckin.simosa.app/auth/refresh"
    new-instance v1, Ljava/net/URL;
    invoke-direct {v1, v0}, Ljava/net/URL;-><init>(Ljava/lang/String;)V
    invoke-virtual {v1}, Ljava/net/URL;->openConnection()Ljava/net/URLConnection;
    move-result-object v1
    check-cast v1, Ljava/net/HttpURLConnection;

    const-string v0, "POST"
    invoke-virtual {v1, v0}, Ljava/net/HttpURLConnection;->setRequestMethod(Ljava/lang/String;)V
    const-string v0, "Content-Type"
    const-string v2, "application/json"
    invoke-virtual {v1, v0, v2}, Ljava/net/HttpURLConnection;->setRequestProperty(Ljava/lang/String;Ljava/lang/String;)V
    const-string v0, "x-app-build"
    const-string v2, "163"
    invoke-virtual {v1, v0, v2}, Ljava/net/HttpURLConnection;->setRequestProperty(Ljava/lang/String;Ljava/lang/String;)V
    const/16 v0, 0x3a98
    invoke-virtual {v1, v0}, Ljava/net/HttpURLConnection;->setConnectTimeout(I)V
    invoke-virtual {v1, v0}, Ljava/net/HttpURLConnection;->setReadTimeout(I)V
    const/4 v0, 0x1
    invoke-virtual {v1, v0}, Ljava/net/HttpURLConnection;->setDoOutput(Z)V

    invoke-virtual {v1}, Ljava/net/HttpURLConnection;->getOutputStream()Ljava/io/OutputStream;
    move-result-object v2
    const-string v3, "UTF-8"
    invoke-virtual {v14, v3}, Ljava/lang/String;->getBytes(Ljava/lang/String;)[B
    move-result-object v3
    invoke-virtual {v2, v3}, Ljava/io/OutputStream;->write([B)V
    invoke-virtual {v2}, Ljava/io/OutputStream;->flush()V
    invoke-virtual {v2}, Ljava/io/OutputStream;->close()V

    invoke-virtual {v1}, Ljava/net/HttpURLConnection;->getResponseCode()I
    move-result v0

    # Read response body
    const/16 v3, 0xc8
    if-lt v0, v3, :ref_err_stream
    const/16 v3, 0x12c
    if-ge v0, v3, :ref_err_stream
    invoke-virtual {v1}, Ljava/net/HttpURLConnection;->getInputStream()Ljava/io/InputStream;
    move-result-object v2
    goto :ref_read

    :ref_err_stream
    invoke-virtual {v1}, Ljava/net/HttpURLConnection;->getErrorStream()Ljava/io/InputStream;
    move-result-object v2

    :ref_read
    new-instance v3, Ljava/io/BufferedReader;
    new-instance v4, Ljava/io/InputStreamReader;
    invoke-direct {v4, v2}, Ljava/io/InputStreamReader;-><init>(Ljava/io/InputStream;)V
    invoke-direct {v3, v4}, Ljava/io/BufferedReader;-><init>(Ljava/io/Reader;)V
    new-instance v4, Ljava/lang/StringBuilder;
    invoke-direct {v4}, Ljava/lang/StringBuilder;-><init>()V

    :ref_read_loop
    invoke-virtual {v3}, Ljava/io/BufferedReader;->readLine()Ljava/lang/String;
    move-result-object v5
    if-eqz v5, :ref_read_done
    invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    goto :ref_read_loop

    :ref_read_done
    invoke-virtual {v3}, Ljava/io/BufferedReader;->close()V
    invoke-virtual {v1}, Ljava/net/HttpURLConnection;->disconnect()V
    invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v2


    # Check if success
    const/16 v3, 0xc8
    if-lt v0, v3, :refresh_fail
    const/16 v3, 0x12c
    if-ge v0, v3, :refresh_fail

    # Parse JSON: unwrap data object, extract access_token
    new-instance v3, Lorg/json/JSONObject;
    invoke-direct {v3, v2}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
    move-object v6, v3
    const-string v4, "data"
    invoke-virtual {v3, v4}, Lorg/json/JSONObject;->optJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;
    move-result-object v3
    if-nez v3, :have_data_wrapper
    move-object v3, v6
    :have_data_wrapper

    const-string v4, "access_token"
    invoke-virtual {v3, v4}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v4

    if-eqz v4, :refresh_no_at
    invoke-virtual {v4}, Ljava/lang/String;->isEmpty()Z
    move-result v5
    if-nez v5, :refresh_no_at

    # Save rotated refresh_token if present
    const-string v5, "refresh_token"
    invoke-virtual {v3, v5}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v5
    if-eqz v5, :skip_rt_update
    invoke-virtual {v5}, Ljava/lang/String;->isEmpty()Z
    move-result v6
    if-nez v6, :skip_rt_update

    invoke-interface {v9}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v6
    const-string v7, "simosa_refresh_token"
    invoke-interface {v6, v7, v5}, Landroid/content/SharedPreferences$Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;
    const-string v7, "uuid"
    invoke-virtual {v3, v7}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v7
    if-eqz v7, :skip_uuid_up
    invoke-virtual {v7}, Ljava/lang/String;->isEmpty()Z
    move-result v8
    if-nez v8, :skip_uuid_up
    const-string v8, "simosa_uuid"
    invoke-interface {v6, v8, v7}, Landroid/content/SharedPreferences$Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;
    :skip_uuid_up
    invoke-interface {v6}, Landroid/content/SharedPreferences$Editor;->apply()V
    :skip_rt_update

    const-string v0, "CHECKIN_REFRESHED"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    const-string v0, "refreshed"
    invoke-static {v0}, Lmorphe/Notify;->setSession(Ljava/lang/String;)V

    # Build Bearer from refreshed access_token
    new-instance v2, Ljava/lang/StringBuilder;
    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
    const-string v3, "Bearer "
    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v11
    const/4 v12, 0x0
    goto :do_checkin_request

    :refresh_no_at
    const-string v0, "CHECKIN_REFRESH_FAIL no_access_token"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    return-void

    :refresh_fail
    invoke-virtual {v2}, Ljava/lang/String;->length()I
    move-result v3
    const/16 v4, 0xc8
    if-le v3, v4, :rf_no_trunc
    const/4 v3, 0x0
    invoke-virtual {v2, v3, v4}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object v2
    :rf_no_trunc
    new-instance v3, Ljava/lang/StringBuilder;
    invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V
    const-string v4, "CHECKIN_REFRESH_FAIL "
    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v3, v0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    const-string v4, " "
    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v3, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    return-void

    :no_refresh_token
    const-string v0, "CHECKIN_NO_TOKEN no_refresh"
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    const-string v0, "no token"
    invoke-static {v0}, Lmorphe/Notify;->setCheckin(Ljava/lang/String;)V
    return-void

    # === Check-in request (shared path) ===
    :do_checkin_request
    const-string v0, "https://api-prod-dailycheckin.simosa.app/checkin"
    new-instance v1, Ljava/net/URL;
    invoke-direct {v1, v0}, Ljava/net/URL;-><init>(Ljava/lang/String;)V
    invoke-virtual {v1}, Ljava/net/URL;->openConnection()Ljava/net/URLConnection;
    move-result-object v1
    check-cast v1, Ljava/net/HttpURLConnection;

    const-string v0, "POST"
    invoke-virtual {v1, v0}, Ljava/net/HttpURLConnection;->setRequestMethod(Ljava/lang/String;)V
    const-string v0, "Authorization"
    invoke-virtual {v1, v0, v11}, Ljava/net/HttpURLConnection;->setRequestProperty(Ljava/lang/String;Ljava/lang/String;)V
    const-string v0, "x-app-build"
    const-string v2, "163"
    invoke-virtual {v1, v0, v2}, Ljava/net/HttpURLConnection;->setRequestProperty(Ljava/lang/String;Ljava/lang/String;)V
    const-string v0, "Content-Type"
    const-string v2, "application/json"
    invoke-virtual {v1, v0, v2}, Ljava/net/HttpURLConnection;->setRequestProperty(Ljava/lang/String;Ljava/lang/String;)V
    const/16 v0, 0x3a98
    invoke-virtual {v1, v0}, Ljava/net/HttpURLConnection;->setConnectTimeout(I)V
    invoke-virtual {v1, v0}, Ljava/net/HttpURLConnection;->setReadTimeout(I)V
    const/4 v0, 0x1
    invoke-virtual {v1, v0}, Ljava/net/HttpURLConnection;->setDoOutput(Z)V

    invoke-virtual {v1}, Ljava/net/HttpURLConnection;->getOutputStream()Ljava/io/OutputStream;
    move-result-object v2
    const-string v4, "{}"
    const-string v5, "UTF-8"
    invoke-virtual {v4, v5}, Ljava/lang/String;->getBytes(Ljava/lang/String;)[B
    move-result-object v4
    invoke-virtual {v2, v4}, Ljava/io/OutputStream;->write([B)V
    invoke-virtual {v2}, Ljava/io/OutputStream;->flush()V
    invoke-virtual {v2}, Ljava/io/OutputStream;->close()V

    invoke-virtual {v1}, Ljava/net/HttpURLConnection;->getResponseCode()I
    move-result v0

    const/16 v4, 0xc8
    if-lt v0, v4, :ci_err_stream
    const/16 v4, 0x12c
    if-ge v0, v4, :ci_err_stream
    invoke-virtual {v1}, Ljava/net/HttpURLConnection;->getInputStream()Ljava/io/InputStream;
    move-result-object v2
    goto :ci_read

    :ci_err_stream
    invoke-virtual {v1}, Ljava/net/HttpURLConnection;->getErrorStream()Ljava/io/InputStream;
    move-result-object v2

    :ci_read
    new-instance v4, Ljava/io/BufferedReader;
    new-instance v5, Ljava/io/InputStreamReader;
    invoke-direct {v5, v2}, Ljava/io/InputStreamReader;-><init>(Ljava/io/InputStream;)V
    invoke-direct {v4, v5}, Ljava/io/BufferedReader;-><init>(Ljava/io/Reader;)V
    new-instance v5, Ljava/lang/StringBuilder;
    invoke-direct {v5}, Ljava/lang/StringBuilder;-><init>()V

    :ci_read_loop
    invoke-virtual {v4}, Ljava/io/BufferedReader;->readLine()Ljava/lang/String;
    move-result-object v6
    if-eqz v6, :ci_read_done
    invoke-virtual {v5, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    goto :ci_read_loop

    :ci_read_done
    invoke-virtual {v4}, Ljava/io/BufferedReader;->close()V
    invoke-virtual {v1}, Ljava/net/HttpURLConnection;->disconnect()V
    invoke-virtual {v5}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v2

    invoke-virtual {v2}, Ljava/lang/String;->length()I
    move-result v4
    const/16 v5, 0xc8
    if-le v4, v5, :ci_no_trunc
    const/4 v4, 0x0
    invoke-virtual {v2, v4, v5}, Ljava/lang/String;->substring(II)Ljava/lang/String;
    move-result-object v2
    :ci_no_trunc

    new-instance v4, Ljava/lang/StringBuilder;
    invoke-direct {v4}, Ljava/lang/StringBuilder;-><init>()V

    const/16 v5, 0xc8
    if-lt v0, v5, :ci_fail
    const/16 v5, 0x12c
    if-ge v0, v5, :ci_fail

    const-string v5, "CHECKIN_OK "
    invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v4, v0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    const-string v5, " "
    invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v4, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V

    invoke-interface {v9}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v0
    const-string v1, "checkin_day"
    invoke-interface {v0, v1, v10}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;
    invoke-interface {v0}, Landroid/content/SharedPreferences$Editor;->apply()V
    const-string v0, "checked in"
    invoke-static {v0}, Lmorphe/Notify;->setCheckin(Ljava/lang/String;)V
    return-void

    :ci_fail
    # 401 or 419 with cached token -> fall through to refresh
    if-eqz v12, :ci_not_auth_fail
    const/16 v5, 0x191
    if-eq v0, v5, :ci_auth_retry
    const/16 v5, 0x1a3
    if-eq v0, v5, :ci_auth_retry
    goto :ci_not_auth_fail
    :ci_auth_retry
    const-string v5, "CHECKIN_TOKEN_EXPIRED retrying via refresh"
    invoke-static {v5}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    goto :try_refresh_token

    :ci_not_auth_fail
    # 409 -> mark checkin_day (already done today)
    const/16 v5, 0x199
    if-ne v0, v5, :ci_not_409
    invoke-interface {v9}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v5
    const-string v6, "checkin_day"
    invoke-interface {v5, v6, v10}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;
    invoke-interface {v5}, Landroid/content/SharedPreferences$Editor;->apply()V
    :ci_not_409

    # Log the failure
    const-string v5, "CHECKIN_FAIL "
    invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v4, v0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    const-string v5, " "
    invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v4, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    const-string v0, "fail"
    invoke-static {v0}, Lmorphe/Notify;->setCheckin(Ljava/lang/String;)V

    :try_end_checkin
    .catch Ljava/lang/Throwable; {:try_start_checkin .. :try_end_checkin} :catch_checkin
    return-void

    :catch_checkin
    move-exception v0
    invoke-virtual {v0}, Ljava/lang/Throwable;->toString()Ljava/lang/String;
    move-result-object v1
    new-instance v2, Ljava/lang/StringBuilder;
    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
    const-string v3, "CHECKIN_ERR "
    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lmorphe/Notify;->log(Ljava/lang/String;)V
    return-void
.end method
