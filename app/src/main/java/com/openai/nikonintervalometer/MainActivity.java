package com.openai.nikonintervalometer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
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
    private static final int GREY = Color.rgb(170, 170, 170);
    private static final int DARK_BUTTON = Color.rgb(22, 22, 22);
    private static final int DISABLED_GREY = Color.rgb(110, 110, 110);
    private static final int DISABLED_BUTTON = Color.rgb(12, 12, 12);

    private UsbManager usbManager;
    private NikonBulbRemote camera;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private volatile boolean cancelRequested = false;
    private volatile boolean statusCheckInFlight = false;
    private volatile boolean cameraSetupReady = false;
    private volatile boolean manualModeReady = false;
    private volatile boolean bulbReady = false;
    private volatile boolean focusReady = false;
    private boolean destroyed = false;
    private int completed = 0;

    private TextView status;
    private TextView countdown;
    private TextView cameraModeCheck;
    private TextView shutterCheck;
    private TextView focusCheck;
    private TextView batteryCheck;
    private TextView totalTimeView;
    private TextView timeLeftView;
    private EditText exposureField;
    private EditText pauseField;
    private EditText countField;
    private Button startButton;
    private Button stopButton;
    private Button playbackButton;
    private Button closePlaybackButton;
    private Button previousButton;
    private Button nextButton;
    private ImageView playbackImage;
    private TextView playbackStatus;
    private LinearLayout playbackNav;
    private int[] playbackHandles = new int[0];
    private int playbackIndex = -1;

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
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && !camera.isConnected()) {
                    connectOrRequestPermission(device);
                }
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
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        main.post(cameraStatusPoller);

        UsbDevice attached = null;
        Intent launchIntent = getIntent();
        if (launchIntent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(launchIntent.getAction())) {
            attached = launchIntent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }
        if (attached != null) connectOrRequestPermission(attached);
        else scanAndConnect();
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

        TextView title = text("Intervalometer", 30);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWrap());

        status = text("Looking for camera…", 16);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = fullWrap();
        statusLp.setMargins(0, dp(12), 0, 0);
        root.addView(status, statusLp);

        root.addView(label("Exposure time (seconds)"), fullWrap());
        exposureField = numberField("", true);
        root.addView(exposureField, fullWrap());

        root.addView(label("Number of exposures"), topMargin(14));
        countField = numberField("", false);
        root.addView(countField, fullWrap());

        root.addView(label("Pause after each exposure (seconds)"), topMargin(14));
        pauseField = numberField("0", true);
        root.addView(pauseField, fullWrap());

        totalTimeView = text("Total time: --:--:--", 16);
        totalTimeView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams totalLp = fullWrap();
        totalLp.setMargins(0, dp(20), 0, 0);
        root.addView(totalTimeView, totalLp);

        timeLeftView = text("Time left: --:--:--", 18);
        timeLeftView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams leftLp = fullWrap();
        leftLp.setMargins(0, dp(6), 0, 0);
        root.addView(timeLeftView, leftLp);

        TextWatcher timingWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!running) updatePlannedTimes();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        exposureField.addTextChangedListener(timingWatcher);
        pauseField.addTextChangedListener(timingWatcher);
        countField.addTextChangedListener(timingWatcher);

        countdown = text("Not Ready", 20);
        countdown.setTextColor(RED);
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

        playbackButton = button("PLAYBACK");
        playbackButton.setOnClickListener(v -> openPlayback());
        LinearLayout.LayoutParams playbackLp = fullWrap();
        playbackLp.setMargins(0, dp(18), 0, 0);
        root.addView(playbackButton, playbackLp);

        closePlaybackButton = button("CLOSE PLAYBACK");
        closePlaybackButton.setVisibility(View.GONE);
        closePlaybackButton.setOnClickListener(v -> closePlayback());
        LinearLayout.LayoutParams closePlaybackLp = fullWrap();
        closePlaybackLp.setMargins(0, dp(18), 0, 0);
        root.addView(closePlaybackButton, closePlaybackLp);

        playbackStatus = text("Camera playback", 14);
        playbackStatus.setGravity(Gravity.CENTER);
        playbackStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams playbackStatusLp = fullWrap();
        playbackStatusLp.setMargins(0, dp(10), 0, dp(8));
        root.addView(playbackStatus, playbackStatusLp);

        playbackImage = new ImageView(this);
        playbackImage.setAdjustViewBounds(true);
        playbackImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        playbackImage.setBackgroundColor(OLED_BLACK);
        playbackImage.setVisibility(View.GONE);
        LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(280));
        root.addView(playbackImage, imageLp);

        playbackNav = new LinearLayout(this);
        playbackNav.setOrientation(LinearLayout.HORIZONTAL);
        playbackNav.setGravity(Gravity.CENTER);
        playbackNav.setVisibility(View.GONE);

        previousButton = button("PREVIOUS");
        nextButton = button("NEXT");
        previousButton.setOnClickListener(v -> showPlaybackIndex(playbackIndex - 1));
        nextButton.setOnClickListener(v -> {
            if (playbackIndex == playbackHandles.length - 1) {
                refreshPlaybackAndShowFirst();
            } else {
                showPlaybackIndex(playbackIndex + 1);
            }
        });

        LinearLayout.LayoutParams navButtonLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        navButtonLp.setMargins(dp(4), dp(8), dp(4), 0);
        playbackNav.addView(previousButton, navButtonLp);
        playbackNav.addView(nextButton, navButtonLp);
        root.addView(playbackNav, fullWrap());

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

        batteryCheck = checklistItem("Camera Battery: --%");
        checklist.addView(batteryCheck, topMargin(8));

        setChecklistUnknown();
        setContentView(scroll);
        updatePlannedTimes();
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

        connectOrRequestPermission(device);
    }

    private void connectOrRequestPermission(UsbDevice device) {
        if (device == null || camera.isConnected()) return;

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

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            connectOrRequestPermission(device);
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
        manualModeReady = setup.manualMode;
        bulbReady = setup.bulb;
        focusReady = setup.manualFocus;

        setCheck(cameraModeCheck, "Camera Mode: Manual", manualModeReady);
        setCheck(shutterCheck, "Shutter Speed: Bulb", bulbReady);
        setCheck(focusCheck, "Autofocus: MF", focusReady);
        batteryCheck.setText("Camera Battery: " + setup.batteryLevel + "%");
        batteryCheck.setTextColor(RED);

        cameraSetupReady = manualModeReady && bulbReady && focusReady;
        updateReadinessStatus();
        updateButtons();
    }

    private void setChecklistUnknown() {
        cameraSetupReady = false;
        manualModeReady = false;
        bulbReady = false;
        focusReady = false;
        if (cameraModeCheck == null) return;
        setCheck(cameraModeCheck, "Camera Mode: Manual", false);
        setCheck(shutterCheck, "Shutter Speed: Bulb", false);
        setCheck(focusCheck, "Autofocus: MF", false);
        if (batteryCheck != null) {
            batteryCheck.setText("Camera Battery: --%");
            batteryCheck.setTextColor(RED);
        }
        updateReadinessStatus();
        updateButtons();
    }

    private void setCheck(TextView view, String label, boolean good) {
        view.setText((good ? "✓  " : "✕  ") + label);
        view.setTextColor(good ? RED : GREY);
    }

    private void openPlayback() {
        if (!camera.isConnected()) {
            Toast.makeText(this, "Connect the camera first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (running) return;

        playbackButton.setEnabled(false);
        playbackStatus.setVisibility(View.VISIBLE);
        playbackStatus.setText("Loading photos…");

        io.execute(() -> {
            try {
                int[] handles = camera.getImageHandles();
                if (handles.length == 0) {
                    main.post(() -> {
                        playbackStatus.setText("No playable images found");
                        playbackButton.setEnabled(true);
                    });
                    return;
                }

                playbackHandles = handles;
                playbackIndex = handles.length - 1;
                loadPlaybackImage(playbackIndex);
            } catch (Exception e) {
                main.post(() -> {
                    playbackStatus.setText("Playback error: " + e.getMessage());
                    playbackButton.setEnabled(true);
                });
            }
        });
    }

    private void closePlayback() {
        playbackImage.setImageDrawable(null);
        playbackImage.setVisibility(View.GONE);
        playbackStatus.setVisibility(View.GONE);
        playbackNav.setVisibility(View.GONE);
        closePlaybackButton.setVisibility(View.GONE);
        playbackButton.setVisibility(View.VISIBLE);
        playbackButton.setEnabled(camera.isConnected() && !running);
    }

    private void showPlaybackIndex(int index) {
        if (running || index < 0 || index >= playbackHandles.length) return;
        playbackButton.setEnabled(false);
        previousButton.setEnabled(false);
        nextButton.setEnabled(false);
        io.execute(() -> loadPlaybackImage(index, true));
    }

    private void refreshPlaybackAndShowFirst() {
        if (running) return;
        playbackButton.setEnabled(false);
        previousButton.setEnabled(false);
        nextButton.setEnabled(false);

        io.execute(() -> {
            try {
                int[] handles = camera.getImageHandles();
                if (handles.length == 0) throw new Exception("No playable images found");
                playbackHandles = handles;
                loadPlaybackImage(0, true);
            } catch (Exception e) {
                showPlaybackError(e);
            }
        });
    }

    private void loadPlaybackImage(int index) {
        loadPlaybackImage(index, true);
    }

    private void loadPlaybackImage(int index, boolean retryOnStaleHandle) {
        try {
            int handle = playbackHandles[index];
            byte[] jpeg = camera.getThumbnail(handle);
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bitmap == null) throw new Exception("Camera thumbnail could not be decoded");

            main.post(() -> {
                playbackIndex = index;
                playbackImage.setImageBitmap(bitmap);
                playbackImage.setVisibility(View.VISIBLE);
                playbackStatus.setVisibility(View.VISIBLE);
                playbackNav.setVisibility(View.VISIBLE);
                playbackStatus.setText("Photo " + (index + 1) + " / " + playbackHandles.length);
                playbackButton.setVisibility(View.GONE);
                closePlaybackButton.setVisibility(View.VISIBLE);
                previousButton.setEnabled(index > 0);
                nextButton.setEnabled(playbackHandles.length > 0);
                playbackButton.setEnabled(true);
            });
        } catch (Exception e) {
            String message = e.getMessage();
            boolean staleHandle = retryOnStaleHandle
                    && message != null
                    && message.toLowerCase(Locale.US).contains("0x2009");

            if (staleHandle) {
                try {
                    int[] handles = camera.getImageHandles();
                    if (handles.length == 0) throw new Exception("No playable images found");
                    playbackHandles = handles;
                    int retryIndex = Math.min(index, handles.length - 1);
                    loadPlaybackImage(retryIndex, false);
                    return;
                } catch (Exception retryError) {
                    showPlaybackError(retryError);
                    return;
                }
            }

            showPlaybackError(e);
        }
    }

    private void showPlaybackError(Exception e) {
        String message = e.getMessage();
        main.post(() -> {
            playbackStatus.setVisibility(View.VISIBLE);
            playbackStatus.setText("Playback error: " + message);
            playbackButton.setEnabled(true);
            previousButton.setEnabled(playbackIndex > 0);
            nextButton.setEnabled(playbackHandles.length > 0 && playbackIndex >= 0);
        });
    }

    private void startSequence() {
        if (!camera.isConnected()) {
            Toast.makeText(this, "Connect the camera first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isReadyToStart()) {
            updateReadinessStatus();
            Toast.makeText(this, countdown.getText(), Toast.LENGTH_SHORT).show();
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
        countdown.setTextColor(RED);
        updateButtons();

        long totalMs = plannedTotalMs(exposureSeconds, pauseSeconds, shots);
        totalTimeView.setText("Total time: " + formatDuration(totalMs));
        timeLeftView.setText("Time left: " + formatDuration(totalMs));

        io.execute(() -> runSequence(exposureSeconds, pauseSeconds, shots));
    }

    private void runSequence(double exposureSeconds, double pauseSeconds, int shots) {
        try {
            for (int shot = 1; shot <= shots && !cancelRequested; shot++) {
                int shotNo = shot;

                main.post(() -> setStatus("Waiting for camera…"));
                camera.startCaptureNoAf();

                main.post(() -> setStatus("Capturing"));
                boolean fullExposure = exposureCountdown(
                        shotNo, shots, exposureSeconds, pauseSeconds);

                main.post(() -> setStatus("Sending STOP"));
                camera.stopCapture();

                if (!fullExposure || cancelRequested) break;

                completed = shot;
                main.post(() -> setStatus("Exposure saved"));

                if (shot < shots && pauseSeconds > 0) {
                    if (!pauseCountdown(shotNo, shots, exposureSeconds, pauseSeconds)) break;
                }
            }

            if (!cancelRequested && completed == shots) {
                main.post(() -> {
                    countdown.setText("Finished — " + completed + " exposures");
                    timeLeftView.setText("Time left: 00:00:00");
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

    private boolean exposureCountdown(int shot, int total, double exposureSeconds, double pauseSeconds) {
        long exposureMs = (long)(exposureSeconds * 1000.0);
        long pauseMs = (long)(pauseSeconds * 1000.0);
        long end = System.currentTimeMillis() + exposureMs;

        while (!cancelRequested) {
            long remaining = end - System.currentTimeMillis();
            if (remaining <= 0) return true;

            long futureMs = (long)(total - shot) * exposureMs
                    + (long)(total - shot) * pauseMs;
            long sequenceRemaining = remaining + futureMs;
            double remainingSec = remaining / 1000.0;

            main.post(() -> {
                countdown.setText(String.format(
                        Locale.US, "Exposure %d / %d — %.1f s", shot, total, remainingSec));
                timeLeftView.setText("Time left: " + formatDuration(sequenceRemaining));
            });

            try {
                Thread.sleep(Math.min(100, Math.max(1, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean pauseCountdown(int shot, int total, double exposureSeconds, double pauseSeconds) {
        long exposureMs = (long)(exposureSeconds * 1000.0);
        long pauseMs = (long)(pauseSeconds * 1000.0);
        long end = System.currentTimeMillis() + pauseMs;

        while (!cancelRequested) {
            long remaining = end - System.currentTimeMillis();
            if (remaining <= 0) return true;

            long futureExposureMs = (long)(total - shot) * exposureMs;
            long futurePauseMs = (long)Math.max(0, total - shot - 1) * pauseMs;
            long sequenceRemaining = remaining + futureExposureMs + futurePauseMs;
            double remainingSec = remaining / 1000.0;

            main.post(() -> {
                countdown.setText(String.format(
                        Locale.US, "Next exposure in %.1f s", remainingSec));
                timeLeftView.setText("Time left: " + formatDuration(sequenceRemaining));
            });

            try {
                Thread.sleep(Math.min(100, Math.max(1, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void updatePlannedTimes() {
        try {
            double exposureSeconds = Math.max(0.2,
                    Double.parseDouble(exposureField.getText().toString().trim()));
            double pauseSeconds = Math.max(0.0,
                    Double.parseDouble(pauseField.getText().toString().trim()));
            int shots = Math.max(1,
                    Integer.parseInt(countField.getText().toString().trim()));

            long totalMs = plannedTotalMs(exposureSeconds, pauseSeconds, shots);
            totalTimeView.setText("Total time: " + formatDuration(totalMs));
            timeLeftView.setText("Time left: " + formatDuration(totalMs));
        } catch (Exception ignored) {
            totalTimeView.setText("Total time: --:--:--");
            timeLeftView.setText("Time left: --:--:--");
        }
        updateReadinessStatus();
        updateButtons();
    }

    private long plannedTotalMs(double exposureSeconds, double pauseSeconds, int shots) {
        long exposureMs = (long)(exposureSeconds * 1000.0);
        long pauseMs = (long)(pauseSeconds * 1000.0);
        return (long)shots * exposureMs + (long)Math.max(0, shots - 1) * pauseMs;
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0, (millis + 999) / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private void stopNow() {
        if (!running) return;
        cancelRequested = true;
        countdown.setText("Stopping exposure…");
        setStatus("STOP requested");
    }

    private boolean hasValidInputs() {
        try {
            String exposureText = exposureField.getText().toString().trim();
            String countText = countField.getText().toString().trim();
            String pauseText = pauseField.getText().toString().trim();
            if (exposureText.isEmpty() || countText.isEmpty() || pauseText.isEmpty()) return false;

            double exposure = Double.parseDouble(exposureText);
            int shots = Integer.parseInt(countText);
            double pause = Double.parseDouble(pauseText);
            return exposure > 0.0 && shots >= 1 && pause >= 0.0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isReadyToStart() {
        return camera != null && camera.isConnected() && cameraSetupReady && hasValidInputs();
    }

    private void updateReadinessStatus() {
        if (running || countdown == null || exposureField == null || countField == null || pauseField == null) return;

        String reason = null;
        if (camera == null || !camera.isConnected()) {
            reason = "Connect camera";
        } else if (!manualModeReady) {
            reason = "Camera check: set Manual mode";
        } else if (!bulbReady) {
            reason = "Camera check: set Bulb";
        } else if (!focusReady) {
            reason = "Camera check: set MF";
        } else {
            String exposureText = exposureField.getText().toString().trim();
            String countText = countField.getText().toString().trim();
            String pauseText = pauseField.getText().toString().trim();

            if (exposureText.isEmpty()) {
                reason = "Set exposure time";
            } else {
                try {
                    if (Double.parseDouble(exposureText) <= 0.0) reason = "Exposure time must be above 0";
                } catch (Exception e) {
                    reason = "Check exposure time";
                }
            }

            if (reason == null) {
                if (countText.isEmpty()) {
                    reason = "Set number of exposures";
                } else {
                    try {
                        if (Integer.parseInt(countText) < 1) reason = "Number of exposures must be at least 1";
                    } catch (Exception e) {
                        reason = "Check number of exposures";
                    }
                }
            }

            if (reason == null) {
                if (pauseText.isEmpty()) {
                    reason = "Set pause time";
                } else {
                    try {
                        if (Double.parseDouble(pauseText) < 0.0) reason = "Pause cannot be negative";
                    } catch (Exception e) {
                        reason = "Check pause time";
                    }
                }
            }
        }

        if (reason == null) {
            countdown.setText("Ready");
            countdown.setTextColor(RED);
        } else {
            countdown.setText(reason);
            countdown.setTextColor(GREY);
        }
    }

    private void updateButtons() {
        if (startButton == null || stopButton == null
                || exposureField == null || pauseField == null || countField == null) return;

        boolean connected = camera != null && camera.isConnected();
        boolean canStart = !running && isReadyToStart();
        startButton.setEnabled(canStart);
        startButton.setTextColor(canStart ? RED : DISABLED_GREY);
        startButton.setBackgroundTintList(ColorStateList.valueOf(
                canStart ? DARK_BUTTON : DISABLED_BUTTON));
        stopButton.setEnabled(running);
        exposureField.setEnabled(!running);
        pauseField.setEnabled(!running);
        countField.setEnabled(!running);
        if (playbackButton != null) playbackButton.setEnabled(connected && !running);
        if (closePlaybackButton != null) closePlaybackButton.setEnabled(!running);
        if (previousButton != null) previousButton.setEnabled(connected && !running && playbackIndex > 0);
        if (nextButton != null) nextButton.setEnabled(connected && !running
                && playbackHandles.length > 0 && playbackIndex >= 0);
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
