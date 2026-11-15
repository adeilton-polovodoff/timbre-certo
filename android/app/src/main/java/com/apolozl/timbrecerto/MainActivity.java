package com.apolozl.timbrecerto;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TankMidiPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
