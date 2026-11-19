package com.ghostide.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Toast;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.StatFs;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ghostbot.app.LlamaBrain;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * GhostIDE - standalone offline AI code editor for Android.
 *
 * Everything runs on the phone: the llama.cpp engine (JNI bridge
 * reused from the MrGhosty on-device build), the Qwen2.5-Coder-1.5B
 * model (one-time download into app-private storage), the IDE web
 * app served by an in-app HTTP server, and the user's files in an
 * app-private workspace. No PC, no account, no cloud.
 */
public class MainActivity extends Activity {

    // Model variants live in Models.java (Phase 3.1 model matrix).
    static final int N_CTX = 4096;

    private static final int MENU_RELOAD = 1;
    private static final int MENU_HELP = 2;
    private static final int MENU_MODEL = 3;
    private static final int MENU_IMPORT = 4;
    private static final int MENU_EXPORT = 5;
    private static final int MENU_SYNC_PULL = 6;
    private static final int MENU_SYNC_PUSH = 7;
    private static final int MENU_SHARE = 8;
    private static final int REQ_IMPORT = 101;
    private static final int REQ_EXPORT = 102;
    private static final int REQ_SPEECH = 103;
    private static final int REQ_MIC_PERM = 104;
    private String voiceTarget = "chat";
    /* Mobile Cycle 2 (M7): the composer dictation
       session (in-process SpeechRecognizer). The legacy
       G13 path above it uses the speech activity and
       needs none of this. voicePermForComposer tells
       onRequestPermissionsResult which flow is waiting
       on the RECORD_AUDIO answer. */
    private android.speech.SpeechRecognizer composerRec;
    private boolean composerListening = false;
    private boolean voicePermForComposer = false;
    private File pendingExport;

    private static final int BG = Color.rgb(23, 24, 28);
    private static final int FG = Color.rgb(230, 230, 230);
    private static final int DIM = Color.rgb(154, 159, 176);
    private static final int GREEN = Color.rgb(44, 110, 73);

    private final LlamaBrain brain = new LlamaBrain();
    private IdeServer server;
    private WebView webView;
    private boolean inHelp = false;
    /* Mobile Cycle 3 (M2): supervision state. The
       activity tracks its own foreground state so the
       approval notification is only posted when the
       user is actually away, and a tap on that
       notification leaves a flag the page picks up
       through the bridge (takeApprovalOpen). Static,
       because a notification tap can land on a fresh
       instance while the flag's consumer is the page
       in whichever instance hosts the WebView. */
    private volatile boolean appForeground = false;
    private static volatile boolean approvalOpenRequested =
        false;
    private final Handler uiHandler = new Handler();
    private Runnable downloadPoll;

    private Models.Variant variant() { return Models.selected(this); }
    private File modelFile() { return Models.file(this, variant()); }
    private File partFile() { return Models.part(this, variant()); }
    private boolean modelReady() { return Models.ready(this, variant()); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        noteApprovalIntent(getIntent());
        handleShareIntent(getIntent());
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        seedWorkspace();
        if (!LlamaBrain.LIBS_LOADED) {
            showMessage("On-device engine failed to load",
                "The bundled llama.cpp libraries could not be loaded on "
                + "this device (arm64-v8a required).\n\n"
                + LlamaBrain.LIBS_ERROR);
            return;
        }
        recoverStuckModelSelection();
        if (!modelReady()) {
            showDownload();
        } else {
            loadModelAndStart();
        }
    }

    /**
     * Self-healing for a bad model selection: loadModelAndStart
     * leaves a "loadMarker" pref naming the variant being loaded,
     * cleared when the load finishes. If the app starts and the
     * marker is still set, the last load never finished (the phone
     * could not hold that model: hang, kill, or crash), so fall
     * back to the 1.5B. The same fallback applies when the selected
     * model was never fully downloaded but the 1.5B is ready.
     */
    private void recoverStuckModelSelection() {
        String stuck = getSharedPreferences("ghostide", MODE_PRIVATE)
            .getString("loadMarker", "");
        boolean changed = false;
        if (!stuck.isEmpty()) {
            getSharedPreferences("ghostide", MODE_PRIVATE)
                .edit().remove("loadMarker").apply();
            if (!"1.5b".equals(stuck)) changed = true;
        }
        Models.Variant sel = variant();
        if (!Models.ready(this, sel) && !"1.5b".equals(sel.id)
                && Models.ready(this, Models.V15)) {
            changed = true;
        }
        if (changed && Models.ready(this, Models.V15)) {
            Models.select(this, "1.5b");
            Toast.makeText(this,
                "Switched back to the 1.5B model (the safe one): the "
                + "other model did not load cleanly on this phone.",
                Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (composerRec != null) {
            try { composerRec.destroy(); } catch (Exception ignored) {}
            composerRec = null;
            composerListening = false;
        }
        brain.free();
        super.onDestroy();
    }

    // ---------------- screens ----------------

    private LinearLayout screen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(48, 64, 48, 48);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(GREEN);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 32;
        b.setLayoutParams(lp);
        return b;
    }

    private void showMessage(String title, String body) {
        LinearLayout root = screen();
        root.addView(text("👻 GhostIDE", 24, FG));
        root.addView(text(title, 18, FG));
        TextView b = text(body, 14, DIM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 24;
        b.setLayoutParams(lp);
        root.addView(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        setContentView(sv);
    }

    /* Mobile Cycle 2 (M6): the phone guard device reads.
       Thermal comes from the same PowerManager call the
       GhostNative bridge exposes to the bundle; battery
       comes from the sticky battery intent, which needs
       no permission; free storage from StatFs over the
       app's own files dir. A read that fails answers -1
       and the guard claims nothing from it. */
    private int thermalNow() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.os.PowerManager pm =
                    (android.os.PowerManager)
                        getSystemService(POWER_SERVICE);
                if (pm != null)
                    return pm.getCurrentThermalStatus();
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private int batteryPct() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(
                Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return -1;
            int level = i.getIntExtra(
                BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(
                BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return -1;
            return Math.round(level * 100f / scale);
        } catch (Exception ignored) {}
        return -1;
    }

    private boolean batteryCharging() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(
                Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return false;
            int st = i.getIntExtra(
                BatteryManager.EXTRA_STATUS, -1);
            return st == BatteryManager.BATTERY_STATUS_CHARGING
                || st == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception ignored) {}
        return false;
    }

    private long freeStorageBytes() {
        try {
            return new StatFs(getFilesDir().getAbsolutePath())
                .getAvailableBytes();
        } catch (Exception ignored) {}
        return -1;
    }

    private static String gbText(long bytes) {
        if (bytes < 0) return null;
        return String.format(Locale.US, "%.1f",
            bytes / (double) (1024L * 1024L * 1024L));
    }

    /* One readout line for the store screens: this
       phone's real RAM and free storage, each labelled
       honestly when it cannot be read. */
    private String deviceReadout() {
        String ram = gbText(Models.totalRamBytes(this));
        String free = gbText(freeStorageBytes());
        return "This phone: "
            + (ram != null ? "about " + ram + " GB of RAM"
                : "RAM could not be read")
            + (free != null
                ? " · about " + free + " GB of free storage"
                : " · free storage could not be read")
            + ".";
    }

    /* The M6 warning gate: when the device state draws
       a warning, show it with a proceed-anyway choice;
       otherwise run the action at once. Never blocks. */
    private void confirmDeviceWarning(String title,
            String proceedLabel, final Runnable proceed) {
        String warn = PhoneGuard.stateWarning(thermalNow(),
            batteryPct(), batteryCharging());
        if (warn == null) { proceed.run(); return; }
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(warn + " A big download or a large "
                + "model makes more heat and drains the "
                + "battery faster. You can go ahead anyway, "
                + "or wait until the phone has cooled down "
                + "or is plugged in.")
            .setPositiveButton(proceedLabel,
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(
                            DialogInterface d, int which) {
                        proceed.run();
                    }
                })
            .setNegativeButton("Not now", null)
            .show();
    }

    private void showDownload() {
        stopDownloadPoll();
        deleteIfWrongSize();
        final Models.Variant v = variant();
        LinearLayout root = screen();
        root.addView(text("👻 GhostIDE", 24, FG));
        root.addView(text("One-time model download", 18, FG));
        TextView body = text(
            "GhostIDE runs a real AI model on your phone: "
            + v.label + " (code + chat + autocomplete).\n\n"
            + "It downloads once - " + v.mb() + " MB - into this "
            + "app's private storage, then works fully offline "
            + "forever. The download runs in the background: you "
            + "can leave the app, lock the phone, or use other "
            + "apps and it keeps going, with progress in the "
            + "notification bar. It resumes automatically if "
            + "interrupted, and the file is checksum-verified "
            + "before it is used.",
            14, DIM);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = 24;
        body.setLayoutParams(blp);
        root.addView(body);
        /* Mobile Cycle 2 (M6): the real device readout,
           and the plain caution when the selected model
           is the desktop-class 7B. */
        root.addView(text(deviceReadout(), 13, DIM));
        if (PhoneGuard.isLargeModel(v))
            root.addView(text(Models.PHONE_LARGE_CAUTION,
                13, DIM));

        final ProgressBar bar = new ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = 40;
        bar.setLayoutParams(plp);
        root.addView(bar);
        final TextView status = text("Ready when you are.", 13, DIM);
        root.addView(status);

        final Button start = button(
            "Download model (" + v.mb() + " MB)");
        final Button cancel = button("Cancel download");
        cancel.setBackgroundColor(Color.rgb(70, 72, 82));
        root.addView(start);
        root.addView(cancel);
        setContentView(root);

        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View vw) {
                /* Mobile Cycle 2 (M6): a hot or nearly
                   empty phone hears about it before the
                   download starts, and decides itself. */
                confirmDeviceWarning("Before you download",
                    "Download anyway", new Runnable() {
                        @Override public void run() {
                            askNotificationPermission();
                            DownloadService.start(
                                MainActivity.this, v);
                        }
                    });
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this,
                    DownloadService.class);
                i.setAction(DownloadService.ACTION_CANCEL);
                startService(i);
            }
        });

        downloadPoll = new Runnable() {
            @Override public void run() {
                DownloadService.State s = DownloadService.state;
                long done = DownloadService.bytesDone;
                long total = DownloadService.totalBytes > 0
                    ? DownloadService.totalBytes : v.bytes;
                if (s == DownloadService.State.RUNNING) {
                    start.setEnabled(false);
                    start.setText("Downloading in background...");
                    cancel.setVisibility(View.VISIBLE);
                    if (done > 0) {
                        bar.setProgress((int) (done * 1000 / total));
                        status.setText((done / 1048576) + " MB of "
                            + (total / 1048576)
                            + " MB - safe to leave the app");
                    } else {
                        status.setText("Connecting...");
                    }
                } else if (s == DownloadService.State.DONE) {
                    stopDownloadPoll();
                    loadModelAndStart();
                    return;
                } else if (s == DownloadService.State.ERROR) {
                    start.setEnabled(true);
                    start.setText("Resume download");
                    cancel.setVisibility(View.GONE);
                    status.setText(DownloadService.errorText);
                } else {
                    start.setEnabled(true);
                    start.setText("Download model (" + v.mb() + " MB)");
                    cancel.setVisibility(View.GONE);
                    File part = partFile();
                    if (part.exists() && part.length() > 0) {
                        bar.setProgress((int) (
                            part.length() * 1000 / v.bytes));
                        status.setText("Partial download saved: "
                            + (part.length() / 1048576)
                            + " MB - resume whenever you like.");
                    }
                }
                uiHandler.postDelayed(this, 500);
            }
        };
        uiHandler.post(downloadPoll);
    }

    private void stopDownloadPoll() {
        if (downloadPoll != null) {
            uiHandler.removeCallbacks(downloadPoll);
            downloadPoll = null;
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(
                    "android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[] {"android.permission.POST_NOTIFICATIONS"},
                7);
        }
    }

    private void deleteIfWrongSize() {
        File f = modelFile();
        if (f.exists() && f.length() != variant().bytes) f.delete();
    }

    private void loadModelAndStart() {
        getSharedPreferences("ghostide", MODE_PRIVATE)
            .edit().putString("loadMarker", variant().id).apply();
        LinearLayout root = screen();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(text("👻 GhostIDE", 24, FG));
        ProgressBar spin = new ProgressBar(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 48;
        spin.setLayoutParams(lp);
        root.addView(spin);
        root.addView(text("Loading the model on-device…", 14, DIM));
        setContentView(root);

        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = brain.load(
                    modelFile().getAbsolutePath(), N_CTX);
                ui(new Runnable() {
                    @Override public void run() {
                        getSharedPreferences("ghostide", MODE_PRIVATE)
                            .edit().remove("loadMarker").apply();
                        if (ok) {
                            startIde();
                        } else {
                            showMessage("Model failed to load",
                                brain.loadError()
                                + "\n\nThe model file may be corrupt. "
                                + "Use the menu (⋮) -> Model -> delete "
                                + "and download it again.");
                        }
                    }
                });
            }
        }, "ghostide-load").start();
    }

    /* Pair mode (Roadmap G16): the phone joins a desktop
       GhostIDE server on the LAN and works in its workspace;
       presence + conflict protection live in the web UI. */
    private void showPairDialog() {
        final android.widget.EditText input =
            new android.widget.EditText(this);
        input.setHint("192.168.1.20:8765");
        input.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
            .setTitle("Pair with a desktop")
            .setMessage("Start GhostIDE on the desktop in LAN "
                + "mode (start-lan), then enter its address. "
                + "The IDE opens from the desktop, and Pair "
                + "mode in Tools links the two.")
            .setView(input)
            .setPositiveButton("Pair",
                new android.content.DialogInterface
                        .OnClickListener() {
                    @Override
                    public void onClick(
                            android.content.DialogInterface d,
                            int which) {
                        String host = input.getText().toString()
                            .trim()
                            .replaceFirst("^https?://", "")
                            .replaceAll("/+$", "");
                        if (host.isEmpty()) return;
                        getSharedPreferences("ghostide",
                            MODE_PRIVATE).edit()
                            .putString("pairHost", host).apply();
                        startPairedIde(host);
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void startPairedIde(String host) {
        ensureWebView();
        inHelp = false;
        setContentView(webView);
        webView.loadUrl("http://" + host + "/");
    }

    private void startIde() {
        try {
            if (server == null)
                server = new IdeServer(this, brain, modelDisplayName());
            server.start();
        } catch (final Exception e) {
            showMessage("IDE server failed to start",
                String.valueOf(e.getMessage()));
            return;
        }
        ensureWebView();
        inHelp = false;
        setContentView(webView);
        webView.loadUrl("http://127.0.0.1:" + IdeServer.PORT + "/");
    }

    private void ensureWebView() {
        if (webView != null) return;
        webView = new WebView(this);
        webView.setBackgroundColor(BG);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        /* Fit the page to the phone screen exactly: the wide
           viewport follows the device-width meta tag, and
           overview mode fits that layout to the screen
           instead of leaving the app in a partial-width
           column with dead space beside it. */
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        webView.addJavascriptInterface(new VoiceBridge(),
            "GhostVoice");
        webView.addJavascriptInterface(new NativeBridge(),
            "GhostNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                if (failingUrl != null
                        && failingUrl.contains("127.0.0.1")) {
                    showMessage("IDE server unreachable",
                        description + "\n\nReopen GhostIDE to restart "
                        + "the local server.");
                }
            }
        });
        /* Without a WebChromeClient, Android silently swallows the
           page's alert()/confirm()/prompt() calls (confirm returns
           false, prompt returns null). The IDE uses them for New
           file/folder, rename, delete, go-to-line, duplicate and
           more, so every one of those buttons looked dead on the
           phone. Bridge them to real dialogs. */
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url,
                    String message, final JsResult result) {
                try {
                    new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK",
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(
                                        DialogInterface d, int w) {
                                    result.confirm();
                                }
                            })
                        .setOnCancelListener(
                            new DialogInterface.OnCancelListener() {
                                @Override public void onCancel(
                                        DialogInterface d) {
                                    result.confirm();
                                }
                            })
                        .show();
                } catch (Exception e) {
                    result.confirm();
                }
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url,
                    String message, final JsResult result) {
                try {
                    new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK",
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(
                                        DialogInterface d, int w) {
                                    result.confirm();
                                }
                            })
                        .setNegativeButton("Cancel",
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(
                                        DialogInterface d, int w) {
                                    result.cancel();
                                }
                            })
                        .setOnCancelListener(
                            new DialogInterface.OnCancelListener() {
                                @Override public void onCancel(
                                        DialogInterface d) {
                                    result.cancel();
                                }
                            })
                        .show();
                } catch (Exception e) {
                    result.cancel();
                }
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url,
                    String message, String defaultValue,
                    final JsPromptResult result) {
                try {
                    final EditText input =
                        new EditText(MainActivity.this);
                    if (defaultValue != null) {
                        input.setText(defaultValue);
                        input.setSelection(defaultValue.length());
                    }
                    new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("OK",
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(
                                        DialogInterface d, int w) {
                                    result.confirm(input.getText()
                                        .toString());
                                }
                            })
                        .setNegativeButton("Cancel",
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(
                                        DialogInterface d, int w) {
                                    result.cancel();
                                }
                            })
                        .setOnCancelListener(
                            new DialogInterface.OnCancelListener() {
                                @Override public void onCancel(
                                        DialogInterface d) {
                                    result.cancel();
                                }
                            })
                        .show();
                } catch (Exception e) {
                    result.cancel();
                }
                return true;
            }
        });
    }

    private void showHelp() {
        ensureWebView();
        inHelp = true;
        setContentView(webView);
        webView.loadUrl("file:///android_asset/help.html");
    }

    /* Switch the active model and load it (the Models
       screen's Use action, unchanged behaviour; M6 only
       routes the 7B through the warning first). */
    private void useModel(Models.Variant mv) {
        Models.select(this, mv.id);
        brain.free();
        if (server != null)
            server.setModelName(modelDisplayName());
        loadModelAndStart();
    }

    private void showModelScreen() {
        final Models.Variant active = variant();
        LinearLayout root = screen();
        root.addView(text("👻 GhostIDE", 24, FG));
        root.addView(text("Models", 18, FG));
        long storeBytes = 0;
        for (Models.Variant any : Models.ALL) {
            if (Models.ready(this, any)) storeBytes += any.bytes;
        }
        TextView intro = text(
            "Engine: llama.cpp on-device (arm64) · Context: " + N_CTX
            + " tokens\nModels live in this app's private storage "
            + "and are checksum-verified after download.\n"
            + "Downloaded models are using "
            + (storeBytes / 1048576) + " MB on this phone. "
            + "Delete any model to reclaim its space.",
            13, DIM);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = 16;
        intro.setLayoutParams(ilp);
        root.addView(intro);
        /* Mobile Cycle 2 (M6): the real device readout
           the fit marks and the recommendation below
           are computed from. */
        root.addView(text(deviceReadout(), 13, DIM));
        final long ramBytes = Models.totalRamBytes(this);
        final Models.Variant recVariant =
            Models.recommended(ramBytes);

        for (final Models.Variant mv : Models.ALL) {
            boolean isActive = mv.id.equals(active.id);
            boolean ready = Models.ready(this, mv);
            TextView head = text(
                mv.label + (isActive ? "  ·  ACTIVE" : "")
                + (mv == recVariant
                    ? "  ·  RECOMMENDED" : ""), 16, FG);
            LinearLayout.LayoutParams hlp =
                new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = 36;
            head.setLayoutParams(hlp);
            root.addView(head);
            root.addView(text(
                mv.note + "\nStatus: " + (ready
                    ? "downloaded (" + mv.mb() + " MB)"
                    : "not downloaded (" + mv.mb() + " MB)"),
                13, DIM));
            /* Mobile Cycle 2 (M6): the 7B carries the
               plain caution instead of a fit mark; the
               phone-sized models carry their fit on
               this phone's real RAM. */
            if (PhoneGuard.isLargeModel(mv)) {
                root.addView(text(Models.PHONE_LARGE_CAUTION,
                    13, DIM));
            } else {
                String fit = Models.fitClass(mv.ramGB,
                    ramBytes);
                root.addView(text(
                    "unknown".equals(fit)
                        ? "Fit on this phone is unknown "
                          + "(its RAM could not be read)."
                        : "fits".equals(fit)
                          ? "Fits this phone."
                          : "tight".equals(fit)
                            ? "Tight on this phone."
                            : "Too big for this phone.",
                    13, DIM));
            }
            if (!ready) {
                Button dl = button("Download " + mv.label);
                dl.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vw) {
                        Models.select(MainActivity.this, mv.id);
                        showDownload();
                    }
                });
                root.addView(dl);
            } else if (!isActive) {
                Button use = button("Use " + mv.label);
                use.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vw) {
                        /* Mobile Cycle 2 (M6): loading
                           the 7B on a hot or nearly empty
                           phone warns first; the user
                           decides. Other loads are
                           uninterrupted. */
                        if (PhoneGuard.isLargeModel(mv)) {
                            confirmDeviceWarning(
                                "Before you load this model",
                                "Load anyway", new Runnable() {
                                    @Override public void run() {
                                        useModel(mv);
                                    }
                                });
                        } else {
                            useModel(mv);
                        }
                    }
                });
                root.addView(use);
                Button del = button("Delete " + mv.label);
                del.setBackgroundColor(Color.rgb(150, 60, 60));
                del.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vw) {
                        Models.file(MainActivity.this, mv).delete();
                        Models.part(MainActivity.this, mv).delete();
                        showModelScreen();
                    }
                });
                root.addView(del);
            } else {
                Button del = button("Delete model");
                del.setBackgroundColor(Color.rgb(150, 60, 60));
                del.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vw) {
                        brain.free();
                        modelFile().delete();
                        partFile().delete();
                        showDownload();
                    }
                });
                root.addView(del);
            }
        }

        final String pairHost = getSharedPreferences(
            "ghostide", MODE_PRIVATE)
            .getString("pairHost", "");
        if (pairHost != null && !pairHost.isEmpty()) {
            Button openPair = button(
                "Open paired IDE (" + pairHost + ")");
            openPair.setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View vw) {
                        startPairedIde(pairHost);
                    }
                });
            root.addView(openPair);
            Button unpair = button("Stop pairing");
            unpair.setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View vw) {
                        getSharedPreferences("ghostide",
                            MODE_PRIVATE).edit()
                            .remove("pairHost").apply();
                        showModelScreen();
                    }
                });
            root.addView(unpair);
        } else {
            Button pair = button("Pair with a desktop…");
            pair.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vw) {
                    showPairDialog();
                }
            });
            root.addView(pair);
        }

        Button back = button("Back to IDE");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View vw) {
                if (brain.isLoaded()) startIde();
                else if (modelReady()) loadModelAndStart();
                else showDownload();
            }
        });
        root.addView(back);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        setContentView(sv);
    }

    private String modelDisplayName() {
        return "qwen2.5-coder-" + variant().id + " (on-device)";
    }

    // ---------------- menu / back ----------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_RELOAD, 0, "Reload IDE");
        menu.add(0, MENU_IMPORT, 0, "Import file…");
        menu.add(0, MENU_EXPORT, 0, "Export file…");
        menu.add(0, MENU_SYNC_PULL, 0, "Sync: pull from PC…");
        menu.add(0, MENU_SYNC_PUSH, 0, "Sync: push to PC…");
        menu.add(0, MENU_SHARE, 0, "Share file…");
        menu.add(0, MENU_HELP, 0, "Help");
        menu.add(0, MENU_MODEL, 0, "Model");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_RELOAD:
                if (brain.isLoaded()) startIde();
                return true;
            case MENU_HELP:
                showHelp();
                return true;
            case MENU_MODEL:
                showModelScreen();
                return true;
            case MENU_IMPORT:
                Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                open.addCategory(Intent.CATEGORY_OPENABLE);
                open.setType("*/*");
                startActivityForResult(open, REQ_IMPORT);
                return true;
            case MENU_EXPORT:
                chooseExportFile();
                return true;
            case MENU_SYNC_PULL:
                askPcAddress(true);
                return true;
            case MENU_SYNC_PUSH:
                askPcAddress(false);
                return true;
            case MENU_SHARE:
                chooseShareFile();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /* -------- import / export (Phase 1.4, system pickers) -------- */

    private void chooseExportFile() {
        if (server == null) {
            Toast.makeText(this, "IDE not running yet", Toast.LENGTH_SHORT)
                .show();
            return;
        }
        final List<File> files = new ArrayList<>();
        collectFiles(server.workspaceDir(), files);
        if (files.isEmpty()) {
            Toast.makeText(this, "No files in the workspace yet",
                Toast.LENGTH_SHORT).show();
            return;
        }
        final File root = server.workspaceDir();
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++)
            labels[i] = root.toURI().relativize(
                files.get(i).toURI()).getPath();
        new AlertDialog.Builder(this)
            .setTitle("Export which file?")
            .setItems(labels, new android.content.DialogInterface
                    .OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dlg,
                                    int which) {
                    pendingExport = files.get(which);
                    Intent create = new Intent(
                        Intent.ACTION_CREATE_DOCUMENT);
                    create.addCategory(Intent.CATEGORY_OPENABLE);
                    create.setType("*/*");
                    create.putExtra(Intent.EXTRA_TITLE,
                        pendingExport.getName());
                    startActivityForResult(create, REQ_EXPORT);
                }
            })
            .show();
    }

    private static void collectFiles(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().startsWith(".")) continue;
            if (k.isDirectory()) collectFiles(k, out);
            else out.add(k);
        }
    }

    /* ---------------- Roadmap G13: voice dictation ----------------
       The web UI calls GhostVoice.dictate("chat"|"editor"); the
       system speech recognizer does the listening (nothing is
       recorded by the app itself), and the transcript returns
       to the page through window.__ghostVoiceResult. */
    /* -------- Roadmap G35: companion touches --------
       Share-sheet capture into the project, and a native
       notification bridge for long AI jobs. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        noteApprovalIntent(intent);
        handleShareIntent(intent);
    }

    /* Mobile Cycle 3 (M2): lifecycle truth for the
       supervision decisions, and the approval
       notification's landing flag. onResume/onPause on
       the activity are the honest "is the user looking
       at this app" signal; the page's own visibility
       is the bundle's half of the same decision. */
    @Override
    protected void onResume() {
        super.onResume();
        appForeground = true;
    }

    @Override
    protected void onPause() {
        appForeground = false;
        super.onPause();
    }

    private void noteApprovalIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(
                Supervision.EXTRA_APPROVAL, false)) {
            approvalOpenRequested = true;
            intent.removeExtra(Supervision.EXTRA_APPROVAL);
        }
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        String type = intent.getType();
        if (type != null && !type.startsWith("text/plain")) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) return;
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        try {
            File ws = new File(getFilesDir(), "workspace");
            if (!ws.exists()) ws.mkdirs();
            File cap = new File(ws, "captures.md");
            StringBuilder sb = new StringBuilder();
            if (!cap.exists())
                sb.append("# Captures\n\nShared into this "
                    + "project from other apps.\n\n");
            sb.append("## ").append(new SimpleDateFormat(
                "yyyy-MM-dd HH:mm", Locale.US).format(new Date()))
                .append("\n\n");
            if (subject != null && !subject.trim().isEmpty())
                sb.append("**").append(subject.trim())
                    .append("**\n\n");
            sb.append(text.trim()).append("\n\n");
            FileOutputStream fos = new FileOutputStream(cap, true);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            Toast.makeText(this, "Captured to project: captures.md",
                Toast.LENGTH_LONG).show();
            if (webView != null) {
                webView.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (webView != null)
                            webView.evaluateJavascript(
                                "window.__ghostCaptured && "
                                + "window.__ghostCaptured()", null);
                    }
                }, 600);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Capture failed: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    public class NativeBridge {
        /* Roadmap G40: the OS thermal status (0 none ..
           6 shutdown), or -1 where unavailable. Read by
           the frontend's generation thermal guard. */
        @android.webkit.JavascriptInterface
        public int thermalStatus() {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    android.os.PowerManager pm =
                        (android.os.PowerManager)
                            getSystemService(POWER_SERVICE);
                    if (pm != null)
                        return pm.getCurrentThermalStatus();
                }
            } catch (Exception ignored) {}
            return -1;
        }

        @android.webkit.JavascriptInterface
        public void jobDone(final String title, final String body) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    postJobNotification(title, body);
                }
            });
        }

        /* Mobile Cycle 3 (M2): supervision. The bundle
           drives these around a phone agent run; every
           decision word comes from Supervision (pure,
           JVM-pinned). No method here can read run
           content back: the bridge takes the task title
           (the user's own one-line name for the work)
           and returns only decision words. */

        @android.webkit.JavascriptInterface
        public void runStarted(final String taskTitle) {
            RunService.start(MainActivity.this, taskTitle);
        }

        @android.webkit.JavascriptInterface
        public void runEnded() {
            RunService.stop(MainActivity.this);
            try {
                NotificationManager nm =
                    (NotificationManager) getSystemService(
                        NOTIFICATION_SERVICE);
                if (nm != null)
                    nm.cancel(Supervision.APPROVAL_ID);
            } catch (Exception ignored) {}
        }

        @android.webkit.JavascriptInterface
        public boolean notificationsGranted() {
            boolean granted = true;
            if (Build.VERSION.SDK_INT >= 33) {
                granted = checkSelfPermission(
                    "android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
            }
            return Supervision.permissionGranted(
                Build.VERSION.SDK_INT, granted);
        }

        /**
         * An approval just started waiting. Returns the
         * decision word: "posted" (the notification is
         * up), "foreground" (the user is in the app;
         * the approval is on their screen already) or
         * "denied" (notifications are off, so nothing
         * was posted; the run simply waits, as it did
         * before M2).
         */
        @android.webkit.JavascriptInterface
        public String approvalNeeded(final String taskTitle) {
            if (appForeground) return "foreground";
            if (!notificationsGranted()) return "denied";
            if (!Supervision.shouldNotify(appForeground,
                    true)) return "foreground";
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    postApprovalNotification(taskTitle);
                }
            });
            return "posted";
        }

        /**
         * True once per approval-notification tap: the
         * page polls this at boot and when it becomes
         * visible again, and focuses the waiting
         * approval. Reading clears the flag.
         */
        @android.webkit.JavascriptInterface
        public boolean takeApprovalOpen() {
            boolean was = approvalOpenRequested;
            approvalOpenRequested = false;
            return was;
        }

        /* Mobile Cycle 2 (M4): git account tokens. The
           bundle can save a token into the Keystore-backed
           store and ask whether one exists; there is
           deliberately no getter, so no page script can
           ever read a token back out. IdeServer reads the
           store in-process at git time. Failures answer
           false / do nothing: the Settings status line
           re-reads hasGitToken after every save, so a
           failed save shows up as "no token saved". */
        @android.webkit.JavascriptInterface
        public boolean saveGitToken(final String host,
                final String token) {
            try {
                GitTokenKeystore.forApp(
                    getApplicationContext())
                    .put(host, token);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @android.webkit.JavascriptInterface
        public boolean hasGitToken(final String host) {
            try {
                return GitTokenKeystore.forApp(
                    getApplicationContext()).has(host);
            } catch (Exception e) {
                return false;
            }
        }

        @android.webkit.JavascriptInterface
        public void removeGitToken(final String host) {
            try {
                GitTokenKeystore.forApp(
                    getApplicationContext()).remove(host);
            } catch (Exception ignored) {}
        }

        /* Mobile Cycle 1 (M5): hand a URL to the system
           browser - the APK update check's Download
           button. https only, and that is all it does:
           the app itself never downloads or installs an
           update; the user installs the APK the browser
           fetched, over this build, themselves. */
        @android.webkit.JavascriptInterface
        public void openExternal(final String url) {
            if (url == null || !url.startsWith("https://"))
                return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        startActivity(new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(url)));
                    } catch (Exception ignored) {}
                }
            });
        }
    }

    private void postJobNotification(String title, String body) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(
                        "android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    "android.permission.POST_NOTIFICATIONS"}, 9041);
                return;
            }
            NotificationManager nm = (NotificationManager)
                getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            String channel = "ghostide-jobs";
            if (nm.getNotificationChannel(channel) == null) {
                NotificationChannel ch = new NotificationChannel(
                    channel, "GhostIDE job completions",
                    NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0,
                open, PendingIntent.FLAG_UPDATE_CURRENT
                    | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
            nm.notify(2201, n);
        } catch (Exception ignored) {}
    }

    /* Mobile Cycle 3 (M2): the approval notification.
       Title and body come from Supervision: the task's
       own title and the fact that an approval waits,
       never a command or file content. Tapping opens
       the app with the approval extra set, which the
       page turns into a focus of the waiting approval
       (takeApprovalOpen). */
    private void postApprovalNotification(String taskTitle) {
        try {
            NotificationManager nm = (NotificationManager)
                getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(
                    Supervision.APPROVAL_CHANNEL) == null) {
                NotificationChannel ch =
                    new NotificationChannel(
                        Supervision.APPROVAL_CHANNEL,
                        Supervision.APPROVAL_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            open.putExtra(Supervision.EXTRA_APPROVAL, true);
            PendingIntent pi = PendingIntent.getActivity(this,
                Supervision.APPROVAL_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                    | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this,
                    Supervision.APPROVAL_CHANNEL)
                .setSmallIcon(
                    android.R.drawable.stat_notify_chat)
                .setContentTitle(Supervision.approvalTitle())
                .setContentText(
                    Supervision.approvalBody(taskTitle))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
            nm.notify(Supervision.APPROVAL_ID, n);
        } catch (Exception ignored) {}
    }

    public class VoiceBridge {
        @android.webkit.JavascriptInterface
        public void dictate(final String target) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    startDictation(target);
                }
            });
        }

        /* Mobile Cycle 2 (M7): composer dictation. A
           second tap while a session is live stops it
           (the recogniser still delivers what it heard);
           otherwise a session starts, through the same
           RECORD_AUDIO permission the legacy path uses.
           Results return to the page through the
           __ghostVoicePrompt* callbacks, never through
           the legacy __ghostVoiceResult: the composer
           path carries no commands and sends nothing. */
        @android.webkit.JavascriptInterface
        public void startComposerDictation() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    toggleComposerDictation();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void stopComposerDictation() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    stopComposerSession();
                }
            });
        }
    }

    private void startDictation(String target) {
        voiceTarget = target == null ? "chat" : target;
        if (checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager
                    .PERMISSION_GRANTED) {
            voicePermForComposer = false;
            requestPermissions(new String[]{
                android.Manifest.permission.RECORD_AUDIO},
                REQ_MIC_PERM);
            return;
        }
        fireSpeechIntent();
    }

    /* ---------------- Mobile Cycle 2 (M7): composer
       dictation ----------------
       The legacy path above runs the system's speech
       ACTIVITY: it can only hand back one finished
       string, and every failure (no recogniser,
       permission denied, cancelled) collapses to the
       same silent null. The composer needs more: live
       partials while the user speaks, and a named state
       for every way a session can end, so the prompt
       box can say what happened in plain words. That
       means the in-process SpeechRecognizer API, which
       is what runs here. On API 31+ the on-device
       recogniser is used when the device reports one
       (genuinely offline); below that, or without one,
       the system speech service runs it, and
       EXTRA_PREFER_OFFLINE only ASKS that service to
       stay offline - the page is told which of the two
       is in use, so it never claims "offline" for the
       service path. */

    private void toggleComposerDictation() {
        if (composerListening) {
            stopComposerSession();
            return;
        }
        if (checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager
                    .PERMISSION_GRANTED) {
            voicePermForComposer = true;
            requestPermissions(new String[]{
                android.Manifest.permission.RECORD_AUDIO},
                REQ_MIC_PERM);
            return;
        }
        startComposerSession();
    }

    private void startComposerSession() {
        if (!android.speech.SpeechRecognizer
                .isRecognitionAvailable(this)) {
            deliverVoicePromptState("unavailable", null);
            return;
        }
        boolean onDevice = false;
        try {
            if (VoiceSupport.useOnDevice(
                    Build.VERSION.SDK_INT,
                    Build.VERSION.SDK_INT >= 31
                        && android.speech.SpeechRecognizer
                            .isOnDeviceRecognitionAvailable(
                                this))) {
                composerRec = android.speech.SpeechRecognizer
                    .createOnDeviceSpeechRecognizer(this);
                onDevice = true;
            } else {
                composerRec = android.speech.SpeechRecognizer
                    .createSpeechRecognizer(this);
            }
        } catch (Exception e) {
            composerRec = null;
            deliverVoicePromptState("error", null);
            return;
        }
        composerRec.setRecognitionListener(
            new android.speech.RecognitionListener() {
                @Override public void onReadyForSpeech(
                        Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(
                        float rmsdB) {}
                @Override public void onBufferReceived(
                        byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onEvent(int eventType,
                        Bundle params) {}
                @Override public void onPartialResults(
                        Bundle partialResults) {
                    String heard = firstResult(partialResults);
                    if (heard != null)
                        deliverVoicePromptPartial(heard);
                }
                @Override public void onResults(
                        Bundle results) {
                    String heard = firstResult(results);
                    endComposerSession();
                    if (heard != null)
                        deliverVoicePromptFinal(heard);
                    else
                        deliverVoicePromptState(
                            "nomatch", null);
                }
                @Override public void onError(int error) {
                    String state =
                        VoiceSupport.errorState(error);
                    endComposerSession();
                    deliverVoicePromptState(state, null);
                }
            });
        Intent i = new Intent(
            android.speech.RecognizerIntent
                .ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent
                .EXTRA_LANGUAGE_MODEL,
            android.speech.RecognizerIntent
                .LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent
                .EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(android.speech.RecognizerIntent
                .EXTRA_PREFER_OFFLINE, true);
        try {
            composerRec.startListening(i);
            composerListening = true;
            deliverVoicePromptState("listening",
                VoiceSupport.kind(onDevice));
        } catch (Exception e) {
            endComposerSession();
            deliverVoicePromptState("error", null);
        }
    }

    private static String firstResult(Bundle bundle) {
        if (bundle == null) return null;
        java.util.ArrayList<String> all =
            bundle.getStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        if (all == null || all.isEmpty()) return null;
        String first = all.get(0);
        return first == null || first.isEmpty() ? null : first;
    }

    private void stopComposerSession() {
        if (composerRec != null && composerListening) {
            /* stopListening still delivers the final
               result (or an error) through the listener,
               which is what ends the session. */
            try { composerRec.stopListening(); }
            catch (Exception ignored) {}
        }
    }

    private void endComposerSession() {
        composerListening = false;
        if (composerRec != null) {
            try { composerRec.destroy(); }
            catch (Exception ignored) {}
            composerRec = null;
        }
    }

    private void deliverVoicePromptState(String state,
            String kind) {
        if (webView == null) return;
        String js = "window.__ghostVoicePromptState && "
            + "window.__ghostVoicePromptState("
            + org.json.JSONObject.quote(state) + ", "
            + (kind == null ? "null"
               : org.json.JSONObject.quote(kind)) + ")";
        webView.evaluateJavascript(js, null);
    }

    private void deliverVoicePromptPartial(String text) {
        if (webView == null) return;
        String js = "window.__ghostVoicePromptPartial && "
            + "window.__ghostVoicePromptPartial("
            + org.json.JSONObject.quote(text) + ")";
        webView.evaluateJavascript(js, null);
    }

    private void deliverVoicePromptFinal(String text) {
        if (webView == null) return;
        String js = "window.__ghostVoicePromptFinal && "
            + "window.__ghostVoicePromptFinal("
            + org.json.JSONObject.quote(text) + ")";
        webView.evaluateJavascript(js, null);
    }

    private void fireSpeechIntent() {
        Intent i = new Intent(
            android.speech.RecognizerIntent
                .ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent
                .EXTRA_LANGUAGE_MODEL,
            android.speech.RecognizerIntent
                .LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT,
            "Speak - GhostIDE is listening");
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (Exception e) {
            deliverVoice(null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req,
            String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_MIC_PERM) {
            boolean granted = grants.length > 0 && grants[0]
                    == android.content.pm.PackageManager
                        .PERMISSION_GRANTED;
            if (voicePermForComposer) {
                voicePermForComposer = false;
                if (granted) {
                    startComposerSession();
                } else {
                    /* M7: the composer path names the
                       denial; the page shows where to
                       grant the permission. (The legacy
                       path below still answers with its
                       historical silent null.) */
                    deliverVoicePromptState("denied", null);
                }
            } else if (granted) {
                fireSpeechIntent();
            } else {
                deliverVoice(null);
            }
        }
    }

    private void deliverVoice(String text) {
        if (webView == null) return;
        String js = "window.__ghostVoiceResult && "
            + "window.__ghostVoiceResult("
            + org.json.JSONObject.quote(voiceTarget) + ", "
            + (text == null ? "null"
               : org.json.JSONObject.quote(text)) + ")";
        webView.evaluateJavascript(js, null);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SPEECH) {
            String heard = null;
            if (res == RESULT_OK && data != null) {
                java.util.ArrayList<String> all =
                    data.getStringArrayListExtra(
                        android.speech.RecognizerIntent
                            .EXTRA_RESULTS);
                if (all != null && !all.isEmpty())
                    heard = all.get(0);
            }
            deliverVoice(heard);
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (req == REQ_IMPORT) {
            String name = "imported.txt";
            try {
                android.database.Cursor c = getContentResolver().query(
                    uri, null, null, null, null);
                if (c != null) {
                    int idx = c.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) name = c.getString(idx);
                    c.close();
                }
            } catch (Exception ignored) {}
            final String fname = name.replaceAll("[/\\\\]", "_");
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        File dest = new File(
                            server.workspaceDir(), fname);
                        InputStream in = getContentResolver()
                            .openInputStream(uri);
                        FileOutputStream o = new FileOutputStream(dest);
                        copyStream(in, o);
                        toastOnUi("Imported " + fname);
                    } catch (Exception e) {
                        toastOnUi("Import failed: " + e.getMessage());
                    }
                    reloadIdeOnUi();
                }
            }).start();
        } else if (req == REQ_EXPORT && pendingExport != null) {
            final File src = pendingExport;
            pendingExport = null;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        OutputStream o = getContentResolver()
                            .openOutputStream(uri);
                        FileInputStream in = new FileInputStream(src);
                        copyStream(in, o);
                        toastOnUi("Exported " + src.getName());
                    } catch (Exception e) {
                        toastOnUi("Export failed: " + e.getMessage());
                    }
                }
            }).start();
        }
    }

    private static void copyStream(InputStream in, OutputStream o)
            throws java.io.IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) o.write(buf, 0, n);
        o.flush();
        o.close();
        in.close();
    }

    private void toastOnUi(final String m) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG)
                    .show();
            }
        });
    }

    /* -------- share a workspace file to another app -------- */
    private void chooseShareFile() {
        if (server == null) {
            Toast.makeText(this, "IDE not running yet",
                Toast.LENGTH_SHORT).show();
            return;
        }
        final List<File> files = new ArrayList<>();
        collectFiles(server.workspaceDir(), files);
        if (files.isEmpty()) {
            Toast.makeText(this, "No files in the workspace yet",
                Toast.LENGTH_SHORT).show();
            return;
        }
        final File root = server.workspaceDir();
        CharSequence[] labels = new CharSequence[files.size()];
        for (int i = 0; i < files.size(); i++) {
            labels[i] = root.toURI().relativize(
                files.get(i).toURI()).getPath();
        }
        new AlertDialog.Builder(this)
            .setTitle("Share which file?")
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    shareFile(root, files.get(which));
                }
            })
            .show();
    }

    private void shareFile(File root, File f) {
        String rel = root.toURI().relativize(f.toURI()).getPath();
        Uri.Builder b = new Uri.Builder()
            .scheme("content")
            .authority(GhostShareProvider.AUTHORITY);
        for (String seg : rel.split("/")) b.appendPath(seg);
        Uri uri = b.build();
        String n = f.getName().toLowerCase(java.util.Locale.US);
        String type = "text/plain";
        if (n.endsWith(".png")) type = "image/png";
        else if (n.endsWith(".jpg") || n.endsWith(".jpeg"))
            type = "image/jpeg";
        else if (n.endsWith(".gif")) type = "image/gif";
        else if (n.endsWith(".json")) type = "application/json";
        else if (n.endsWith(".zip")) type = "application/zip";
        else if (n.endsWith(".pdf")) type = "application/pdf";
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(type);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "Share file"));
        } catch (Exception e) {
            Toast.makeText(this, "No app can receive that share",
                Toast.LENGTH_SHORT).show();
        }
    }

    private void reloadIdeOnUi() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (webView != null) webView.reload();
            }
        });
    }

    /* -------- LAN sync with the desktop IDE (Phase 3.3) -------- */
    /* Same WiFi only, no cloud, no accounts. The desktop IDE must be
       running in LAN mode (GHOST_IDE_HOST=0.0.0.0) - see its README. */

    private void askPcAddress(final boolean pull) {
        if (server == null) {
            Toast.makeText(this, "IDE not running yet",
                Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("PC address, e.g. 192.168.1.20");
        String saved = getSharedPreferences("ghostide", MODE_PRIVATE)
            .getString("pcAddress", "");
        input.setText(saved);
        new AlertDialog.Builder(this)
            .setTitle(pull ? "Pull workspace from PC" : "Push workspace to PC")
            .setMessage(pull
                ? "Files from the PC's current workspace will be copied "
                  + "here, overwriting files with the same name."
                : "This phone's workspace files will be copied to the "
                  + "PC's current workspace, overwriting same-named files.")
            .setView(input)
            .setPositiveButton(pull ? "Pull" : "Push",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        String addr = input.getText().toString().trim();
                        if (addr.isEmpty()) return;
                        getSharedPreferences("ghostide", MODE_PRIVATE)
                            .edit().putString("pcAddress", addr).apply();
                        if (!addr.contains(":")) addr += ":5173";
                        final String base = "http://" + addr;
                        new Thread(new Runnable() {
                            @Override public void run() {
                                if (pull) doSyncPull(base);
                                else doSyncPush(base);
                            }
                        }).start();
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static boolean isTextFile(File f) {
        if (f.length() > 512 * 1024) return false;
        String n = f.getName().toLowerCase(java.util.Locale.US);
        int dot = n.lastIndexOf('.');
        if (dot < 0) return true;
        String ext = n.substring(dot + 1);
        String[] ok = {"py", "js", "ts", "jsx", "tsx", "json", "md",
            "txt", "html", "css", "java", "c", "h", "cpp", "sh", "xml",
            "yml", "yaml", "toml", "ini", "cfg", "csv", "gitignore"};
        for (String e : ok) if (e.equals(ext)) return true;
        return false;
    }

    private void doSyncPull(String base) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(base + "/api/sync/bundle")
                    .openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            String body = readAll(conn.getInputStream());
            org.json.JSONObject d = new org.json.JSONObject(body);
            if (d.has("error")) {
                toastOnUi("Sync failed: " + d.getString("error"));
                return;
            }
            org.json.JSONArray files = d.getJSONArray("files");
            File root = server.workspaceDir();
            int n = 0;
            for (int i = 0; i < files.length(); i++) {
                org.json.JSONObject fo = files.getJSONObject(i);
                String path = fo.getString("path");
                if (path.contains("..") || path.startsWith("/")) continue;
                File dest = new File(root, path);
                if (dest.getParentFile() != null)
                    dest.getParentFile().mkdirs();
                FileOutputStream o = new FileOutputStream(dest);
                o.write(fo.getString("content").getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
                o.close();
                n++;
            }
            toastOnUi("Pulled " + n + " file(s) from the PC workspace");
            reloadIdeOnUi();
        } catch (Exception e) {
            toastOnUi("Sync failed: " + e.getMessage()
                + " - is the desktop IDE running in LAN mode on "
                + "the same WiFi?");
        }
    }

    private void doSyncPush(String base) {
        try {
            List<File> files = new ArrayList<>();
            collectFiles(server.workspaceDir(), files);
            File root = server.workspaceDir();
            org.json.JSONArray arr = new org.json.JSONArray();
            int n = 0;
            for (File f : files) {
                if (!isTextFile(f)) continue;
                org.json.JSONObject fo = new org.json.JSONObject();
                fo.put("path", root.toURI().relativize(
                    f.toURI()).getPath());
                fo.put("content", readTextFile(f));
                arr.put(fo);
                n++;
            }
            org.json.JSONObject payload = new org.json.JSONObject();
            payload.put("files", arr);
            byte[] bytes = payload.toString().getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(base + "/api/sync/apply")
                    .openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            OutputStream o = conn.getOutputStream();
            o.write(bytes);
            o.close();
            String resp = readAll(conn.getInputStream());
            org.json.JSONObject d = new org.json.JSONObject(resp);
            if (d.has("error")) {
                toastOnUi("Sync failed: " + d.getString("error"));
                return;
            }
            toastOnUi("Pushed " + d.optInt("applied", n)
                + " file(s) to the PC");
        } catch (Exception e) {
            toastOnUi("Sync failed: " + e.getMessage()
                + " - is the desktop IDE running in LAN mode on "
                + "the same WiFi?");
        }
    }

    private static String readAll(InputStream in)
            throws java.io.IOException {
        java.io.ByteArrayOutputStream b =
            new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(),
            java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String readTextFile(File f)
            throws java.io.IOException {
        return readAll(new FileInputStream(f));
    }

    @Override
    public void onBackPressed() {
        if (inHelp) {
            inHelp = false;
            if (brain.isLoaded()) { startIde(); return; }
        }
        if (webView != null && webView.canGoBack() && !inHelp) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    // ---------------- workspace seeding ----------------

    private void seedWorkspace() {
        try {
            File ws = new File(getFilesDir(), "workspace");
            if (ws.exists() && ws.list() != null && ws.list().length > 0) {
                return;
            }
            ws.mkdirs();
            String[] seeds = getAssets().list("playground");
            if (seeds == null) return;
            for (String name : seeds) {
                InputStream in = getAssets().open("playground/" + name);
                FileOutputStream out = new FileOutputStream(
                    new File(ws, name));
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                in.close();
                out.close();
            }
        } catch (Exception ignored) {
            // Seeding is a convenience; the IDE works with an empty folder.
        }
    }

    private void ui(Runnable r) { runOnUiThread(r); }
}
