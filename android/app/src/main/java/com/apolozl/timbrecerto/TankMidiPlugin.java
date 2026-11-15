package com.apolozl.timbrecerto;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Ponte com a Tank-G.
 *
 * Três caminhos:
 *  - "usb-direct": fala direto com a interface USB-MIDI da pedaleira (USB host do Android),
 *    montando os pacotes USB-MIDI como o app oficial faz no Windows. É o modo principal.
 *  - "usb-midi": pelo serviço MIDI do Android (reserva).
 *  - "ble": Bluetooth LE MIDI pelo serviço MIDI do Android.
 *
 * Em todos, as mensagens enviadas e as respostas da pedaleira vão para um log que o app lê com drain().
 */
@CapacitorPlugin(name = "TankMidi")
public class TankMidiPlugin extends Plugin {

    private static final String ACTION_USB_PERMISSION = "com.apolozl.timbrecerto.USB_PERMISSION";
    private static final int TANK_VENDOR_ID = 0x4C4A;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String mode = "";
    private String connectedName = "";

    // Serviço MIDI do Android
    private MidiManager midi;
    private MidiDevice device;
    private MidiInputPort port;
    private MidiOutputPort outPort;
    private final ByteArrayOutputStream midiAcc = new ByteArrayOutputStream();
    private boolean midiInSysex = false;

    // USB direto
    private UsbManager usb;
    private UsbDeviceConnection conn;
    private UsbInterface usbIntf;
    private UsbEndpoint epOut;
    private UsbEndpoint epIn;
    private Thread reader;
    private volatile boolean reading = false;
    private final ByteArrayOutputStream usbAcc = new ByteArrayOutputStream();

    // Log de mensagens (enviadas e recebidas)
    private final ArrayDeque<JSObject> log = new ArrayDeque<>();
    private final Object logLock = new Object();

    @Override
    public void load() {
        Context ctx = getContext();
        if (ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            midi = (MidiManager) ctx.getSystemService(Context.MIDI_SERVICE);
        }
        usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    }

    // ---------- log ----------

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    private void addLog(String dir, byte[] data) {
        JSObject o = new JSObject();
        o.put("d", dir);
        o.put("h", hex(data));
        o.put("t", System.currentTimeMillis());
        synchronized (logLock) {
            log.addLast(o);
            while (log.size() > 400) log.removeFirst();
        }
    }

    @PluginMethod
    public void drain(PluginCall call) {
        JSArray items = new JSArray();
        synchronized (logLock) {
            for (JSObject o : log) items.put(o);
            log.clear();
        }
        JSObject r = new JSObject();
        r.put("items", items);
        r.put("connected", isConnected());
        r.put("mode", mode);
        call.resolve(r);
    }

    private boolean isConnected() {
        if ("usb-direct".equals(mode)) return conn != null && epOut != null;
        return port != null;
    }

    // ---------- informações ----------

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
        r.put("available", midi != null || usb != null);
        call.resolve(r);
    }

    @PluginMethod
    public void listDevices(PluginCall call) {
        JSArray list = new JSArray();
        if (usb != null) {
            for (UsbDevice d : usb.getDeviceList().values()) {
                JSObject o = new JSObject();
                o.put("name", d.getProductName() != null ? d.getProductName() : d.getDeviceName());
                o.put("type", String.format("usb %04X:%04X%s", d.getVendorId(), d.getProductId(), findMidiInterface(d) != null ? " midi" : ""));
                list.put(o);
            }
        }
        if (midi != null) {
            for (MidiDeviceInfo info : midi.getDevices()) {
                JSObject o = new JSObject();
                o.put("name", nameOf(info));
                o.put("type", info.getType() == MidiDeviceInfo.TYPE_USB ? "midi-usb" : (info.getType() == MidiDeviceInfo.TYPE_BLUETOOTH ? "midi-bluetooth" : "midi-virtual"));
                list.put(o);
            }
        }
        JSObject r = new JSObject();
        r.put("devices", list);
        call.resolve(r);
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("connected", isConnected());
        r.put("name", connectedName);
        r.put("mode", mode);
        call.resolve(r);
    }

    // ---------- USB direto ----------

    private static UsbInterface findMidiInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            if (it.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO && it.getInterfaceSubclass() == 3) {
                for (int e = 0; e < it.getEndpointCount(); e++) {
                    if (it.getEndpoint(e).getDirection() == UsbConstants.USB_DIR_OUT) return it;
                }
            }
        }
        return null;
    }

    private UsbDevice findTankUsb() {
        if (usb == null) return null;
        UsbDevice pick = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (findMidiInterface(d) == null) continue;
            if (d.getVendorId() == TANK_VENDOR_ID) return d;
            if (pick == null) pick = d;
        }
        return pick;
    }

    @PluginMethod
    public void connectUsbDirect(PluginCall call) {
        if (usb == null) { call.reject("Este aparelho não tem modo USB host (OTG)."); return; }
        final UsbDevice dev = findTankUsb();
        if (dev == null) {
            call.reject("Nenhuma pedaleira encontrada no cabo USB. Confira se o cabo passa dados e se o OTG está ligado.");
            return;
        }
        if (usb.hasPermission(dev)) { openDirect(call, dev); return; }

        final Context ctx = getContext();
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(ctx.getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent, flags);
        BroadcastReceiver br = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                try { ctx.unregisterReceiver(this); } catch (Exception ignored) { }
                boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (ok || usb.hasPermission(dev)) {
                    openDirect(call, dev);
                } else {
                    call.reject("Permissão de USB negada. Toque em Conectar de novo e aceite o aviso do Android.");
                }
            }
        };
        ContextCompat.registerReceiver(ctx, br, new IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED);
        usb.requestPermission(dev, pi);
    }

    private void openDirect(PluginCall call, UsbDevice dev) {
        closeAll();
        UsbInterface it = findMidiInterface(dev);
        if (it == null) { call.reject("A pedaleira não mostrou a interface MIDI no USB."); return; }
        UsbEndpoint o = null, in = null;
        for (int e = 0; e < it.getEndpointCount(); e++) {
            UsbEndpoint ep = it.getEndpoint(e);
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT) o = ep; else in = ep;
        }
        UsbDeviceConnection c = usb.openDevice(dev);
        if (c == null) { call.reject("Não deu para abrir o USB da pedaleira."); return; }
        if (!c.claimInterface(it, true)) {
            c.close();
            call.reject("A conexão MIDI da pedaleira está ocupada. Feche o M-EFCS e outros apps de música e tente de novo.");
            return;
        }
        conn = c;
        usbIntf = it;
        epOut = o;
        epIn = in;
        mode = "usb-direct";
        connectedName = dev.getProductName() != null ? dev.getProductName() : "Tank-G";
        startReader();
        JSObject r = new JSObject();
        r.put("name", connectedName);
        r.put("mode", mode);
        call.resolve(r);
    }

    private void startReader() {
        if (conn == null || epIn == null) return;
        reading = true;
        final UsbDeviceConnection c = conn;
        final UsbEndpoint ep = epIn;
        reader = new Thread(() -> {
            byte[] buf = new byte[Math.max(64, ep.getMaxPacketSize())];
            while (reading) {
                int n;
                long t0 = System.currentTimeMillis();
                try { n = c.bulkTransfer(ep, buf, buf.length, 250); } catch (Exception e) { n = -1; }
                if (n > 0) {
                    parseUsbMidi(buf, n);
                } else if (System.currentTimeMillis() - t0 < 20) {
                    // erro imediato (cabo solto): não gira em falso
                    try { Thread.sleep(150); } catch (InterruptedException ie) { break; }
                }
            }
        }, "TankUsbReader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Pacotes USB-MIDI (4 bytes) vindos da pedaleira. */
    private void parseUsbMidi(byte[] b, int n) {
        for (int k = 0; k + 3 < n; k += 4) {
            int cin = b[k] & 0x0F;
            if (cin == 0x4) {
                usbAcc.write(b[k + 1]); usbAcc.write(b[k + 2]); usbAcc.write(b[k + 3]);
            } else if (cin == 0x5 || cin == 0x6 || cin == 0x7) {
                int len = cin - 4;
                for (int j = 1; j <= len; j++) usbAcc.write(b[k + j]);
                addLog("in", usbAcc.toByteArray());
                usbAcc.reset();
            } else if (cin >= 0x8) {
                int len = (cin == 0xC || cin == 0xD) ? 2 : 3;
                addLog("in", Arrays.copyOfRange(b, k + 1, k + 1 + len));
            }
        }
    }

    /** Monta os pacotes USB-MIDI (cabo 0) para uma mensagem. */
    private static byte[] toUsbMidi(byte[] m) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int i = 0, n = m.length;
        if (n > 0 && (m[0] & 0xFF) == 0xF0) {
            while (i < n) {
                int left = n - i;
                if (left > 3) {
                    o.write(0x04); o.write(m[i]); o.write(m[i + 1]); o.write(m[i + 2]); i += 3;
                } else if (left == 3) {
                    o.write(0x07); o.write(m[i]); o.write(m[i + 1]); o.write(m[i + 2]); i += 3;
                } else if (left == 2) {
                    o.write(0x06); o.write(m[i]); o.write(m[i + 1]); o.write(0); i += 2;
                } else {
                    o.write(0x05); o.write(m[i]); o.write(0); o.write(0); i += 1;
                }
            }
        } else {
            while (i < n) {
                int st = m[i] & 0xFF;
                int cin = (st >> 4) & 0x0F;
                int len = (cin == 0xC || cin == 0xD) ? 2 : 3;
                o.write(cin);
                for (int k = 0; k < 3; k++) o.write((k < len && i + k < n) ? m[i + k] : 0);
                i += len;
            }
        }
        return o.toByteArray();
    }

    // ---------- serviço MIDI do Android (cabo e Bluetooth) ----------

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
        midi.openDevice(info, d -> finishOpen(call, d, nameOf(info), "usb-midi"), handler);
    }

    @PluginMethod
    public void connectBluetooth(PluginCall call) {
        if (midi == null) { call.reject("Este aparelho não tem suporte a MIDI."); return; }
        String address = call.getString("address");
        if (address == null) { call.reject("Endereço Bluetooth ausente."); return; }
        try {
            BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter == null || !adapter.isEnabled()) { call.reject("Ligue o Bluetooth do celular."); return; }
            BluetoothDevice bt = adapter.getRemoteDevice(address);
            closeAll();
            final String name = call.getString("name", "Tank-G");
            midi.openBluetoothDevice(bt, d -> finishOpen(call, d, name, "ble"), handler);
        } catch (SecurityException e) {
            call.reject("Permissão de Bluetooth negada. Libere em Configurações > Apps > Timbre Certo.");
        } catch (Exception e) {
            call.reject("Não deu para abrir o Bluetooth MIDI: " + e.getMessage());
        }
    }

    private void finishOpen(PluginCall call, MidiDevice d, String name, String newMode) {
        if (d == null) { call.reject("A pedaleira não respondeu. Feche o app M-EFCS e tente de novo."); return; }
        MidiInputPort p = d.openInputPort(0);
        if (p == null) {
            try { d.close(); } catch (Exception ignored) { }
            call.reject("A porta MIDI está ocupada. Feche o app M-EFCS e tente de novo.");
            return;
        }
        device = d;
        port = p;
        try {
            MidiOutputPort op = d.openOutputPort(0);
            if (op != null) {
                op.connect(new MidiReceiver() {
                    @Override
                    public void onSend(byte[] msg, int offset, int count, long timestamp) {
                        feedMidi(msg, offset, count);
                    }
                });
                outPort = op;
            }
        } catch (Exception ignored) { }
        mode = newMode;
        connectedName = name;
        JSObject r = new JSObject();
        r.put("name", name);
        r.put("mode", mode);
        call.resolve(r);
    }

    private void feedMidi(byte[] b, int off, int cnt) {
        synchronized (midiAcc) {
            for (int i = off; i < off + cnt; i++) {
                int x = b[i] & 0xFF;
                if (x == 0xF0) {
                    midiAcc.reset();
                    midiInSysex = true;
                    midiAcc.write(x);
                } else if (midiInSysex) {
                    midiAcc.write(x);
                    if (x == 0xF7) {
                        midiInSysex = false;
                        addLog("in", midiAcc.toByteArray());
                    }
                } else if (x >= 0x80 && x < 0xF8) {
                    addLog("in", new byte[]{(byte) x});
                }
            }
        }
    }

    // ---------- envio ----------

    @PluginMethod
    public void send(PluginCall call) {
        JSArray data = call.getArray("data");
        if (data == null) { call.reject("Sem dados."); return; }
        byte[] buf;
        try {
            buf = new byte[data.length()];
            for (int i = 0; i < data.length(); i++) buf[i] = (byte) (data.getInt(i) & 0xFF);
        } catch (Exception e) {
            call.reject("Dados inválidos.");
            return;
        }
        try {
            if ("usb-direct".equals(mode) && conn != null && epOut != null) {
                byte[] pk = toUsbMidi(buf);
                int max = Math.max(4, (epOut.getMaxPacketSize() / 4) * 4);
                for (int off = 0; off < pk.length; off += max) {
                    int len = Math.min(max, pk.length - off);
                    int w = conn.bulkTransfer(epOut, pk, off, len, 1000);
                    if (w < 0) { call.reject("Falha ao enviar pelo USB. Confira o cabo e conecte de novo."); return; }
                }
                addLog("out", buf);
                call.resolve();
                return;
            }
            if (port == null) { call.reject("Conecte a Tank-G primeiro."); return; }
            port.send(buf, 0, buf.length);
            addLog("out", buf);
            call.resolve();
        } catch (Exception e) {
            call.reject("Falha ao enviar: " + e.getMessage());
        }
    }

    // ---------- desconexão ----------

    @PluginMethod
    public void disconnect(PluginCall call) {
        closeAll();
        call.resolve();
    }

    private void closeAll() {
        reading = false;
        if (reader != null) {
            try { reader.join(400); } catch (InterruptedException ignored) { }
            reader = null;
        }
        if (conn != null) {
            try { if (usbIntf != null) conn.releaseInterface(usbIntf); } catch (Exception ignored) { }
            try { conn.close(); } catch (Exception ignored) { }
        }
        conn = null;
        usbIntf = null;
        epOut = null;
        epIn = null;
        try { if (outPort != null) outPort.close(); } catch (Exception ignored) { }
        try { if (port != null) port.close(); } catch (Exception ignored) { }
        try { if (device != null) device.close(); } catch (Exception ignored) { }
        outPort = null;
        port = null;
        device = null;
        connectedName = "";
        mode = "";
        usbAcc.reset();
    }

    @Override
    protected void handleOnDestroy() {
        closeAll();
    }
}
