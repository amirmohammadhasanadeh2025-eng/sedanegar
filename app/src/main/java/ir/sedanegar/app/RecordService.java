package ir.sedanegar.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** سرویس پیش‌زمینه تا ضبط با خاموش شدن صفحه یا رفتن به برنامه‌ی دیگر قطع نشود. */
public class RecordService extends Service {
    private static final String CHANNEL = "rec";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ضبط صدا", NotificationManager.IMPORTANCE_LOW));

        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("صدانگار")
                .setContentText("در حال ضبط صدا…")
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        return START_NOT_STICKY;
    }
}
