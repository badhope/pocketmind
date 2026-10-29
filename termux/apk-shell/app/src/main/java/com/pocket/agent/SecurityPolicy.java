package com.pocket.agent;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 纯 Java 的安全策略：不引用任何 android.* ，因此可以在 JVM 上直接单测。
 *
 * 为什么单独抽出来：宿主是没有硬件虚拟化的 KVM 虚拟机，Android 模拟器起不来
 * （x86_64 需 accel、ARM64 被 QEMU2 拒绝），"在模拟器里点一遍"这条路走不通。
 * 但安全判定本身是纯字符串逻辑 —— 抽成无 Android 依赖的类后，
 * `./gradlew testDebugUnitTest` 就能在 JVM 上真实执行它，
 * 而不是靠"看代码觉得没问题"。
 *
 * 三个策略：
 *   1. isTrustedUrl   —— WebView 导航白名单（页面上有可执行 Termux 命令的 JS 桥）
 *   2. isSafeRepoUrl  —— 一键部署的仓库地址（会被拼进 shell 命令）
 *   3. shellQuote     —— POSIX 单引号转义（纵深防御）
 */
public final class SecurityPolicy {

    private SecurityPolicy() {
    }

    /** 允许在带 JS 桥的 WebView 里加载的页面。 */
    public static boolean isTrustedUrl(String url) {
        if (url == null) return false;
        // 不做 trim 后放行：首尾空白属于 URL 混淆信号（不同解析器行为不一致），直接拒绝
        if (!url.equals(url.trim())) return false;
        String s = url;
        if (s.isEmpty()) return false;
        // 控制字符/空白一律拒绝：可被用来做 URL 解析歧义（"http://127.0.0.1\n.evil.com"）
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c == 0x7F) return false;
        }
        URI u;
        try {
            u = new URI(s);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = u.getScheme();
        if (scheme == null) return false;
        scheme = scheme.toLowerCase();
        if ("file".equals(scheme)) {
            // 只允许 APK 自带页面；file:///sdcard 之类一律拒绝
            return s.startsWith("file:///android_asset/");
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        String host = u.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        return "127.0.0.1".equals(host) || "localhost".equals(host);
    }

    /**
     * 一键部署的仓库地址是否可接受。
     *
     * 只允许 http(s) 且字符集限定在 URL 安全子集；显式排除 shell 元字符
     * （引号、分号、反引号、$、竖线、&、尖括号、括号、反斜杠），
     * 即便后续拼接逻辑出错也不会变成命令注入。
     */
    public static boolean isSafeRepoUrl(String url) {
        if (url == null) return false;
        String s = url.trim();
        if (s.isEmpty() || s.length() > 500) return false;
        if (!s.startsWith("http://") && !s.startsWith("https://")) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "-._~:/?#[]@!*+,=%".indexOf(c) >= 0;
            if (!ok) return false;
        }
        return true;
    }

    /** POSIX 单引号转义：整体包成字面量，内部单引号写成 '\''。 */
    public static String shellQuote(String raw) {
        if (raw == null) return "''";
        return "'" + raw.replace("'", "'\\''") + "'";
    }
}
