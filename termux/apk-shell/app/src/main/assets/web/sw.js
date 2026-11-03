/* 口袋智灵 · Service Worker —— 极简离线壳
 *
 * 策略：
 *  - install：预缓存 app shell，并从 index.html 动态提取 /assets/* 与 /icons/* 一并缓存
 *  - fetch：同源静态资源 cache-first；/api/* 一律网络直连，绝不缓存
 *  - 兜底：只有页面导航请求离线时才回退 index.html；资源请求失败给 504（绝不拿 HTML 冒充 JS）
 *  - activate：清理旧版本缓存
 */
const CACHE = "pa-shell-v3";
const PRECACHE = ["/", "/index.html", "/manifest.webmanifest", "/icons/icon-192.png", "/icons/icon-512.png"];

async function precacheAppShell() {
  const cache = await caches.open(CACHE);
  await cache.addAll(PRECACHE).catch(() => {});
  try {
    // 动态提取构建产物（hashed 文件名），避免构建后手工维护清单
    const res = await fetch("/index.html", { cache: "reload" });
    const html = await res.text();
    const refs = [...html.matchAll(/(?:src|href)="([^"]+)"/g)]
      .map((m) => m[1])
      .filter((p) => p.startsWith("/assets/") || p.startsWith("/icons/"));
    await Promise.allSettled(refs.map((r) => cache.add(r)));
  } catch {
    /* 提取失败不影响后续 */
  }
}

self.addEventListener("install", (e) => {
  e.waitUntil(precacheAppShell());
  self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
  );
  self.clients.claim();
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  const url = new URL(req.url);
  // API、非 GET、跨源：一律网络直连
  if (req.method !== "GET" || url.origin !== self.location.origin || url.pathname.startsWith("/api/")) {
    return;
  }
  const isNavigate = req.mode === "navigate";

  // 页面导航：网络优先（新版本立即生效），成功后更新 app shell 缓存；离线回退缓存
  if (isNavigate) {
    e.respondWith(
      fetch(req)
        .then((res) => {
          if (res.ok) {
            const copy = res.clone();
            caches.open(CACHE).then((c) => c.put("/index.html", copy)).catch(() => {});
          }
          return res;
        })
        .catch(() => caches.match("/index.html"))
    );
    return;
  }

  e.respondWith(
    caches.match(req).then(
      (hit) =>
        hit ||
        fetch(req)
          .then((res) => {
            if (res.ok && (url.pathname.startsWith("/assets/") || url.pathname.startsWith("/icons/"))) {
              const copy = res.clone();
              caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
            }
            return res;
          })
          .catch(() =>
            // 离线兜底：页面导航给 app shell；资源请求明确失败，不冒充内容
            isNavigate ? caches.match("/index.html") : new Response("", { status: 504, statusText: "Offline" })
          )
    )
  );
});
