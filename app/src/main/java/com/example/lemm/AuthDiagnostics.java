package com.example.lemm;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;

/**
 * Answers the only question that matters when Google Sign-In returns DEVELOPER_ERROR (status 10).
 *
 * <p>Error 10 means: <i>"Google has no OAuth client registered for the (package name, signing
 * certificate) pair that this APK was actually signed with."</i> Everyone's first instinct is to add
 * the SHA-1 to Firebase again — and again — but that is useless if the fingerprint you are pasting is
 * not the fingerprint of the APK that is actually running on the phone.
 *
 * <p>They differ more often than people expect:
 * <ul>
 *   <li>the APK on the phone was built on a <b>different computer</b> (a different {@code
 *       ~/.android/debug.keystore} → a different SHA-1);</li>
 *   <li>{@code debug.keystore} was regenerated (deleting it makes a brand-new key silently);</li>
 *   <li>the build is signed with a <b>release</b> key whose SHA-1 was never registered;</li>
 *   <li>the app was installed from Play, which <b>re-signs</b> with the Play App Signing key.</li>
 * </ul>
 *
 * <p>So this reads the certificate of the <b>installed, running</b> app and prints its SHA-1. That
 * string is the ground truth. If it is not in the Firebase console, sign-in cannot work — no matter
 * how many times the "correct" one was pasted in.
 */
final class AuthDiagnostics {

    private AuthDiagnostics() {}

    /** SHA-1 of the certificate this running APK is signed with, formatted like the Firebase console. */
    static String signingSha1(Context ctx) {
        try {
            Signature[] sigs;
            PackageManager pm = ctx.getPackageManager();
            String pkg = ctx.getPackageName();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                @SuppressWarnings("deprecation")
                Signature[] legacy = info.signatures;
                sigs = legacy;
            }
            if (sigs == null || sigs.length == 0) return "(none)";

            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(sigs[0].toByteArray());

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "(unavailable: " + e.getMessage() + ")";
        }
    }

    /**
     * The three facts Google checks, as the phone actually sees them. Paste this into the Firebase
     * console and compare — whichever line does not match is the bug.
     */
    static String report(Context ctx) {
        String webClientId = ctx.getString(R.string.default_web_client_id);
        return "Package:\n  " + ctx.getPackageName()
                + "\n\nSHA-1 of the APK actually running:\n  " + signingSha1(ctx)
                + "\n\nWeb client ID in use:\n  " + webClientId
                + "\n\nThe SHA-1 above must be listed in Firebase console → Project settings →"
                + " your Android app → SHA certificate fingerprints. If it is not there, that is"
                + " the bug, and no other change will fix it.";
    }
}
