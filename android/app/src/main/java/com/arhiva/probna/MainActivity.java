package com.arhiva.probna;

import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import com.getcapacitor.BridgeActivity;
import java.util.List;

public class MainActivity extends BridgeActivity {
    // Birač fotografija (zvanično: androidx.activity PickVisualMedia); prijava pre pokretanja aktivnosti
    private ValueCallback<Uri[]> slikePovratak;
    private final ActivityResultLauncher<PickVisualMediaRequest> biracVise =
        registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(), (List<Uri> uris) ->
            vratiSlike(uris == null || uris.isEmpty() ? null : uris.toArray(new Uri[0])));
    private final ActivityResultLauncher<PickVisualMediaRequest> biracJedan =
        registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), (Uri uri) ->
            vratiSlike(uri == null ? null : new Uri[] { uri }));

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SacuvajKaoPlugin.class);   // pre super.onCreate (zvanična dokumentacija Capacitor-a)
        registerPlugin(TrakePlugin.class);
        registerPlugin(ZakljucavanjePlugin.class);
        registerPlugin(PrecicaPlugin.class);
        super.onCreate(savedInstanceState);
        // Galerija: Androidov birač fotografija umesto opšteg birača fajlova (brže, kao u Chrome-u)
        getBridge().getWebView().setWebChromeClient(new BiracSlika(getBridge(), this));
        // SystemBars postavi svoju boju preko glavne niti; naša ide posle nje (ista nit, redom)
        getWindow().getDecorView().post(() -> TrakePlugin.primeni(this));
    }

    void izaberiSlike(ValueCallback<Uri[]> povratak, boolean vise) {
        vratiSlike(null);   // prethodni nezavršen izbor se otkazuje
        slikePovratak = povratak;
        PickVisualMediaRequest zahtev = new PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
            .build();
        try {
            if (vise) biracVise.launch(zahtev); else biracJedan.launch(zahtev);
        } catch (Exception e) {
            vratiSlike(null);
        }
    }

    private void vratiSlike(Uri[] uris) {
        ValueCallback<Uri[]> p = slikePovratak;
        slikePovratak = null;
        if (p != null) p.onReceiveValue(uris);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);  // SystemBars ovde vraća boju trake iz teme telefona
        getWindow().getDecorView().post(() -> TrakePlugin.primeni(this));
    }
}
