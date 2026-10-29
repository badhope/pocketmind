/* 口袋智灵 · Android WebView 壳
 *
 * 相比初代的变化：
 *   - agentd 不可达时显示中文错误页（含启动命令、复制、重试），而非白屏
 *   - 页面状态杀进程后恢复（onSaveInstanceState）
 *   - debug 版本开启 WebView 远程调试
 *   - JS Bridge：reload / copyText / vibrate / notify / launchTermux
 *   - URL 端口由 BuildConfig.AGENTD_PORT + resValue 组装，可配置
 *   - ForegroundService 监控 agentd 存活，掉线自动切错误页
 *   - onReceivedTitle / onReceivedSslError / onReceivedHttpError 全套处理
 *   - 启动前健康检查；onResume 回到前台时重新 ping
 *
 * 零 AndroidX 依赖，纯框架 API（minSdk 26 全覆盖）。
 */

package com.pocket.agent;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.http.SslError;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.ConsoleMessage;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private static final String TAG = "PocketAgent";
    private static final int NOTIFICATION_PERMISSION_CODE = 1001;
    private static final String CHANNEL_ID = "pocket_agent";
    private static final int NOTIFICATION_ID_BASE = 1000;
    private static final int HEALTH_TIMEOUT_MS = 2000;

    private WebView web;
    private boolean agentdUnreachable = false;
    private String pageTitle;

    // ── 监控器广播接收：实时驱动错误页切换 ──
    private BroadcastReceiver monitorReceiver;
    private boolean monitorReceiverRegistered = false;

    /** 正在做自检 / ping 中，避免重复并发 */
    private final AtomicBoolean healthInProgress = new AtomicBoolean(false);

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureNotificationChannel(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setLoadsImagesAutomatically(true);
        // 混合内容：本服务是 http://127.0.0.1，页面资源也全是 http，
        // 从不需要在 https 页面里加载 http 资源。设为 NEVER_ALLOW 收紧攻击面。
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // 壳只加载本机服务与内置 assets，不需要读取任意本地文件 / content://。
        // 关掉这些开关可以缩小 WebView 被滥用时的可达范围。
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);

        // 调试版本开启 WebView 远程调试，chrome://inspect 即可联调
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
            Log.d(TAG, "WebView remote debugging enabled");
        }

        web.setWebViewClient(new PocketWebViewClient());
        web.setWebChromeClient(new PocketChromeClient());

        // JS Bridge —— 给 Web UI 调用原生能力
        web.addJavascriptInterface(new PocketBridge(this), "PocketNative");

        // 状态恢复优先于首屏加载
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            // 启动自检：先 ping agentd，失败则立刻切错误页
            performStartupSelfCheck();
        }

        setContentView(web);

        // 注册监控广播 + 驻活 ForegroundService
        registerMonitorReceiver();
        registerTokenReceiver();
        startMonitorService();
        // Android 13+ 预测性返回：必须显式注册回调，否则返回手势直接退出应用，
        // WebView 的历史回退（在设置面板里返回上一层）失效。
        registerBackHandler();
        // 尽力从 Termux 读取访问令牌（后台，不阻塞首屏）
        new Thread(this::requestTokenFromTermux).start();
    }

    // ════════════════════════════════════════════════
    //  前台服务启停 + 广播监听
    // ════════════════════════════════════════════════

    private void startMonitorService() {
        Intent i = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void stopMonitorService() {
        // 仅保留给「用户显式停止」的入口（当前未接线）；退出界面不应停服务，
        // 见 onDestroy 的说明。
        stopService(new Intent(this, MonitorService.class));
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerMonitorReceiver() {
        if (monitorReceiverRegistered) return;
        monitorReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) return;
                boolean alive = intent.getBooleanExtra(MonitorService.EXTRA_IS_ALIVE, true);
                Log.d(TAG, "Monitor broadcast: alive=" + alive);
                if (!alive && !agentdUnreachable) {
                    // agentd 掉线 → 立刻切错误页
                    showErrorPage();
                }
                // 注意：alive=true 不会自动恢复，让用户点重试，避免 SSE 流中断后静默重载
            }
        };
        IntentFilter filter = new IntentFilter(MonitorService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(monitorReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(monitorReceiver, filter);
        }
        monitorReceiverRegistered = true;
    }

    // ════════════════════════════════════════════════
    //  启动自检 & onResume ping
    // ════════════════════════════════════════════════

    private void performStartupSelfCheck() {
        if (!healthInProgress.compareAndSet(false, true)) return;
        new Thread(() -> {
            final boolean alive = doHealthCheck();
            mainHandler().post(() -> {
                healthInProgress.set(false);
                if (alive) {
                    agentdUnreachable = false;
                    loadAgentdUrl();
                } else {
                    agentdUnreachable = true;
                    showSetupPage();
                }
            });
        }).start();
    }

    /** onResume：从后台回来时 ping 一下，若已恢复则静默重载 */
    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        pingOnResume();
    }

    /**
     * onPause：把生命周期转发给 WebView。
     *
     * Cordova/Capacitor 的 Android 壳都做这一步。不转发时，App 退到后台
     * WebView 仍继续跑 JS 定时器与媒体解码 —— SSE 长连接、健康轮询、
     * 流式打字动画都在后台空转，是手机上非常明显的耗电与发热来源。
     * 转发后 WebView 会暂停 JS 计时器，回到前台 onResume 自动恢复。
     */
    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    private void pingOnResume() {
        if (!healthInProgress.compareAndSet(false, true)) return;
        new Thread(() -> {
            final boolean alive = doHealthCheck();
            mainHandler().post(() -> {
                healthInProgress.set(false);
                if (alive && agentdUnreachable) {
                    // 服务重新上线，自动恢复
                    agentdUnreachable = false;
                    loadAgentdUrl();
                } else if (!alive && !agentdUnreachable) {
                    // 前台期间服务挂了，切错误页
                    agentdUnreachable = true;
                    showErrorPage();
                }
            });
        }).start();
    }

    /** HTTP GET 探测 agentd，2 秒超时 */
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
            return conn.getResponseCode() > 0;
        } catch (Exception e) {
            Log.d(TAG, "health ping failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── 根据 BuildConfig 组装 http://127.0.0.1:PORT ──
    private void loadAgentdUrl() {
        String url = getString(R.string.agentd_protocol) + "://"
                + getString(R.string.agentd_host) + ":"
                + BuildConfig.AGENTD_PORT;
        Log.d(TAG, "Loading agentd URL: " + url);
        web.loadUrl(url);
    }

    // ════════════════════════════════════════════════
    //  WebView 全套事件处理
    // ════════════════════════════════════════════════

    // ════ WebView 事件处理 ════

    /**
     * 允许在 WebView 里加载的页面白名单：只限本机 agentd 与内置 assets。
     * 判定逻辑在 SecurityPolicy 里（纯 Java，可用 JVM 单测真实执行）。
     */
    private boolean isTrustedUrl(String url) {
        return SecurityPolicy.isTrustedUrl(url);
    }

    private class PocketWebViewClient extends WebViewClient {

        /**
         * 导航守卫：白名单外的一律交给系统浏览器，不在带桥的 WebView 里打开。
         * 返回 true 表示"已由应用接管"，WebView 不再加载该 URL。
         */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(Uri.parse(url));
        }

        private boolean handleNavigation(Uri uri) {
            if (uri == null) return true;
            String url = uri.toString();
            if (isTrustedUrl(url)) return false;
            Log.w(TAG, "Blocked in-WebView navigation, opening externally: " + url);
            try {
                Intent ext = new Intent(Intent.ACTION_VIEW, uri);
                ext.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(ext);
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "无法打开链接", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                agentdUnreachable = true;
                Log.w(TAG, "agentd unreachable: " + error.getDescription());
                showErrorPage();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            if (request.isForMainFrame()) {
                int code = errorResponse.getStatusCode();
                Log.w(TAG, "HTTP error " + code + " for " + request.getUrl());
                // 4xx / 5xx 都当作服务异常
                agentdUnreachable = true;
                showErrorPage();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            // 正常页面加载完成后清除错误标记
            if (url != null && !url.startsWith("file:///android_asset/")) {
                agentdUnreachable = false;
            }
        }
    }

    // ════ WebChromeClient：标题 + console + 文件选择 ════
    private class PocketChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage msg) {
            Log.d(TAG, "WebView [" + msg.messageLevel() + "] " + msg.message()
                    + " -- " + msg.sourceId() + ":" + msg.lineNumber());
            return true;
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            pageTitle = title;
            Log.d(TAG, "Page title: " + title);
        }

        /**
         * WebView 里 &lt;input type="file"&gt; 的支持。
         *
         * 为什么必须有：设置面板的「导入备份」用一个动态创建的 file input 选 JSON；
         * WebView 默认不实现 onShowFileChooser，点了没有任何反应 —— 用户看到的是
         * "按钮没反应"，而导入是换机/重装恢复数据的唯一入口（审计 D1）。
         * 实现方式与 Capacitor / Cordova 的 Android WebView 壳一致：
         * 收下回调 → 拉起系统文件选择器 → onActivityResult 回填。
         */
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (!bridgeAllowed()) return false;
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(null);
            }
            pendingFileCallback = callback;
            try {
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                // 导入只接受 JSON；按 params 的 acceptTypes 收紧，空则用 json
                String[] accept = params != null ? params.getAcceptTypes() : null;
                String type = "*/*";
                if (accept != null) {
                    for (String a : accept) {
                        if (a != null && !a.trim().isEmpty()) {
                            // "application/json,.json" 这类值取第一个 MIME
                            type = a.split(",")[0].trim();
                            break;
                        }
                    }
                }
                pick.setType(type.isEmpty() ? "*/*" : type);
                startActivityForResult(Intent.createChooser(pick, "选择备份文件"),
                        REQUEST_PICK_FILE);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "file chooser failed: " + e.getMessage());
                pendingFileCallback = null;
                Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
    }

    private static final int REQUEST_PICK_FILE = 2001;
    /** 等待系统文件选择器返回的 WebView 回调（同一时刻只允许一个）。 */
    private ValueCallback<Uri[]> pendingFileCallback;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_FILE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        ValueCallback<Uri[]> cb = pendingFileCallback;
        pendingFileCallback = null;
        if (cb == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            Uri single = data.getData();
            if (single != null) {
                result = new Uri[]{single};
            } else if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int i = 0; i < n; i++) {
                    result[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
        }
        cb.onReceiveValue(result);
    }

    private void showSetupPage() {
        web.loadUrl("file:///android_asset/agentd_setup.html");
    }

    private void showErrorPage() {
        web.loadUrl("file:///android_asset/agentd_error.html");
    }

    // ════════════════════════════════════════════════
    //  生命周期完整性
    // ════════════════════════════════════════════════

    /** Android 13+ 通知开关是否打开 */
    private boolean canPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** 返回键：错误页直接退出；否则优先页面历史 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (handleBack()) return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 统一的返回逻辑：错误页退出，否则 WebView 能后退就后退。
     * 两套入口共用它 —— 旧版 onKeyDown 与 Android 13+ 的预测性返回回调。
     */
    private boolean handleBack() {
        if (agentdUnreachable) {
            finish();
            return true;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return false;
    }

    /**
     * Android 13+ 预测性返回（predictive back）。
     *
     * manifest 里开了 enableOnBackInvokedCallback 后，系统手势不再走 onKeyDown，
     * 必须注册 OnBackInvokedCallback，否则用户一划就退出整个应用 ——
     * 而不是先关闭设置面板/返回上一页。低版本保持原 onKeyDown 路径。
     */
    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> {
                        if (!handleBack()) finish();
                    });
        } catch (Exception e) {
            Log.d(TAG, "registerBackHandler unavailable: " + e.getMessage());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) {
            web.saveState(outState);
        }
    }

    // ── 通知权限回调（Android 13+ 用户在系统弹窗中点击允许后） ──
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 授权成功后补发缓存的待发通知（JS 不会自动重发）
        if (requestCode == NOTIFICATION_PERMISSION_CODE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            postNotification(permissionTitle, permissionBody);
        }
    }

    // 通知权限请求期间缓存的参数（PocketBridge.notify 写入，授权回调读取）
    private String permissionTitle;
    private String permissionBody;

    private void postNotification(String title, String body) {
        NotificationManager mgr = getSystemService(NotificationManager.class);
        if (mgr == null) return;
        android.app.Notification n = new android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title != null ? title : "口袋智灵")
                .setContentText(body != null ? body : "")
                .setAutoCancel(true)
                .build();
        try {
            mgr.notify(NOTIFICATION_ID_BASE, n);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        if (monitorReceiverRegistered) {
            unregisterReceiver(monitorReceiver);
            monitorReceiverRegistered = false;
        }
        if (tokenReceiver != null) {
            try {
                unregisterReceiver(tokenReceiver);
            } catch (IllegalArgumentException ignored) {
                /* 未注册成功时忽略 */
            }
            tokenReceiver = null;
        }
        // 注意：这里**不**停止 MonitorService。
        // 保活服务是本应用存在意义的一部分（agentd 掉线要能通知用户）；
        // 早前在这里 stopService，导致用户一退出界面保活就没了，通知栏也跟着消失。
        // 服务自身由系统按 START_STICKY 管理，用户可在系统设置里停止本应用。
        if (web != null) {
            // 先移除 JS 桥再 destroy：addJavascriptInterface 注入的对象会被
            // WebView 的 native 层持有，直接 destroy 在 API 11+ 上存在已知的对象泄漏
            // （Cordova 的 WebView 销毁流程同样先清桥）。
            try {
                web.removeJavascriptInterface("PocketNative");
            } catch (Exception ignored) {
                /* 旧版本或已移除 */
            }
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════

    private Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    /** 在 Termux 中执行一条命令（通过 Termux 的 RunCommandService）。 */
    private void runInTermux(String cmd) {
        try {
            Intent intent = new Intent("com.termux.RUN_COMMAND");
            intent.setClassName("com.termux", "com.termux.app.RunCommandService");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", cmd});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
            startService(intent);
        } catch (SecurityException e) {
            // Termux 未授予 RUN_COMMAND 权限：提示用户授权，而不是静默失败
            Log.w(TAG, "RunCommandService permission denied: " + e.getMessage());
            Toast.makeText(this,
                    "请在系统设置中允许本应用「在 Termux 环境中运行命令」权限，并在 Termux 设置中开启 allow-external-apps 后重试",
                    Toast.LENGTH_LONG).show();
            launchTermux();
        } catch (Exception e) {
            Log.w(TAG, "RunCommandService failed: " + e.getMessage());
            launchTermux();
        }
    }

    /** 尝试唤醒 Termux（包启动 + deep link 兜底）。 */
    private void launchTermux() {
        try {
            Intent launch = getPackageManager()
                    .getLaunchIntentForPackage("com.termux");
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                Log.d(TAG, "Launched Termux by package");
                return;
            }
        } catch (Exception ignored) {}
        try {
            Intent deep = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("term://com.termux/"));
            deep.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(deep);
            Log.d(TAG, "Launched Termux by deep link");
        } catch (Exception e) {
            Log.w(TAG, "No Termux installed: " + e.getMessage());
        }
    }

    /** 打开 Termux 的 F-Droid 下载页。 */
    private void openFdroid() {
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://f-droid.org/packages/com.termux/"));
            browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(browser);
        } catch (Exception e) {
            Log.w(TAG, "openFdroid failed: " + e.getMessage());
        }
    }

    private static void ensureNotificationChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager mgr = ctx.getSystemService(NotificationManager.class);
            if (mgr != null && mgr.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "口袋智灵", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("定时任务、审批等系统消息");
                mgr.createNotificationChannel(ch);
            }
        }
    }

    // ════════════════════════════════════════════════
    //  JS Bridge 原生接口（零 AndroidX，纯框架 API）

    /**
     * 桥调用守卫：只接受来自受信页面（本机 agentd 或 APK 内置页）的调用。
     *
     * 为什么需要：addJavascriptInterface 的桥对「当前加载的页面」全部可见。
     * 导航白名单已经拦住了外部页面，但白名单依赖 URL 解析；这里再做一次
     * 「当前页面 URL 是否受信」的运行时校验，形成纵深防御 —— 任何页面
     * 被意外加载时，桥方法会直接拒绝而不是执行 Termux 命令。
     */
    private boolean bridgeAllowed() {
        if (web == null) return false;
        String url = web.getUrl();
        return isTrustedUrl(url);
    }

    private static class PocketBridge {
        private final MainActivity activity;

        PocketBridge(MainActivity a) { this.activity = a; }

        /** 由错误页"重试"按钮调用，切回 agentd URL */
        @android.webkit.JavascriptInterface
        public void reload() {
            if (!activity.bridgeAllowed()) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.web != null) {
                    activity.agentdUnreachable = false;
                    activity.loadAgentdUrl();
                }
            });
        }

        /** 写入系统剪贴板 */
        @android.webkit.JavascriptInterface
        public void copyText(String text) {
            if (!activity.bridgeAllowed() || text == null) return;
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("pocket", text));
        }

        /** 触发一次振动（ms 单位） */
        @android.webkit.JavascriptInterface
        public void vibrate(long ms) {
            if (!activity.bridgeAllowed()) return;
            Vibrator v;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) return;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(ms);
                }
            } catch (Exception ignored) {}
        }

        /** 发一条系统通知（Android 13+ 自动申请 POST_NOTIFICATIONS） */
        @android.webkit.JavascriptInterface
        public void notify(String title, String body) {
            if (!activity.bridgeAllowed()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    activity.permissionTitle = title;
                    activity.permissionBody = body;
                    activity.requestPermissions(
                            new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            NOTIFICATION_PERMISSION_CODE);
                    return;
                }
                if (!activity.canPostNotifications()) {
                    Log.w(TAG, "POST_NOTIFICATIONS permanently denied");
                    return;
                }
            }
            postNotification(title, body);
        }

        /** 尝试唤醒 Termux（deep link intent） */
        @android.webkit.JavascriptInterface
        public void launchTermux() {
            if (!activity.bridgeAllowed()) return;
            activity.launchTermux();
        }

        /**
         * 一键部署：在 Termux 中安装依赖、克隆仓库、安装并启动 agentd。
         *
         * 安全：repoUrl 会被拼进 shell 命令，必须严格校验形状并转义，
         * 否则一个精心构造的 URL（含 `;`、`$()`、反引号）就能执行任意命令。
         * 只接受 https/http 的 git URL，字符集限制在 RFC 3986 的安全子集。
         */
        @android.webkit.JavascriptInterface
        public void deployAgent(String repoUrl) {
            if (!activity.bridgeAllowed()) return;
            String fallback = activity.getString(R.string.repo_url);
            String url = (repoUrl == null || repoUrl.trim().isEmpty()) ? fallback : repoUrl.trim();
            if (!isSafeRepoUrl(url)) {
                activity.toastOnUiThread("仓库地址不合法，已取消部署");
                Log.w(TAG, "deployAgent rejected unsafe repo url");
                return;
            }
            String quoted = shellQuote(url);
            // git clone 已存在时自动 git pull 更新；install.sh 幂等可重复执行
            String cmd = "pkg install -y git python termux-api && "
                    + "(git clone " + quoted + " pocketmind 2>/dev/null || (cd pocketmind && git pull)) && "
                    + "cd pocketmind && bash termux/install.sh && bash termux/start.sh";
            activity.runInTermux(cmd);
        }

        /** 仅启动已部署的 agentd 服务。 */
        @android.webkit.JavascriptInterface
        public void startAgent() {
            if (!activity.bridgeAllowed()) return;
            activity.runInTermux("cd ~/pocketmind 2>/dev/null && bash termux/start.sh");
        }

        /** 重新执行健康检查；就绪后由原生层自动切换到 Web 界面。 */
        @android.webkit.JavascriptInterface
        public void checkAgain() {
            if (!activity.bridgeAllowed()) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.web != null) {
                    activity.agentdUnreachable = false;
                    activity.performStartupSelfCheck();
                }
            });
        }

        /** 打开 Termux 的 F-Droid 下载页。 */
        @android.webkit.JavascriptInterface
        public void openFdroid() {
            if (!activity.bridgeAllowed()) return;
            activity.openFdroid();
        }

        /**
         * 读取本机 agentd 的访问令牌（Termux 侧写入的 token 文件）。
         *
         * 背景：Android 上 127.0.0.1 是全机共享的，若 agentd 无令牌，
         * 同机任意 App 都能读你的会话、执行工具。因此 install.sh 首次安装时
         * 会生成一个随机令牌写入 $HOME/.agent/termux-agent/token，
         * 壳通过 Termux 读出来注入 WebView localStorage，用户无需手抄。
         *
         * 读不到（未部署 / 无权限）时返回空字符串，前端退回手动输入。
         */
        @android.webkit.JavascriptInterface
        public String readToken() {
            if (!activity.bridgeAllowed()) return "";
            return activity.readTokenFromTermux();
        }

        /** 默认部署仓库地址（由 resValue 注入，避免 Java 里硬编码另一份）。 */
        @android.webkit.JavascriptInterface
        public String getRepoUrl() {
            return activity.getString(R.string.repo_url);
        }

        /**
         * 把文本内容保存成文件（备份导出用）。
         *
         * 为什么需要：前端导出走的是 `new Blob()` + `<a download>`，这在浏览器里能用，
         * 但在 WebView 里**不会触发任何下载** —— blob: 既不走 DownloadListener，
         * 也没有系统下载器接管。用户点「导出备份」只会看到什么都没发生（审计 D2）。
         *
         * 这里改走原生保存：
         *   - Android 10+ 用 MediaStore 写进「下载」目录（无需存储权限）；
         *   - 更低版本写到应用专属外部目录（同样无需权限）。
         * 返回保存后的可读路径，前端用它提示用户。
         */
        @android.webkit.JavascriptInterface
        public String saveTextFile(String fileName, String content) {
            if (!activity.bridgeAllowed()) return "";
            String name = (fileName == null || fileName.trim().isEmpty())
                    ? "pocketmind-backup.json" : fileName.trim();
            // 只取文件名部分，杜绝 ../ 之类路径穿越
            name = name.replaceAll(".*[/\\\\]", "");
            if (!name.matches("[A-Za-z0-9._-]{1,80}")) name = "pocketmind-backup.json";
            return activity.writeTextToDownloads(name, content == null ? "" : content);
        }

        /**
         * 电池优化是否已豁免（前端据此决定是否显示"保持后台运行"引导）。
         *
         * 为什么需要：Android 的 Doze 会在锁屏后限制后台网络与 CPU。
         * agentd 是跑在 Termux 里的独立进程，本壳只负责提醒；但若系统把
         * Termux 冻结，SSE 会断、定时任务不触发、审批提醒收不到。
         * Termux / Andronix / UserLAnd 这类常驻应用的官方指引都是
         * 「设置 → 电池 → 不受限制」。
         */
        @android.webkit.JavascriptInterface
        public boolean isBatteryOptimizationIgnored() {
            if (!activity.bridgeAllowed()) return true;
            return activity.checkBatteryOptimizationIgnored();
        }

        /** 打开系统的「忽略电池优化」申请页；系统不支持时退到应用详情页。 */
        @android.webkit.JavascriptInterface
        public void requestIgnoreBatteryOptimization() {
            if (!activity.bridgeAllowed()) return;
            activity.openBatteryOptimizationSettings();
        }

        // 通知发送统一走 MainActivity.postNotification（授权回调需访问）
        private void postNotification(String title, String body) {
            activity.postNotification(title, body);
        }
    }

    /** 仓库地址校验与 shell 转义：实现见 SecurityPolicy（JVM 可单测）。 */
    private static boolean isSafeRepoUrl(String url) {
        return SecurityPolicy.isSafeRepoUrl(url);
    }

    /** POSIX 单引号转义：委托 SecurityPolicy，保证整个字符串被当成一个字面量。 */
    private static String shellQuote(String raw) {
        return SecurityPolicy.shellQuote(raw);
    }

    private void toastOnUiThread(String msg) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    /**
     * 系统是否已对本应用豁免电池优化（Doze 白名单）。
     * API 23 以下没有该机制，直接视为已豁免。
     */
    private boolean checkBatteryOptimizationIgnored() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            // 部分定制 ROM 会抛 SecurityException：按「未豁免」处理，引导用户手动设置
            return false;
        }
    }

    /**
     * 打开「忽略电池优化」申请对话框。
     *
     * 用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 直接弹系统授权框（需在
     * manifest 声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）；国产 ROM 常拦截该 intent，
     * 因此失败时逐级退到「电池优化列表」→「应用详情页」，让用户仍能手动操作。
     */
    private void openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toastOnUiThread("当前系统无需设置电池优化");
            return;
        }
        // 1) 直接申请豁免
        try {
            Intent req = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            req.setData(Uri.parse("package:" + getPackageName()));
            startActivity(req);
            return;
        } catch (Exception ignored) {
            /* 部分 ROM 不支持，继续降级 */
        }
        // 2) 电池优化列表
        try {
            startActivity(new Intent(
                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        } catch (Exception ignored) {
            /* 继续降级 */
        }
        // 3) 应用详情页
        try {
            Intent detail = new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            detail.setData(Uri.parse("package:" + getPackageName()));
            startActivity(detail);
        } catch (Exception e) {
            toastOnUiThread("请在系统设置中手动将本应用设为「不受电池限制」");
        }
    }

    /**
     * 把文本写到「下载」目录，返回可展示的路径（失败返回空串）。
     *
     * Android 10+ 通过 MediaStore.Downloads 写入，不需要 WRITE_EXTERNAL_STORAGE；
     * Android 8/9 退到应用专属外部目录（getExternalFilesDir），同样无需权限。
     * 两者都不申请危险权限 —— 备份里含会话内容，能少一个权限就少一个。
     */
    private String writeTextToDownloads(String fileName, String content) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return "";
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) return "";
                    os.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.flush();
                }
                // 让文件管理器/下载应用立即看到它
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
                String shown = "下载/" + fileName;
                toastOnUiThread("已保存到 " + shown);
                return shown;
            }
            java.io.File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists() && !dir.mkdirs()) return "";
            java.io.File out = new java.io.File(dir, fileName);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            toastOnUiThread("已保存到 " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "saveTextFile failed: " + e.getMessage());
            toastOnUiThread("保存失败：" + e.getMessage());
            return "";
        }
    }

    /**
     * 通过 Termux 读取 agentd 访问令牌。
     *
     * 走 RUN_COMMAND 前台执行并把 stdout 写到一个壳可读的位置并不可行
     * （Termux 的 stdout 只通过 PendingIntent 返回，跨应用广播有额外复杂度）；
     * 这里用最朴素也最可靠的方式：读取 Termux 写入的 token 文件，
     * 该文件在安装时 chmod 600，只有 Termux 自己可读 —— 因此由 Termux 打印。
     * 为避免同步阻塞 UI，实际调用点在后台线程，见 loadTokenAsync()。
     */
    /**
     * 读取 agentd 访问令牌（同步返回当前缓存）。
     *
     * 令牌是从 Termux 异步取回的（RUN_COMMAND 的结果只能经 PendingIntent 回传），
     * 因此首次调用通常返回空串。这里在缓存为空时顺带触发一次拉取，
     * 前端通过轮询/事件拿到后自动重试 —— 两边配合才不需要用户手抄令牌。
     */
    private String readTokenFromTermux() {
        if (tokenCache.isEmpty() && !tokenRequestInFlight) {
            new Thread(this::requestTokenFromTermux).start();
        }
        return tokenCache;
    }

    /** 令牌缓存：由 Termux 的 RUN_COMMAND 结果回填。 */
    private volatile String tokenCache = "";

    /** 避免前端轮询时反复发起 RUN_COMMAND。 */
    private volatile boolean tokenRequestInFlight = false;

    /**
     * 主动请求 Termux 输出令牌：执行 cat，结果通过 PendingIntent 回传。
     * 注意：这是「尽力而为」——Termux 未安装/未授权时静默失败，前端退回手动输入。
     */
    private void requestTokenFromTermux() {
        try {
            Intent intent = new Intent("com.termux.RUN_COMMAND");
            intent.setClassName("com.termux", "com.termux.app.RunCommandService");
            intent.putExtra("com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/usr/bin/bash");
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                    new String[]{"-c", "cat ~/.agent/termux-agent/token 2>/dev/null"});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                    "/data/data/com.termux/files/home");
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
            // 结果通过 PendingIntent 回传（Termux 只支持这一条通道）
            Intent resultIntent = new Intent(ACTION_TOKEN_RESULT);
            resultIntent.setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 1001, resultIntent, flags);
            intent.putExtra("RUN_COMMAND_SERVICE.EXTRA_PENDING_INTENT", pi);
            tokenRequestInFlight = true;
            startService(intent);
        } catch (Exception e) {
            tokenRequestInFlight = false;
            Log.d(TAG, "requestTokenFromTermux unavailable: " + e.getMessage());
        }
    }

    public static final String ACTION_TOKEN_RESULT = "com.pocket.agent.ACTION_TOKEN_RESULT";

    private BroadcastReceiver tokenReceiver;

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerTokenReceiver() {
        tokenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                tokenRequestInFlight = false;
                if (intent == null) return;
                android.os.Bundle b = intent.getBundleExtra(
                        "TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE");
                if (b == null) return;
                String out = b.getString("TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT");
                int exit = b.getInt("TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE", -1);
                if (exit == 0 && out != null) {
                    String t = out.trim();
                    if (t.length() >= 8 && t.length() <= 256) {
                        tokenCache = t;
                        Log.d(TAG, "token loaded from Termux");
                        // 通知 Web 层：页面可能已经在运行
                        if (web != null) {
                            web.evaluateJavascript(
                                    "window.dispatchEvent(new CustomEvent('pocket-token',{detail:"
                                            + jsString(t) + "}))", null);
                        }
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_TOKEN_RESULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tokenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(tokenReceiver, f);
        }
    }

    /** 把 Java 字符串安全地嵌进 JS 源码（转义引号/反斜杠/换行/尖括号）。 */
    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '<': sb.append("\\u003c"); break;
                case '>': sb.append("\\u003e"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
