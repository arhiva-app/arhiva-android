package com.arhiva.probna;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Window;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/* Trake telefona (gornja i donja) u boji pozadine teme Arhive.
   Capacitor 8 (SystemBars) odmakne stranicu od traka, pa se iza traka vidi pozadina prozora (decorView).
   Arhiva javi boju teme; ovde se ta boja postavi prozoru, a ikonice trake budu tamne na svetloj boji i svetle na tamnoj.
   Boja se pamti, pa je ista i pri sledećem pokretanju (pre nego što se Arhiva učita). SystemBars pri promeni podešavanja
   telefona vrati svoju boju — MainActivity tada ponovo zove primeni(). */
@CapacitorPlugin(name = "Trake")
public class TrakePlugin extends Plugin {
    static final String PREFS = "arhiva_trake", KLJUC = "boja";

    static void primeni(Activity a) {
        if (a == null) return;
        SharedPreferences sp = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        if (!sp.contains(KLJUC)) return;
        int boja = sp.getInt(KLJUC, Color.BLACK);
        Window w = a.getWindow();
        w.getDecorView().setBackgroundColor(boja);
        boolean svetla = (Color.red(boja) * 299 + Color.green(boja) * 587 + Color.blue(boja) * 114) / 1000 >= 128;
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.setAppearanceLightStatusBars(svetla);      // svetla pozadina → tamne ikonice
        c.setAppearanceLightNavigationBars(svetla);
    }

    @PluginMethod
    public void boja(PluginCall call) {
        int boja;
        try { boja = Color.parseColor(call.getString("boja", "")); }
        catch (Exception e) { call.reject("LOSA_BOJA"); return; }
        getActivity().getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putInt(KLJUC, boja | 0xFF000000).apply();
        getBridge().executeOnMainThread(() -> { primeni(getActivity()); call.resolve(); });
    }
}
