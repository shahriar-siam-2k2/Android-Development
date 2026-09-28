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
import android.widget.EditText;
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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // --- DEVELOPMENT MODE ---
    // Set to true to bypass IP connection tests. Set to false for production.
    private boolean developmentMode = false;

    // Target ESP32 Local IP
    private String robotIp = "192.168.4.1"; // Default fallback

    // Firebase Architecture
    private FirebaseDatabase database;
    private DatabaseReference cmdRef;
    private DatabaseReference telemetryHistoryRef;
    private DatabaseReference connectedRef;

    // HTTP Executor for Direct ESP32 Communication
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    // Timers & State
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable pumpBlinkRunnable;
    private Runnable telemetryPoller;
    private boolean pumpColorToggle = false;
    private long lastRobotHeartbeat = 0;

    private boolean isLoggedIn = false;
    private boolean isRobotConnected = false;
    private boolean isCloudConnected = false;
    private boolean wasOffline = true;

    // UI Elements
    private LinearLayout loginOverlay;
    private View mainAppContent;
    private EditText inputUsername, inputPassword;
    private WebView streamWebView, mapWebView;
    private TextView txtBattery, txtWaterLevel, txtHumidity, txtTemp, txtCloudStatus, txtRobotStatus, txtCloudError;
    private View cloudIndicator, robotIndicator;
    private LinearLayout controllerContainer;
    private Button btnPumpToggle, btnEStop, btnCallRobot, btnForceSync, btnConnectManual, btnExit;

    // Overlay Elements
    private View ipDialogOverlay, syncLoginOverlay;
    private EditText inputManualIp, inputSyncUsername, inputSyncPassword;
    private Button btnConnectIp, btnCancelIp, btnSubmitSyncLogin, btnCancelSyncLogin;

    private boolean isPumpActive = false;
    private boolean isLocked = false;
    private double myLat = 23.7937;
    private double myLon = 90.4066;

    // Track which button triggered the IP Popup
    private enum IpDialogContext { MANUAL, BYPASS }
    private IpDialogContext currentIpContext;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable offline disk caching. Commands log locally without net, upload when connected.
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        } catch (Exception ignored) {}

        setContentView(R.layout.activity_main);

        // Initialize Firebase
        database = FirebaseDatabase.getInstance("https://firebot-db-default-rtdb.asia-southeast1.firebasedatabase.app/");
        cmdRef = database.getReference("commands/latest");
        telemetryHistoryRef = database.getReference("telemetry_history");
        connectedRef = database.getReference(".info/connected");

        cmdRef.keepSynced(true);

        bindViews();
        setupLoginSystem();
        setupWebViews();
        setupChassisControls();
        setupActuatorControls();
        setupSpecialButtons();
        setupOverlays();
        startCloudListeners();
    }

    private void bindViews() {
        loginOverlay = findViewById(R.id.loginOverlay);
        mainAppContent = findViewById(R.id.mainAppContent);
        inputUsername = findViewById(R.id.inputUsername);
        inputPassword = findViewById(R.id.inputPassword);

        streamWebView = findViewById(R.id.streamWebView);
        mapWebView = findViewById(R.id.mapWebView);
        txtBattery = findViewById(R.id.txtBattery);
        txtWaterLevel = findViewById(R.id.txtWaterLevel);
        txtHumidity = findViewById(R.id.txtHumidity);
        txtTemp = findViewById(R.id.txtTemp);

        txtCloudStatus = findViewById(R.id.txtCloudStatus);
        txtCloudError = findViewById(R.id.txtCloudError);
        cloudIndicator = findViewById(R.id.cloudIndicator);
        txtRobotStatus = findViewById(R.id.txtRobotStatus);
        robotIndicator = findViewById(R.id.robotIndicator);

        controllerContainer = findViewById(R.id.controllerContainer);
        btnPumpToggle = findViewById(R.id.btnPumpToggle);
        btnEStop = findViewById(R.id.btnEStop);
        btnCallRobot = findViewById(R.id.btnCallRobot);
        btnForceSync = findViewById(R.id.btnForceSync);
        btnConnectManual = findViewById(R.id.btnConnectManual);
        btnExit = findViewById(R.id.btnExit);

        ipDialogOverlay = findViewById(R.id.ipDialogOverlay);
        inputManualIp = findViewById(R.id.inputManualIp);
        btnConnectIp = findViewById(R.id.btnConnectIp);
        btnCancelIp = findViewById(R.id.btnCancelIp);

        syncLoginOverlay = findViewById(R.id.syncLoginOverlay);
        inputSyncUsername = findViewById(R.id.inputSyncUsername);
        inputSyncPassword = findViewById(R.id.inputSyncPassword);
        btnSubmitSyncLogin = findViewById(R.id.btnSubmitSyncLogin);
        btnCancelSyncLogin = findViewById(R.id.btnCancelSyncLogin);
    }

    private void setupLoginSystem() {
        findViewById(R.id.btnLogin).setOnClickListener(v -> {
            if (!isCloudConnected && !developmentMode) {
                Toast.makeText(this, "Cloud is offline. Cannot login.", Toast.LENGTH_SHORT).show();
                return;
            }

            String user = inputUsername.getText().toString().trim();
            String pass = inputPassword.getText().toString();

            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Enter credentials", Toast.LENGTH_SHORT).show();
                return;
            }

            database.getReference("users").child(user).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (snapshot.exists()) {
                        String dbPass = snapshot.child("password").getValue(String.class);
                        if (pass.equals(dbPass)) {
                            if (snapshot.hasChild("robot_ip")) {
                                robotIp = snapshot.child("robot_ip").getValue(String.class);
                            }
                            isLoggedIn = true;
                            unlockApp();
                        } else {
                            Toast.makeText(MainActivity.this, "Wrong password", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(MainActivity.this, "User not found", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Toast.makeText(MainActivity.this, "Network Error", Toast.LENGTH_SHORT).show();
                }
            });
        });

        findViewById(R.id.btnBypassOffline).setOnClickListener(v -> {
            currentIpContext = IpDialogContext.BYPASS;
            ipDialogOverlay.setVisibility(View.VISIBLE);
        });

        // Exit Button inside control panel
        btnExit.setOnClickListener(v -> {
            isLoggedIn = false;
            loginOverlay.setVisibility(View.VISIBLE);
            mainAppContent.setVisibility(View.GONE);
            streamWebView.loadUrl("about:blank");
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupOverlays() {
        // --- IP CONNECT OVERLAY ---
        btnConnectManual.setOnClickListener(v -> {
            if (isRobotConnected && !developmentMode) {
                Toast.makeText(this, "Robot is already connected with App", Toast.LENGTH_SHORT).show();
            } else {
                currentIpContext = IpDialogContext.MANUAL;
                ipDialogOverlay.setVisibility(View.VISIBLE);
            }
        });

        btnCancelIp.setOnClickListener(v -> ipDialogOverlay.setVisibility(View.GONE));

        btnConnectIp.setOnClickListener(v -> {
            String ip = inputManualIp.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(this, "Enter an IP address", Toast.LENGTH_SHORT).show();
                return;
            }

            // --- DEVELOPMENT MODE BYPASS ---
            if (developmentMode) {
                Toast.makeText(this, "Dev Mode: Bypassing Connection Test", Toast.LENGTH_SHORT).show();
                robotIp = ip;
                ipDialogOverlay.setVisibility(View.GONE);
                if (currentIpContext == IpDialogContext.BYPASS) {
                    isLoggedIn = false;
                    unlockApp();
                } else {
                    loadVideoStream(); // Reload stream with new dummy IP
                }
                return; // Stop here, do not run the actual HTTP test below
            }
            // -------------------------------

            Toast.makeText(this, "Attempting connection...", Toast.LENGTH_SHORT).show();

            testRobotConnection(ip, success -> {
                if (success) {
                    robotIp = ip;
                    ipDialogOverlay.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "Connected successfully!", Toast.LENGTH_SHORT).show();
                    if (currentIpContext == IpDialogContext.BYPASS) {
                        isLoggedIn = false;
                        unlockApp();
                    } else {
                        loadVideoStream(); // Reload stream with new IP
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Failed to connect to IP: " + ip, Toast.LENGTH_SHORT).show();
                }
            });
        });

        // --- SYNC LOGIN OVERLAY ---
        btnCancelSyncLogin.setOnClickListener(v -> syncLoginOverlay.setVisibility(View.GONE));

        btnSubmitSyncLogin.setOnClickListener(v -> {
            if (!isCloudConnected && !developmentMode) {
                Toast.makeText(this, "Couldn't Connect to Cloud", Toast.LENGTH_SHORT).show();
                return;
            }
            String user = inputSyncUsername.getText().toString().trim();
            String pass = inputSyncPassword.getText().toString();

            database.getReference("users").child(user).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (snapshot.exists() && pass.equals(snapshot.child("password").getValue(String.class))) {
                        isLoggedIn = true;
                        syncLoginOverlay.setVisibility(View.GONE);
                        Toast.makeText(MainActivity.this, "Login successful, syncing...", Toast.LENGTH_SHORT).show();
                        performSync();
                    } else {
                        Toast.makeText(MainActivity.this, "Login Failed", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override
                public void onCancelled(@NonNull DatabaseError error) {}
            });
        });
    }

    private void unlockApp() {
        loginOverlay.setVisibility(View.GONE);
        mainAppContent.setVisibility(View.VISIBLE);
        loadVideoStream();
        startLocalTelemetryPoller();
    }

    private void loadVideoStream() {
        String streamHtml = "<html><body style='margin:0;padding:0;background-color:black;'><img src='http://"
                + robotIp + ":81/stream' width='100%' height='100%' style='object-fit:contain;'/></body></html>";
        streamWebView.loadDataWithBaseURL(null, streamHtml, "text/html", "UTF-8", null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebViews() {
        WebSettings streamSettings = streamWebView.getSettings();
        streamSettings.setJavaScriptEnabled(true);
        streamSettings.setLoadWithOverviewMode(true);
        streamSettings.setUseWideViewPort(true);
        streamWebView.setWebViewClient(new WebViewClient());

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
                        btnPumpToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(pumpColorToggle ? 0xFF008799 : 0xFF00E1FF));
                        uiHandler.postDelayed(this, 300);
                    }
                };
                uiHandler.post(pumpBlinkRunnable);
            } else {
                sendCommand("PUMP", "OFF");
                if (pumpBlinkRunnable != null) uiHandler.removeCallbacks(pumpBlinkRunnable);
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
            Toast.makeText(this, "Calling robot...", Toast.LENGTH_SHORT).show();
        });

        btnEStop.setOnClickListener(v -> {
            isLocked = !isLocked;
            if (isLocked) {
                sendCommand("ESTOP", "ON");
                btnEStop.setBackgroundColor(0xFFB71C1C);
                btnEStop.setText("UNLOCK");
                setViewGroupEnabled(controllerContainer, false);
            } else {
                sendCommand("ESTOP", "OFF");
                btnEStop.setBackgroundColor(0xFFD32F2F);
                btnEStop.setText("E-STOP");
                setViewGroupEnabled(controllerContainer, true);
            }
        });

        btnForceSync.setOnClickListener(v -> {
            if (!isCloudConnected && !developmentMode) {
                Toast.makeText(this, "Couldn't Connect to Cloud", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isLoggedIn) {
                syncLoginOverlay.setVisibility(View.VISIBLE);
            } else {
                performSync();
            }
        });
    }

    private void performSync() {
        FirebaseDatabase.getInstance().goOffline();
        FirebaseDatabase.getInstance().goOnline();
        Toast.makeText(this, "Syncing offline data to cloud...", Toast.LENGTH_SHORT).show();

        // Send a lightweight ping to the database to verify the connection
        database.getReference("system/last_sync").setValue(System.currentTimeMillis(), new DatabaseReference.CompletionListener() {
            @Override
            public void onComplete(DatabaseError error, @NonNull DatabaseReference ref) {
                if (error == null) {
                    Toast.makeText(MainActivity.this, "Sync Successful!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Sync Failed: Check Connection", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void blinkStopButton(Button btnStop) {
        for (int i = 0; i < 10; i++) {
            final boolean isDark = (i % 2 == 0);
            uiHandler.postDelayed(() -> btnStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isDark ? 0xFF991F00 : 0xFFFF3300)), i * 150L);
        }
    }

    private void setViewGroupEnabled(ViewGroup viewGroup, boolean enabled) {
        viewGroup.setAlpha(enabled ? 1.0f : 0.4f);
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            child.setEnabled(enabled);
            if (child instanceof ViewGroup) setViewGroupEnabled((ViewGroup) child, enabled);
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

    private void sendCommand(String part, String cmd) {
        // Queue to Firebase
        Map<String, Object> commandData = new HashMap<>();
        commandData.put("part", part);
        commandData.put("cmd", cmd);
        commandData.put("timestamp", System.currentTimeMillis());
        cmdRef.setValue(commandData);

        // Fire direct HTTP to ESP32
        networkExecutor.execute(() -> {
            try {
                URL url = new URL("http://" + robotIp + "/command?part=" + part + "&cmd=" + cmd);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1000);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        });
    }

    // Helper to verify ESP32 connectivity before locking in an IP
    private interface ConnectionCallback {
        void onResult(boolean success);
    }

    private void testRobotConnection(String ip, ConnectionCallback callback) {
        networkExecutor.execute(() -> {
            boolean success = false;
            try {
                URL url = new URL("http://" + ip + "/telemetry"); // Pinging the data endpoint
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2500);
                if (conn.getResponseCode() == 200) {
                    success = true;
                }
                conn.disconnect();
            } catch (Exception e) {
                success = false;
            }
            final boolean finalSuccess = success;
            uiHandler.post(() -> callback.onResult(finalSuccess));
        });
    }

    private void startLocalTelemetryPoller() {
        if (telemetryPoller != null) return;
        telemetryPoller = new Runnable() {
            @Override
            public void run() {
                // If in dev mode, we can optionally fake connected status even if the HTTP poll fails
                // but for now, we'll let it try to poll so it doesn't break normal behavior if a real ESP is connected.
                networkExecutor.execute(() -> {
                    try {
                        URL url = new URL("http://" + robotIp + "/telemetry");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(1500);

                        if (conn.getResponseCode() == 200) {
                            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                            StringBuilder response = new StringBuilder();
                            String line;
                            while ((line = in.readLine()) != null) response.append(line);
                            in.close();

                            JSONObject data = new JSONObject(response.toString());

                            Map<String, Object> teleMap = new HashMap<>();
                            teleMap.put("bat", data.getInt("bat"));
                            teleMap.put("water", data.getInt("water"));
                            teleMap.put("hum", data.getDouble("hum"));
                            teleMap.put("temp", data.getDouble("temp"));
                            teleMap.put("lat", data.getDouble("lat"));
                            teleMap.put("lon", data.getDouble("lon"));
                            teleMap.put("timestamp", System.currentTimeMillis());
                            telemetryHistoryRef.push().setValue(teleMap);

                            uiHandler.post(() -> {
                                lastRobotHeartbeat = System.currentTimeMillis();
                                updateRobotStatus(true);
                                txtBattery.setText("🔋 Battery: " + data.optInt("bat") + "%");
                                txtWaterLevel.setText("💧 Tank: " + data.optInt("water") + "%");
                                txtHumidity.setText("☁️ Humid: " + data.optDouble("hum") + " % RH");
                                txtTemp.setText("🌡 Temp: " + data.optDouble("temp") + " °C");
                                updateMapLocation(data.optDouble("lat"), data.optDouble("lon"));
                            });
                        }
                        conn.disconnect();
                    } catch (Exception e) {
                        if (System.currentTimeMillis() - lastRobotHeartbeat > 3000) {
                            uiHandler.post(() -> {
                                // If developmentMode is true, we keep the robot status online to test the UI
                                updateRobotStatus(developmentMode);
                            });
                        }
                    }
                });
                uiHandler.postDelayed(this, 1500);
            }
        };
        uiHandler.post(telemetryPoller);
    }

    private void startCloudListeners() {
        connectedRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                isCloudConnected = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                uiHandler.post(() -> {
                    if (isCloudConnected) {
                        txtCloudStatus.setText("CLOUD CONNECTED");
                        txtCloudStatus.setTextColor(0xFF4CAF50);
                        cloudIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
                        btnForceSync.setEnabled(true);
                        txtCloudError.setVisibility(View.GONE);

                        // --- AUTO-SYNC DETECTION ---
                        // If we were offline and just reconnected, Firebase is automatically syncing.
                        if (wasOffline) {
                            Toast.makeText(MainActivity.this, "Network detected. Auto-syncing...", Toast.LENGTH_SHORT).show();

                            // Send a ping at the back of the line. When this ping succeeds,
                            // it guarantees all previously queued offline commands have also synced.
                            database.getReference("system/last_sync").setValue(System.currentTimeMillis(), new DatabaseReference.CompletionListener() {
                                @Override
                                public void onComplete(DatabaseError error, @NonNull DatabaseReference ref) {
                                    if (error == null) {
                                        Toast.makeText(MainActivity.this, "Auto-Sync Successful!", Toast.LENGTH_SHORT).show();
                                    } else {
                                        Toast.makeText(MainActivity.this, "Auto-Sync Failed", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                        }
                        wasOffline = false; // Reset the tracker

                    } else {
                        wasOffline = true; // Mark that the app lost internet
                        txtCloudStatus.setText("CLOUD OFFLINE");
                        txtCloudStatus.setTextColor(0xFFF44336);
                        cloudIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF44336));
                        btnForceSync.setEnabled(developmentMode); // Allow sync button if in dev mode
                        txtCloudError.setVisibility(View.VISIBLE);
                    }
                });
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void updateRobotStatus(boolean isConnected) {
        isRobotConnected = isConnected;
        if (isConnected) {
            txtRobotStatus.setText("ROBOT ONLINE");
            txtRobotStatus.setTextColor(0xFF4CAF50);
            robotIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
        } else {
            txtRobotStatus.setText("ROBOT OFFLINE");
            txtRobotStatus.setTextColor(0xFFF44336);
            robotIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF44336));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
    }
}