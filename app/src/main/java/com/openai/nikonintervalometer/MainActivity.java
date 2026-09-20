package com.openai.nikonintervalometer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
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

    private static final int OLED_BLACK = Color.BLACK;
    private static final int RED = Color.rgb(255, 45, 45);
    private static final int GREEN = Color.rgb(0, 230, 118);
    private static final int DARK_BUTTON = Color.rgb(22, 22, 22);

    private UsbManager usbManager;
    private NikonBulbRemote camera;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private volatile boolean cancelRequested = false;
    private volatile boolean statusCheckInFlight = false;
    private boolean destroyed = false;
    private int completed = 0;

    private TextView status;
    private TextView countdown;
    private TextView cameraModeCheck;
    private TextView shutterCheck;
    private TextView focusCheck;
    private EditText exposureField;
    private EditText pauseField;
    private EditText countField;
    private Button connectButton;
    private Button startButton;
    private Button stopButton;

    private final Runnable cameraStatusPoller = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (camera != null && camera.isConnected() && !running && !statusCheckInFlight) {
                refreshCameraChecklist();
            }
            main.postDelayed(this, 1500);
        }
    };

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
                setChecklistUnknown();
                setStatus("Camera disconnected");
                updateButtons();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(OLED_BLACK);
        getWindow().setNavigationBarColor(OLED_BLACK);

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

        main.post(cameraStatusPoller);
        scanAndConnect();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(OLED_BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(32));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(OLED_BLACK);
        scroll.addView(root);

        TextView title = text("Bulb Remote", 30);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWrap());

        status = text("Looking for camera…", 16);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = fullWrap();
        statusLp.setMargins(0, dp(12), 0, 0);
        root.addView(status, statusLp);

        connectButton = button("CONNECT CAMERA");
        connectButton.setOnClickListener(v -> scanAndConnect());
        LinearLayout.LayoutParams conn = fullWrap();
        conn.setMargins(0, dp(12), 0, dp(24));
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

        LinearLayout checklist = new LinearLayout(this);
        checklist.setOrientation(LinearLayout.VERTICAL);
        checklist.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams checkBoxLp = fullWrap();
        checkBoxLp.setMargins(0, dp(28), 0, 0);
        root.addView(checklist, checkBoxLp);

        TextView checkTitle = text("CAMERA CHECK", 14);
        checkTitle.setTextColor(RED);
        checklist.addView(checkTitle, fullWrap());

        cameraModeCheck = checklistItem("Camera Mode: Manual");
        checklist.addView(cameraModeCheck, topMargin(10));

        shutterCheck = checklistItem("Shutter Speed: Bulb");
        checklist.addView(shutterCheck, topMargin(8));

        focusCheck = checklistItem("Autofocus: MF");
        checklist.addView(focusCheck, topMargin(8));

        setChecklistUnknown();
        setContentView(scroll);
        updateButtons();
    }

    private void scanAndConnect() {
        UsbDevice device = camera.findCamera();
        if (device == null) {
            setStatus("No Nikon/PTP camera detected");
            setChecklistUnknown();
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
                    refreshCameraChecklist();
                });
            } catch (Exception e) {
                main.post(() -> {
                    setStatus("Connection failed: " + e.getMessage());
                    setChecklistUnknown();
                    updateButtons();
                });
            }
        });
    }

    private void refreshCameraChecklist() {
        if (!camera.isConnected() || running || statusCheckInFlight) return;
        statusCheckInFlight = true;

        io.execute(() -> {
            try {
                NikonBulbRemote.CameraSetup setup = camera.readCameraSetup();
                main.post(() -> applyChecklist(setup));
            } catch (Exception ignored) {
                // A transient busy state should not turn a previously valid checklist into an error.
            } finally {
                statusCheckInFlight = false;
            }
        });
    }

    private void applyChecklist(NikonBulbRemote.CameraSetup setup) {
        setCheck(cameraModeCheck, "Camera Mode: Manual", setup.manualMode);
        setCheck(shutterCheck, "Shutter Speed: Bulb", setup.bulb);
        setCheck(focusCheck, "Autofocus: MF", setup.manualFocus);
    }

    private void setChecklistUnknown() {
        if (cameraModeCheck == null) return;
        setCheck(cameraModeCheck, "Camera Mode: Manual", false);
        setCheck(shutterCheck, "Shutter Speed: Bulb", false);
        setCheck(focusCheck, "Autofocus: MF", false);
    }

    private void setCheck(TextView view, String label, boolean good) {
        view.setText((good ? "✓  " : "✕  ") + label);
        view.setTextColor(good ? GREEN : RED);
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

                main.post(() -> setStatus("Waiting for camera…"));
                camera.startCaptureNoAf();

                main.post(() -> setStatus("Capturing"));
                boolean fullExposure = exposureCountdown(shotNo, shots, exposureSeconds);

                main.post(() -> setStatus("Sending STOP"));
                camera.stopCapture();

                if (!fullExposure || cancelRequested) break;

                completed = shot;
                main.post(() -> setStatus("Exposure saved"));

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
            main.post(() -> {
                updateButtons();
                refreshCameraChecklist();
            });
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
        view.setTextColor(RED);
        return view;
    }

    private TextView label(String value) {
        return text(value, 14);
    }

    private TextView checklistItem(String value) {
        TextView view = text(value, 18);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private EditText numberField(String value, boolean decimal) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setTextSize(22);
        field.setSingleLine(true);
        field.setTextColor(RED);
        field.setHintTextColor(Color.rgb(120, 20, 20));
        field.setBackgroundTintList(ColorStateList.valueOf(RED));
        field.setInputType(InputType.TYPE_CLASS_NUMBER |
                (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        return field;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(RED);
        button.setBackgroundTintList(ColorStateList.valueOf(DARK_BUTTON));
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
        destroyed = true;
        main.removeCallbacks(cameraStatusPoller);
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
