# Implemented by the patch on the two daily-reward ViewModels (Le4/N ladder, Li4/v check-in).
# The patch adds the interface and these four methods to each ViewModel, so the code here never
# names an obfuscated app class.
.class public interface abstract Lapp/proxma/simosa/ClaimTarget;
.super Ljava/lang/Object;

# The ViewModel's application Context (its own private Context field, read inside the ViewModel).
.method public abstract proxmaContext()Landroid/content/Context;
.end method

# viewModelScope.launch(block) through the app's own kotlinx.coroutines facade.
.method public abstract proxmaLaunch(Lkotlin/jvm/functions/Function2;)V
.end method

# kotlinx.coroutines.delay(ms, continuation) through the app's own (obfuscated) DelayKt.
.method public abstract proxmaDelay(JLkotlin/coroutines/Continuation;)Ljava/lang/Object;
.end method

# Re-check every guard against the ViewModel's current state, latch, then call the app's own claim.
.method public abstract proxmaFire()V
.end method
