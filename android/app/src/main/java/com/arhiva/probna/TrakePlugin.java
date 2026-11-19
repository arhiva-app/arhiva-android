package com.arhiva.probna;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import android.view.View;
import android.view.Window;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.WebViewListener;
import java.util.Locale;
import com.getcapacitor.annotation.CapacitorPlugin;

/* Trake telefona: gornja u boji pozadine teme, donja providna (v333).
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

    /* v333 (B2, Zoran 1.11./19.11.): donja traka telefona PROVIDNA — stranica ide do dna ekrana, iza dugmića telefona;
       gornja ostaje kao do sada (stranica počinje ispod sata i baterije). Capacitor SystemBars je isključen
       (capacitor.config.json: SystemBars.insetsHandling = "disable"), pa trake ovde radi Arhiva sama, po zvaničnom
       WindowInsetsCompat: prozor od ivice do ivice (WindowCompat.setDecorFitsSystemWindows false), decorView se odmakne
       gore/levo/desno za trake, a dole samo za tastaturu. Visina donje trake (dp) ide u CSS promenljivu --sab, kojom se
       u stranici odmiče sve što stoji pri dnu. Vraćaju se umetci 0 (ne CONSUMED — Capacitor: chromium 461332423). */
    private int dnoDp = 0;

    @Override
    public void load() {
        Activity a = getActivity();
        Window w = a.getWindow();
        WindowCompat.setDecorFitsSystemWindows(w, false);
        if (Build.VERSION.SDK_INT < 35) {                                   // od Androida 15 su trake same providne
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);   // tri dugmeta: bez sive podloge
        View d = w.getDecorView();
        ViewCompat.setOnApplyWindowInsetsListener(d, (v, in) -> {
            Insets s = in.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            boolean tastatura = in.isVisible(WindowInsetsCompat.Type.ime());
            int ime = in.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(s.left, s.top, s.right, tastatura ? ime : 0);
            float gust = a.getResources().getDisplayMetrics().density;
            dnoDp = tastatura ? 0 : Math.round(s.bottom / gust);
            upisiDno();
            return new WindowInsetsCompat.Builder(in)
                .setInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 0, 0, 0))
                .build();
        });
        getBridge().addWebViewListener(new WebViewListener() {
            @Override public void onPageLoaded(WebView webView) { upisiDno(); }   // posle svakog učitavanja stranice
        });
        ViewCompat.requestApplyInsets(d);
    }

    private void upisiDno() {
        final String js = String.format(Locale.US,
            "try{document.documentElement.style.setProperty('--sab','%dpx')}catch(e){}", dnoDp);
        getBridge().executeOnMainThread(() -> {
            if (getBridge() != null && getBridge().getWebView() != null) getBridge().getWebView().evaluateJavascript(js, null);
        });
    }

    @PluginMethod
    public void dno(PluginCall call) {   // stranica pita pri pokretanju (ako je upis stigao pre nje)
        com.getcapacitor.JSObject r = new com.getcapacitor.JSObject(); r.put("dp", dnoDp); call.resolve(r);
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
