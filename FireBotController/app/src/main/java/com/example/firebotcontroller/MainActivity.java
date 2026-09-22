package com.example.firebotcontroller;

import android.annotation.SuppressLint;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String BASE_URL = "http://192.168.4.1";
    private static final String STREAM_URL = "http://192.168.4.1:81/stream";

    private final OkHttpClient httpClient = new OkHttpClient();
    private final Handler telemetryHandler = new Handler(Looper.getMainLooper());
    private Runnable telemetryRunnable;

    // Pump Blinking Variables
    private final Handler pumpBlinkHandler = new Handler(Looper.getMainLooper());
    private Runnable pumpBlinkRunnable;
    private boolean pumpColorToggle = false;

    private WebView streamWebView, mapWebView;
    private TextView txtBattery, txtWaterLevel, txtHumidity, txtTemp, txtStatus;
    private View statusIndicator;
    private LinearLayout controllerContainer;
    private Button btnPumpToggle, btnEStop, btnCallRobot;

    private boolean isPumpActive = false;
    private boolean isLocked = false;

    // Fallback GPS location if the phone needs to send its own location
    private double myLat = 23.7937;
    private double myLon = 90.4066;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind Views
        streamWebView = findViewById(R.id.streamWebView);
        mapWebView = findViewById(R.id.mapWebView);
        txtBattery = findViewById(R.id.txtBattery);
        txtWaterLevel = findViewById(R.id.txtWaterLevel);
        txtHumidity = findViewById(R.id.txtHumidity);
        txtTemp = findViewById(R.id.txtTemp);
        txtStatus = findViewById(R.id.txtStatus);
        statusIndicator = findViewById(R.id.statusIndicator);
        controllerContainer = findViewById(R.id.controllerContainer);
        btnPumpToggle = findViewById(R.id.btnPumpToggle);
        btnEStop = findViewById(R.id.btnEStop);
        btnCallRobot = findViewById(R.id.btnCallRobot);

        setupWebViews();
        setupChassisControls();
        setupActuatorControls();
        setupSpecialButtons();
        startTelemetryPolling();
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
        bindHoldAction(findViewById(R.id.btnFwd), "fwd");
        bindHoldAction(findViewById(R.id.btnRev), "rev");
        bindHoldAction(findViewById(R.id.btnLeft), "left");
        bindHoldAction(findViewById(R.id.btnRight), "right");

        findViewById(R.id.btnStop).setOnClickListener(v -> {
            if (!isLocked) {
                sendCommand("stop");
                blinkStopButton((Button) v);
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupActuatorControls() {
        // Upgraded standard actuators to use bindHoldAction for instant stop and color inversion
        bindHoldAction(findViewById(R.id.btnArmUp), "arm_up");
        bindHoldAction(findViewById(R.id.btnArmDown), "arm_down");
        bindHoldAction(findViewById(R.id.btnBaseL), "base_left");
        bindHoldAction(findViewById(R.id.btnBaseR), "base_right");
        bindHoldAction(findViewById(R.id.btnNozzleUp), "nozzle_up");
        bindHoldAction(findViewById(R.id.btnNozzleDown), "nozzle_down");
        bindHoldAction(findViewById(R.id.btnNozzlePanL), "nozzle_pan_l");
        bindHoldAction(findViewById(R.id.btnNozzlePanR), "nozzle_pan_r");

        btnPumpToggle.setOnClickListener(v -> {
            if (isLocked) return;

            isPumpActive = !isPumpActive;
            if (isPumpActive) {
                sendCommand("pump_on");
                btnPumpToggle.setText("HAULT");
                btnPumpToggle.setTextColor(0xFFFFFFFF); // White text

                // Start continuous blinking
                pumpBlinkRunnable = new Runnable() {
                    @Override
                    public void run() {
                        pumpColorToggle = !pumpColorToggle;
                        if (pumpColorToggle) {
                            btnPumpToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF008799)); // Darker given color
                        } else {
                            btnPumpToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF00E1FF)); // Original Cyan
                        }
                        pumpBlinkHandler.postDelayed(this, 300); // Toggle every 300ms
                    }
                };
                pumpBlinkHandler.post(pumpBlinkRunnable);
            } else {
                sendCommand("pump_off");
                // Stop blinking and reset
                if (pumpBlinkRunnable != null) {
                    pumpBlinkHandler.removeCallbacks(pumpBlinkRunnable);
                }
                btnPumpToggle.setText("PUMP");
                btnPumpToggle.setTextColor(0xFF000000); // Black text
                btnPumpToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF00E1FF)); // Original Cyan
            }
        });
    }

    private void setupSpecialButtons() {
        btnCallRobot.setOnClickListener(v -> {
            if (isLocked) return;
            sendCommand("call_loc_" + myLat + "_" + myLon);
            Toast.makeText(this, "Calling robot to your location...", Toast.LENGTH_SHORT).show();
        });

        btnEStop.setOnClickListener(v -> {
            isLocked = !isLocked;

            if (isLocked) {
                sendCommand("emergency_stop");
                btnEStop.setBackgroundColor(0xFFB71C1C);
                btnEStop.setText("UNLOCK CONTROLS");
                setViewGroupEnabled(controllerContainer, false);
            } else {
                btnEStop.setBackgroundColor(0xFFD32F2F);
                btnEStop.setText("E-STOP (LOCK)");
                setViewGroupEnabled(controllerContainer, true);
            }
        });
    }

    // Custom 5-blink logic for the Stop Button
    private void blinkStopButton(Button btnStop) {
        Handler handler = new Handler(Looper.getMainLooper());
        int blinkCount = 5;
        long delay = 150; // ms between flashes

        for (int i = 0; i < blinkCount * 2; i++) {
            final boolean isDark = (i % 2 == 0);
            handler.postDelayed(() -> {
                if (isDark) {
                    btnStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF991F00)); // Dark Red
                } else {
                    btnStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF3300)); // Bright Red
                }
            }, i * delay);
        }
    }

    // Recursively disables layout buttons
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
    private void bindHoldAction(View view, String actionCommand) {
        Button button = (Button) view;
        button.setOnTouchListener((v, event) -> {
            if (isLocked) return false;

            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                sendCommand(actionCommand);
                // Invert Colors (Foreground becomes Background, Background becomes Foreground)
                button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF)); // White Background
                button.setTextColor(0xFF424242); // Dark Text
                v.setPressed(true);
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                sendCommand("stop");
                // Revert Colors
                button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF424242)); // Dark Background
                button.setTextColor(0xFFFFFFFF); // White Text
                v.setPressed(false);
                return true;
            }
            return false;
        });
    }

    private void sendCommand(String cmd) {
        String url = BASE_URL + "/cmd?val=" + cmd;
        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                updateConnectionStatus(false);
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                updateConnectionStatus(true);
                response.close();
            }
        });
    }

    private void startTelemetryPolling() {
        telemetryRunnable = new Runnable() {
            @Override
            public void run() {
                fetchTelemetry();
                telemetryHandler.postDelayed(this, 1000);
            }
        };
        telemetryHandler.post(telemetryRunnable);
    }

    private void fetchTelemetry() {
        Request request = new Request.Builder().url(BASE_URL + "/telemetry").build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                updateConnectionStatus(false);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    updateConnectionStatus(true);
                    String json = response.body().string();
                    try {
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        int battery = obj.get("bat").getAsInt();
                        int water = obj.get("water").getAsInt();
                        double humidity = obj.get("hum").getAsDouble();
                        double temp = obj.get("temp").getAsDouble();
                        double lat = obj.get("lat").getAsDouble();
                        double lon = obj.get("lon").getAsDouble();

                        runOnUiThread(() -> {
                            txtBattery.setText("🔋 Battery: " + battery + "%");
                            txtWaterLevel.setText("💧 Tank: " + water + "%");
                            txtHumidity.setText("☁️ Humid: " + humidity + " % RH");
                            txtTemp.setText("🌡 Temp: " + temp + " °C");
                            updateMapLocation(lat, lon);
                        });
                    } catch (Exception ignored) {}
                }
                response.close();
            }
        });
    }

    private void updateConnectionStatus(boolean isConnected) {
        runOnUiThread(() -> {
            if (isConnected) {
                txtStatus.setText("CONNECTED TO FIREBOT");
                txtStatus.setTextColor(0xFF4CAF50); // Green
                statusIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
            } else {
                txtStatus.setText("DISCONNECTED");
                txtStatus.setTextColor(0xFFF44336); // Red
                statusIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF44336));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (telemetryHandler != null && telemetryRunnable != null) {
            telemetryHandler.removeCallbacks(telemetryRunnable);
        }
        if (pumpBlinkHandler != null && pumpBlinkRunnable != null) {
            pumpBlinkHandler.removeCallbacks(pumpBlinkRunnable);
        }
    }
}