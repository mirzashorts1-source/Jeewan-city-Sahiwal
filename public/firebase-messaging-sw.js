importScripts("https://www.gstatic.com/firebasejs/11.6.1/firebase-app-compat.js");
importScripts("https://www.gstatic.com/firebasejs/11.6.1/firebase-messaging-compat.js");

const APP_SHELL_CACHE = "jeewan-city-shell-v2";
const RUNTIME_ASSET_CACHE = "jeewan-city-runtime-v2";
const APP_SHELL_URLS = [
    "./",
    "./index.html",
    "./icon-192.png",
    "./ads.txt",
    "./app-ads.txt"
];
const BYPASS_HOST_SNIPPETS = [
    "firestore.googleapis.com",
    "identitytoolkit.googleapis.com",
    "securetoken.googleapis.com",
    "firebaseinstallations.googleapis.com",
    "fcmregistrations.googleapis.com",
    "fcm.googleapis.com",
    "api.cloudinary.com",
    "res.cloudinary.com",
    "pagead2.googlesyndication.com",
    "googleads.g.doubleclick.net",
    "googletagmanager.com",
    "google-analytics.com"
];

let messaging = null;
try {
    if (typeof firebase !== "undefined") {
        if (!firebase.apps || !firebase.apps.length) {
            firebase.initializeApp({
                apiKey: "AIzaSyD7S2ekPA2B6uMoqg621qaITRW9KqhTz1s",
                authDomain: "jeewan-city.firebaseapp.com",
                projectId: "jeewan-city",
                storageBucket: "jeewan-city.firebasestorage.app",
                messagingSenderId: "394435713585",
                appId: "1:394435713585:web:0c355cbe92fa69887fd310",
                measurementId: "G-DTJWCMHGBB"
            });
        }
        messaging = firebase.messaging();
    }
} catch (err) {}

self.addEventListener("install", (event) => {
    event.waitUntil(
        caches.open(APP_SHELL_CACHE).then((cache) =>
            Promise.allSettled(
                APP_SHELL_URLS.map((url) =>
                    cache.add(new Request(url, { cache: "reload" })).catch(() => null)
                )
            )
        ).then(() => self.skipWaiting())
    );
});

self.addEventListener("activate", (event) => {
    event.waitUntil(
        caches.keys().then((keys) =>
            Promise.all(
                keys
                    .filter((key) => key.startsWith("jeewan-city-") && key !== APP_SHELL_CACHE && key !== RUNTIME_ASSET_CACHE)
                    .map((key) => caches.delete(key))
            )
        ).then(() => self.clients.claim())
    );
});

function shouldBypassRequest(url) {
    if (!url || (url.protocol !== "http:" && url.protocol !== "https:")) return true;
    const host = url.hostname.toLowerCase();
    for (let i = 0; i < BYPASS_HOST_SNIPPETS.length; i++) {
        if (host.includes(BYPASS_HOST_SNIPPETS[i])) return true;
    }
    return false;
}

function isCacheableStaticAsset(url) {
    if (url.origin === self.location.origin) return true;
    if (url.hostname === "www.gstatic.com" && url.pathname.includes("/firebasejs/")) return true;
    if (url.hostname === "cdnjs.cloudflare.com" && url.pathname.includes("/qrcodejs/")) return true;
    return false;
}

self.addEventListener("fetch", (event) => {
    const req = event.request;
    if (!req || req.method !== "GET") return;
    let url;
    try {
        url = new URL(req.url);
    } catch (e) {
        return;
    }
    if (shouldBypassRequest(url)) return;

    if (req.mode === "navigate") {
        event.respondWith(
            caches.open(APP_SHELL_CACHE).then((cache) =>
                Promise.all([
                    cache.match(req, { ignoreSearch: true }),
                    cache.match("./index.html"),
                    cache.match("./")
                ]).then(([exactMatch, indexMatch, rootMatch]) => {
                    const cached = exactMatch || indexMatch || rootMatch;
                    const networkFetch = fetch(req)
                        .then((networkRes) => {
                            if (networkRes && networkRes.ok) {
                                cache.put(req, networkRes.clone()).catch(() => {});
                                cache.put("./index.html", networkRes.clone()).catch(() => {});
                            }
                            return networkRes;
                        })
                        .catch(() => cached || Response.error());

                    if (cached) {
                        event.waitUntil(networkFetch.catch(() => null));
                        return cached;
                    }
                    return networkFetch;
                })
            )
        );
        return;
    }

    if (!isCacheableStaticAsset(url)) return;

    event.respondWith(
        caches.open(RUNTIME_ASSET_CACHE).then((cache) =>
            caches.match(req).then((cached) => {
                const networkFetch = fetch(req)
                    .then((networkRes) => {
                        if (networkRes && (networkRes.ok || networkRes.type === "opaque")) {
                            cache.put(req, networkRes.clone()).catch(() => {});
                        }
                        return networkRes;
                    })
                    .catch(() => cached);

                if (cached) {
                    event.waitUntil(networkFetch.catch(() => null));
                    return cached;
                }
                return networkFetch;
            })
        )
    );
});

if (messaging) {
    messaging.onBackgroundMessage((payload) => {
        const data = payload && payload.data ? payload.data : {};
        const notif = payload && payload.notification ? payload.notification : {};
        const postId = data.postId || data.messageId || "";
        const title = notif.title || data.title || "Jeewan City Sahiwal";
        const body = notif.body || data.body || "New message in Jeewan City group";
        const icon = notif.icon || data.icon || "/icon-192.png";
        const badge = data.badge || "/icon-192.png";
        const tag = postId ? ("jeewan-post-" + postId) : "jeewan-city-chat";

        return self.registration.showNotification(title, {
            body: body,
            icon: icon,
            badge: badge,
            tag: tag,
            renotify: true,
            vibrate: [200, 100, 200],
            data: {
                postId: postId,
                url: postId ? ("/?postId=" + encodeURIComponent(postId)) : "/"
            }
        });
    });
}

self.addEventListener("notificationclick", (event) => {
    event.notification.close();
    const data = (event.notification && event.notification.data) || {};
    const postId = data.postId || "";
    const targetUrl = data.url || "/";

    event.waitUntil(
        clients.matchAll({ type: "window", includeUncontrolled: true }).then((clientList) => {
            for (let i = 0; i < clientList.length; i++) {
                const client = clientList[i];
                if ("focus" in client) {
                    client.postMessage({ type: "OPEN_POST", postId: postId });
                    return client.focus();
                }
            }
            if (clients.openWindow) {
                return clients.openWindow(targetUrl);
            }
            return null;
        })
    );
});
