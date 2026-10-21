package com.arhiva.probna;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Base64;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/* v196 Prečice (zvanično: androidx ShortcutManagerCompat).
   1) napravi: prava prečica na početnom ekranu (requestPinShortcut) — naziv i sličica stavke; dodir otvara Arhivu
      sa dodatkom EXTRA = id stavke. Arhiva je otvara tek posle otključavanja (logika je u Arhivi, blok PREČICA).
   2) poslednji: dinamičke prečice (dugi pritisak na ikonicu Arhive) — poslednje otvorene stavke; prazan spisak ih briše.
   3) cekaj: id iz prečice kojom je Arhiva otvorena (jednom; posle se briše). Dok Arhiva radi, nova prečica javlja „stavka“. */
@CapacitorPlugin(name = "Precica")
public class PrecicaPlugin extends Plugin {
    static final String EXTRA = "arhiva_stavka";
    private String naCekanju;

    @Override
    public void load() {
        uzmi(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (uzmi(intent)) notifyListeners("stavka", new JSObject());
    }

    private boolean uzmi(Intent i) {
        if (i == null) return false;
        String id = i.getStringExtra(EXTRA);
        if (id == null || id.isEmpty()) return false;
        i.removeExtra(EXTRA);   // da se ista stavka ne otvori ponovo (npr. kad se aktivnost ponovo napravi)
        naCekanju = id;
        return true;
    }

    @PluginMethod
    public void cekaj(PluginCall call) {
        JSObject r = new JSObject();
        if (naCekanju != null) r.put("id", naCekanju);
        naCekanju = null;
        call.resolve(r);
    }

    @PluginMethod
    public void podrzano(PluginCall call) {
        JSObject r = new JSObject();
        r.put("podrzano", ShortcutManagerCompat.isRequestPinShortcutSupported(getContext()));
        r.put("proizvodjac", Build.MANUFACTURER);
        call.resolve(r);
    }

    @PluginMethod
    public void napravi(PluginCall call) {
        Context c = getContext();
        String id = call.getString("id");
        if (id == null || id.isEmpty()) { call.reject("NEMA_ID"); return; }
        JSObject r = new JSObject();
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(c)) { r.put("podrzano", false); call.resolve(r); return; }
        try {
            boolean ok = ShortcutManagerCompat.requestPinShortcut(c,
                gradi(c, "s:" + id, id, call.getString("naziv", ""), call.getString("slika"), 0), null);
            r.put("podrzano", true);
            r.put("ok", ok);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("PRECICA: " + e.getMessage());
        }
    }

    @PluginMethod
    public void poslednji(PluginCall call) {
        Context c = getContext();
        try {
            JSArray lista = call.getArray("lista", new JSArray());
            int max = Math.min(4, ShortcutManagerCompat.getMaxShortcutCountPerActivity(c));
            List<ShortcutInfoCompat> sve = new ArrayList<>();
            for (int k = 0; k < lista.length() && sve.size() < max; k++) {
                JSONObject o = lista.getJSONObject(k);
                String id = o.optString("id", "");
                if (id.isEmpty()) continue;
                sve.add(gradi(c, "p:" + id, id, o.optString("naziv", ""), o.optString("slika", null), k));
            }
            if (sve.isEmpty()) ShortcutManagerCompat.removeAllDynamicShortcuts(c);
            else ShortcutManagerCompat.setDynamicShortcuts(c, sve);
            call.resolve();
        } catch (Exception e) {
            call.reject("POSLEDNJI: " + e.getMessage());
        }
    }

    private static ShortcutInfoCompat gradi(Context c, String kljuc, String id, String naziv, String slika, int rang) {
        Intent i = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW).putExtra(EXTRA, id);
        IconCompat ik = null;
        if (slika != null && !slika.isEmpty()) {
            try {
                byte[] b = Base64.decode(slika, Base64.DEFAULT);
                Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                if (bm != null) ik = IconCompat.createWithBitmap(bm);
            } catch (Exception e) { /* bez sličice → ikonica Arhive */ }
        }
        if (ik == null) ik = IconCompat.createWithResource(c, R.mipmap.ic_launcher);
        String n = (naziv == null || naziv.trim().isEmpty()) ? "Arhiva" : naziv.trim();
        return new ShortcutInfoCompat.Builder(c, kljuc)
            .setShortLabel(n.length() > 25 ? n.substring(0, 25) : n)
            .setLongLabel(n.length() > 60 ? n.substring(0, 60) : n)
            .setIcon(ik)
            .setIntent(i)
            .setRank(rang)
            .build();
    }
}
