package com.ghostide.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Foreground service that downloads the AI model in the background.
 *
 * The download keeps running while GhostIDE is not on screen (other
 * apps in front, screen off): a persistent progress notification,
 * a partial wakelock, resumable .part file (HTTP Range), exact-size
 * verification, and a completion notification that reopens the app.
 * Progress is exposed through statics for the activity to render
 * when it is visible; the service itself never depends on the UI.
 */
public class DownloadService extends Service {

    public static final String ACTION_CANCEL =
        "com.ghostide.app.action.CANCEL_DOWNLOAD";
    public static final String EXTRA_VARIANT =
        "com.ghostide.app.extra.VARIANT";

    private static final String CHANNEL = "ghostide_downloads";
    private static final int NOTIF_ID = 42;

    public enum State { IDLE, RUNNING, DONE, ERROR, CANCELLED }

    public static volatile State state = State.IDLE;
    public static volatile long bytesDone = 0;
    public static volatile long totalBytes = 0;
    public static volatile String errorText = "";

    private volatile boolean cancelRequested = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    // The variant this run is fetching (set in onStartCommand).
    private Models.Variant variant;
    private long expectedBytes;
    private String expectedSha;

    public static boolean isRunning() { return state == State.RUNNING; }

    /** Start (or resume) the download of one model variant. */
    public static void start(Context c, Models.Variant v) {
        Intent i = new Intent(c, DownloadService.class);
        i.putExtra(EXTRA_VARIANT, v.id);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelRequested = true;
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) return START_STICKY;
        String vid = intent != null
            ? intent.getStringExtra(EXTRA_VARIANT) : null;
        variant = vid != null ? Models.byId(vid) : Models.selected(this);
        expectedBytes = variant.bytes;
        expectedSha = variant.sha256;
        totalBytes = expectedBytes;
        cancelRequested = false;
        state = State.RUNNING;
        errorText = "";
        startForeground(NOTIF_ID, buildNotification("Starting…", -1));
        acquireWakeLock();
        worker = new Thread(new Runnable() {
            @Override public void run() { runDownload(); }
        }, "ghostide-download");
        worker.start();
        return START_STICKY;
    }

    private void runDownload() {
        HttpURLConnection conn = null;
        try {
            Models.dir(this).mkdirs();
            File part = Models.part(this, variant);
            long have = part.exists() ? part.length() : 0;
            if (have > expectedBytes) { part.delete(); have = 0; }
            bytesDone = have;
            conn = (HttpURLConnection) new URL(
                variant.url).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            if (have > 0)
                conn.setRequestProperty("Range", "bytes=" + have + "-");
            conn.connect();
            int code = conn.getResponseCode();
            if (code != 200 && code != 206)
                throw new Exception("server returned HTTP " + code);
            if (code == 200 && have > 0) { part.delete(); have = 0; bytesDone = 0; }
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(part, have > 0);
            byte[] buf = new byte[1 << 16];
            long done = have;
            long lastNotify = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (cancelRequested) {
                    out.close();
                    in.close();
                    part.delete();
                    finish(State.CANCELLED, null);
                    return;
                }
                out.write(buf, 0, n);
                done += n;
                bytesDone = done;
                long now = System.currentTimeMillis();
                if (now - lastNotify > 1000) {
                    lastNotify = now;
                    notifyProgress(done);
                }
            }
            out.close();
            in.close();
            if (done != expectedBytes) {
                // Keep the .part: a later start resumes from it.
                throw new Exception("connection ended at " + done
                    + " of " + expectedBytes
                    + " bytes - reopen GhostIDE to resume");
            }
            String sha = sha256(part);
            if (!sha.equalsIgnoreCase(expectedSha)) {
                part.delete();
                throw new Exception("checksum mismatch - the download "
                    + "was corrupt and has been discarded. Start the "
                    + "download again.");
            }
            if (!part.renameTo(Models.file(this, variant)))
                throw new Exception("could not move model into place");
            finish(State.DONE, null);
        } catch (Exception e) {
            finish(State.ERROR, String.valueOf(e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void finish(State s, String err) {
        state = s;
        if (err != null) errorText = err;
        releaseWakeLock();
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        if (s == State.DONE) {
            nm.notify(NOTIF_ID, buildFinalNotification(
                "Model downloaded",
                "Open GhostIDE - the IDE will load it now."));
        } else if (s == State.ERROR) {
            nm.notify(NOTIF_ID, buildFinalNotification(
                "Model download stopped",
                err + " Reopen GhostIDE to resume."));
        } else {
            nm.cancel(NOTIF_ID);
        }
        stopForeground(true);
        stopSelf();
    }

    // ---------------- notifications ----------------

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Model download",
                    NotificationManager.IMPORTANCE_LOW);
                ch.setDescription(
                    "Progress of the one-time AI model download");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification.Builder builder() {
        ensureChannel();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        b.setContentIntent(PendingIntent.getActivity(this, 0, open, flags));
        b.setSmallIcon(android.R.drawable.stat_sys_download);
        b.setContentTitle("GhostIDE model download");
        return b;
    }

    private Notification buildNotification(String text, int permille) {
        Notification.Builder b = builder();
        b.setContentText(text);
        b.setOngoing(true);
        if (permille >= 0) b.setProgress(1000, permille, false);
        else b.setProgress(0, 0, true);
        Intent cancel = new Intent(this, DownloadService.class);
        cancel.setAction(ACTION_CANCEL);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel,
            "Cancel", PendingIntent.getService(this, 1, cancel, flags));
        return b.build();
    }

    private Notification buildFinalNotification(String title, String text) {
        Notification.Builder b = builder();
        b.setContentTitle(title);
        b.setContentText(text);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done);
        b.setOngoing(false);
        b.setAutoCancel(true);
        return b.build();
    }

    private static String sha256(File f) throws Exception {
        java.security.MessageDigest md =
            java.security.MessageDigest.getInstance("SHA-256");
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest())
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void notifyProgress(long done) {
        int permille = (int) (done * 1000 / expectedBytes);
        String text = (done / 1048576) + " MB of "
            + (expectedBytes / 1048576) + " MB";
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text, permille));
    }

    // ---------------- wakelock ----------------

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager)
                getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ghostide:download");
            wakeLock.acquire(3 * 60 * 60 * 1000L /*3h safety cap*/);
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
