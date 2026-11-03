package com.arhiva.probna;

import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import androidx.activity.result.IntentSenderRequest;
import android.os.Bundle;
import com.getcapacitor.JSArray;
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

    /* PROBA DELJENJA (privremeno, 3.11.2026): zaseban Google prozor sa izborom naloga (uvek pita), po želji sa Google-ovim
       biračem fajlova/fascikli (zvanično: developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker —
       samo drive.file, setOptOutIncludingGrantedScopes(true), Prompt CONSENT|SELECT_ACCOUNT, PICKER_OAUTH_TRIGGER;
       izabrani ID-jevi u getTokenResponseParams()["picked_file_ids"]). Ne dira dozvolu za slanje na Drive.
       proba({birac}) → {ok, token, ids:[…]} | {ok:false, razlog, poruka} */
    @PluginMethod
    public void proba(PluginCall call) {
        final boolean birac = Boolean.TRUE.equals(call.getBoolean("birac", false));
        AuthorizationRequest.Builder b = AuthorizationRequest.builder()
            .setRequestedScopes(opseg())
            .setOptOutIncludingGrantedScopes(true)
            .setPrompt(AuthorizationRequest.Prompt.CONSENT | AuthorizationRequest.Prompt.SELECT_ACCOUNT);
        if (birac) {
            b.addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
             .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_FOLDER_SELECTION, "true")
             .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_MULTIPLE, "true");
        }
        try {
            Identity.getAuthorizationClient(getActivity()).authorize(b.build())
                .addOnSuccessListener(r -> {
                    if (!r.hasResolution()) { token(call, r); return; }
                    PendingIntent pi = r.getPendingIntent();
                    if (pi == null || !(getActivity() instanceof MainActivity)) { odbij(call, "greska", "nema prozora"); return; }
                    if (cekam != null) odbij(cekam, "otkazano", null);
                    cekam = call;
                    ((MainActivity) getActivity()).googleDozvola(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
                })
                .addOnFailureListener(e -> odbij(call, "greska", e.getMessage()));
        } catch (Exception e) {
            odbij(call, "greska", e.getMessage());
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
        /* proba deljenja: ID-jevi izabrani u Google-ovom biraču (zarezom odvojeni; vrednost može biti i niz) */
        JSArray ids = new JSArray();
        try {
            Bundle pp = r.getTokenResponseParams();
            Object raw = pp == null ? null : pp.get("picked_file_ids");
            if (raw instanceof String) { for (String x : ((String) raw).split(",")) if (!x.trim().isEmpty()) ids.put(x.trim()); }
            else if (raw instanceof String[]) { for (String x : (String[]) raw) if (x != null && !x.trim().isEmpty()) ids.put(x.trim()); }
            else if (raw instanceof java.util.Collection) { for (Object x : (java.util.Collection<?>) raw) if (x != null) ids.put(String.valueOf(x).trim()); }
            else if (raw != null) ids.put(String.valueOf(raw));
        } catch (Exception e) { /* stariji Google Play servisi: bez ID-jeva */ }
        o.put("ids", ids);
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
