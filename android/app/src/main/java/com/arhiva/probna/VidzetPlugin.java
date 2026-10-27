package com.arhiva.probna;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.app.Activity;
import android.content.Context;
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
   skloni(): v221 — Arhiva u pozadinu (prozor vidžeta je sada ekran u Arhivi; poseban Androidov prozor VidzetEkran je ukinut). */
@CapacitorPlugin(name = "Vidzet")
public class VidzetPlugin extends Plugin {

    @PluginMethod
    public void postavi(PluginCall call) {
        Context c = getContext();
        try {
            JSONObject d = new JSONObject(call.getString("podaci", "{}"));
            File dir = new File(c.getFilesDir(), "vidzet");
            if (!dir.exists()) dir.mkdirs();
            /* v234: sličice — zajednički vidžet (docs → dK.png) i svaki vidžet posebno (vidzeti.<id>.docs → w<id>_K.png);
               fajl koji ovaj put nije upisan se briše (dokument skinut, vidžet sklonjen) */
            java.util.HashSet<String> upisano = new java.util.HashSet<>();
            upisi(c, d.optJSONArray("docs"), "d", upisano);
            JSONObject ws = d.optJSONObject("vidzeti");
            if (ws != null) {
                java.util.Iterator<String> it = ws.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    JSONObject w = ws.optJSONObject(id);
                    if (w != null) upisi(c, w.optJSONArray("docs"), "w" + id + "_", upisano);
                }
            }
            File[] sve = dir.listFiles();
            if (sve != null) for (File f : sve) if (!upisano.contains(f.getName())) f.delete();
            c.getSharedPreferences(ArhivaVidzet.PREFS, Context.MODE_PRIVATE).edit().putString(ArhivaVidzet.KLJUC, d.toString()).apply();
            ArhivaVidzet.osvezi(c);
            JSObject r = new JSObject();
            r.put("broj", ArhivaVidzet.ids(c).length);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("VIDZET: " + e.getMessage());
        }
    }

    private static void upisi(Context c, JSONArray a, String pref, java.util.Set<String> upisano) throws Exception {
        if (a == null) return;
        for (int k = 0; k < 4 && k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            String s = o == null ? null : o.optString("s", null);
            if (o != null) o.remove("s");
            if (s == null || s.isEmpty()) continue;
            File f = ArhivaVidzet.slika(c, pref, k);
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(Base64.decode(s, Base64.DEFAULT)); }
            upisano.add(f.getName());
        }
    }

    @PluginMethod
    public void stanje(PluginCall call) {
        Context c = getContext();
        JSObject r = new JSObject();
        int[] ids = ArhivaVidzet.ids(c);
        r.put("broj", ids.length);
        JSONArray lista = new JSONArray();   // v234: koji vidžeti su na početnom ekranu (svaki ima svoje podatke u Arhivi)
        for (int id : ids) lista.put(String.valueOf(id));
        r.put("ids", lista);
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

    /* v221: korak nazad sa prozora vidžeta (ili iz dokumenta otvorenog sa vidžeta) → Arhiva ide u pozadinu, ostaje kakva je
       (zvanično Activity.moveTaskToBack); na početnom ekranu telefona je opet vidžet. */
    @PluginMethod
    public void skloni(PluginCall call) {
        Activity a = getActivity();
        if (a == null) { call.reject("VIDZET: nema aktivnosti"); return; }
        a.runOnUiThread(() -> { a.moveTaskToBack(true); call.resolve(); });
    }
}
