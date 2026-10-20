package com.arhiva.probna;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/* „Sačuvaj kao“: Android sam pita gde i pod kojim imenom (ACTION_CREATE_DOCUMENT, Storage Access Framework).
   Arhiva prvo upiše fajl u keš aplikacije (Filesystem, CACHE), ovde se on prepiše u izabrano mesto i obriše iz keša. */
@CapacitorPlugin(name = "SacuvajKao")
public class SacuvajKaoPlugin extends Plugin {

    private File kesFajl(PluginCall call) {
        String p = call.getString("putanja");
        if (p == null || p.contains("..")) return null;
        File f = new File(getContext().getCacheDir(), p);
        return f.isFile() ? f : null;
    }

    @PluginMethod
    public void sacuvaj(PluginCall call) {
        File f = kesFajl(call);
        if (f == null) { call.reject("NEMA_FAJLA"); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(call.getString("mime", "application/octet-stream"));
        i.putExtra(Intent.EXTRA_TITLE, call.getString("ime", "fajl"));
        startActivityForResult(call, i, "posleIzbora");
    }

    @ActivityCallback
    private void posleIzbora(PluginCall call, ActivityResult result) {
        if (call == null) return;
        final File f = kesFajl(call);
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (f != null) f.delete();
            JSObject r = new JSObject();
            r.put("otkazano", true);
            call.resolve(r);
            return;
        }
        if (f == null) { call.reject("NEMA_FAJLA"); return; }
        final Uri uri = data.getData();
        new Thread(() -> {
            try (InputStream in = new FileInputStream(f);
                 OutputStream out = getContext().getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new Exception("NEMA_IZLAZA");
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                out.flush();
            } catch (Exception e) {
                f.delete();
                call.reject("UPIS: " + e.getMessage());
                return;
            }
            f.delete();
            JSObject r = new JSObject();
            r.put("ime", ime(uri));
            call.resolve(r);
        }).start();
    }

    private String ime(Uri uri) {
        try (Cursor c = getContext().getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) { /* ime nije obavezno */ }
        return "";
    }
}
