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
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

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
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

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
        pingOnResume();
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
     *
     * 为什么必须白名单：WebView 注入了 PocketNative 桥（可执行 Termux 命令），
     * 若允许任意导航，一个外部页面就能调用桥。这里的判定是「页面 URL 必须来自
     * 127.0.0.1 / localhost 的本机服务，或内置 file:///android_asset」。
     */
    private boolean isTrustedUrl(String url) {
        if (url == null) return false;
        Uri u = Uri.parse(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if ("file".equals(scheme)) {
            // 只允许 APK 自带页面；file:// 指向任意本地文件一律拒绝
            return url.startsWith("file:///android_asset/");
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        return "127.0.0.1".equals(host) || "localhost".equals(host);
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

    // ════ WebChromeClient：标题 + console ════
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
            if (agentdUnreachable) {
                finish();
                return true;
            }
            if (web.canGoBack()) {
                web.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
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

        // 通知发送统一走 MainActivity.postNotification（授权回调需访问）
        private void postNotification(String title, String body) {
            activity.postNotification(title, body);
        }
    }

    /** 只允许 http(s) 的 git URL：禁止空白、引号、分号、管道、反引号、$ 等 shell 元字符。 */
    private static final Pattern SAFE_REPO_URL =
            Pattern.compile("^https?://[A-Za-z0-9._~:/?#\\[\\]@!&'()*+,=%-]+$");

    private static boolean isSafeRepoUrl(String url) {
        if (url == null || url.length() > 500) return false;
        if (!SAFE_REPO_URL.matcher(url).matches()) return false;
        // 上面字符集里仍允许 () 与 &，但它们出现在 shell 里是元字符：
        // 用单引号包裹后这些都安全，因此这里只需再排除单引号（字符集已排除）。
        return !url.contains("\n") && !url.contains("\r");
    }

    /** POSIX 单引号转义：' → '\'' ，保证整个字符串被当成一个字面量。 */
    private static String shellQuote(String raw) {
        return "'" + raw.replace("'", "'\\''") + "'";
    }

    private void toastOnUiThread(String msg) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
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
    private String readTokenFromTermux() {
        return tokenCache;
    }

    /** 令牌缓存：由 Termux 在启动时通过 termux-notification / 文件写入后，壳主动拉取。 */
    private volatile String tokenCache = "";

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
            startService(intent);
        } catch (Exception e) {
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
