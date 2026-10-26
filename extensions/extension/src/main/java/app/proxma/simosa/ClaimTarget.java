package app.proxma.simosa;

import android.content.Context;

/**
 * Implemented (by the patch) on each Simosa ViewModel that owns a daily reward.
 *
 * <p>The patch adds {@code proxmaContext()}, {@code proxmaFire()} and {@code proxmaOnApplied()}.
 * All three live inside the ViewModel's own class, so the private state they touch is read where it
 * is legal to read it — never from a synthetic callback class.
 */
public interface ClaimTarget {

    /** The ViewModel's context field. */
    Context proxmaContext();

    /** Re-checks every guard and, if it still passes, makes the app's own claim call. */
    void proxmaFire();
}
