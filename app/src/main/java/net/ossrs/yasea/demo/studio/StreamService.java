package net.ossrs.yasea.demo.studio;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.File;

import net.ossrs.yasea.demo.MovieSaver;
import net.ossrs.yasea.demo.R;
import net.ossrs.yasea.engine.ScreenSource;
import net.ossrs.yasea.engine.StreamEngine;

/**
 * Hosts the {@link StreamEngine}. Promoted to a foreground service while a stream or
 * recording is active so capture continues in the background and with the screen off.
 */
public class StreamService extends Service implements StreamEngine.Listener {

    public static final String ACTION_STOP = "net.ossrs.yasea.demo.STOP_ALL";
    private static final String CHANNEL = "streaming";
    private static final int NOTIF_ID = 17;

    public final class LocalBinder extends Binder {
        public StreamService getService() {
            return StreamService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private StreamEngine engine;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean foreground;
    private boolean projectionType;
    private String statusText = "Ready";

    @Override
    public void onCreate() {
        super.onCreate();
        engine = new StreamEngine(this);
        engine.addListener(this);
    }

    public StreamEngine getEngine() {
        return engine;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            engine.stopStream();
            engine.stopRecord();
            demote();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** Becomes a foreground service (call when a stream / recording / screen share begins). */
    public void promote(boolean withProjection) {
        if (withProjection) {
            projectionType = true;
        }
        int types = 0;
        if (Build.VERSION.SDK_INT >= 29) {
            if (granted(Manifest.permission.CAMERA)) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            if (granted(Manifest.permission.RECORD_AUDIO)) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            if (projectionType) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            }
        }
        if (types == 0 && Build.VERSION.SDK_INT >= 29) {
            return; // nothing a foreground service type could legally cover
        }
        // The service must be started (not just bound) to outlive the activity.
        ContextCompat.startForegroundService(this, new Intent(this, StreamService.class));
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), types);
            foreground = true;
        } catch (RuntimeException e) {
            foreground = false;
        }
        acquireLocks();
    }

    public void demote() {
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        projectionType = false;
        releaseLocks();
    }

    /** Called by the activity once the user granted screen capture. */
    public boolean startScreenShare(int resultCode, Intent data) {
        promote(true);
        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            MediaProjection mp = mpm.getMediaProjection(resultCode, data);
            if (mp == null) {
                return false;
            }
            engine.addSource(new ScreenSource(this, mp));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean granted(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamMaster:engine");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "StreamMaster:wifi");
                wifiLock.setReferenceCounted(false);
            }
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Streaming and recording",
                NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, StudioActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, StreamService.class).setAction(ACTION_STOP), piFlags);
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_recording_dot)
            .setContentTitle("StreamMaster")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build();
    }

    private void refreshNotification() {
        if (foreground) {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, buildNotification());
        }
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        if (engine.isStreaming()) {
            sb.append("LIVE: ").append(engine.getStreamState().name().toLowerCase());
        }
        if (engine.isRecording()) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append("Recording");
        }
        statusText = sb.length() == 0 ? "Ready" : sb.toString();
        if (sb.length() == 0 && foreground) {
            demote();
            stopSelf(); // stays alive only while the activity is still bound
        } else {
            refreshNotification();
        }
    }

    @Override
    public void onStreamState(StreamEngine.StreamState state, String message) {
        updateStatus();
    }

    @Override
    public void onRecordState(boolean recording, String path, String message) {
        updateStatus();
        if (!recording && path != null) {
            // Finished: move the temp file into Movies/StreamMaster off the main thread.
            final File f = new File(path);
            final Context app = getApplicationContext();
            new Thread(() -> MovieSaver.publish(app, f, f.getName()), "publish-recording").start();
        }
    }

    @Override
    public void onEngineMessage(String message) {
    }

    @Override
    public void onSourcesChanged() {
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiped from recents: keep going if actively streaming/recording, else clean up.
        if (!engine.isStreaming() && !engine.isRecording()) {
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        engine.removeListener(this);
        engine.release();
        releaseLocks();
        super.onDestroy();
    }
}
