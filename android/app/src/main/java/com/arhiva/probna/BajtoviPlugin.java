package com.arhiva.probna;

import android.net.Uri;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/* v266 (Zoran 3.11.): brz prenos fajla iz Arhive u keš aplikacije — čisti bajtovi, bez prepakovanja u tekst (base64).
   Zvanično: androidx.webkit WebViewCompat.addWebMessageListener + WebMessageCompat.TYPE_ARRAY_BUFFER (od 1.8.0),
   samo kad telefon podržava WEB_MESSAGE_LISTENER i WEB_MESSAGE_ARRAY_BUFFER; inače se objekat ne ubacuje i Arhiva
   radi starim putem (Filesystem). Slušalac se dodaje u load(), pre učitavanja stranice (Capacitor Bridge: registerAllPlugins
   pa loadWebView), kako dokumentacija traži. Poruke: tekst {k:"pocni",putanja} / delovi (ArrayBuffer, ≤ 4 MB) / {k:"kraj"};
   odgovor na svaku: {"k":"ok"}, na kraj {"k":"gotovo","uri"}, greška {"k":"greska","poruka"}. Upis ide redom u jednoj niti. */
@CapacitorPlugin(name = "Bajtovi")
public class BajtoviPlugin extends Plugin {
    private final ExecutorService pisac = Executors.newSingleThreadExecutor();
    private FileOutputStream tok;
    private File fajl;

    @Override
    public void load() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) return;
        Set<String> pravila = new HashSet<>();
        for (String r : getBridge().getAllowedOriginRules()) {   // samo poreklo (šema://domen[:port]), bez putanje
            Uri u = Uri.parse(r);
            if (u.getScheme() != null && u.getHost() != null && !u.getHost().isEmpty())
                pravila.add(u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : ""));
        }
        if (pravila.isEmpty()) return;
        try {
            WebViewCompat.addWebMessageListener(getBridge().getWebView(), "arhivaBajtovi", pravila,
                (view, poruka, izvor, glavniOkvir, odgovor) -> {
                    if (!glavniOkvir) return;
                    if (poruka.getType() == WebMessageCompat.TYPE_ARRAY_BUFFER) {
                        final byte[] b = poruka.getArrayBuffer();
                        pisac.execute(() -> deo(b, odgovor));
                    } else {
                        final String s = poruka.getData();
                        pisac.execute(() -> naredba(s, odgovor));
                    }
                });
        } catch (Exception e) {
            Logger.error("Bajtovi: slušalac nije dodat", e);
        }
    }

    private void naredba(String s, JavaScriptReplyProxy odgovor) {
        try {
            JSONObject o = new JSONObject(s == null ? "{}" : s);
            String k = o.optString("k");
            if ("pocni".equals(k)) {
                zatvori();
                String p = o.optString("putanja");
                File kes = getContext().getCacheDir();
                File f = new File(kes, p);
                if (p.isEmpty() || p.contains("..") || !f.getCanonicalPath().startsWith(kes.getCanonicalPath() + File.separator))
                    throw new IllegalArgumentException("neispravna putanja");
                File dir = f.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("fascikla nije napravljena");
                fajl = f;
                tok = new FileOutputStream(f, false);
                posalji(odgovor, "{\"k\":\"ok\"}");
            } else if ("kraj".equals(k)) {
                if (tok == null) throw new IllegalStateException("nema otvorenog fajla");
                File f = fajl;
                tok.getFD().sync();
                zatvori();
                JSONObject r = new JSONObject();
                r.put("k", "gotovo");
                r.put("uri", Uri.fromFile(f).toString());
                posalji(odgovor, r.toString());
            } else {
                throw new IllegalArgumentException("nepoznata naredba");
            }
        } catch (Exception e) {
            greska(odgovor, e);
        }
    }

    private void deo(byte[] b, JavaScriptReplyProxy odgovor) {
        try {
            if (tok == null) throw new IllegalStateException("nema otvorenog fajla");
            tok.write(b);
            posalji(odgovor, "{\"k\":\"ok\"}");
        } catch (Exception e) {
            greska(odgovor, e);
        }
    }

    private void greska(JavaScriptReplyProxy odgovor, Exception e) {
        zatvori();
        try {
            JSONObject r = new JSONObject();
            r.put("k", "greska");
            r.put("poruka", String.valueOf(e.getMessage()));
            posalji(odgovor, r.toString());
        } catch (Exception ignored) {
            posalji(odgovor, "{\"k\":\"greska\",\"poruka\":\"?\"}");
        }
    }

    private void zatvori() {
        if (tok != null) {
            try { tok.close(); } catch (Exception ignored) {}
        }
        tok = null;
        fajl = null;
    }

    // JavaScriptReplyProxy je @UiThread — odgovor ide preko glavne niti
    private void posalji(JavaScriptReplyProxy odgovor, String s) {
        getBridge().executeOnMainThread(() -> {
            try { odgovor.postMessage(s); } catch (Exception e) { Logger.error("Bajtovi: odgovor", e); }
        });
    }
}
