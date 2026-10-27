package com.arhiva.probna;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.io.File;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/* v214 Vidžet Arhive na početnom ekranu (zvanično: AppWidgetProvider, RemoteViews; Android 12+ prilagodljivi prikazi
   po veličini — RemoteViews(Map<SizeF, RemoteViews>)). Veličinu bira korisnik (dug pritisak, povlačenje ivica):
   mali (1 × 1): znak Arhive i broj podsetnika koji traže pažnju (isto kao zvonce u Arhivi); srednji: 3 najbliža podsetnika;
   veliki: podsetnici i do 4 izabrana dokumenta. v215: Arhiva šalje samo podsetnike koje je korisnik izabrao (Svi · Izabrani ·
   Nijedan); kod „Nijedan“ (mode) blok podsetnika se sakriva, a srednja veličina pokazuje dokumente. Podatke šalje Arhiva (dodatak Vidzet → SharedPreferences, sličice u fajlovima);
   dani do roka se računaju ovde, pri svakom osvežavanju (updatePeriodMillis = 1 h), da broj i boje važe i kad Arhiva nije otvarana.
   Dodir otvara Arhivu (dodatak Precica: EXTRA = id dokumenta ili „-“, VRSTA = „vidzet“); logika je u Arhivi, blok VIDŽET. */
public class ArhivaVidzet extends AppWidgetProvider {
    static final String PREFS = "arhiva_vidzet", KLJUC = "podaci";
    private static final int CRVENA = 0xFFE57363, ZUTA = 0xFFE9C46A, SIVA = 0xFF9A9A9F;
    private static final int[][] REDOVI = {
        {R.id.vz_r1, R.id.vz_r1_n, R.id.vz_r1_k}, {R.id.vz_r2, R.id.vz_r2_n, R.id.vz_r2_k}, {R.id.vz_r3, R.id.vz_r3_n, R.id.vz_r3_k}};
    private static final int[][] DOKUMENTI = {
        {R.id.vz_d1, R.id.vz_d1_s, R.id.vz_d1_n}, {R.id.vz_d2, R.id.vz_d2_s, R.id.vz_d2_n},
        {R.id.vz_d3, R.id.vz_d3_s, R.id.vz_d3_n}, {R.id.vz_d4, R.id.vz_d4_s, R.id.vz_d4_n}};

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) azuriraj(c, m, id);
    }

    /* v234: sklonjen vidžet — brišu se njegove sličice (zapis u Arhivi se briše kad Arhiva sledeći put pita koji vidžeti postoje) */
    @Override
    public void onDeleted(Context c, int[] ids) {
        for (int id : ids) for (int k = 0; k < 4; k++) { File f = slika(c, "w" + id + "_", k); if (f.exists()) f.delete(); }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        azuriraj(c, m, id);   // Android 11 i stariji: prikaz po novoj veličini
    }

    static int[] ids(Context c) {
        return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, ArhivaVidzet.class));
    }

    static void osvezi(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : ids(c)) azuriraj(c, m, id);
    }

    /* sličica k-tog dokumenta: „d“ = zajednički vidžet (stariji web), „w<id>_“ = vidžet <id> (v234) */
    static File slika(Context c, String pref, int k) {
        return new File(new File(c.getFilesDir(), "vidzet"), pref + k + ".png");
    }

    /* v234 (Zoran): svaki vidžet samostalan — Arhiva šalje „vidzeti“ {"<id>": {n, mode, rokovi, docs}}; ovde se pravi podatak
       za jedan vidžet u istom obliku kao ranije (ostatak koda se ne menja). Vidžet koji Arhiva još nije videla (upravo
       postavljen): „Arhiva“, svi podsetnici, bez dokumenata — Arhiva mu da ime kad se sledeći put otvori. Bez „vidzeti“
       (stariji web): jedan zajednički podatak kao do sada. */
    static JSONObject za(JSONObject d, int id) {
        JSONObject ws = d.optJSONObject("vidzeti");
        if (ws == null) return d;
        JSONObject w = ws.optJSONObject(String.valueOf(id)), o = new JSONObject();
        try {
            JSONObject l = d.optJSONObject("L");
            l = l == null ? new JSONObject() : new JSONObject(l.toString());
            o.put("lang", d.optString("lang"));
            if (w != null) {
                l.put("title", w.optString("n", "Arhiva"));
                o.put("mode", w.optString("mode", "svi"));
                JSONArray r = w.optJSONArray("rokovi"), a = w.optJSONArray("docs");
                o.put("rokovi", r == null ? new JSONArray() : r);
                o.put("docs", a == null ? new JSONArray() : a);
            } else {
                l.put("title", "Arhiva");
                o.put("mode", "svi");
                JSONArray s = d.optJSONArray("svi");
                if (s != null) o.put("rokovi", s);
                o.put("docs", new JSONArray());
            }
            o.put("L", l);
            o.put("_f", "w" + id + "_");
        } catch (Exception e) { /* ostaje ono što je upisano */ }
        return o;
    }

    /* zahtev za PendingIntent: različit po vidžetu i po mestu na njemu (isti kod = ista namera, pa bi dodir na jedan
       vidžet otvorio drugi — zvanično: PendingIntent se razlikuje po requestCode, ne po dodacima) */
    static int kod(int id, int k) {
        return (id & 0x3FFFFF) * 32 + k;
    }

    static JSONObject podaci(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try { return new JSONObject(sp.getString(KLJUC, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private static void azuriraj(Context c, AppWidgetManager m, int id) {
        JSONObject d = za(podaci(c), id);
        RemoteViews rv;
        if (Build.VERSION.SDK_INT >= 31) {
            Map<SizeF, RemoteViews> v = new HashMap<>();
            v.put(new SizeF(40f, 40f), mali(c, d, id));
            v.put(new SizeF(180f, 100f), srednji(c, d, bezPodsetnika(d), id));   // v215: bez podsetnika srednji pokazuje dokumente
            v.put(new SizeF(180f, 170f), srednji(c, d, true, id));
            rv = new RemoteViews(v);
        } else {
            Bundle o = m.getAppWidgetOptions(id);
            int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
            rv = (w >= 180 && h >= 170) ? srednji(c, d, true, id) : (w >= 180 && h >= 100) ? srednji(c, d, bezPodsetnika(d), id) : mali(c, d, id);
        }
        m.updateAppWidget(id, rv);
    }

    private static PendingIntent otvori(Context c, String id, int kod) {
        return otvori(c, id, "vidzet", kod);
    }

    private static PendingIntent otvori(Context c, String id, String vrsta, int kod) {
        Intent i = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
            .putExtra(PrecicaPlugin.EXTRA, id).putExtra(PrecicaPlugin.VRSTA, vrsta)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // različit kod = posebna namera po redu/dokumentu (namere se inače razlikuju samo po dodacima)
        return PendingIntent.getActivity(c, kod, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /* v215: korisnik je izabrao „Nijedan“ — vidžet bez podsetnika (samo dokumenti, bez broja) */
    static boolean bezPodsetnika(JSONObject d) {
        return "nijedan".equals(d.optString("mode"));
    }

    static String txt(JSONObject d, String k, String pod) {
        JSONObject l = d.optJSONObject("L");
        String s = l == null ? null : l.optString(k, null);
        return (s == null || s.isEmpty()) ? pod : s;
    }

    /* dani do roka: razlika kalendarskih dana (isto kao expDaysLeft u Arhivi) */
    static int dana(String ymd) {
        try {
            String[] p = ymd.split("-");
            Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            u.clear(); u.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
            Calendar n = Calendar.getInstance(), t = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            t.clear(); t.set(n.get(Calendar.YEAR), n.get(Calendar.MONTH), n.get(Calendar.DAY_OF_MONTH));
            return (int) Math.round((u.getTimeInMillis() - t.getTimeInMillis()) / 86400000.0);
        } catch (Exception e) { return Integer.MAX_VALUE; }
    }

    static String rec(JSONObject d, int n) {
        n = Math.abs(n);
        if ("en".equals(d.optString("lang"))) return n == 1 ? "day" : "days";
        return (n % 10 == 1 && n % 100 != 11) ? "dan" : "dana";
    }

    private static String kada(JSONObject d, int n, String f, int upoz) {
        if (n < 0) return txt(d, "gone", "prošlo") + " · " + f;
        if (n > upoz) return f;
        String s = n == 0 ? txt(d, "today", "danas") : n == 1 ? txt(d, "tomorrow", "sutra")
            : txt(d, "inN", "za {n} {d}").replace("{n}", String.valueOf(n)).replace("{d}", rec(d, n));
        return s + " · " + f;
    }

    /* v216: kratko „prošlo / danas / sutra / za N dana“ (prozor VidzetEkran: datum ide u drugi red) */
    static String kratko(JSONObject d, int n) {
        if (n < 0) return txt(d, "gone", "prošlo");
        if (n == 0) return txt(d, "today", "danas");
        if (n == 1) return txt(d, "tomorrow", "sutra");
        return txt(d, "inN", "za {n} {d}").replace("{n}", String.valueOf(n)).replace("{d}", rec(d, n));
    }

    static int boja(int n, int upoz) {
        return n < 0 ? CRVENA : n <= upoz ? ZUTA : SIVA;
    }

    /* v221 (Zoran): dodir na vidžet (ne na red ili dokument) → prozor vidžeta, sada ekran UNUTAR Arhive (vrsta „vidzet-prozor“):
       jedna aplikacija, jedan zadatak — bez bljeska, bez dve Arhive u skorašnjim, nazad uvek jedan korak. */
    private static PendingIntent prozor(Context c, int id) {
        return otvori(c, String.valueOf(id), "vidzet-prozor", kod(id, 2));   // v234: id = koji vidžet je dodirnut
    }

    static JSONArray rokovi(JSONObject d) {
        JSONArray a = d.optJSONArray("rokovi");
        return a == null ? new JSONArray() : a;
    }

    private static RemoteViews mali(Context c, JSONObject d, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.vidzet_mali);
        int broj = 0; boolean proslo = false;
        JSONArray r = rokovi(d);
        for (int k = 0; k < r.length(); k++) {
            JSONObject o = r.optJSONObject(k);
            if (o == null) continue;
            int n = dana(o.optString("d"));
            if (n <= o.optInt("w", 7)) { broj++; if (n < 0) proslo = true; }
        }
        v.setTextViewText(R.id.vz_ime, txt(d, "title", "Arhiva"));
        if (broj > 0) {
            v.setViewVisibility(R.id.vz_broj, View.VISIBLE);
            v.setTextViewText(R.id.vz_broj, broj > 99 ? "99+" : String.valueOf(broj));
            v.setInt(R.id.vz_broj, "setBackgroundResource", proslo ? R.drawable.vz_broj_crveno : R.drawable.vz_broj_zuto);
            v.setTextColor(R.id.vz_broj, proslo ? Color.WHITE : 0xFF2A2100);
        } else {
            v.setViewVisibility(R.id.vz_broj, View.GONE);
        }
        v.setOnClickPendingIntent(R.id.vz_koren, prozor(c, id));
        return v;
    }

    private static RemoteViews srednji(Context c, JSONObject d, boolean veliki, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), veliki ? R.layout.vidzet_veliki : R.layout.vidzet_srednji);
        v.setTextViewText(R.id.vz_naslov, txt(d, "title", "Arhiva"));
        v.setOnClickPendingIntent(R.id.vz_koren, prozor(c, id));
        JSONArray r = rokovi(d);
        int prikazano = 0;
        for (int k = 0; k < r.length() && prikazano < REDOVI.length; k++) {
            JSONObject o = r.optJSONObject(k);
            if (o == null) continue;
            int n = dana(o.optString("d"));
            if (n == Integer.MAX_VALUE) continue;
            int upoz = o.optInt("w", 7);
            int[] ids = REDOVI[prikazano++];
            v.setViewVisibility(ids[0], View.VISIBLE);
            v.setTextViewText(ids[1], o.optString("n"));
            v.setTextViewText(ids[2], kada(d, n, o.optString("f"), upoz));
            v.setTextColor(ids[2], boja(n, upoz));
            v.setOnClickPendingIntent(ids[0], otvori(c, o.optString("id", "-"), kod(id, 10 + prikazano)));
        }
        for (int k = prikazano; k < REDOVI.length; k++) v.setViewVisibility(REDOVI[k][0], View.GONE);
        v.setViewVisibility(R.id.vz_prazno, prikazano == 0 ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.vz_prazno, txt(d, prikazano == 0 && !d.has("rokovi") ? "open" : "none", prikazano == 0 && !d.has("rokovi") ? "Otvori Arhivu" : "Nema podsetnika."));
        boolean bez = bezPodsetnika(d);
        v.setViewVisibility(R.id.vz_rok_blok, bez ? View.GONE : View.VISIBLE);
        if (veliki) {
            v.setTextViewText(R.id.vz_s_rok, txt(d, "rokovi", "Podsetnici"));
            v.setViewVisibility(R.id.vz_s_rok, bez ? View.GONE : View.VISIBLE);
            v.setTextViewText(R.id.vz_s_dok, txt(d, "dokumenti", "Izabrani dokumenti"));
            JSONArray a = d.optJSONArray("docs");
            int n = a == null ? 0 : Math.min(a.length(), DOKUMENTI.length);
            for (int k = 0; k < DOKUMENTI.length; k++) {
                int[] ids = DOKUMENTI[k];
                JSONObject o = k < n ? a.optJSONObject(k) : null;
                if (o == null) { v.setViewVisibility(ids[0], k < n || n == 0 ? View.GONE : View.INVISIBLE); continue; }
                v.setViewVisibility(ids[0], View.VISIBLE);
                v.setTextViewText(ids[2], o.optString("n"));
                Bitmap b = null;
                try {
                    File f = slika(c, d.optString("_f", "d"), k);
                    if (f.exists()) {   // v216: sličice su sada 420 px (za prozor); vidžetu je dovoljna polovina (manji paket)
                        BitmapFactory.Options op = new BitmapFactory.Options(); op.inSampleSize = 2;
                        b = BitmapFactory.decodeFile(f.getAbsolutePath(), op);
                    }
                } catch (Exception e) { b = null; }
                if (b != null) v.setImageViewBitmap(ids[1], b); else v.setImageViewResource(ids[1], R.mipmap.ic_launcher);
                v.setOnClickPendingIntent(ids[0], otvori(c, o.optString("id", "-"), kod(id, 20 + k)));
            }
            v.setViewVisibility(R.id.vz_dok_red, n > 0 ? View.VISIBLE : View.GONE);
            v.setViewVisibility(R.id.vz_dok_prazno, n > 0 ? View.GONE : View.VISIBLE);
            v.setTextViewText(R.id.vz_dok_prazno, txt(d, "noDocs", "Dokument dodaješ u Arhivi: Prečica → Na vidžetu."));
        }
        return v;
    }
}
