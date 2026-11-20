package ir.sedanegar.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

/**
 * سرویس پیش‌زمینه‌ی ضبط. اعلان آن روی صفحه‌ی قفل هم دیده می‌شود،
 * زمان ضبط را نشان می‌دهد و دکمه‌های مکث، ادامه و پایان دارد.
 */
public class RecordService extends Service {
    private static final String CHANNEL = "rec2";
    static final String A_PAUSE = "ir.sedanegar.app.PAUSE";
    static final String A_RESUME = "ir.sedanegar.app.RESUME";
    static final String A_STOP = "ir.sedanegar.app.STOP";

    private static RecordService inst;
    private String state = "rec";
    private long elapsedMs = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
    }

    @Override
    public void onDestroy() {
        if (inst == this) inst = null;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (A_PAUSE.equals(a)) { MainActivity.dispatchCmd("pause"); return START_NOT_STICKY; }
        if (A_RESUME.equals(a)) { MainActivity.dispatchCmd("resume"); return START_NOT_STICKY; }
        if (A_STOP.equals(a)) { MainActivity.dispatchCmd("stop"); return START_NOT_STICKY; }

        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "ضبط صدا", NotificationManager.IMPORTANCE_LOW);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        state = "rec";
        elapsedMs = 0;
        startForeground(1, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        return START_NOT_STICKY;
    }

    /** از صفحه‌ی وب صدا زده می‌شود تا اعلان با وضعیت ضبط هماهنگ بماند. */
    static void update(String st, long ms) {
        RecordService s = inst;
        if (s == null) return;
        s.state = "paused".equals(st) ? "paused" : "rec";
        s.elapsedMs = ms;
        try {
            s.getSystemService(NotificationManager.class).notify(1, s.build());
        } catch (Exception ignored) { }
    }

    private PendingIntent act(String action, int code) {
        Intent i = new Intent(this, RecordService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action action(int iconRes, String title, PendingIntent pi) {
        Notification.Action.Builder b = new Notification.Action.Builder(Icon.createWithResource(this, iconRes), title, pi);
        if (Build.VERSION.SDK_INT >= 31) b.setAuthenticationRequired(false);
        return b.build();
    }

    private Notification build() {
        boolean rec = "rec".equals(state);

        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        long sec = elapsedMs / 1000;
        String clock = String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle(rec ? "در حال ضبط…" : "ضبط مکث شده")
                .setContentText(rec ? "صدانگار" : "مدت ضبط تا الان " + clock)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(rec)
                .setUsesChronometer(rec)
                .setWhen(System.currentTimeMillis() - elapsedMs);

        if (rec) b.addAction(action(R.drawable.ic_act_pause, "مکث", act(A_PAUSE, 11)));
        else b.addAction(action(R.drawable.ic_act_play, "ادامه", act(A_RESUME, 12)));
        b.addAction(action(R.drawable.ic_act_stop, "پایان", act(A_STOP, 13)));
        return b.build();
    }
}
