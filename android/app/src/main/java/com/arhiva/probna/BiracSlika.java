package com.arhiva.probna;

import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;

/**
 * Izbor slika iz galerije Androidovim biračem fotografija (Photo Picker), kao u Chrome-u.
 * Samo kad polje traži isključivo slike i nije kamera; sve ostalo (dokumenti, kamera) ide kao do sada (Capacitor).
 */
public class BiracSlika extends BridgeWebChromeClient {
    private final MainActivity aktivnost;

    public BiracSlika(Bridge bridge, MainActivity aktivnost) {
        super(bridge);
        this.aktivnost = aktivnost;
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> povratak, FileChooserParams p) {
        if (!p.isCaptureEnabled() && samoSlike(p.getAcceptTypes())) {
            aktivnost.izaberiSlike(povratak, p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            return true;
        }
        return super.onShowFileChooser(webView, povratak, p);
    }

    static boolean samoSlike(String[] vrste) {
        boolean ima = false;
        if (vrste == null) return false;
        for (String v : vrste) {
            if (v == null) continue;
            for (String x : v.split(",")) {
                x = x.trim().toLowerCase();
                if (x.isEmpty()) continue;
                if (!x.startsWith("image/")) return false;
                ima = true;
            }
        }
        return ima;
    }
}
