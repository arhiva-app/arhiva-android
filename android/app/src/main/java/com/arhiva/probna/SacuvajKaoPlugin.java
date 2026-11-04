package com.arhiva.probna;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/* „Sačuvaj kao“: Android sam pita gde i pod kojim imenom (ACTION_CREATE_DOCUMENT, Storage Access Framework).
   Arhiva prvo upiše fajl u keš aplikacije (Filesystem, CACHE), ovde se on prepiše u izabrano mesto i obriše iz keša.
   v293 (Zoran 4.11.): fascikla za rezervnu kopiju — bira se JEDNOM (ACTION_OPEN_DOCUMENT_TREE, trajno pravo čitanja i upisa
   samo te fascikle: takePersistableUriPermission), posle izvoz ide pravo u nju. Zvanično: developer.android.com
   „Access documents and other files from shared storage“ (Storage Access Framework, DocumentsContract).
   - izaberiFasciklu(): {uri, ime} ili {otkazano}
   - proveriFasciklu({uri}): {ok} (pravo upisa i dalje važi); stari APK nema ovu metodu → Arhiva ne nudi fasciklu
   - uFasciklu({putanja, uri, ime, mime, zadrzi}): fajl iz keša u fasciklu (isto ime = prepiše se), pa se brišu starije
     Arhivine kopije (arhiva-backup-GGGG-MM-DD.zip/json) preko `zadrzi`; {ime: ime fascikle}; reject „PRAVO“ = nema prava/fascikle
   - otpustiFasciklu({uri}): pravo se vraća Androidu */
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

    /* ---------- v293: fascikla za rezervnu kopiju ---------- */
    private static final Pattern KOPIJA = Pattern.compile("^arhiva-backup-\\d{4}-\\d{2}-\\d{2}\\.(json|zip)$");
    private static final int PRAVA = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    @PluginMethod
    public void izaberiFasciklu(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(PRAVA | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, i, "posleFascikle");
    }

    @ActivityCallback
    private void posleFascikle(PluginCall call, ActivityResult result) {
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
            getContext().getContentResolver().takePersistableUriPermission(tree, PRAVA);
        } catch (Exception e) {
            call.reject("PRAVO: " + e.getMessage());
            return;
        }
        JSObject r = new JSObject();
        r.put("uri", tree.toString());
        r.put("ime", imeFascikle(tree));
        call.resolve(r);
    }

    private boolean mozeUpis(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        for (UriPermission p : getContext().getContentResolver().getPersistedUriPermissions())
            if (p.isWritePermission() && p.getUri().toString().equals(uri)) return true;
        return false;
    }

    @PluginMethod
    public void proveriFasciklu(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ok", mozeUpis(call.getString("uri")));
        call.resolve(r);
    }

    @PluginMethod
    public void otpustiFasciklu(PluginCall call) {
        try {
            getContext().getContentResolver().releasePersistableUriPermission(Uri.parse(call.getString("uri", "")), PRAVA);
        } catch (Exception e) { /* već otpušteno */ }
        call.resolve();
    }

    @PluginMethod
    public void uFasciklu(PluginCall call) {
        final File f = kesFajl(call);
        if (f == null) { call.reject("NEMA_FAJLA"); return; }
        final String uri = call.getString("uri");
        if (!mozeUpis(uri)) { f.delete(); call.reject("PRAVO: nema prava upisa"); return; }
        final String ime = call.getString("ime", "fajl");
        final String mime = call.getString("mime", "application/octet-stream");
        final int zadrzi = Math.max(1, call.getInt("zadrzi", 3));
        new Thread(() -> {
            ContentResolver cr = getContext().getContentResolver();
            Uri tree = Uri.parse(uri);
            try {
                String koren = DocumentsContract.getTreeDocumentId(tree);
                Uri cilj = null;
                for (String[] d : deca(tree, koren)) if (ime.equals(d[1])) { cilj = DocumentsContract.buildDocumentUriUsingTree(tree, d[0]); break; }
                if (cilj == null) cilj = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, koren), mime, ime);
                if (cilj == null) throw new Exception("fajl nije napravljen");
                try (InputStream in = new FileInputStream(f); OutputStream out = cr.openOutputStream(cilj, "wt")) {
                    if (out == null) throw new Exception("NEMA_IZLAZA");
                    byte[] b = new byte[1 << 16];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    out.flush();
                }
                /* starije Arhivine kopije (samo po imenu arhiva-backup-…), čuva se poslednjih `zadrzi` */
                try {
                    List<String[]> kop = new ArrayList<>();
                    for (String[] d : deca(tree, koren)) if (d[1] != null && KOPIJA.matcher(d[1]).matches()) kop.add(d);
                    Collections.sort(kop, (a, c) -> c[1].compareTo(a[1]));
                    for (int i = zadrzi; i < kop.size(); i++)
                        DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, kop.get(i)[0]));
                } catch (Exception e) { /* brisanje starih nije obavezno */ }
            } catch (Exception e) {
                f.delete();
                call.reject("PRAVO: " + e.getMessage());
                return;
            }
            f.delete();
            JSObject r = new JSObject();
            r.put("ime", imeFascikle(tree));
            call.resolve(r);
        }).start();
    }

    /* deca fascikle: [id dokumenta, ime] */
    private List<String[]> deca(Uri tree, String koren) {
        List<String[]> l = new ArrayList<>();
        Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, koren);
        try (Cursor c = getContext().getContentResolver().query(u, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            while (c != null && c.moveToNext()) l.add(new String[] { c.getString(0), c.getString(1) });
        }
        return l;
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
}
