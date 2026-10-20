package com.arhiva.probna;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/* Zaključavanje Arhive. Logika (PIN, šablon, kada se zaključava) je u web Arhivi; ovde samo ono što zna telefon:
   - stanje(): {biometrija: ima li otisak/lice, telefon: ima li telefon zaključavanje ekrana}
   - biometrija({naslov, podnaslov, dugme}): Androidov prozor za otisak ili lice; dugme ("Unesi PIN") vodi na PIN Arhive.
     Vraća {ok:true} ili {ok:false, razlog:'dugme'|'otkazano'|'nema'|'greska', kod}.
   - potvrdiTelefonom({naslov}): otisak, lice ili PIN/šablon TELEFONA (za zaboravljen PIN Arhive). {ok}
   - ekran(): {ugasen: vreme poslednjeg gašenja ekrana (ms)}; uz to događaj "ekranUgasen".
   Klase biometrije (zvanična dokumentacija androidx.biometric): BIOMETRIC_WEAK obuhvata i otključavanje licem kamerom;
   BIOMETRIC_WEAK | DEVICE_CREDENTIAL radi na svim verzijama (STRONG | DEVICE_CREDENTIAL ne radi na API 28-29). */
@CapacitorPlugin(name = "Zakljucavanje")
public class ZakljucavanjePlugin extends Plugin {
    private static volatile long ugasen = 0;
    private BroadcastReceiver prijemnik;

    @Override
    public void load() {
        prijemnik = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                ugasen = System.currentTimeMillis();
                notifyListeners("ekranUgasen", new JSObject(), true);
            }
        };
        // samo sistemsko obaveštenje: Android 14+ tada ne traži oznaku EXPORTED/NOT_EXPORTED
        getContext().getApplicationContext().registerReceiver(prijemnik, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override
    protected void handleOnDestroy() {
        try { if (prijemnik != null) getContext().getApplicationContext().unregisterReceiver(prijemnik); } catch (Exception ignored) {}
        prijemnik = null;
    }

    @PluginMethod
    public void stanje(PluginCall call) {
        JSObject r = new JSObject();
        BiometricManager bm = BiometricManager.from(getContext());
        r.put("biometrija", bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS);
        KeyguardManager km = (KeyguardManager) getContext().getSystemService(Context.KEYGUARD_SERVICE);
        r.put("telefon", km != null && km.isDeviceSecure());
        call.resolve(r);
    }

    @PluginMethod
    public void ekran(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ugasen", ugasen);
        call.resolve(r);
    }

    @PluginMethod
    public void biometrija(PluginCall call) {
        BiometricPrompt.PromptInfo.Builder b = new BiometricPrompt.PromptInfo.Builder()
            .setTitle(call.getString("naslov", "Arhiva"))
            .setNegativeButtonText(call.getString("dugme", "PIN"))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setConfirmationRequired(false);
        String pod = call.getString("podnaslov");
        if (pod != null && !pod.isEmpty()) b.setSubtitle(pod);
        prikazi(call, b.build());
    }

    @PluginMethod
    public void potvrdiTelefonom(PluginCall call) {
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
            .setTitle(call.getString("naslov", "Arhiva"))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build();
        prikazi(call, info);
    }

    private void prikazi(PluginCall call, BiometricPrompt.PromptInfo info) {
        getActivity().runOnUiThread(() -> {
            try {
                FragmentActivity a = (FragmentActivity) getActivity();
                BiometricPrompt bp = new BiometricPrompt(a, ContextCompat.getMainExecutor(getContext()), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        JSObject r = new JSObject();
                        r.put("ok", true);
                        call.resolve(r);
                    }

                    @Override
                    public void onAuthenticationError(int kod, @NonNull CharSequence poruka) {
                        String razlog;
                        switch (kod) {
                            case BiometricPrompt.ERROR_NEGATIVE_BUTTON: razlog = "dugme"; break;
                            case BiometricPrompt.ERROR_USER_CANCELED:
                            case BiometricPrompt.ERROR_CANCELED: razlog = "otkazano"; break;
                            case BiometricPrompt.ERROR_NO_BIOMETRICS:
                            case BiometricPrompt.ERROR_HW_NOT_PRESENT:
                            case BiometricPrompt.ERROR_HW_UNAVAILABLE:
                            case BiometricPrompt.ERROR_LOCKOUT:
                            case BiometricPrompt.ERROR_LOCKOUT_PERMANENT: razlog = "nema"; break;
                            default: razlog = "greska";
                        }
                        JSObject r = new JSObject();
                        r.put("ok", false);
                        r.put("razlog", razlog);
                        r.put("kod", kod);
                        r.put("poruka", poruka.toString());
                        call.resolve(r);
                    }
                    // onAuthenticationFailed: prst nije prepoznat — prozor ostaje otvoren, korisnik pokušava ponovo
                });
                bp.authenticate(info);
            } catch (Exception e) {
                JSObject r = new JSObject();
                r.put("ok", false);
                r.put("razlog", "greska");
                r.put("poruka", String.valueOf(e.getMessage()));
                call.resolve(r);
            }
        });
    }
}
