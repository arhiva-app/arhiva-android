package com.arhiva.probna;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/* v214 Vidžet: Arhiva šalje podatke (rokovi, izabrani dokumenti sa sličicama, tekstovi na jeziku Arhive).
   postavi({podaci}): sličice → fajlovi (filesDir/vidzet/dK.png), ostalo → SharedPreferences, pa osvežavanje vidžeta.
   stanje(): koliko vidžeta je na početnom ekranu i da li telefon ume da ga postavi na zahtev.
   naEkran(): zvanično AppWidgetManager.requestPinAppWidget (Android 8+) — telefon pita korisnika gde da ga stavi.
   prozor(): v218 korak nazad u Arhivi posle dolaska iz prozora vidžeta → ponovo prozor vidžeta (Zoran: nazad = tačno jedan
   ekran unazad). Arhiva ide iza (moveTaskToBack), pa nazad iz prozora vodi na početni ekran telefona, ne u Arhivu. */
@CapacitorPlugin(name = "Vidzet")
public class VidzetPlugin extends Plugin {

    @PluginMethod
    public void postavi(PluginCall call) {
        Context c = getContext();
        try {
            JSONObject d = new JSONObject(call.getString("podaci", "{}"));
            File dir = new File(c.getFilesDir(), "vidzet");
            if (!dir.exists()) dir.mkdirs();
            JSONArray a = d.optJSONArray("docs");
            for (int k = 0; k < 4; k++) {
                File f = ArhivaVidzet.slika(c, k);
                JSONObject o = (a != null && k < a.length()) ? a.optJSONObject(k) : null;
                String s = o == null ? null : o.optString("s", null);
                if (o != null) o.remove("s");
                if (s == null || s.isEmpty()) { if (f.exists()) f.delete(); continue; }
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(Base64.decode(s, Base64.DEFAULT)); }
            }
            c.getSharedPreferences(ArhivaVidzet.PREFS, Context.MODE_PRIVATE).edit().putString(ArhivaVidzet.KLJUC, d.toString()).apply();
            ArhivaVidzet.osvezi(c);
            JSObject r = new JSObject();
            r.put("broj", ArhivaVidzet.ids(c).length);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("VIDZET: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stanje(PluginCall call) {
        Context c = getContext();
        JSObject r = new JSObject();
        r.put("broj", ArhivaVidzet.ids(c).length);
        boolean moze = false;
        if (Build.VERSION.SDK_INT >= 26) {
            try { moze = AppWidgetManager.getInstance(c).isRequestPinAppWidgetSupported(); } catch (Exception e) { moze = false; }
        }
        r.put("moze", moze);
        r.put("proizvodjac", Build.MANUFACTURER);
        call.resolve(r);
    }

    @PluginMethod
    public void naEkran(PluginCall call) {
        Context c = getContext();
        JSObject r = new JSObject();
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                AppWidgetManager m = AppWidgetManager.getInstance(c);
                if (m.isRequestPinAppWidgetSupported()) ok = m.requestPinAppWidget(new ComponentName(c, ArhivaVidzet.class), null, null);
            } catch (Exception e) { ok = false; }
        }
        r.put("ok", ok);
        call.resolve(r);
    }

    @PluginMethod
    public void prozor(PluginCall call) {
        Activity a = getActivity();
        try {
            Intent i = new Intent(getContext(), VidzetEkran.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            getContext().startActivity(i);
            if (a != null) a.runOnUiThread(() -> a.moveTaskToBack(true));
            call.resolve();
        } catch (Exception e) {
            call.reject("VIDZET: " + e.getMessage());
        }
    }
}
