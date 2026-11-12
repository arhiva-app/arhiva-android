package com.arhiva.probna;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import androidx.activity.result.IntentSenderRequest;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.List;

/* v315 (B3): skener dokumenata — Google ML Kit Document Scanner (zvanično: developers.google.com/ml-kit/vision/doc-scanner/android).
   Ceo prozor skenera (ivice, ugao, senke, više strana) daju Google Play usluge, na telefonu; Arhivi ne treba dozvola za kameru.
   - skeniraj(): {fajlovi:[{ime, mime, putanja, velicina}], greske} | {otkazano:true} | reject (skener nije dostupan)
     Strane (JPEG) se kopiraju u keš aplikacije (fascikla „skener“); Arhiva ih čita deo po deo i posle briše (obrisi).
   - deo({putanja, od}), obrisi(): isto kao Fascikle / Primanje. */
@CapacitorPlugin(name = "Skener")
public class SkenerPlugin extends Plugin {
    private static final int DEO = 1024 * 1024;
    private PluginCall cekam;

    private File folder() {
        File f = new File(getContext().getCacheDir(), "skener");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    @PluginMethod
    public void skeniraj(PluginCall call) {
        if (!(getActivity() instanceof MainActivity)) { call.reject("SKENER: nema prozora"); return; }
        GmsDocumentScannerOptions opcije = new GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)   // „Iz galerije“ ostaje posebno dugme (Zoran 12.11.)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build();
        GmsDocumentScanner skener = GmsDocumentScanning.getClient(opcije);
        skener.getStartScanIntent(getActivity())
            .addOnSuccessListener(is -> {
                if (cekam != null) { JSObject o = new JSObject(); o.put("otkazano", true); cekam.resolve(o); }
                cekam = call;
                ((MainActivity) getActivity()).pokreniSkener(new IntentSenderRequest.Builder(is).build());
            })
            .addOnFailureListener(e -> call.reject("SKENER: " + e.getMessage()));
    }

    /* MainActivity javlja ishod Google-ovog skenera */
    void rezultat(int kod, Intent data) {
        final PluginCall c = cekam;
        cekam = null;
        if (c == null) return;
        if (kod != Activity.RESULT_OK || data == null) { JSObject o = new JSObject(); o.put("otkazano", true); c.resolve(o); return; }
        GmsDocumentScanningResult r = GmsDocumentScanningResult.fromActivityResultIntent(data);
        final List<GmsDocumentScanningResult.Page> strane = r == null ? null : r.getPages();
        if (strane == null || strane.isEmpty()) { JSObject o = new JSObject(); o.put("otkazano", true); c.resolve(o); return; }
        new Thread(() -> {
            ContentResolver cr = getContext().getContentResolver();
            JSArray lista = new JSArray();
            int greske = 0, k = 0;
            long t = System.currentTimeMillis();
            for (GmsDocumentScanningResult.Page s : strane) {
                k++;
                try {
                    Uri u = s.getImageUri();
                    File kopija = new File(folder(), "s" + t + "_" + k + ".jpg");
                    long n = 0;
                    try (InputStream in = cr.openInputStream(u); FileOutputStream out = new FileOutputStream(kopija)) {
                        if (in == null) throw new Exception("nema toka");
                        byte[] b = new byte[64 * 1024];
                        int x;
                        while ((x = in.read(b)) > 0) { out.write(b, 0, x); n += x; }
                    }
                    JSObject o = new JSObject();
                    o.put("ime", "Skenirano " + k + ".jpg");
                    o.put("mime", "image/jpeg");
                    o.put("putanja", kopija.getAbsolutePath());
                    o.put("velicina", n);
                    lista.put(o);
                } catch (Exception e) {
                    greske++;
                }
            }
            JSObject o = new JSObject();
            o.put("fajlovi", lista);
            o.put("greske", greske);
            c.resolve(o);
        }).start();
    }

    @PluginMethod
    public void deo(PluginCall call) {
        try {
            File f = new File(call.getString("putanja", ""));
            if (!f.getCanonicalPath().startsWith(folder().getCanonicalPath() + File.separator)) { call.reject("PUTANJA"); return; }
            Object ov = call.getData().opt("od");   // broj može stići kao Integer ili Long (pouka v253)
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
