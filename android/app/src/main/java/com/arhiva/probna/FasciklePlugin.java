package com.arhiva.probna;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Base64;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PermissionState;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;

/* v272 (Zoran 3.11.): praćene fascikle — korisnik jednom izabere fasciklu (ACTION_OPEN_DOCUMENT_TREE, Storage Access Framework),
   Arhiva dobija trajno pravo čitanja samo te fascikle (takePersistableUriPermission), bez dozvole za sve slike.
   Zvanično (developer.android.com, documents-files): od Android 11 ne može koren memorije ni fascikla Download.
   - izaberi({pocetna}): Androidov izbor fascikle, otvoren na pocetna (npr. "DCIM/Screenshots"); {uri, ime} ili {otkazano}
   - proveri({uri}): da li pravo i dalje važi
   - novi({uri, posle, vec, max}): fajlovi direktno u fascikli izmenjeni posle `posle` (ms), bez onih čiji je id u `vec`;
     kopiraju se u keš (cacheDir/fascikle) → [{id, ime, mime, vreme, velicina, putanja}] (najstariji prvi, najviše max)
   - deo({putanja, od}) / obrisi(): čitanje kopije u delovima (kao Primanje) i brisanje kopija
   - otpusti({uri}): pravo se vraća Androidu (prekidač isključen) */
/* v286 (Zoran 4.11.): dozvola „pristup lokaciji u medijima“ (ACCESS_MEDIA_LOCATION, Android 10+). Bez nje Android u slikama
   koje Arhiva čita upisuje nule umesto GPS koordinata (proba v285: „GPS skriven“).
   - dozvolaLokacije(): pita jednom (Androidov prozor), vraća {stanje: "granted" | "denied" | "prompt..."} */
@CapacitorPlugin(name = "Fascikle", permissions = {
    @Permission(alias = "lokacija", strings = { Manifest.permission.ACCESS_MEDIA_LOCATION })
})
public class FasciklePlugin extends Plugin {
    @PluginMethod
    public void dozvolaLokacije(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || getPermissionState("lokacija") == PermissionState.GRANTED) {
            JSObject o = new JSObject(); o.put("stanje", "granted"); call.resolve(o); return;
        }
        requestPermissionForAlias("lokacija", call, "posleDozvoleLokacije");
    }

    /* v287: naziv mesta iz koordinata — Androidov Geocoder (android.location.Geocoder; od API 33 sa GeocodeListener-om,
       ranije sinhrono u posebnoj niti). mesto({lat, lon, jezik}) → {mesto, deo, okrug, oblast, drzava} ili {} kad nema rezultata;
       greška (npr. bez mreže) → reject, pa Arhiva pokuša kasnije. */
    @PluginMethod
    public void mesto(PluginCall call) {
        Double lat = call.getDouble("lat"), lon = call.getDouble("lon");
        if (lat == null || lon == null) { call.reject("nema koordinata"); return; }
        if (!Geocoder.isPresent()) { call.reject("Geocoder nije dostupan"); return; }
        final Geocoder g = new Geocoder(getContext(), Locale.forLanguageTag(call.getString("jezik", "sr-Latn-RS")));
        final double la = lat, lo = lon;
        if (Build.VERSION.SDK_INT >= 33) {
            g.getFromLocation(la, lo, 1, new Geocoder.GeocodeListener() {
                @Override public void onGeocode(List<Address> a) { call.resolve(adresa(a)); }
                @Override public void onError(String poruka) { call.reject(poruka == null ? "Geocoder greška" : poruka); }
            });
        } else {
            new Thread(() -> {
                try { call.resolve(adresa(g.getFromLocation(la, lo, 1))); }
                catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
            }).start();
        }
    }

    private static JSObject adresa(List<Address> l) {
        JSObject o = new JSObject();
        if (l == null || l.isEmpty()) return o;
        Address a = l.get(0);
        if (a.getLocality() != null) o.put("mesto", a.getLocality());
        if (a.getSubLocality() != null) o.put("deo", a.getSubLocality());
        if (a.getSubAdminArea() != null) o.put("okrug", a.getSubAdminArea());
        if (a.getAdminArea() != null) o.put("oblast", a.getAdminArea());
        if (a.getCountryName() != null) o.put("drzava", a.getCountryName());
        return o;
    }

    @PermissionCallback
    private void posleDozvoleLokacije(PluginCall call) {
        JSObject o = new JSObject(); o.put("stanje", getPermissionState("lokacija").toString()); call.resolve(o);
    }

    private static final int DEO = 1024 * 1024;

    private File folder() {
        File d = new File(getContext().getCacheDir(), "fascikle");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    @PluginMethod
    public void izaberi(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        String p = call.getString("pocetna");
        if (p != null && !p.isEmpty()) {
            try {
                i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + p));
            } catch (Exception e) { /* početna je samo predlog */ }
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, i, "posleIzbora");
    }

    @ActivityCallback
    private void posleIzbora(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            JSObject r = new JSObject();
            r.put("otkazano", true);
            call.resolve(r);
            return;
        }
        Uri tree = data.getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            call.reject("PRAVO: " + e.getMessage());
            return;
        }
        JSObject r = new JSObject();
        r.put("uri", tree.toString());
        r.put("ime", imeFascikle(tree));
        call.resolve(r);
    }

    private String imeFascikle(Uri tree) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = getContext().getContentResolver().query(doc, new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
            }
        } catch (Exception e) { /* ime nije obavezno */ }
        return "";
    }

    private boolean imaPravo(String uri) {
        if (uri == null) return false;
        for (UriPermission p : getContext().getContentResolver().getPersistedUriPermissions())
            if (p.isReadPermission() && p.getUri().toString().equals(uri)) return true;
        return false;
    }

    @PluginMethod
    public void proveri(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ok", imaPravo(call.getString("uri")));
        call.resolve(r);
    }

    @PluginMethod
    public void otpusti(PluginCall call) {
        try {
            getContext().getContentResolver().releasePersistableUriPermission(Uri.parse(call.getString("uri", "")), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) { /* već otpušteno */ }
        call.resolve();
    }

    @PluginMethod
    public void novi(PluginCall call) {
        final String u = call.getString("uri");
        if (!imaPravo(u)) { JSObject r = new JSObject(); r.put("nemaPrava", true); call.resolve(r); return; }
        Object pv = call.getData().opt("posle");   // broj može stići kao Integer ili Long (pouka v253)
        final long posle = pv instanceof Number ? ((Number) pv).longValue() : 0L;
        final int max = call.getInt("max", 50);
        final HashSet<String> vec = new HashSet<>();
        try {
            JSONArray a = call.getData().optJSONArray("vec");
            if (a != null) for (int k = 0; k < a.length(); k++) vec.add(a.optString(k));
        } catch (Exception e) { /* bez spiska */ }
        new Thread(() -> {
            try {
                ContentResolver cr = getContext().getContentResolver();
                Uri tree = Uri.parse(u);
                Uri deca = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                String[] kol = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE };
                java.util.ArrayList<Object[]> nadjeni = new java.util.ArrayList<>();
                try (Cursor c = cr.query(deca, kol, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        String id = c.getString(0), ime = c.getString(1), mime = c.getString(2);
                        long vreme = c.isNull(3) ? 0 : c.getLong(3);
                        // v283: skriveni fajlovi (ime počinje tačkom) se preskaču — obrisano u galeriji („.trashed-…“),
                        // fajl koji se još upisuje („.pending-…“), sistemski (.nomedia); galerija ih ne prikazuje.
                        if (ime == null || ime.startsWith(".")) continue;
                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) || vreme <= posle || vec.contains(id)) continue;
                        nadjeni.add(new Object[] { id, ime, mime, vreme, c.isNull(4) ? 0L : c.getLong(4) });
                    }
                }
                nadjeni.sort((x, y) -> Long.compare((long) x[3], (long) y[3]));
                JSArray lista = new JSArray();
                int greske = 0, k = 0;
                for (Object[] f : nadjeni) {
                    if (k >= max) break;
                    try {
                        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, (String) f[0]);
                        File kopija = new File(folder(), "f" + System.currentTimeMillis() + "_" + (k++));
                        long n = 0;
                        try (InputStream in = cr.openInputStream(doc); FileOutputStream out = new FileOutputStream(kopija)) {
                            if (in == null) throw new Exception("nema toka");
                            byte[] b = new byte[64 * 1024];
                            int r;
                            while ((r = in.read(b)) > 0) { out.write(b, 0, r); n += r; }
                        }
                        JSObject o = new JSObject();
                        o.put("id", f[0]);
                        o.put("ime", f[1] == null ? "" : f[1]);
                        o.put("mime", f[2]);
                        o.put("vreme", f[3]);
                        o.put("velicina", n);
                        o.put("putanja", kopija.getAbsolutePath());
                        lista.put(o);
                    } catch (Exception e) {
                        greske++;
                    }
                }
                JSObject r = new JSObject();
                r.put("fajlovi", lista);
                r.put("greske", greske);
                r.put("jos", nadjeni.size() > max);
                call.resolve(r);
            } catch (Exception e) {
                call.reject("FASCIKLA: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void deo(PluginCall call) {
        try {
            File f = new File(call.getString("putanja", ""));
            if (!f.getCanonicalPath().startsWith(folder().getCanonicalPath() + File.separator)) { call.reject("PUTANJA"); return; }
            Object ov = call.getData().opt("od");
            long od = ov instanceof Number ? ((Number) ov).longValue() : 0L;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                long ostalo = Math.max(0, raf.length() - od);
                byte[] b = new byte[(int) Math.min(DEO, ostalo)];
                raf.seek(od);
                raf.readFully(b);
                JSObject r = new JSObject();
                r.put("podaci", Base64.encodeToString(b, Base64.NO_WRAP));
                r.put("kraj", od + b.length >= raf.length());
                r.put("od", od);
                call.resolve(r);
            }
        } catch (Exception e) {
            call.reject("DEO: " + e.getMessage());
        }
    }

    @PluginMethod
    public void obrisi(PluginCall call) {
        File[] sve = folder().listFiles();
        if (sve != null) for (File f : sve) f.delete();
        call.resolve();
    }
}
