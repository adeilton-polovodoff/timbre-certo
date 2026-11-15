package com.apolozl.timbrecerto;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

/**
 * Ponte MIDI nativa para a Tank-G.
 * Usa a API android.media.midi: cabo USB (USB-MIDI) ou Bluetooth LE MIDI.
 * O Android cuida do empacotamento do SysEx nos dois casos.
 */
@CapacitorPlugin(name = "TankMidi")
public class TankMidiPlugin extends Plugin {

    private MidiManager midi;
    private MidiDevice device;
    private MidiInputPort port;
    private String connectedName = "";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void load() {
        Context ctx = getContext();
        if (ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            midi = (MidiManager) ctx.getSystemService(Context.MIDI_SERVICE);
        }
    }

    private static String nameOf(MidiDeviceInfo info) {
        Bundle p = info.getProperties();
        String name = p.getString(MidiDeviceInfo.PROPERTY_NAME);
        if (name == null) name = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT);
        if (name == null) name = "MIDI " + info.getId();
        return name;
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject r = new JSObject();
        r.put("available", midi != null);
        call.resolve(r);
    }

    @PluginMethod
    public void listDevices(PluginCall call) {
        if (midi == null) { call.reject("Este aparelho não tem suporte a MIDI."); return; }
        JSArray list = new JSArray();
        for (MidiDeviceInfo info : midi.getDevices()) {
            if (info.getInputPortCount() == 0) continue;
            JSObject o = new JSObject();
            o.put("id", info.getId());
            o.put("name", nameOf(info));
            o.put("type", info.getType() == MidiDeviceInfo.TYPE_USB ? "usb" : (info.getType() == MidiDeviceInfo.TYPE_BLUETOOTH ? "bluetooth" : "virtual"));
            list.put(o);
        }
        JSObject r = new JSObject();
        r.put("devices", list);
        call.resolve(r);
    }

    /** Conecta no primeiro aparelho USB-MIDI (de preferência um com "tank", "m-vave" ou "usb-midi" no nome). */
    @PluginMethod
    public void connectUsb(PluginCall call) {
        if (midi == null) { call.reject("Este aparelho não tem suporte a MIDI."); return; }
        MidiDeviceInfo pick = null;
        for (MidiDeviceInfo info : midi.getDevices()) {
            if (info.getType() != MidiDeviceInfo.TYPE_USB || info.getInputPortCount() == 0) continue;
            String n = nameOf(info).toLowerCase();
            if (pick == null || n.contains("tank") || n.contains("m-vave") || n.contains("mvave") || n.contains("usb-midi") || n.contains("cuvave")) pick = info;
        }
        if (pick == null) { call.reject("Nenhuma pedaleira no cabo USB. Ligue a Tank-G e confira o cabo USB-C (OTG)."); return; }
        final MidiDeviceInfo info = pick;
        closeAll();
        midi.openDevice(info, d -> finishOpen(call, d, nameOf(info)), handler);
    }

    /** Conecta por Bluetooth LE MIDI usando o endereço vindo da busca de Bluetooth. */
    @PluginMethod
    public void connectBluetooth(PluginCall call) {
        if (midi == null) { call.reject("Este aparelho não tem suporte a MIDI."); return; }
        String address = call.getString("address");
        if (address == null) { call.reject("Endereço Bluetooth ausente."); return; }
        try {
            BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) { call.reject("Ligue o Bluetooth do celular."); return; }
            BluetoothDevice bt = adapter.getRemoteDevice(address);
            closeAll();
            final String name = call.getString("name", "Tank-G");
            midi.openBluetoothDevice(bt, d -> finishOpen(call, d, name), handler);
        } catch (SecurityException e) {
            call.reject("Permissão de Bluetooth negada. Libere em Configurações > Apps > Timbre Certo.");
        } catch (Exception e) {
            call.reject("Não deu para abrir o Bluetooth MIDI: " + e.getMessage());
        }
    }

    private void finishOpen(PluginCall call, MidiDevice d, String name) {
        if (d == null) { call.reject("A pedaleira não respondeu. Feche o app M-EFCS e tente de novo."); return; }
        MidiInputPort p = d.openInputPort(0);
        if (p == null) {
            try { d.close(); } catch (Exception ignored) {}
            call.reject("A porta MIDI está ocupada. Feche o app M-EFCS e tente de novo.");
            return;
        }
        device = d;
        port = p;
        connectedName = name;
        JSObject r = new JSObject();
        r.put("name", name);
        call.resolve(r);
    }

    @PluginMethod
    public void send(PluginCall call) {
        if (port == null) { call.reject("Conecte a Tank-G primeiro."); return; }
        JSArray data = call.getArray("data");
        if (data == null) { call.reject("Sem dados."); return; }
        try {
            JSONArray a = data;
            byte[] buf = new byte[a.length()];
            for (int i = 0; i < a.length(); i++) buf[i] = (byte) (a.getInt(i) & 0xFF);
            port.send(buf, 0, buf.length);
            call.resolve();
        } catch (Exception e) {
            call.reject("Falha ao enviar: " + e.getMessage());
        }
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("connected", port != null);
        r.put("name", connectedName);
        call.resolve(r);
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        closeAll();
        call.resolve();
    }

    private void closeAll() {
        try { if (port != null) port.close(); } catch (Exception ignored) {}
        try { if (device != null) device.close(); } catch (Exception ignored) {}
        port = null;
        device = null;
        connectedName = "";
    }

    @Override
    protected void handleOnDestroy() {
        closeAll();
    }
}
