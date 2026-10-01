package com.example.firebotcontroller;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
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

import org.json.JSONArray;
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

    private boolean developmentMode = false;
    private String robotIp = "192.168.4.1";

    private FirebaseDatabase database;
    private DatabaseReference cmdRef;
    private DatabaseReference connectedRef;
    private ValueEventListener telemetryListener;

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable pumpBlinkRunnable;
    private Runnable telemetryPoller;
    private boolean pumpColorToggle = false;
    private long lastRobotHeartbeat = 0;

    private boolean isLoggedIn = false;
    private String currentUsername = "";
    private boolean isRobotConnected = false;
    private boolean isCloudConnected = false;
    private boolean wasOffline = true;

    private LinearLayout loginOverlay;
    private View mainAppContent;
    private EditText inputUsername, inputPassword;
    private WebView streamWebView, mapWebView;
    private TextView txtBattery, txtWaterLevel, txtHumidity, txtTemp, txtHeadingText, txtNoInternet;
    private View compassDial;
    private TextView txtCloudStatus, txtRobotStatus, txtCloudError;
    private View cloudIndicator, robotIndicator;
    private LinearLayout controllerContainer;
    private Button btnPumpToggle, btnEStop, btnCallRobot, btnForceSync, btnConnectManual, btnExit, btnLogin;

    private View ipDialogOverlay, syncLoginOverlay;
    private EditText inputManualIp, inputSyncUsername, inputSyncPassword;
    private Button btnConnectIp, btnCancelIp, btnSubmitSyncLogin, btnCancelSyncLogin;

    private boolean isPumpActive = false;
    private boolean isLocked = false;
    private double myLat = 23.7937;
    private double myLon = 90.4066;

    private enum IpDialogContext { MANUAL, BYPASS }
    private IpDialogContext currentIpContext;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        } catch (Exception ignored) {}

        setContentView(R.layout.activity_main);

        database = FirebaseDatabase.getInstance("https://firebot-db-default-rtdb.asia-southeast1.firebasedatabase.app/");
        cmdRef = database.getReference("commands/latest");
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
        txtHeadingText = findViewById(R.id.txtHeadingText);
        compassDial = findViewById(R.id.compassDial);

        txtCloudStatus = findViewById(R.id.txtCloudStatus);
        txtCloudError = findViewById(R.id.txtCloudError);
        cloudIndicator = findViewById(R.id.cloudIndicator);
        txtRobotStatus = findViewById(R.id.txtRobotStatus);
        robotIndicator = findViewById(R.id.robotIndicator);
        txtNoInternet = findViewById(R.id.txtNoInternet);

        controllerContainer = findViewById(R.id.controllerContainer);
        btnPumpToggle = findViewById(R.id.btnPumpToggle);
        btnEStop = findViewById(R.id.btnEStop);
        btnCallRobot = findViewById(R.id.btnCallRobot);
        btnForceSync = findViewById(R.id.btnForceSync);
        btnConnectManual = findViewById(R.id.btnConnectManual);
        btnExit = findViewById(R.id.btnExit);
        btnLogin = findViewById(R.id.btnLogin);

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
        btnLogin.setOnClickListener(v -> {
            if (!isCloudConnected && !developmentMode) return;

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
                            currentUsername = user;
                            if (snapshot.hasChild("robot_ip")) {
                                robotIp = snapshot.child("robot_ip").getValue(String.class);
                            }
                            isLoggedIn = true;

                            Map<String, Object> act = new HashMap<>();
                            act.put("action", "Logged in");
                            act.put("timestamp", System.currentTimeMillis());
                            database.getReference("users").child(user).child("activity_history").push().setValue(act);
                            database.getReference("users").child(user).child("last_login").setValue(System.currentTimeMillis());

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

        btnExit.setOnClickListener(v -> {
            if (telemetryPoller != null) {
                uiHandler.removeCallbacks(telemetryPoller);
                telemetryPoller = null;
            }

            networkExecutor.execute(() -> {
                try {
                    URL url = new URL("http://" + robotIp + "/command?part=SYSTEM&cmd=DISCONNECT");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(1000);
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception ignored) {}
            });

            if (isLoggedIn && !currentUsername.isEmpty()) {
                Map<String, Object> act = new HashMap<>();
                act.put("action", "Logged out");
                act.put("timestamp", System.currentTimeMillis());
                database.getReference("users").child(currentUsername).child("activity_history").push().setValue(act);
            }

            isLoggedIn = false;
            currentUsername = "";
            loginOverlay.setVisibility(View.VISIBLE);
            mainAppContent.setVisibility(View.GONE);
            streamWebView.loadUrl("about:blank");
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupOverlays() {
        btnConnectManual.setOnClickListener(v -> {
            if (isRobotConnected && !developmentMode) {
                Toast.makeText(this, "Robot is already connected", Toast.LENGTH_SHORT).show();
            } else {
                currentIpContext = IpDialogContext.MANUAL;
                ipDialogOverlay.setVisibility(View.VISIBLE);
            }
        });

        btnCancelIp.setOnClickListener(v -> ipDialogOverlay.setVisibility(View.GONE));

        btnConnectIp.setOnClickListener(v -> {
            String ip = inputManualIp.getText().toString().trim();
            if (ip.isEmpty()) return;

            Toast.makeText(this, "Attempting connection...", Toast.LENGTH_SHORT).show();
            testRobotConnection(ip, success -> {
                if (success || developmentMode) {
                    robotIp = ip;
                    ipDialogOverlay.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "Connected successfully!", Toast.LENGTH_SHORT).show();
                    if (currentIpContext == IpDialogContext.BYPASS) {
                        isLoggedIn = false;
                        unlockApp();
                    } else {
                        loadVideoStream();
                        startTelemetryCloudListener();
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Failed to connect to IP", Toast.LENGTH_SHORT).show();
                }
            });
        });

        btnCancelSyncLogin.setOnClickListener(v -> syncLoginOverlay.setVisibility(View.GONE));

        btnSubmitSyncLogin.setOnClickListener(v -> {
            if (!isCloudConnected && !developmentMode) return;

            String user = inputSyncUsername.getText().toString().trim();
            String pass = inputSyncPassword.getText().toString();

            database.getReference("users").child(user).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (snapshot.exists() && pass.equals(snapshot.child("password").getValue(String.class))) {
                        isLoggedIn = true;
                        currentUsername = user;
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
        loadMapData(isCloudConnected); // FIX: Passes internet state to the map
        startTelemetryCloudListener();
        startLocalTelemetryPoller();
    }

    private void loadVideoStream() {
        // FIX: Pointing to the dedicated Dual-Core video port (81)
        String streamHtml = "<html><body style='margin:0;padding:0;background-color:black;'><img src='http://"
                + robotIp + ":81/stream' width='100%' height='100%' style='object-fit:contain;'/></body></html>";

        streamWebView.loadDataWithBaseURL("http://" + robotIp, streamHtml, "text/html", "UTF-8", null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebViews() {
        WebSettings streamSettings = streamWebView.getSettings();
        streamSettings.setJavaScriptEnabled(true);
        streamSettings.setLoadWithOverviewMode(true);
        streamSettings.setUseWideViewPort(true);
        streamSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        streamWebView.setWebViewClient(new WebViewClient());

        WebSettings mapSettings = mapWebView.getSettings();
        mapSettings.setJavaScriptEnabled(true);
        mapWebView.setWebViewClient(new WebViewClient());
    }

    private void loadMapData(boolean isOnline) {
        // FIX: Display a clean offline fallback if there is no internet
        if (!isOnline && !developmentMode) {
            String offlineHtml = "<html><body style='margin:0;padding:0;background:#DDE3E8;display:flex;justify-content:center;align-items:center;height:100vh;font-family:sans-serif;color:#586977;'><h4>Map Unavailable Offline</h4></body></html>";
            mapWebView.loadDataWithBaseURL(null, offlineHtml, "text/html", "UTF-8", null);
            return;
        }

        String mapHtml = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no' />"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet/dist/leaflet.css' />"
                + "<script src='https://unpkg.com/leaflet/dist/leaflet.js'></script>"
                + "<style>body { margin:0; padding:0; background:#DDE3E8; } #map { width:100vw; height:100vh; } .leaflet-control-attribution { display:none !important; }</style></head>"
                + "<body><div id='map'></div><script>"
                + "var map = L.map('map', {zoomControl: false}).setView([" + myLat + ", " + myLon + "], 16);"
                + "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png').addTo(map);"
                + "var marker = L.marker([" + myLat + ", " + myLon + "]).addTo(map);"
                + "function updateLocation(lat, lon) { map.setView([lat, lon]); marker.setLatLng([lat, lon]); }"
                + "</script></body></html>";
        mapWebView.loadDataWithBaseURL(null, mapHtml, "text/html", "UTF-8", null);
    }

    private void updateMapLocation(double lat, double lon) {
        if (isCloudConnected) {
            mapWebView.evaluateJavascript("if(typeof updateLocation === 'function') { updateLocation(" + lat + ", " + lon + "); }", null);
        }
    }

    private String getHeadingText(double deg) {
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return dirs[(int) Math.round(((deg %= 360) < 0 ? deg + 360 : deg) / 45.0) % 8];
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
                        btnPumpToggle.setBackgroundTintList(ColorStateList.valueOf(pumpColorToggle ? 0xFF008799 : 0xFF00E1FF));
                        uiHandler.postDelayed(this, 300);
                    }
                };
                uiHandler.post(pumpBlinkRunnable);
            } else {
                sendCommand("PUMP", "OFF");
                if (pumpBlinkRunnable != null) uiHandler.removeCallbacks(pumpBlinkRunnable);
                btnPumpToggle.setText("PUMP");
                btnPumpToggle.setTextColor(0xFF000000);
                btnPumpToggle.setBackgroundTintList(ColorStateList.valueOf(0xFF00E1FF));
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
            if (!isCloudConnected && !developmentMode) return;

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

        if (isLoggedIn && !currentUsername.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences("OfflineQueue", MODE_PRIVATE);
            try {
                String existing = prefs.getString("cmds", "[]");
                JSONArray arr = new JSONArray(existing);

                if (arr.length() > 0) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);

                        Map<String, Object> cmdLog = new HashMap<>();
                        cmdLog.put("part", obj.getString("part"));
                        cmdLog.put("command", obj.getString("command"));
                        cmdLog.put("timestamp", obj.getLong("timestamp"));
                        cmdLog.put("received", "yes");
                        database.getReference("users").child(currentUsername).child("command_history").push().setValue(cmdLog);

                        if (i == arr.length() - 1) {
                            Map<String, Object> latestData = new HashMap<>();
                            latestData.put("part", obj.getString("part"));
                            latestData.put("cmd", obj.getString("command"));
                            latestData.put("timestamp", obj.getLong("timestamp"));
                            database.getReference("commands/latest").setValue(latestData);
                        }
                    }
                    prefs.edit().clear().apply();
                }
            } catch (Exception ignored) {}
        }

        database.getReference("system/last_sync").setValue(System.currentTimeMillis(), new DatabaseReference.CompletionListener() {
            @Override
            public void onComplete(DatabaseError error, @NonNull DatabaseReference ref) {
                if (error == null) {
                    Toast.makeText(MainActivity.this, "Sync Successful!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Sync Failed", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void blinkStopButton(Button btnStop) {
        for (int i = 0; i < 10; i++) {
            final boolean isDark = (i % 2 == 0);
            uiHandler.postDelayed(() -> btnStop.setBackgroundTintList(ColorStateList.valueOf(isDark ? 0xFF991F00 : 0xFFFF3300)), i * 150L);
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
                button.setBackgroundTintList(ColorStateList.valueOf(0xFFFFFFFF));
                button.setTextColor(0xFF424242);
                v.setPressed(true);
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                sendCommand(part, "STOP");
                button.setBackgroundTintList(ColorStateList.valueOf(0xFF424242));
                button.setTextColor(0xFFFFFFFF);
                v.setPressed(false);
                return true;
            }
            return false;
        });
    }

    private void sendCommand(String part, String cmd) {
        long ts = System.currentTimeMillis();

        Map<String, Object> commandData = new HashMap<>();
        commandData.put("part", part);
        commandData.put("cmd", cmd);
        commandData.put("timestamp", ts);
        cmdRef.setValue(commandData);

        if (isLoggedIn && !currentUsername.isEmpty() && isCloudConnected) {
            Map<String, Object> cmdLog = new HashMap<>();
            cmdLog.put("part", part);
            cmdLog.put("command", cmd);
            cmdLog.put("timestamp", ts);
            cmdLog.put("received", "yes");
            database.getReference("users").child(currentUsername).child("command_history").push().setValue(cmdLog);
        } else {
            SharedPreferences prefs = getSharedPreferences("OfflineQueue", MODE_PRIVATE);
            try {
                String existing = prefs.getString("cmds", "[]");
                JSONArray arr = new JSONArray(existing);
                JSONObject obj = new JSONObject();
                obj.put("part", part);
                obj.put("command", cmd);
                obj.put("timestamp", ts);
                arr.put(obj);
                prefs.edit().putString("cmds", arr.toString()).apply();
            } catch (Exception ignored) {}
        }

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

    private interface ConnectionCallback {
        void onResult(boolean success);
    }

    private void testRobotConnection(String ip, ConnectionCallback callback) {
        networkExecutor.execute(() -> {
            boolean success = false;
            try {
                URL url = new URL("http://" + ip + "/telemetry");
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

    private void startTelemetryCloudListener() {
        String ipKey = robotIp.replace(".", "_");
        DatabaseReference activeTelemetryRef = database.getReference("telemetry").child(ipKey);

        if (telemetryListener != null) {
            activeTelemetryRef.removeEventListener(telemetryListener);
        }

        telemetryListener = activeTelemetryRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    long ts = snapshot.child("timestamp").getValue(Long.class) != null ? snapshot.child("timestamp").getValue(Long.class) : 0;
                    if (System.currentTimeMillis() - ts < 4000) {
                        updateRobotStatus(true);
                    } else {
                        updateRobotStatus(false);
                    }

                    txtBattery.setText("🔋 Bat: " + snapshot.child("bat").getValue() + " %");
                    txtWaterLevel.setText("💧 Tank: " + snapshot.child("water").getValue() + " %");
                    txtHumidity.setText("☁️ Hum: " + snapshot.child("hum").getValue() + " % RH");
                    txtTemp.setText("🌡 Temp: " + snapshot.child("temp").getValue() + " °C");

                    if (snapshot.hasChild("heading")) {
                        double heading = snapshot.child("heading").getValue(Double.class);
                        txtHeadingText.setText(Math.round(heading) + "° " + getHeadingText(heading));
                        compassDial.setRotation((float) heading);
                    }

                    if (snapshot.hasChild("lat") && snapshot.hasChild("lon")) {
                        double lat = snapshot.child("lat").getValue(Double.class);
                        double lon = snapshot.child("lon").getValue(Double.class);
                        if (Math.abs(myLat - lat) > 0.0001 || Math.abs(myLon - lon) > 0.0001) {
                            myLat = lat;
                            myLon = lon;
                            updateMapLocation(lat, lon);
                        }
                    }
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void startLocalTelemetryPoller() {
        if (telemetryPoller != null) return;
        telemetryPoller = new Runnable() {
            @Override
            public void run() {
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
                            teleMap.put("heading", data.optDouble("heading", 0.0));
                            teleMap.put("timestamp", System.currentTimeMillis());

                            String ipKey = robotIp.replace(".", "_");
                            database.getReference("telemetry").child(ipKey).setValue(teleMap);

                            uiHandler.post(() -> lastRobotHeartbeat = System.currentTimeMillis());
                        }
                        conn.disconnect();
                    } catch (Exception e) {
                        if (System.currentTimeMillis() - lastRobotHeartbeat > 3000) {
                            uiHandler.post(() -> updateRobotStatus(developmentMode));
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
                        txtNoInternet.setVisibility(View.GONE);
                        btnLogin.setEnabled(true);
                        btnLogin.setBackgroundTintList(ColorStateList.valueOf(0xFF1976D2));

                        txtCloudStatus.setText("CLOUD CONNECTED");
                        txtCloudStatus.setTextColor(0xFF4CAF50);
                        cloudIndicator.setBackgroundTintList(ColorStateList.valueOf(0xFF4CAF50));
                        btnForceSync.setEnabled(true);
                        txtCloudError.setVisibility(View.GONE);

                        if (wasOffline) {
                            loadMapData(true); // FIX: Reload real map when internet returns
                            performSync();
                        }
                        wasOffline = false;

                    } else {
                        txtNoInternet.setVisibility(View.VISIBLE);
                        btnLogin.setEnabled(false);
                        btnLogin.setBackgroundTintList(ColorStateList.valueOf(Color.GRAY));

                        if (!wasOffline) {
                            loadMapData(false); // FIX: Show offline placeholder when internet drops
                        }
                        wasOffline = true;

                        txtCloudStatus.setText("CLOUD OFFLINE");
                        txtCloudStatus.setTextColor(0xFFF44336);
                        cloudIndicator.setBackgroundTintList(ColorStateList.valueOf(0xFFF44336));
                        btnForceSync.setEnabled(developmentMode);
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
            robotIndicator.setBackgroundTintList(ColorStateList.valueOf(0xFF4CAF50));
        } else {
            txtRobotStatus.setText("ROBOT OFFLINE");
            txtRobotStatus.setTextColor(0xFFF44336);
            robotIndicator.setBackgroundTintList(ColorStateList.valueOf(0xFFF44336));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (telemetryPoller != null) {
            uiHandler.removeCallbacks(telemetryPoller);
        }
        if (telemetryListener != null) {
            database.getReference("telemetry").child(robotIp.replace(".", "_")).removeEventListener(telemetryListener);
        }
    }
}