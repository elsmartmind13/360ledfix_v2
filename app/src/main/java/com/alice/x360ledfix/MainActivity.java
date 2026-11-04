package com.alice.x360ledfix;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;

/**
 * x360 LED Fix v2.
 *
 * Отправляет геймпаду Xbox 360 (через оригинальный беспроводной ресивер
 * 045E:0719) LED-команду в ТОМ ЖЕ формате, что и драйвер xpad ядра Linux
 * (xpad_send_led_command, XTYPE_XBOX360W):
 *
 *   12 байт: 00 00 08 (0x40 + код) 00 00 00 00 00 00 00 00
 *
 * коды: 0 - погасить всё, 6..9 - стабильно гореть как игрок 1..4.
 * Дополнительно, для подстраховки, посылается вариант с заголовком 0x01
 * (протокол XBE2) - приёмник незнакомые пакеты молча игнорирует.
 */
public class MainActivity extends Activity {

    private static final int VENDOR_ID  = 0x045E;
    private static final int PRODUCT_ID = 0x0719;
    private static final String ACTION_USB_PERMISSION = "com.alice.x360ledfix.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;

    private TextView logView;
    private CheckBox repeatBox;
    private int lastCode = 0;
    private boolean repeatOn = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable repeatTask = new Runnable() {
        @Override public void run() {
            if (repeatOn) {
                sendLed(lastCode);
                handler.postDelayed(this, 10000);
            }
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    sendLed(lastCode);
                } else {
                    log("Доступ к USB НЕ разрешён - команда не отправлена.\n");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("x360 LED Fix v2\nПакет: 00 00 08 (0x40+код) — формат драйвера xpad");
        title.setTextSize(15);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setMinHeight(dp(240));
        root.addView(logView);

        repeatBox = new CheckBox(this);
        repeatBox.setText("Повторять команду каждые 10 секунд");
        repeatBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                repeatOn = checked;
                handler.removeCallbacks(repeatTask);
                if (checked) handler.postDelayed(repeatTask, 10000);
            }
        });
        root.addView(repeatBox);

        root.addView(mkButton("ПОГАСИТЬ ИНДИКАТОРЫ (код 0)", 0));
        root.addView(mkButton("Игрок 1 - стабильно", 6));
        root.addView(mkButton("Игрок 2 - стабильно", 7));
        root.addView(mkButton("Игрок 3 - стабильно", 8));
        root.addView(mkButton("Игрок 4 - стабильно", 9));
        root.addView(mkButton("Список USB-устройств", -2));
        root.addView(mkButton("Очистить журнал", -3));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        log("v2 готова. Включите геймпад и нажмите кнопку.\n");
        listUsbDevices();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        repeatOn = false;
        handler.removeCallbacks(repeatTask);
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
            connection = null;
        }
    }

    private Button mkButton(String text, final int code) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (code == -2) { listUsbDevices(); return; }
                if (code == -3) { logView.setText(""); return; }
                lastCode = code;
                sendLed(code);
            }
        });
        return b;
    }

    private UsbDevice findReceiver() {
        HashMap<String, UsbDevice> list = usbManager.getDeviceList();
        for (UsbDevice d : list.values()) {
            if (d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID) return d;
        }
        return null;
    }

    private void sendLed(final int code) {
        new Thread(new Runnable() {
            @Override public void run() {
                if (device == null) {
                    device = findReceiver();
                    if (device == null) {
                        log("\nРесивер 045E:0719 не найден. Вставьте его в USB и повторите.\n");
                        return;
                    }
                }
                if (!usbManager.hasPermission(device)) {
                    requestPermission(device);
                    return;
                }

                UsbDeviceConnection conn = connection;
                if (conn == null) {
                    conn = usbManager.openDevice(device);
                    if (conn == null) { log("\nНе удалось открыть USB-устройство.\n"); return; }
                    connection = conn;
                }

                byte[] pktStd = ledPacket(false, code); // формат xpad: 00 00 08 40+код
                byte[] pktXbe = ledPacket(true,  code); // вариант XBE2: 01 00 08 40+код

                StringBuilder sb = new StringBuilder();
                sb.append("\n--- LED код ").append(code).append(" ---\n");
                sb.append("стандарт: ").append(hex(pktStd)).append("\n");
                sb.append("XBE2    : ").append(hex(pktXbe)).append("\n");

                int goodIfaces = 0;
                for (int i = 0; i < device.getInterfaceCount(); i++) {
                    UsbInterface iface = device.getInterface(i);
                    sb.append("интерфейс ").append(i).append(": ");
                    boolean claimed = conn.claimInterface(iface, true);
                    if (!claimed) {
                        sb.append("ЗАХВАЧЕНА другим процессом, запись невозможна\n");
                        continue;
                    }
                    UsbEndpoint out = null;
                    for (int e = 0; e < iface.getEndpointCount(); e++) {
                        UsbEndpoint ep = iface.getEndpoint(e);
                        if (ep.getDirection() == UsbConstants.USB_DIR_OUT) { out = ep; break; }
                    }
                    if (out == null) {
                        sb.append("нет OUT-эндпоинта\n");
                        conn.releaseInterface(iface);
                        continue;
                    }
                    int wStd = conn.bulkTransfer(out, pktStd, pktStd.length, 200);
                    int wXbe = conn.bulkTransfer(out, pktXbe, pktXbe.length, 200);
                    conn.releaseInterface(iface);
                    sb.append("ep 0x").append(hex2(out.getAddress()))
                      .append(": стандарт=").append(wStd).append(" байт, XBE2=").append(wXbe).append(" байт\n");
                    if (wStd == 12 || wXbe == 12) goodIfaces++;
                }

                if (goodIfaces > 0) {
                    sb.append("ИТОГ: запись удалась в ").append(goodIfaces)
                      .append(" интерфейс(ов). Смотрите на индикаторы геймпада.\n");
                } else {
                    sb.append("ИТОГ: ни одна запись не удалась.\n");
                }
                sb.append("Примечание: если кнопки геймпада перестанут доходить до ТВ -\n")
                  .append("выньте и снова вставьте ресивер (LED-состояние хранится в геймпаде).\n");
                log(sb.toString());
            }
        }).start();
    }

    private byte[] ledPacket(boolean xbe2, int code) {
        byte[] p = new byte[12];
        p[0] = xbe2 ? (byte) 0x01 : (byte) 0x00;
        p[1] = 0x00;
        p[2] = 0x08;
        p[3] = (byte) (0x40 + code);
        // p[4..11] остаются нулями
        return p;
    }

    private void requestPermission(UsbDevice dev) {
        int flags = (Build.VERSION.SDK_INT >= 31) ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
        usbManager.requestPermission(dev, pi);
        log("\nЗапрошен доступ к USB. Подтвердите диалог на экране телевизора,\nзатем нажмите кнопку ещё раз.\n");
    }

    private void listUsbDevices() {
        HashMap<String, UsbDevice> list = usbManager.getDeviceList();
        StringBuilder sb = new StringBuilder("--- USB-устройства ---\n");
        for (UsbDevice d : list.values()) {
            sb.append(String.format("%04X:%04X", d.getVendorId(), d.getProductId()))
              .append(" \"").append(d.getProductName()).append("\"");
            if (d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID) sb.append("  <-- РЕСИВЕР");
            sb.append("\n");
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface f = d.getInterface(i);
                sb.append("   intf ").append(i).append(":");
                for (int e = 0; e < f.getEndpointCount(); e++) {
                    UsbEndpoint ep = f.getEndpoint(e);
                    sb.append(" 0x").append(hex2(ep.getAddress()))
                      .append(ep.getDirection() == UsbConstants.USB_DIR_OUT ? "(OUT)" : "(IN)");
                }
                sb.append("\n");
            }
        }
        if (list.isEmpty()) sb.append("(список пуст)\n");
        log(sb.toString());
    }

    private void log(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                logView.append(s);
            }
        });
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString();
    }

    private static String hex2(int v) {
        return String.format("%02X", v);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
