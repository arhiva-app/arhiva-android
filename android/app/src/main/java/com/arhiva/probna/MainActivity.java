package com.arhiva.probna;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SacuvajKaoPlugin.class);   // pre super.onCreate (zvanična dokumentacija Capacitor-a)
        super.onCreate(savedInstanceState);
    }
}
