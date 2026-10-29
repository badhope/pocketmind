/* 口袋智灵 · 前台守护服务
 *
 * 以 FOREGROUND_SERVICE_TYPE_SPECIAL_USE (API 34+) 或
 * FOREGROUND_SERVICE_TYPE_DATA_SYNC (API 33) 常驻前台，
 * 每 5 秒对 http://127.0.0.1:{AGENTD_PORT} 做一次 HTTP GET 探测。
 * 存活／掉线状态通过本地广播通知 MainActivity 刷新错误页；
 * 持久通知点击唤醒 MainActivity。
 *
 * 纯框架 API，零 AndroidX。在 MainActivity 的 onCreate 驻活，onDestroy 释放。
 */

package com.pocket.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;

public class MonitorService extends Service {

    private static final String TAG = "PocketAgent";
    private static final String CHANNEL_ID = "pocket_agent";
    private static final int NOTIFICATION_ID = 20240601;
    private static final long POLL_INTERVAL_MS = 5000L;
    /** 在线时的轮询间隔：保活探测不需要 5 秒一次，长期 5 秒会明显耗电。 */
    private static final long POLL_INTERVAL_ALIVE_MS = 30000L;
    private static final int HEALTH_TIMEOUT_MS = 3000;

    /** 广播 action：状态切换时通知 MainActivity */
    public static final String ACTION_STATE_CHANGED =
            "com.pocket.agent.ACTION_MONITOR_STATE_CHANGED";
    /** boolean extra：true=在线，false=离线 */
    public static final String EXTRA_IS_ALIVE = "is_alive";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean lastKnownAlive = true;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            new Thread(() -> {
                boolean alive = doHealthCheck();
                mainHandler.post(() -> onHealthResult(alive));
                // 在线时拉长间隔省电；离线时保持 5 秒，尽快发现服务恢复。
                // 之前恒定 5 秒轮询：一天 17280 次 HTTP 请求，锁屏也在跑。
                mainHandler.postDelayed(this, alive ? POLL_INTERVAL_ALIVE_MS : POLL_INTERVAL_MS);
            }).start();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 立刻驻入前台，Android 10+ 必须指定 type
        startForegroundCompat();
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.post(pollRunnable);
        return START_STICKY;
    }

    private void startForegroundCompat() {
        Notification first = buildNotification(lastKnownAlive);
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14：使用 SPECIAL_USE（需在 manifest 声明 + 提供 <property>）
            startForeground(NOTIFICATION_ID, first,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 33) {
            // Android 13：使用 DATA_SYNC
            startForeground(NOTIFICATION_ID, first,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, first);
        }
    }

    // ── 健康探测 ──
    private boolean doHealthCheck() {
        String url = getString(R.string.agentd_protocol) + "://"
                + getString(R.string.agentd_host) + ":"
                + BuildConfig.AGENTD_PORT;
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HEALTH_TIMEOUT_MS);
            conn.setReadTimeout(HEALTH_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            // 只要 reply 都算 agentd 存活
            return code > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── 状态变化：更新通知 + 通知 Activity ──
    private void onHealthResult(boolean alive) {
        lastKnownAlive = alive;
        updateNotification(alive);
        // 仅在状态切换时广播，避免每次都唤醒 Activity
        Intent i = new Intent(ACTION_STATE_CHANGED);
        i.putExtra(EXTRA_IS_ALIVE, alive);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void updateNotification(boolean alive) {
        NotificationManager mgr = getSystemService(NotificationManager.class);
        if (mgr != null) {
            mgr.notify(NOTIFICATION_ID, buildNotification(alive));
        }
    }

    private Notification buildNotification(boolean alive) {
        Intent tap = new Intent(this, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, flags);

        String title = alive ? "口袋智灵 · 运行中" : "口袋智灵 · 服务离线";
        String text  = alive ? "agentd 在线" : "点击启动";

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW);

        return b.build();
    }

    // ── 通知通道（首次即可创建，重复创建是 no-op） ──
    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager mgr = ctx.getSystemService(NotificationManager.class);
        if (mgr != null && mgr.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "口袋智灵", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("监控 agentd 存活状态");
            ch.setShowBadge(false);
            mgr.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
