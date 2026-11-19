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

/**
 * Mobile Cycle 3 (M2): the agent run's foreground
 * service. While a supervised run is active on the
 * phone, this service keeps the app process (and with
 * it IdeServer, the on-device model and the WebView
 * running the agent loop) at foreground priority, so
 * a run the user started and walked away from is far
 * more likely to still be alive, waiting or working,
 * when they come back.
 *
 * What it does NOT do, by design: it does not run the
 * agent, and it does not promise survival. The user
 * swiping the app away, Android's dataSync time limits
 * and plain memory pressure can still end the process;
 * when that happens the run's saved task record and
 * run context are the answer (resume), and the
 * notification text says exactly that. The service is
 * started by the bundle through the GhostNative bridge
 * when a supervised run starts, and stopped when the
 * run ends; if starting it is refused (background
 * restrictions), the run simply proceeds as it did
 * before M2.
 */
public class RunService extends Service {

    public static final String EXTRA_TITLE =
        "com.ghostide.app.extra.RUN_TITLE";

    private static volatile boolean running = false;

    public static boolean isRunning() { return running; }

    public static void start(Context c, String taskTitle) {
        try {
            Intent i = new Intent(c, RunService.class);
            i.putExtra(EXTRA_TITLE, taskTitle);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception ignored) {
            /* Refused (background start limits): the run
               proceeds without the service, as pre-M2. */
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, RunService.class));
        } catch (Exception ignored) {}
        running = false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags,
            int startId) {
        String title = intent != null
            ? intent.getStringExtra(EXTRA_TITLE) : null;
        running = true;
        try {
            startForeground(Supervision.RUN_ID,
                buildNotification(title));
        } catch (Exception e) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification(String title) {
        NotificationManager nm = (NotificationManager)
            getSystemService(NOTIFICATION_SERVICE);
        if (nm != null
                && nm.getNotificationChannel(
                    Supervision.RUN_CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(
                Supervision.RUN_CHANNEL,
                Supervision.RUN_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this,
            Supervision.RUN_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,
                Supervision.RUN_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(Supervision.runTitle())
            .setContentText(Supervision.runBody(title))
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }
}
