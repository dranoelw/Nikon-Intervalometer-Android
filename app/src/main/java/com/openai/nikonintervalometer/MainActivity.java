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
    private NikonBulbRemote camera;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private volatile boolean cancelRequested = false;
    private int completed = 0;

    private TextView status;
    private TextView countdown;
    private EditText exposureField;
    private EditText pauseField;
    private EditText countField;
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
                cancelRequested = true;
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
        camera = new NikonBulbRemote(usbManager);
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

        TextView title = text("D3400 Bulb Remote", 28);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWrap());

        TextView subtitle = text("USB remote: READY → START → wait → STOP", 14);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sub = fullWrap();
        sub.setMargins(0, dp(4), 0, dp(22));
        root.addView(subtitle, sub);

        status = text("Looking for camera…", 16);
        status.setGravity(Gravity.CENTER);
        root.addView(status, fullWrap());

        connectButton = button("CONNECT CAMERA");
        connectButton.setOnClickListener(v -> scanAndConnect());
        LinearLayout.LayoutParams conn = fullWrap();
        conn.setMargins(0, dp(12), 0, dp(20));
        root.addView(connectButton, conn);

        root.addView(label("Exposure time (seconds)"), fullWrap());
        exposureField = numberField("10", true);
        root.addView(exposureField, fullWrap());

        root.addView(label("Pause after each exposure (seconds)"), topMargin(14));
        pauseField = numberField("3", true);
        root.addView(pauseField, fullWrap());

        root.addView(label("Number of exposures"), topMargin(14));
        countField = numberField("3", false);
        root.addView(countField, fullWrap());

        countdown = text("Ready", 20);
        countdown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams countLp = fullWrap();
        countLp.setMargins(0, dp(24), 0, dp(16));
        root.addView(countdown, countLp);

        startButton = button("START SEQUENCE");
        startButton.setTextSize(22);
        startButton.setMinHeight(dp(64));
        startButton.setOnClickListener(v -> startSequence());
        root.addView(startButton, fullWrap());

        stopButton = button("STOP NOW");
        stopButton.setTextSize(20);
        stopButton.setMinHeight(dp(58));
        stopButton.setOnClickListener(v -> stopNow());
        LinearLayout.LayoutParams stopLp = fullWrap();
        stopLp.setMargins(0, dp(12), 0, 0);
        root.addView(stopButton, stopLp);

        TextView note = text(
                "Camera setup: M mode, shutter speed Bulb, memory card inserted, and manual focus recommended. " +
                "The app waits for the camera to be ready, sends START without autofocus, times the exposure on the phone, sends STOP, then waits for the camera to be ready again.",
                13);
        note.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams noteLp = fullWrap();
        noteLp.setMargins(0, dp(24), 0, 0);
        root.addView(note, noteLp);

        setContentView(scroll);
        updateButtons();
    }

    private void scanAndConnect() {
        UsbDevice device = camera.findCamera();
        if (device == null) {
            setStatus("No Nikon/PTP camera detected");
            updateButtons();
            return;
        }

        if (usbManager.hasPermission(device)) {
            connectTo(device);
        } else {
            setStatus("Camera found — requesting USB permission…");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    flags);
            usbManager.requestPermission(device, pi);
        }
    }

    private void connectTo(UsbDevice device) {
        setStatus("Connecting…");
        io.execute(() -> {
            try {
                camera.connect(device);
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

        final double exposureSeconds;
        final double pauseSeconds;
        final int shots;

        try {
            exposureSeconds = Math.max(0.2, Double.parseDouble(exposureField.getText().toString().trim()));
            pauseSeconds = Math.max(0.0, Double.parseDouble(pauseField.getText().toString().trim()));
            shots = Math.max(1, Integer.parseInt(countField.getText().toString().trim()));
        } catch (Exception e) {
            Toast.makeText(this, "Check all timing values", Toast.LENGTH_LONG).show();
            return;
        }

        running = true;
        cancelRequested = false;
        completed = 0;
        updateButtons();

        io.execute(() -> runSequence(exposureSeconds, pauseSeconds, shots));
    }

    private void runSequence(double exposureSeconds, double pauseSeconds, int shots) {
        try {
            for (int shot = 1; shot <= shots && !cancelRequested; shot++) {
                int shotNo = shot;

                main.post(() -> setStatus("Waiting for camera, then START"));
                camera.startCaptureNoAf();

                boolean fullExposure = exposureCountdown(shotNo, shots, exposureSeconds);

                main.post(() -> setStatus("Sending STOP"));
                camera.stopCapture();

                if (!fullExposure || cancelRequested) break;

                completed = shot;
                main.post(() -> setStatus("Exposure saved — camera ready"));

                if (shot < shots && pauseSeconds > 0) {
                    if (!countdownSleep("Next exposure in", pauseSeconds)) break;
                }
            }

            if (!cancelRequested && completed == shots) {
                main.post(() -> {
                    countdown.setText("Finished — " + completed + " exposures");
                    setStatus("Ready");
                });
            } else {
                main.post(() -> {
                    countdown.setText("Stopped after " + completed + " completed exposures");
                    setStatus("Ready");
                });
            }
        } catch (Exception e) {
            try { camera.stopCapture(); } catch (Exception ignored) {}
            main.post(() -> {
                countdown.setText("Stopped");
                setStatus("Camera error: " + e.getMessage());
            });
        } finally {
            running = false;
            cancelRequested = false;
            main.post(this::updateButtons);
        }
    }

    private boolean exposureCountdown(int shot, int total, double seconds) {
        long end = System.currentTimeMillis() + (long)(seconds * 1000.0);

        while (!cancelRequested) {
            long remaining = end - System.currentTimeMillis();
            if (remaining <= 0) return true;

            double remainingSec = remaining / 1000.0;
            main.post(() -> countdown.setText(String.format(
                    Locale.US, "Exposure %d / %d — %.1f s", shot, total, remainingSec)));

            try {
                Thread.sleep(Math.min(100, Math.max(1, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean countdownSleep(String prefix, double seconds) {
        long end = System.currentTimeMillis() + (long)(seconds * 1000.0);

        while (!cancelRequested) {
            long remaining = end - System.currentTimeMillis();
            if (remaining <= 0) return true;

            double remainingSec = remaining / 1000.0;
            main.post(() -> countdown.setText(String.format(
                    Locale.US, "%s %.1f s", prefix, remainingSec)));

            try {
                Thread.sleep(Math.min(100, Math.max(1, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void stopNow() {
        if (!running) return;
        cancelRequested = true;
        countdown.setText("Stopping exposure…");
        setStatus("STOP requested");
    }

    private void updateButtons() {
        boolean connected = camera != null && camera.isConnected();
        startButton.setEnabled(connected && !running);
        stopButton.setEnabled(running);
        connectButton.setEnabled(!running);
        exposureField.setEnabled(!running);
        pauseField.setEnabled(!running);
        countField.setEnabled(!running);
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.BLACK);
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 14);
        view.setTextColor(Color.DKGRAY);
        return view;
    }

    private EditText numberField(String value, boolean decimal) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setTextSize(22);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER |
                (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        return field;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams fullWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int marginDp) {
        LinearLayout.LayoutParams lp = fullWrap();
        lp.setMargins(0, dp(marginDp), 0, 0);
        return lp;
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        cancelRequested = true;
        try {
            if (camera.isCaptureOpen()) camera.stopCapture();
        } catch (Exception ignored) {}
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        camera.disconnect();
        io.shutdownNow();
        super.onDestroy();
    }
}
