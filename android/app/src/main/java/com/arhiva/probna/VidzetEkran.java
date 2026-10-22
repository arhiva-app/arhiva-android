package com.arhiva.probna;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/* v216 (Zoran): dodir na vidžet otvara ovaj prozor preko celog ekrana — i dalje „u vidžetu“, samo za gledanje:
   izabrani podsetnici (kako je izabrano u Arhivi: Svi · Izabrani · Nijedan) i izabrani dokumenti. Podaci su isti kao za vidžet
   (SharedPreferences + sličice), pa se ništa ne učitava iz Arhive. Bez PIN-a (zaključan telefon je zaštita).
   Dodir na podsetnik/dokument → Arhiva otvara taj dokument samo za gledanje (Precica, vrsta „vidzet“).
   „Uredi ›“ → Arhiva (PIN ako je zaključana) na ekranu „Vidžet“, gde se sve menja (vrsta „vidzet-uredi“).
   v219: prozor ostaje živ dok je korisnik u Arhivi (ne gasi se pri otvaranju dokumenta / Uredi); nazad u Arhivi je samo
   skloni (Vidzet.prozor), pa se odmah vidi ovaj isti prozor, osvežen (onResume). Nazad iz prozora → početni ekran. */
public class VidzetEkran extends Activity {
    private static final int POZADINA = 0xFF000000, KARTICA = 0xFF1E2024, LINIJA = 0xFF34363C,
        TEKST = 0xFFE9E8E4, SIVO = 0xFF9A9A9F, ZLATNA = 0xFFD9BE85;
    private float gustina;
    private LinearLayout kolona;
    private ScrollView sv;
    private boolean prvi = true;
    private static int zivih = 0;   // v219: broj živih prozora (nov se pravi pre nego što se stari ugasi)
    static synchronized boolean ziv() { return zivih > 0; }
    private static synchronized void broj(int k) { zivih += k; }

    private int dp(float v) { return Math.round(v * gustina); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        gustina = getResources().getDisplayMetrics().density;
        broj(1);
        sv = new ScrollView(this);
        sv.setBackgroundColor(POZADINA);
        sv.setFillViewport(true);
        kolona = new LinearLayout(this);
        kolona.setOrientation(LinearLayout.VERTICAL);
        kolona.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(kolona, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        nacrtaj();

        // Android 15+: prozor ide do ivica ekrana — sadržaj se pomera ispod statusne i donje trake
        sv.setOnApplyWindowInsetsListener((v, in) -> {
            int gore, dole;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars());
                gore = i.top; dole = i.bottom;
            } else {
                gore = in.getSystemWindowInsetTop(); dole = in.getSystemWindowInsetBottom();
            }
            kolona.setPadding(dp(16), gore + dp(12), dp(16), dole + dp(24));
            return in;
        });
        setContentView(sv);
    }

    /* sadržaj iz podataka vidžeta (isti koje Arhiva šalje vidžetu) */
    private void nacrtaj() {
        kolona.removeAllViews();
        JSONObject d = ArhivaVidzet.podaci(this);
        zaglavlje(d);
        if (!ArhivaVidzet.bezPodsetnika(d)) podsetnici(d);
        dokumenti(d);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prvi) { prvi = false; return; }
        nacrtaj();   // v219: povratak iz Arhive — izmene iz „Uredi“ odmah vidljive
    }

    @Override
    protected void onDestroy() {
        broj(-1);
        super.onDestroy();
    }

    private GradientDrawable okvir(int boja, int ivica, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(boja);
        if (ivica != 0) g.setStroke(dp(1), ivica);
        g.setCornerRadius(dp(r));
        return g;
    }

    private TextView tekst(String s, float sp, int boja, boolean debelo) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(boja);
        if (debelo) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void zaglavlje(JSONObject d) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        r.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView ime = tekst(ArhivaVidzet.txt(d, "title", "Arhiva"), 22, TEKST, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(10);
        r.addView(ime, lp);
        TextView uredi = tekst(ArhivaVidzet.txt(d, "edit", "Uredi ›"), 16, TEKST, false);
        uredi.setBackground(okvir(KARTICA, 0xFF4A4D55, 12));
        uredi.setPadding(dp(14), dp(9), dp(14), dp(9));
        uredi.setOnClickListener(v -> otvori("-", "vidzet-uredi"));
        r.addView(uredi);
        kolona.addView(r);
    }

    private void naslov(String s) {
        TextView t = tekst(s.toUpperCase(), 12, SIVO, true);
        t.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24); lp.bottomMargin = dp(8);
        kolona.addView(t, lp);
    }

    private void prazno(String s) {
        kolona.addView(tekst(s, 15, SIVO, false));
    }

    private void podsetnici(JSONObject d) {
        naslov(ArhivaVidzet.txt(d, "rokovi", "Podsetnici"));
        JSONArray a = ArhivaVidzet.rokovi(d);
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setBackground(okvir(KARTICA, LINIJA, 16));
        int redova = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            int n = ArhivaVidzet.dana(o.optString("d"));
            if (n == Integer.MAX_VALUE) continue;
            if (redova > 0) {
                View l = new View(this);
                l.setBackgroundColor(LINIJA);
                k.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f))));
            }
            k.addView(red(d, o, n));
            redova++;
        }
        if (redova == 0) { prazno(ArhivaVidzet.txt(d, "none", "Nema podsetnika.")); return; }
        kolona.addView(k);
    }

    private View red(JSONObject d, JSONObject o, int n) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout levo = new LinearLayout(this);
        levo.setOrientation(LinearLayout.VERTICAL);
        TextView ime = tekst(o.optString("n"), 17, TEKST, true);
        ime.setMaxLines(2); ime.setEllipsize(TextUtils.TruncateAt.END);
        levo.addView(ime);
        String nt = o.optString("nt", "");
        if (!nt.isEmpty()) {
            TextView b = tekst("✎ " + nt, 14, SIVO, false);
            b.setMaxLines(3); b.setEllipsize(TextUtils.TruncateAt.END);
            levo.addView(b);
        }
        r.addView(levo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout desno = new LinearLayout(this);
        desno.setOrientation(LinearLayout.VERTICAL);
        desno.setGravity(Gravity.END);
        desno.setPadding(dp(10), 0, 0, 0);
        int upoz = o.optInt("w", 7);
        desno.addView(tekst(ArhivaVidzet.kratko(d, n), 15, ArhivaVidzet.boja(n, upoz), true));
        desno.addView(tekst(o.optString("f"), 14, SIVO, false));
        r.addView(desno);
        final String id = o.optString("id", "-");
        r.setOnClickListener(v -> otvori(id, "vidzet"));
        return r;
    }

    private void dokumenti(JSONObject d) {
        naslov(ArhivaVidzet.txt(d, "dokumenti", "Izabrani dokumenti"));
        JSONArray a = d.optJSONArray("docs");
        int n = a == null ? 0 : Math.min(a.length(), 4);
        if (n == 0) { prazno(ArhivaVidzet.txt(d, "noDocs", "Dokument dodaješ u Arhivi: Prečica → Na vidžetu.")); return; }
        LinearLayout red = null;
        for (int k = 0; k < n; k++) {
            if (k % 2 == 0) {
                red = new LinearLayout(this);
                red.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (k > 0) lp.topMargin = dp(14);
                kolona.addView(red, lp);
            }
            JSONObject o = a.optJSONObject(k);
            red.addView(polje(o, k), poljeLp(k % 2 == 1));
        }
        if (n % 2 == 1) {   // neparan broj: prazno mesto da sličica ne bude preko cele širine
            red.addView(new View(this), poljeLp(true));
        }
    }

    private LinearLayout.LayoutParams poljeLp(boolean desno) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (desno) lp.leftMargin = dp(12);
        return lp;
    }

    private View polje(JSONObject o, int k) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView s = new ImageView(this);
        s.setScaleType(ImageView.ScaleType.CENTER_CROP);
        s.setBackground(okvir(KARTICA, LINIJA, 14));
        s.setClipToOutline(true);
        s.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline out) { out.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); }
        });
        Bitmap b = null;
        try { File f = ArhivaVidzet.slika(this, k); if (f.exists()) b = BitmapFactory.decodeFile(f.getAbsolutePath()); } catch (Exception e) { b = null; }
        if (b != null) s.setImageBitmap(b);
        else { s.setImageResource(R.mipmap.ic_launcher); s.setScaleType(ImageView.ScaleType.CENTER_INSIDE); }
        p.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        TextView ime = tekst(o == null ? "" : o.optString("n"), 15, TEKST, false);
        ime.setGravity(Gravity.CENTER_HORIZONTAL);
        ime.setMaxLines(2); ime.setEllipsize(TextUtils.TruncateAt.END);
        ime.setPadding(0, dp(6), 0, 0);
        p.addView(ime);
        final String id = o == null ? "-" : o.optString("id", "-");
        p.setOnClickListener(v -> otvori(id, "vidzet"));
        return p;
    }

    private void otvori(String id, String vrsta) {
        Intent i = new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW)
            .putExtra(PrecicaPlugin.EXTRA, id).putExtra(PrecicaPlugin.VRSTA, vrsta)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);   // v219: bez finish() — prozor čeka ispod Arhive
    }
}
