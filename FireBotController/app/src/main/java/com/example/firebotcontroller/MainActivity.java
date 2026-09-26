package com.example.firebotcontroller;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    // You still need the local IP for the raw video stream (until port forwarding is set up)
    private static final String STREAM_URL = "http://192.168.4.1:81/stream";

    // --- Firebase References ---
    private FirebaseDatabase database;
    private DatabaseReference cmdRef;
    private DatabaseReference telemetryRef;
    private DatabaseReference connectedRef;

    // Pump Blinking Variables
    private final Handler pumpBlinkHandler = new Handler(Looper.getMainLooper());
    private Runnable pumpBlinkRunnable;
    private boolean pumpColorToggle = false;

    // Robot Connection Watchdog
    private long lastRobotHeartbeat = 0;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable watchdogRunnable;

    private WebView streamWebView, mapWebView;
    private TextView txtBattery, txtWaterLevel, txtHumidity, txtTemp, txtCloudStatus, txtRobotStatus;
    private View cloudIndicator, robotIndicator;
    private LinearLayout controllerContainer;
    private Button btnPumpToggle, btnEStop, btnCallRobot;

    private boolean isPumpActive = false;
    private boolean isLocked = false;

    // Fallback GPS location
    private double myLat = 23.7937;
    private double myLon = 90.4066;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- Initialize Firebase ---
        database = FirebaseDatabase.getInstance("https://firebot-db-default-rtdb.asia-southeast1.firebasedatabase.app/");
        cmdRef = database.getReference("commands/latest");
        telemetryRef = database.getReference("telemetry");
        connectedRef = database.getReference(".info/connected");

        // Bind Views
        streamWebView = findViewById(R.id.streamWebView);
        mapWebView = findViewById(R.id.mapWebView);
        txtBattery = findViewById(R.id.txtBattery);
        txtWaterLevel = findViewById(R.id.txtWaterLevel);
        txtHumidity = findViewById(R.id.txtHumidity);
        txtTemp = findViewById(R.id.txtTemp);

        txtCloudStatus = findViewById(R.id.txtCloudStatus);
        cloudIndicator = findViewById(R.id.cloudIndicator);
        txtRobotStatus = findViewById(R.id.txtRobotStatus);
        robotIndicator = findViewById(R.id.robotIndicator);

        controllerContainer = findViewById(R.id.controllerContainer);
        btnPumpToggle = findViewById(R.id.btnPumpToggle);
        btnEStop = findViewById(R.id.btnEStop);
        btnCallRobot = findViewById(R.id.btnCallRobot);

        setupWebViews();
        setupChassisControls();
        setupActuatorControls();
        setupSpecialButtons();

        // Start listening to Firebase
        startFirebaseListeners();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebViews() {
        WebSettings streamSettings = streamWebView.getSettings();
        streamSettings.setJavaScriptEnabled(true);
        streamSettings.setLoadWithOverviewMode(true);
        streamSettings.setUseWideViewPort(true);
        streamWebView.setWebViewClient(new WebViewClient());

        String streamHtml = "<html><body style='margin:0;padding:0;background-color:black;'><img src='"
                + STREAM_URL + "' width='100%' height='100%' style='object-fit:contain;'/></body></html>";
        streamWebView.loadDataWithBaseURL(null, streamHtml, "text/html", "UTF-8", null);

        WebSettings mapSettings = mapWebView.getSettings();
        mapSettings.setJavaScriptEnabled(true);
        mapWebView.setWebViewClient(new WebViewClient());
        updateMapLocation(myLat, myLon);
    }

    private void updateMapLocation(double lat, double lon) {
        String mapHtml = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no' />"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet/dist/leaflet.css' />"
                + "<script src='https://unpkg.com/leaflet/dist/leaflet.js'></script>"
                + "<style>body { margin:0; padding:0; } #map { width:100vw; height:100vh; } .leaflet-control-attribution { display:none !important; }</style></head>"
                + "<body><div id='map'></div><script>"
                + "var map = L.map('map', {zoomControl: false}).setView([" + lat + ", " + lon + "], 16);"
                + "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png').addTo(map);"
                + "L.marker([" + lat + ", " + lon + "]).addTo(map);"
                + "</script></body></html>";

        mapWebView.loadDataWithBaseURL(null, mapHtml, "text/html", "UTF-8", null);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupChassisControls() {
        bindHoldAction(findViewById(R.id.btnFwd), "CHASSIS", "FORWARD");
        bindHoldAction(findViewById(R.id.btnRev), "CHASSIS", "REVERSE");
        bindHoldAction(findViewById(R.id.btnLeft), "CHASSIS", "LEFT");
        bindHoldAction(findViewById(R.id.btnRight), "CHASSIS", "RIGHT");

        findViewById(R.id.btnStop).setOnClickListener(v -> {
            if (!isLocked) {
                sendCommand("CHASSIS", "STOP");
                blinkStopButton((Button) v);
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupActuatorControls() {
        bindHoldAction(findViewById(R.id.btnArmUp), "ARM_LIFT", "UP");
        bindHoldAction(findViewById(R.id.btnArmDown), "ARM_LIFT", "DOWN");
        bindHoldAction(findViewById(R.id.btnBaseL), "ARM_TURN", "LEFT");
        bindHoldAction(findViewById(R.id.btnBaseR), "ARM_TURN", "RIGHT");
        bindHoldAction(findViewById(R.id.btnNozzleUp), "NOZZLE", "UP");
        bindHoldAction(findViewById(R.id.btnNozzleDown), "NOZZLE", "DOWN");
        bindHoldAction(findViewById(R.id.btnNozzlePanL), "NOZZLE", "LEFT");
        bindHoldAction(findViewById(R.id.btnNozzlePanR), "NOZZLE", "RIGHT");

        btnPumpToggle.setOnClickListener(v -> {
            if (isLocked) return;

            isPumpActive = !isPumpActive;
            if (isPumpActive) {
                sendCommand("PUMP", "ON");
                btnPumpToggle.setText("HAULT");
                btnPumpToggle.setTextColor(0xFFFFFFFF);

                pumpBlinkRunnable = new Runnable() {
                    @Override
                    public void run() {
                        pumpColorToggle = !pumpColorToggle;
                        if (pumpColorToggle) {
                            btnPumpToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF008799));
                        } else {
                            btnPumpToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF00E1FF));
                        }
                        pumpBlinkHandler.postDelayed(this, 300);
                    }
                };
                pumpBlinkHandler.post(pumpBlinkRunnable);
            } else {
                sendCommand("PUMP", "OFF");
                if (pumpBlinkRunnable != null) pumpBlinkHandler.removeCallbacks(pumpBlinkRunnable);
                btnPumpToggle.setText("PUMP");
                btnPumpToggle.setTextColor(0xFF000000);
                btnPumpToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF00E1FF));
            }
        });
    }

    private void setupSpecialButtons() {
        btnCallRobot.setOnClickListener(v -> {
            if (isLocked) return;
            sendCommand("AUTOPILOT", "CALL_" + myLat + "_" + myLon);
            Toast.makeText(this, "Calling robot to your location...", Toast.LENGTH_SHORT).show();
        });

        btnEStop.setOnClickListener(v -> {
            isLocked = !isLocked;
            if (isLocked) {
                sendCommand("ESTOP", "ON");
                btnEStop.setBackgroundColor(0xFFB71C1C);
                btnEStop.setText("UNLOCK CONTROLS");
                setViewGroupEnabled(controllerContainer, false);
            } else {
                sendCommand("ESTOP", "OFF");
                btnEStop.setBackgroundColor(0xFFD32F2F);
                btnEStop.setText("E-STOP (LOCK)");
                setViewGroupEnabled(controllerContainer, true);
            }
        });
    }

    private void blinkStopButton(Button btnStop) {
        Handler handler = new Handler(Looper.getMainLooper());
        int blinkCount = 5;
        long delay = 150;

        for (int i = 0; i < blinkCount * 2; i++) {
            final boolean isDark = (i % 2 == 0);
            handler.postDelayed(() -> {
                if (isDark) {
                    btnStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF991F00));
                } else {
                    btnStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF3300));
                }
            }, i * delay);
        }
    }

    private void setViewGroupEnabled(ViewGroup viewGroup, boolean enabled) {
        viewGroup.setAlpha(enabled ? 1.0f : 0.4f);
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            child.setEnabled(enabled);
            if (child instanceof ViewGroup) {
                setViewGroupEnabled((ViewGroup) child, enabled);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindHoldAction(View view, String part, String cmd) {
        Button button = (Button) view;
        button.setOnTouchListener((v, event) -> {
            if (isLocked) return false;

            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                sendCommand(part, cmd);
                button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
                button.setTextColor(0xFF424242);
                v.setPressed(true);
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                sendCommand(part, "STOP");
                button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF424242));
                button.setTextColor(0xFFFFFFFF);
                v.setPressed(false);
                return true;
            }
            return false;
        });
    }

    // Writes the command directly to the Firebase Realtime Database
    private void sendCommand(String part, String cmd) {
        Map<String, Object> commandData = new HashMap<>();
        commandData.put("part", part);
        commandData.put("cmd", cmd);
        commandData.put("timestamp", System.currentTimeMillis());
        cmdRef.setValue(commandData);
    }

    private void startFirebaseListeners() {
        // 1. Listen for Telemetry and Heartbeat
        telemetryRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    try {
                        // The robot must push a timestamp with every telemetry upload
                        if (snapshot.hasChild("timestamp")) {
                            lastRobotHeartbeat = snapshot.child("timestamp").getValue(Long.class);
                            updateRobotStatus(true);
                        }

                        int battery = snapshot.child("bat").getValue(Integer.class);
                        int water = snapshot.child("water").getValue(Integer.class);
                        double humidity = snapshot.child("hum").getValue(Double.class);
                        double temp = snapshot.child("temp").getValue(Double.class);
                        double lat = snapshot.child("lat").getValue(Double.class);
                        double lon = snapshot.child("lon").getValue(Double.class);

                        runOnUiThread(() -> {
                            txtBattery.setText("🔋 Battery: " + battery + "%");
                            txtWaterLevel.setText("💧 Tank: " + water + "%");
                            txtHumidity.setText("☁️ Humid: " + humidity + " % RH");
                            txtTemp.setText("🌡 Temp: " + temp + " °C");
                            updateMapLocation(lat, lon);
                        });
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });

        // 2. Listen for the App's Connection to Firebase
        connectedRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                boolean connected = snapshot.getValue(Boolean.class);
                updateCloudStatus(connected);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });

        // 3. Start the Robot Watchdog (Checks every 1 second)
        watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                // If the last heartbeat is older than 4 seconds, mark Robot Offline
                if (System.currentTimeMillis() - lastRobotHeartbeat > 4000) {
                    updateRobotStatus(false);
                }
                watchdogHandler.postDelayed(this, 1000);
            }
        };
        watchdogHandler.post(watchdogRunnable);
    }

    private void updateCloudStatus(boolean isConnected) {
        runOnUiThread(() -> {
            if (isConnected) {
                txtCloudStatus.setText("CLOUD CONNECTED");
                txtCloudStatus.setTextColor(0xFF4CAF50); // Green
                cloudIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
            } else {
                txtCloudStatus.setText("CLOUD OFFLINE");
                txtCloudStatus.setTextColor(0xFFF44336); // Red
                cloudIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF44336));

                // If cloud drops, we assume we lost the robot too
                updateRobotStatus(false);
            }
        });
    }

    private void updateRobotStatus(boolean isConnected) {
        runOnUiThread(() -> {
            if (isConnected) {
                txtRobotStatus.setText("ROBOT ONLINE");
                txtRobotStatus.setTextColor(0xFF4CAF50); // Green
                robotIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
            } else {
                txtRobotStatus.setText("ROBOT OFFLINE");
                txtRobotStatus.setTextColor(0xFFF44336); // Red
                robotIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF44336));

                // Clear telemetry screen if robot drops
                txtBattery.setText("🔋 Battery: -- %");
                txtWaterLevel.setText("💧 Tank: -- %");
                txtHumidity.setText("☁️ Humid: -- % RH");
                txtTemp.setText("🌡 Temp: -- °C");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pumpBlinkHandler != null && pumpBlinkRunnable != null) {
            pumpBlinkHandler.removeCallbacks(pumpBlinkRunnable);
        }
        if (watchdogHandler != null && watchdogRunnable != null) {
            watchdogHandler.removeCallbacks(watchdogRunnable);
        }
    }
}