# The coroutine body launched on the ViewModel's viewModelScope: suspend in the app's own delay(),
# then fire the claim. It is a plain Function2, so kotlinx wraps it with
# createCoroutineFromSuspendFunction and hands `invoke` the coroutine's continuation; this class is
# also the Continuation delay() resumes, so it can run the claim before completing the coroutine.
# Cancellation (ViewModel cleared during the delay) arrives as a Result.Failure and skips the claim.
.class public final Lapp/proxma/simosa/DelayedClaim;
.super Ljava/lang/Object;
.implements Lkotlin/jvm/functions/Function2;
.implements Lkotlin/coroutines/Continuation;

.field private final target:Lapp/proxma/simosa/ClaimTarget;
.field private final kind:Ljava/lang/String;
.field private final delayMs:J
.field private completion:Lkotlin/coroutines/Continuation;

.method public constructor <init>(Lapp/proxma/simosa/ClaimTarget;Ljava/lang/String;J)V
    .registers 5
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lapp/proxma/simosa/DelayedClaim;->target:Lapp/proxma/simosa/ClaimTarget;
    iput-object p2, p0, Lapp/proxma/simosa/DelayedClaim;->kind:Ljava/lang/String;
    iput-wide p3, p0, Lapp/proxma/simosa/DelayedClaim;->delayMs:J
    return-void
.end method

.method public invoke(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    .registers 6
    check-cast p2, Lkotlin/coroutines/Continuation;
    iput-object p2, p0, Lapp/proxma/simosa/DelayedClaim;->completion:Lkotlin/coroutines/Continuation;
    iget-object v0, p0, Lapp/proxma/simosa/DelayedClaim;->target:Lapp/proxma/simosa/ClaimTarget;
    iget-wide v1, p0, Lapp/proxma/simosa/DelayedClaim;->delayMs:J
    invoke-interface {v0, v1, v2, p0}, Lapp/proxma/simosa/ClaimTarget;->proxmaDelay(JLkotlin/coroutines/Continuation;)Ljava/lang/Object;
    move-result-object v0
    invoke-static {}, Lkotlin/coroutines/intrinsics/IntrinsicsKt;->getCOROUTINE_SUSPENDED()Ljava/lang/Object;
    move-result-object v1
    if-ne v0, v1, :not_suspended
    return-object v0
    :not_suspended
    invoke-direct {p0}, Lapp/proxma/simosa/DelayedClaim;->fire()V
    sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
    return-object v0
.end method

.method public getContext()Lkotlin/coroutines/CoroutineContext;
    .registers 2
    iget-object v0, p0, Lapp/proxma/simosa/DelayedClaim;->completion:Lkotlin/coroutines/Continuation;
    invoke-interface {v0}, Lkotlin/coroutines/Continuation;->getContext()Lkotlin/coroutines/CoroutineContext;
    move-result-object v0
    return-object v0
.end method

.method public resumeWith(Ljava/lang/Object;)V
    .registers 5
    iget-object v0, p0, Lapp/proxma/simosa/DelayedClaim;->completion:Lkotlin/coroutines/Continuation;
    instance-of v1, p1, Lkotlin/Result$Failure;
    if-eqz v1, :resumed
    iget-object v1, p0, Lapp/proxma/simosa/DelayedClaim;->kind:Ljava/lang/String;
    invoke-static {v1}, Lapp/proxma/simosa/AutoClaim;->unschedule(Ljava/lang/String;)V
    const-string v2, "skip cancelled"
    invoke-static {v1, v2}, Lapp/proxma/simosa/AutoClaim;->log(Ljava/lang/String;Ljava/lang/String;)V
    invoke-interface {v0, p1}, Lkotlin/coroutines/Continuation;->resumeWith(Ljava/lang/Object;)V
    return-void
    :resumed
    invoke-direct {p0}, Lapp/proxma/simosa/DelayedClaim;->fire()V
    sget-object v1, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
    invoke-interface {v0, v1}, Lkotlin/coroutines/Continuation;->resumeWith(Ljava/lang/Object;)V
    return-void
.end method

# Never lets a failure escape into the app: anything thrown by the claim path is logged instead.
.method private fire()V
    .registers 5
    iget-object v0, p0, Lapp/proxma/simosa/DelayedClaim;->kind:Ljava/lang/String;
    invoke-static {v0}, Lapp/proxma/simosa/AutoClaim;->unschedule(Ljava/lang/String;)V
    :try_start
    iget-object v1, p0, Lapp/proxma/simosa/DelayedClaim;->target:Lapp/proxma/simosa/ClaimTarget;
    invoke-interface {v1}, Lapp/proxma/simosa/ClaimTarget;->proxmaFire()V
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :failed
    return-void
    :failed
    move-exception v1
    new-instance v2, Ljava/lang/StringBuilder;
    const-string v3, "error "
    invoke-direct {v2, v3}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;
    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v2
    invoke-static {v0, v2}, Lapp/proxma/simosa/AutoClaim;->log(Ljava/lang/String;Ljava/lang/String;)V
    return-void
.end method
