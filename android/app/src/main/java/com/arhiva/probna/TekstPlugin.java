package com.arhiva.probna;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/* v316 (B3, Zoran 12.11.): tekst iz slike — Google ML Kit prepoznavanje teksta v2, latinica, model iz Google Play usluga
   (zvanično: developers.google.com/ml-kit/vision/text-recognition/v2/android). Radi na telefonu, bez interneta i bez slanja slike.
   Model se preuzima jednom (meta-data „ocr“ u manifestu za instalaciju sa Play-a; inače ModuleInstallClient, pripremi()).
   Dok model nije preuzet, process() vraća MlKitException.UNAVAILABLE (zvanična referenca TextRecognizer) → greška kod „NEDOSTUPNO“.
   - pripremi(): {spremno} — ako modela nema, traži preuzimanje (ModuleInstallClient.installModules)
   - procitaj({putanja}): {tekst} | {tekst:'', neCita:true} (fajl nije slika) — putanja je u kešu aplikacije (Arhiva je tamo upiše,
     Bajtovi/Filesystem); fajl se posle uvek briše. Velika slika se umanjuje pri čitanju (duža strana ≤ 4096 px, dovoljno za tekst;
     200 MP slika bi inače napunila memoriju); uspravnost iz EXIF-a. Jedna po jedna (jedna nit). */
@CapacitorPlugin(name = "Tekst")
public class TekstPlugin extends Plugin {
    private static final int NAJVISE = 4096;
    private final ExecutorService rad = Executors.newSingleThreadExecutor();
    private TextRecognizer citac;
    private boolean trazeno = false;

    private synchronized TextRecognizer citac() {
        if (citac == null) citac = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        return citac;
    }

    private synchronized void traziModel() {
        if (trazeno) return;
        trazeno = true;
        try {
            ModuleInstall.getClient(getContext())
                .installModules(ModuleInstallRequest.newBuilder().addApi(citac()).build())
                .addOnFailureListener(e -> { Logger.error("Tekst: preuzimanje modela", e); synchronized (TekstPlugin.this) { trazeno = false; } });
        } catch (Exception e) {
            Logger.error("Tekst: preuzimanje modela", e);
            trazeno = false;
        }
    }

    @PluginMethod
    public void pripremi(PluginCall call) {
        ModuleInstall.getClient(getContext()).areModulesAvailable(citac())
            .addOnSuccessListener(r -> {
                boolean ok = r.areModulesAvailable();
                if (!ok) traziModel();
                JSObject o = new JSObject();
                o.put("spremno", ok);
                call.resolve(o);
            })
            .addOnFailureListener(e -> call.reject("PRIPREMI: " + e.getMessage()));
    }

    @PluginMethod
    public void procitaj(PluginCall call) {
        final String p = call.getString("putanja", "");
        rad.execute(() -> {
            File kes = getContext().getCacheDir();
            File f = new File(kes, p);
            try {
                if (p.isEmpty() || p.contains("..") || !f.getCanonicalPath().startsWith(kes.getCanonicalPath() + File.separator)) {
                    call.reject("PUTANJA");
                    return;
                }
                Bitmap b = ucitaj(f);
                if (b == null) {
                    JSObject o = new JSObject();
                    o.put("tekst", "");
                    o.put("neCita", true);
                    call.resolve(o);
                    return;
                }
                Text t;
                try {
                    t = Tasks.await(citac().process(InputImage.fromBitmap(b, ugao(f))), 90, TimeUnit.SECONDS);
                } finally {
                    b.recycle();
                }
                JSObject o = new JSObject();
                o.put("tekst", t == null ? "" : t.getText());
                call.resolve(o);
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof MlKitException && ((MlKitException) c).getErrorCode() == MlKitException.UNAVAILABLE) {
                    traziModel();
                    call.reject("NEDOSTUPNO", "NEDOSTUPNO");
                } else {
                    call.reject("CITANJE: " + (c == null ? e.getMessage() : c.getMessage()));
                }
            } catch (Throwable e) {   // i OutOfMemoryError
                call.reject("CITANJE: " + e.getMessage());
            } finally {
                f.delete();
            }
        });
    }

    /* slika umanjena tako da duža strana bude ≤ NAJVISE (inSampleSize, stepen dvojke); null = nije slika */
    private static Bitmap ucitaj(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / s > NAJVISE) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
    }

    /* ugao za InputImage (koliko slika treba da se okrene u smeru kazaljke da bude uspravna) iz EXIF oznake */
    private static int ugao(File f) {
        try {
            int or = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (or == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (or == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {}
        return 0;
    }

    @Override
    protected void handleOnDestroy() {
        rad.shutdown();
        synchronized (this) {
            if (citac != null) { try { citac.close(); } catch (Exception ignored) {} citac = null; }
        }
    }
}
