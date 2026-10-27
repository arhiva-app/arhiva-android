package com.arhiva.probna;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.Window;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
    static final String PREFS = "arhiva_trake", KLJUC = "boja", GORE = "gore";

    /* v237 (Zoran, tema Kožni rokovnik): gornja traka može imati svoju boju (zelena koža), donja ostaje boja pozadine.
       Pozadina prozora se crta u dva dela: visina gornje trake (zvanično WindowInsetsCompat.Type.statusBars) u boji „gore“,
       ostalo u boji „boja“. Visina se čita pri svakom crtanju, pa važi i posle okretanja telefona. */
    static class DveBoje extends Drawable {
        final View v; final Paint gore = new Paint(), dole = new Paint();
        DveBoje(View v, int g, int d) { this.v = v; gore.setColor(g); dole.setColor(d); }
        @Override public void draw(Canvas c) {
            int top = 0;
            WindowInsetsCompat in = ViewCompat.getRootWindowInsets(v);
            if (in != null) top = in.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            android.graphics.Rect b = getBounds();
            c.drawRect(b.left, b.top, b.right, b.top + top, gore);
            c.drawRect(b.left, b.top + top, b.right, b.bottom, dole);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter f) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    static boolean svetla(int boja) {
        return (Color.red(boja) * 299 + Color.green(boja) * 587 + Color.blue(boja) * 114) / 1000 >= 128;
    }

    static void primeni(Activity a) {
        if (a == null) return;
        SharedPreferences sp = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        if (!sp.contains(KLJUC)) return;
        int boja = sp.getInt(KLJUC, Color.BLACK);
        int gore = sp.getInt(GORE, boja);
        Window w = a.getWindow();
        View d = w.getDecorView();
        if (gore == boja) d.setBackgroundColor(boja); else d.setBackground(new DveBoje(d, gore, boja));
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, d);
        c.setAppearanceLightStatusBars(svetla(gore));      // svetla pozadina → tamne ikonice
        c.setAppearanceLightNavigationBars(svetla(boja));
    }

    @PluginMethod
    public void boja(PluginCall call) {
        int boja, gore;
        try {
            boja = Color.parseColor(call.getString("boja", "")) | 0xFF000000;
            String g = call.getString("gore", "");
            gore = (g == null || g.isEmpty()) ? boja : (Color.parseColor(g) | 0xFF000000);
        } catch (Exception e) { call.reject("LOSA_BOJA"); return; }
        getActivity().getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putInt(KLJUC, boja).putInt(GORE, gore).apply();
        getBridge().executeOnMainThread(() -> { primeni(getActivity()); call.resolve(); });
    }
}
