package com.arhiva.probna;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import androidx.core.content.IntentCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;

/* v252 Podeli → Arhiva (zvanično: developer.android.com/training/sharing/receive — ACTION_SEND / ACTION_SEND_MULTIPLE, EXTRA_STREAM).
   Pri prijemu se pamte adrese fajlova; cekaj() ih kopira u keš aplikacije (cacheDir/primljeno) i vraća spisak (ime, vrsta, putanja, veličina).
   deo(): fajl u delovima od 1 MB (base64), da veliki PDF ne zagusi most. obrisi(): posle čuvanja u Arhivi.
   Dok Arhiva radi, novo deljenje javlja „primljeno“. Logika (Bez albuma, PIN, poruka) je u Arhivi, blok PRIJEM. */
@CapacitorPlugin(name = "Primanje")
public class PrimanjePlugin extends Plugin {
    private static final int DEO = 1024 * 1024;
    private final ArrayList<Uri> naCekanju = new ArrayList<>();

    @Override
    public void load() {
        uzmi(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (uzmi(intent)) notifyListeners("primljeno", new JSObject());
    }

    private synchronized boolean uzmi(Intent i) {
        if (i == null) return false;
        String a = i.getAction();
        ArrayList<Uri> u = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri x = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri.class);
            if (x != null) u.add(x);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> l = IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri.class);
            if (l != null) for (Uri x : l) if (x != null) u.add(x);
        } else {
            return false;
        }
        i.removeExtra(Intent.EXTRA_STREAM);   // da se isto ne primi ponovo (npr. kad se aktivnost ponovo napravi)
        i.setAction(Intent.ACTION_MAIN);
        naCekanju.addAll(u);
        return !u.isEmpty();
    }

    private synchronized ArrayList<Uri> preuzmi() {
        ArrayList<Uri> l = new ArrayList<>(naCekanju);
        naCekanju.clear();
        return l;
    }

    private File folder() {
        File d = new File(getContext().getCacheDir(), "primljeno");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    @PluginMethod
    public void cekaj(PluginCall call) {
        ContentResolver cr = getContext().getContentResolver();
        JSArray lista = new JSArray();
        int greske = 0, k = 0;
        for (Uri uri : preuzmi()) {
            try {
                String ime = null;
                try (Cursor c = cr.query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                    if (c != null && c.moveToFirst() && !c.isNull(0)) ime = c.getString(0);
                } catch (Exception e) { /* bez imena → poslednji deo adrese */ }
                if (ime == null || ime.isEmpty()) ime = uri.getLastPathSegment();
                File f = new File(folder(), "p" + System.currentTimeMillis() + "_" + (k++));
                long n = 0;
                try (InputStream in = cr.openInputStream(uri); FileOutputStream out = new FileOutputStream(f)) {
                    if (in == null) throw new Exception("nema toka");
                    byte[] b = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(b)) > 0) { out.write(b, 0, r); n += r; }
                }
                JSObject o = new JSObject();
                o.put("ime", ime == null ? "" : ime);
                o.put("mime", cr.getType(uri));
                o.put("putanja", f.getAbsolutePath());
                o.put("velicina", n);
                lista.put(o);
            } catch (Exception e) {
                greske++;
            }
        }
        JSObject r = new JSObject();
        r.put("fajlovi", lista);
        r.put("greske", greske);
        call.resolve(r);
    }

    @PluginMethod
    public void deo(PluginCall call) {
        try {
            File f = new File(call.getString("putanja", ""));
            if (!f.getCanonicalPath().startsWith(folder().getCanonicalPath() + File.separator)) { call.reject("PUTANJA"); return; }
            long od = call.getLong("od", 0L);
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                long ostalo = Math.max(0, raf.length() - od);
                byte[] b = new byte[(int) Math.min(DEO, ostalo)];
                raf.seek(od);
                raf.readFully(b);
                JSObject r = new JSObject();
                r.put("podaci", Base64.encodeToString(b, Base64.NO_WRAP));
                r.put("kraj", od + b.length >= raf.length());
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
