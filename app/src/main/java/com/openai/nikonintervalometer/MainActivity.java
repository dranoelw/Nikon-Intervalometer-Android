package com.openai.nikonintervalometer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.openai.nikonintervalometer.USB_PERMISSION";

    private UsbManager usbManager;
    private PtpCamera camera;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private int completed = 0;

    private TextView status;
    private TextView progress;
    private EditText intervalField;
    private EditText countField;
    private EditText delayField;
    private EditText bulbField;
    private CheckBox bulbCheck;
    private Button connectButton;
    private Button startButton;
    private Button stopButton;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) connectTo(device);
                else setStatus("USB permission denied");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                running = false;
                camera.disconnect();
                setStatus("Camera disconnected");
                updateButtons();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        camera = new PtpCamera(usbManager);
        buildUi();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        scanAndConnect();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(32));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Nikon Intervalometer");
        title.setTextSize(28);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("USB / PTP • Nikon D3400");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = fullWrap(); subLp.setMargins(0, dp(4), 0, dp(24));
        root.addView(subtitle, subLp);

        status = label("Looking for USB camera…", 16);
        status.setGravity(Gravity.CENTER);
        root.addView(status, fullWrap());

        connectButton = button("CONNECT CAMERA");
        connectButton.setOnClickListener(v -> scanAndConnect());
        LinearLayout.LayoutParams buttonLp = fullWrap(); buttonLp.setMargins(0, dp(12), 0, dp(20));
        root.addView(connectButton, buttonLp);

        bulbCheck = new CheckBox(this);
        bulbCheck.setText("Bulb mode");
        bulbCheck.setTextSize(18);
        bulbCheck.setOnCheckedChangeListener((buttonView, checked) -> {
            bulbField.setEnabled(checked && !running);
            updateBulbHint();
        });
        root.addView(bulbCheck, fullWrap());

        root.addView(fieldLabel("Bulb exposure time (seconds)"), marginTop(6));
        bulbField = numberField("30", true);
        bulbField.setEnabled(false);
        root.addView(bulbField, fullWrap());

        TextView bulbHint = label("In Bulb mode the app opens the shutter, times the exposure, then closes it.", 12);
        bulbHint.setTextColor(Color.DKGRAY);
        bulbHint.setTag("bulbHint");
        root.addView(bulbHint, fullWrap());

        root.addView(fieldLabel("Interval between shot starts (seconds)"), marginTop(16));
        intervalField = numberField("35", true);
        root.addView(intervalField, fullWrap());

        root.addView(fieldLabel("Number of shots"), marginTop(14));
        countField = numberField("10", false);
        root.addView(countField, fullWrap());

        root.addView(fieldLabel("Start delay (seconds)"), marginTop(14));
        delayField = numberField("0", true);
        root.addView(delayField, fullWrap());

        progress = label("Ready", 18);
        progress.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams progLp = fullWrap(); progLp.setMargins(0, dp(24), 0, dp(16));
        root.addView(progress, progLp);

        startButton = button("START");
        startButton.setTextSize(22);
        startButton.setMinHeight(dp(64));
        startButton.setOnClickListener(v -> startSequence());
        root.addView(startButton, fullWrap());

        stopButton = button("STOP");
        stopButton.setTextSize(20);
        stopButton.setMinHeight(dp(58));
        stopButton.setOnClickListener(v -> stopSequence());
        LinearLayout.LayoutParams stopLp = fullWrap(); stopLp.setMargins(0, dp(12), 0, 0);
        root.addView(stopButton, stopLp);

        TextView note = label("For Bulb: use Manual (M) mode. The app will try to switch shutter speed to Bulb over PTP; if the D3400 rejects that change, set the camera itself to Bulb and retry.", 13);
        note.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams noteLp = fullWrap(); noteLp.setMargins(0, dp(24), 0, 0);
        root.addView(note, noteLp);

        setContentView(scroll);
        updateButtons();
    }

    private void updateBulbHint() {
        if (bulbCheck != null && bulbCheck.isChecked() && bulbField != null) {
            bulbField.setHint("e.g. 120");
        }
    }

    private void scanAndConnect() {
        UsbDevice d = camera.findStillImageDevice();
        if (d == null) {
            setStatus("No PTP camera detected. Connect D3400 via USB OTG.");
            updateButtons();
            return;
        }
        if (usbManager.hasPermission(d)) {
            connectTo(d);
        } else {
            setStatus("Camera found — requesting USB permission…");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
            usbManager.requestPermission(d, pi);
        }
    }

    private void connectTo(UsbDevice d) {
        setStatus("Connecting…");
        io.execute(() -> {
            try {
                camera.connect(d);
                main.post(() -> {
                    setStatus("Connected: " + camera.getDeviceName());
                    updateButtons();
                });
            } catch (Exception e) {
                main.post(() -> {
                    setStatus("Connection failed: " + e.getMessage());
                    updateButtons();
                });
            }
        });
    }

    private void startSequence() {
        if (!camera.isConnected()) {
            Toast.makeText(this, "Connect the camera first", Toast.LENGTH_SHORT).show();
            return;
        }
        final double interval;
        final int total;
        final double delay;
        final boolean bulb = bulbCheck.isChecked();
        final double bulbSeconds;
        try {
            interval = Math.max(0.5, Double.parseDouble(intervalField.getText().toString().trim()));
            total = Math.max(1, Integer.parseInt(countField.getText().toString().trim()));
            delay = Math.max(0.0, Double.parseDouble(delayField.getText().toString().trim()));
            bulbSeconds = bulb ? Math.max(0.5, Double.parseDouble(bulbField.getText().toString().trim())) : 0.0;
        } catch (Exception e) {
            Toast.makeText(this, "Check interval, shot count, delay and Bulb time", Toast.LENGTH_LONG).show();
            return;
        }

        running = true;
        completed = 0;
        updateButtons();
        io.execute(() -> runSequence(interval, total, delay, bulb, bulbSeconds));
    }

    private void runSequence(double intervalSeconds, int total, double delaySeconds, boolean bulb, double bulbSeconds) {
        try {
            if (!sleepInterruptibly((long)(delaySeconds * 1000))) return;

            if (bulb) {
                boolean setFromApp = camera.setBulbMode();
                main.post(() -> setStatus(setFromApp
                        ? "Bulb selected on camera"
                        : "Bulb property not accepted — camera must already be M + Bulb"));
            }

            long intervalMs = (long)(intervalSeconds * 1000);
            long bulbMs = (long)(bulbSeconds * 1000);

            for (int i = 1; i <= total && running; i++) {
                long shotStart = System.currentTimeMillis();
                int shotNo = i;

                if (bulb) {
                    main.post(() -> progress.setText(String.format(Locale.US,
                            "Bulb %d / %d — %.1f s", shotNo, total, bulbSeconds)));
                    camera.startBulb();
                    boolean fullExposure = sleepInterruptibly(bulbMs);
                    camera.endBulb();
                    if (!fullExposure) return;
                } else {
                    main.post(() -> progress.setText(String.format(Locale.US, "Shot %d / %d", shotNo, total)));
                    camera.capture();
                }

                completed = i;
                if (i < total) {
                    long remaining = intervalMs - (System.currentTimeMillis() - shotStart);
                    if (remaining > 0 && !sleepInterruptibly(remaining)) return;
                }
            }
            if (running) main.post(() -> progress.setText("Finished — " + completed + " shots"));
        } catch (Exception e) {
            if (camera.isBulbOpen()) {
                try { camera.endBulb(); } catch (Exception ignored) {}
            }
            main.post(() -> {
                progress.setText("Stopped");
                setStatus("Capture error: " + e.getMessage());
            });
        } finally {
            running = false;
            main.post(this::updateButtons);
        }
    }

    private boolean sleepInterruptibly(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (running && System.currentTimeMillis() < end) {
            try { Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis()))); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return false; }
        }
        return running;
    }

    private void stopSequence() {
        running = false;
        progress.setText(camera.isBulbOpen() ? "Closing shutter…" : "Stopped after " + completed + " shots");
        updateButtons();
    }

    private void updateButtons() {
        boolean connected = camera != null && camera.isConnected();
        if (startButton != null) startButton.setEnabled(connected && !running);
        if (stopButton != null) stopButton.setEnabled(running);
        if (connectButton != null) connectButton.setEnabled(!running);
        if (intervalField != null) intervalField.setEnabled(!running);
        if (countField != null) countField.setEnabled(!running);
        if (delayField != null) delayField.setEnabled(!running);
        if (bulbCheck != null) bulbCheck.setEnabled(!running);
        if (bulbField != null) bulbField.setEnabled(!running && bulbCheck != null && bulbCheck.isChecked());
    }

    private void setStatus(String s) { if (status != null) status.setText(s); }

    private TextView fieldLabel(String text) {
        TextView t = label(text, 14); t.setTextColor(Color.DKGRAY); return t;
    }
    private TextView label(String text, int sp) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(sp); t.setTextColor(Color.BLACK); return t;
    }
    private EditText numberField(String value, boolean decimal) {
        EditText e = new EditText(this); e.setText(value); e.setTextSize(22); e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setPadding(dp(12), dp(8), dp(12), dp(8)); return e;
    }
    private Button button(String text) { Button b = new Button(this); b.setText(text); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams fullWrap() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams marginTop(int dp) { LinearLayout.LayoutParams lp = fullWrap(); lp.setMargins(0, dp(dp), 0, 0); return lp; }
    private int dp(int d) { return (int)(d * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onDestroy() {
        running = false;
        if (camera.isBulbOpen()) {
            try { camera.endBulb(); } catch (Exception ignored) {}
        }
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        camera.disconnect();
        io.shutdownNow();
        super.onDestroy();
    }
}
