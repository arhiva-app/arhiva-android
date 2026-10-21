package com.arhiva.probna;

import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import androidx.activity.result.IntentSenderRequest;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import java.util.Collections;
import java.util.List;

/* Google Drive (v203): samo dozvola. Slanje fajlova radi web Arhiva (blok GOOGLE DRIVE) preko Drive REST API-ja.
   Zvanično (developer.android.com/identity/authorization): AuthorizationClient.authorize(); ako treba pristanak korisnika,
   rezultat ima PendingIntent (Google-ov prozor: izbor naloga, „Dozvoli“), inače odmah daje token.
   - dozvola({interaktivno}): {ok:true, token} | {ok:false, razlog:'potrebna'|'otkazano'|'greska', poruka}
     interaktivno=false nikad ne otvara prozor (slanje u pozadini): treba pristanak → 'potrebna'.
   - prekini({nalog}): revokeAccess — sledeće povezivanje opet traži izbor naloga i dozvolu.
   Pristup drive.file: samo fajlovi koje je Arhiva napravila. */
@CapacitorPlugin(name = "GoogleDrive")
public class GoogleDrivePlugin extends Plugin {
    static final String DRIVE_FILE = "https://www.googleapis.com/auth/drive.file";
    private PluginCall cekam;

    private static List<Scope> opseg() { return Collections.singletonList(new Scope(DRIVE_FILE)); }

    @PluginMethod
    public void dozvola(PluginCall call) {
        final boolean interaktivno = Boolean.TRUE.equals(call.getBoolean("interaktivno", false));
        AuthorizationRequest zahtev = AuthorizationRequest.builder().setRequestedScopes(opseg()).build();
        Identity.getAuthorizationClient(getActivity()).authorize(zahtev)
            .addOnSuccessListener(r -> {
                if (!r.hasResolution()) { token(call, r); return; }
                if (!interaktivno) { odbij(call, "potrebna", null); return; }
                PendingIntent pi = r.getPendingIntent();
                if (pi == null || !(getActivity() instanceof MainActivity)) { odbij(call, "greska", "nema prozora"); return; }
                if (cekam != null) odbij(cekam, "otkazano", null);
                cekam = call;
                ((MainActivity) getActivity()).googleDozvola(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
            })
            .addOnFailureListener(e -> odbij(call, "greska", e.getMessage()));
    }

    /* MainActivity javlja ishod Google-ovog prozora */
    void rezultat(int kod, Intent data) {
        PluginCall c = cekam;
        cekam = null;
        if (c == null) return;
        if (kod != Activity.RESULT_OK || data == null) { odbij(c, "otkazano", null); return; }
        try {
            token(c, Identity.getAuthorizationClient(getActivity()).getAuthorizationResultFromIntent(data));
        } catch (ApiException e) {
            odbij(c, "greska", e.getMessage());
        }
    }

    @PluginMethod
    public void prekini(PluginCall call) {
        String nalog = call.getString("nalog", "");
        if (nalog == null || nalog.isEmpty()) { call.resolve(); return; }
        try {
            RevokeAccessRequest zahtev = RevokeAccessRequest.builder()
                .setAccount(new Account(nalog, "com.google"))
                .setScopes(opseg())
                .build();
            Identity.getAuthorizationClient(getActivity()).revokeAccess(zahtev)
                .addOnCompleteListener(t -> call.resolve());
        } catch (Exception e) {
            call.resolve();
        }
    }

    private void token(PluginCall c, AuthorizationResult r) {
        String t = r == null ? null : r.getAccessToken();
        if (t == null || t.isEmpty()) { odbij(c, "greska", "nema tokena"); return; }
        JSObject o = new JSObject();
        o.put("ok", true);
        o.put("token", t);
        c.resolve(o);
    }

    private void odbij(PluginCall c, String razlog, String poruka) {
        JSObject o = new JSObject();
        o.put("ok", false);
        o.put("razlog", razlog);
        if (poruka != null) o.put("poruka", poruka);
        c.resolve(o);
    }
}
