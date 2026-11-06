package app.proxma.daraz;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.util.Base64;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Daraz signs every API request through Alibaba's mtop stack, and that signing is keyed to the
 * app's original signing certificate (CN=Daraz, O=Rocket Internet). When the app is patched it is
 * re-signed with a different key, so any code that reads the app's own certificate sees the wrong
 * one; the server then treats the session as invalid and the user appears logged out (the earlier
 * "Failed to generate Mtop signature" is the same failure). The login token on disk is never
 * deleted, which is why un-patching restores the session.
 *
 * This installs an in-process proxy over the framework package-manager binder so that when the app
 * queries its OWN package signature it gets the original Daraz certificate back instead of the
 * patch key. Every other call passes straight through unchanged.
 *
 * It is entirely best-effort: if anything here fails it is swallowed and the app keeps running
 * exactly as it would without this hook, so it can never make the app worse than the un-spoofed
 * build. It only rewrites the signature of OUR OWN package, never any other app's.
 */
public final class SignatureSpoof {

    private static final String TAG = "PROXMA_DARAZ";

    // DER of the original Daraz signing certificate (SHA-256 2aded5a5…), Base64-encoded.
    private static final String ORIG_CERT_B64 =
            "MIIC8zCCAdugAwIBAgIEd4lzDzANBgkqhkiG9w0BAQsFADAqMRgwFgYDVQQKEw9Sb2NrZXQgSW50ZXJuZXQxDjAM" +
            "BgNVBAMTBURhcmF6MB4XDTE0MDkwNDE0MjcxNloXDTM5MDgyOTE0MjcxNlowKjEYMBYGA1UEChMPUm9ja2V0IElu" +
            "dGVybmV0MQ4wDAYDVQQDEwVEYXJhejCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBANEjwPuEYAM9INX7" +
            "dJzZznV8fatKyjUEgl+i460Oz2y4WlbXAmyoOdClBZWAdeFNb82CiqM70n8jNXNUyAr0w3G9n7mPxKzEFH9VFJD" +
            "QcRE7JvbtXd68JREeIqsiXEBycKfRkKtZljmNOr4tJ9haBw5T2WZAiN/IbTGIbhJ1+zxNwUKBWwCmuHxj6TFsEr" +
            "aZJKpdLH1PuqOPG4CfuJJ+PXhosZVFx8do9wMHzCVce2p7sHx5Wai+56nfkkP5eBHVYNbp5m04hSbhsx/39zcLc" +
            "fr4Z8g9H6D1s29L5c7sMoPgSt9Ss7+bXLPYCqTbvOBCCqC9z2fW0nYHxoQ6Dd/F6mECAwEAAaMhMB8wHQYDVR0O" +
            "BBYEFDgtcGW3dg9ZxV6mbZ+uUGodrqn2MA0GCSqGSIb3DQEBCwUAA4IBAQA6/ooiIEIuEIWJqNsOp6IUM/rlHp3v" +
            "dZZo9S1VugGpaP6gXi6DIzMv8uPrmg42y7zsy8MVAVHrApzaiog5fDhn+aGy9CZ9sbagQAfSm74CWwY2lWTjlfe" +
            "9ltD/v1GxHbWc3EG8asdG4wMp7LckVX2m2Q3bre9M/LAPQLNBl1uvd+Joq6LcAiaQBs/ba9mGsZYefxDAcCrn/j" +
            "Pv0GaVn46NwQ2kbz0fo8lLkbB5YcLqVdQYyymm4fPErD2l2sQ6iwoWXVn8w8evt6eTLLxFsxxDbk3Q244122P28" +
            "YedWXZKkHMC9Y0BhpKLtPzuqPH0QAl3Td+f0B+2YoRzLqguZHNl";

    private SignatureSpoof() {}

    // Called more than once on purpose: once as early as Application.attachBaseContext (before
    // Daraz's SecurityGuard / mtop stack can read and cache the patched certificate) and again at
    // Application.onCreate. Each call wraps the mPM of the PackageManager it is handed; the two
    // Application contexts can be distinct ApplicationPackageManager instances with their own mPM,
    // so wrapping both widens the chance SecurityGuard reads a spoofed signature. Re-wrapping the
    // same instance is a no-op (see the Proxy.isProxyClass guard below), so this stays idempotent.
    public static void install(Context context) {
        try {
            if (context == null) return;

            final String pkg = context.getPackageName();
            final Signature origSig = new Signature(Base64.decode(ORIG_CERT_B64, Base64.DEFAULT));

            // Only wrap the app's OWN cached ApplicationPackageManager.mPM — the binder reference
            // that context.getPackageManager().getPackageInfo(...) travels through. Daraz's mtop /
            // SecurityGuard signing reads the app signature exactly that way, so this is enough to
            // hand it the original certificate.
            //
            // We deliberately do NOT swap the process-global ActivityThread.sPackageManager here.
            // That global field is used by framework-internal callers on hot cold-start paths
            // (resource loading, ContentProviders, component resolution); proxying every one of
            // those binder calls through our reflective InvocationHandler returned a tampered
            // PackageInfo to framework callers and kept the app from even opening. Scoping the hook
            // to our own PackageManager keeps start-up untouched.
            //
            // A single process can hold more than one ApplicationPackageManager: the context passed
            // in (the Application, or its base context at attachBaseContext time) and the one behind
            // getApplicationContext() can be distinct instances, each with its own mPM. SecurityGuard
            // reads through whichever Context it was handed, so wrap both. Each wrap is independent
            // and idempotent, so covering the extra instance can only widen the spoof, never break a
            // path that already worked.
            wrapPackageManager(context, pkg, origSig);
            try {
                Context appCtx = context.getApplicationContext();
                if (appCtx != null && appCtx != context) {
                    wrapPackageManager(appCtx, pkg, origSig);
                }
            } catch (Throwable t) {
                Log.w(TAG, "SPOOF appctx skipped " + t);
            }
        } catch (Throwable t) {
            Log.w(TAG, "SPOOF_ERR " + t);
        }
    }

    /**
     * Replace one Context's ApplicationPackageManager.mPM with a proxy that hands our own package
     * the original certificate. Idempotent: a PM already proxied is left alone. Best-effort — any
     * failure is logged and swallowed so it can never make the app worse than the un-spoofed build.
     */
    private static void wrapPackageManager(Context context, String pkg, Signature origSig) {
        try {
            Object apm = context.getPackageManager();
            if (apm == null) return;
            Field mPmField = findField(apm.getClass(), "mPM");
            if (mPmField == null) {
                Log.w(TAG, "SPOOF mPM field not found");
                return;
            }
            mPmField.setAccessible(true);
            Object realPm = mPmField.get(apm);
            if (realPm == null) {
                Log.w(TAG, "SPOOF no real package manager");
                return;
            }

            // Don't double-wrap if we (or someone) already proxied it.
            if (Proxy.isProxyClass(realPm.getClass())) {
                Log.i(TAG, "SPOOF already a proxy, skipping");
                return;
            }

            Class<?> ipmClass = Class.forName("android.content.pm.IPackageManager");
            Object proxy = Proxy.newProxyInstance(
                    ipmClass.getClassLoader(),
                    new Class<?>[]{ipmClass},
                    new PmHandler(realPm, pkg, origSig));

            mPmField.set(apm, proxy);

            Log.i(TAG, "SPOOF installed for " + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "SPOOF_WRAP " + t);
        }
    }

    /** mPM is declared on ApplicationPackageManager; walk up in case getClass() is a ROM subclass. */
    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    private static final class PmHandler implements InvocationHandler {
        private final Object real;
        private final String pkg;
        private final Signature sig;

        PmHandler(Object real, String pkg, Signature sig) {
            this.real = real;
            this.pkg = pkg;
            this.sig = sig;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result;
            try {
                result = method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
            try {
                if (result instanceof PackageInfo
                        && method.getName().startsWith("getPackageInfo")
                        && args != null && args.length > 0
                        && pkg.equals(args[0])) {
                    PackageInfo pi = (PackageInfo) result;
                    if (pi.signatures != null && pi.signatures.length > 0) {
                        pi.signatures = new Signature[]{sig};
                    }
                    spoofSigningInfo(pi);
                }
            } catch (Throwable t) {
                Log.w(TAG, "SPOOF_INV " + t);
            }
            return result;
        }

        /** API 28+ returns the cert through PackageInfo.signingInfo; rewrite it best-effort. */
        private void spoofSigningInfo(PackageInfo pi) {
            try {
                Field siField = PackageInfo.class.getDeclaredField("signingInfo");
                Object si = siField.get(pi);
                if (si == null) return;
                Field sdField = si.getClass().getDeclaredField("mSigningDetails");
                sdField.setAccessible(true);
                Object sd = sdField.get(si);
                if (sd == null) return;
                Signature[] one = new Signature[]{sig};
                // Field name differs by API level: PackageParser$SigningDetails.signatures (28-32)
                // vs SigningDetails.mSignatures (33+).
                if (!trySet(sd, "mSignatures", one) && !trySet(sd, "signatures", one)) {
                    Log.w(TAG, "SPOOF signingInfo field not found");
                }
                // SigningInfo.getSigningCertificateHistory() reads the past-certificate list; on an
                // app that never rotated keys it is normally a single-entry list. Rewrite it too so
                // a reader that walks the history still sees only the original cert. Absent on some
                // ROMs, so this is silent best-effort, not a warning.
                trySet(sd, "mPastSigningCertificates", one);
                trySet(sd, "pastSigningCertificates", one);
            } catch (Throwable t) {
                Log.w(TAG, "SPOOF_SIGNINGINFO " + t);
            }
        }

        private boolean trySet(Object target, String field, Object value) {
            try {
                Field f = target.getClass().getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
