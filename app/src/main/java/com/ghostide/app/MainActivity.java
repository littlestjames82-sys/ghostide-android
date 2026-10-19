package com.ghostide.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Toast;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.ArrayList;
import java.util.List;

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
    private File pendingExport;

    private static final int BG = Color.rgb(23, 24, 28);
    private static final int FG = Color.rgb(230, 230, 230);
    private static final int DIM = Color.rgb(154, 159, 176);
    private static final int GREEN = Color.rgb(44, 110, 73);

    private final LlamaBrain brain = new LlamaBrain();
    private IdeServer server;
    private WebView webView;
    private boolean inHelp = false;
    private final Handler uiHandler = new Handler();
    private Runnable downloadPoll;

    private Models.Variant variant() { return Models.selected(this); }
    private File modelFile() { return Models.file(this, variant()); }
    private File partFile() { return Models.part(this, variant()); }
    private boolean modelReady() { return Models.ready(this, variant()); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
                askNotificationPermission();
                DownloadService.start(MainActivity.this, v);
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
    }

    private void showHelp() {
        ensureWebView();
        inHelp = true;
        setContentView(webView);
        webView.loadUrl("file:///android_asset/help.html");
    }

    private void showModelScreen() {
        final Models.Variant active = variant();
        LinearLayout root = screen();
        root.addView(text("👻 GhostIDE", 24, FG));
        root.addView(text("Models", 18, FG));
        TextView intro = text(
            "Engine: llama.cpp on-device (arm64) · Context: " + N_CTX
            + " tokens\nModels live in this app's private storage "
            + "and are checksum-verified after download.",
            13, DIM);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = 16;
        intro.setLayoutParams(ilp);
        root.addView(intro);

        for (final Models.Variant mv : Models.ALL) {
            boolean isActive = mv.id.equals(active.id);
            boolean ready = Models.ready(this, mv);
            TextView head = text(
                mv.label + (isActive ? "  ·  ACTIVE" : ""), 16, FG);
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
                        Models.select(MainActivity.this, mv.id);
                        brain.free();
                        if (server != null)
                            server.setModelName(modelDisplayName());
                        loadModelAndStart();
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

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
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
