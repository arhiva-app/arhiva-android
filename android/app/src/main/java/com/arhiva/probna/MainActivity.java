package com.arhiva.probna;

import android.content.res.Configuration;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SacuvajKaoPlugin.class);   // pre super.onCreate (zvanična dokumentacija Capacitor-a)
        registerPlugin(TrakePlugin.class);
        super.onCreate(savedInstanceState);
        // SystemBars postavi svoju boju preko glavne niti; naša ide posle nje (ista nit, redom)
        getWindow().getDecorView().post(() -> TrakePlugin.primeni(this));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);  // SystemBars ovde vraća boju trake iz teme telefona
        getWindow().getDecorView().post(() -> TrakePlugin.primeni(this));
    }
}
