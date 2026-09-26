// Runs in discord.com before Discord's own code, followed by the Larpcord userscript.
// It stands in for a userscript manager: `unsafeWindow`, `GM_xmlhttpRequest`, plus
// Notification support, all backed by the Android app through `LarpcordNative`.
// MainActivity wraps this file and the userscript in one function, so everything
// declared here is local to Larpcord and invisible to Discord's code.

var unsafeWindow = window;
var bridge = window.LarpcordNative;
var pending = new Map();
var nextId = 1;

window.LarpcordAndroid = { version: "__APP_VERSION__" };

function send(type, payload) {
    var id = nextId++;
    return new Promise(function (resolve) {
        pending.set(id, resolve);
        bridge.postMessage(JSON.stringify(Object.assign({ type: type, id: id }, payload)));
    });
}

if (bridge) {
    bridge.onmessage = function (event) {
        var msg;
        try { msg = JSON.parse(event.data); } catch (e) { return; }
        if (msg.type === "notificationClick") {
            var n = notifications.get(msg.tag);
            if (n && typeof n.onclick === "function") n.onclick({ target: n });
            return;
        }
        var resolve = pending.get(msg.id);
        if (resolve) {
            pending.delete(msg.id);
            resolve(msg);
        }
    };
}

function toBase64(buffer) {
    var bytes = new Uint8Array(buffer), chunks = [];
    for (var i = 0; i < bytes.length; i += 0x8000) chunks.push(String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000)));
    return btoa(chunks.join(""));
}

function fromBase64(b64) {
    var bin = atob(b64 || ""), bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
}

// Same contract as Tampermonkey's GM_xmlhttpRequest, but the request is made by the app,
// so it isn't limited by CORS or Discord's connect-src policy.
var GM_xmlhttpRequest = function (opts) {
    var method = (opts.method || "GET").toUpperCase();
    var headers = {};
    if (opts.headers) {
        new Headers(opts.headers).forEach(function (value, key) { headers[key] = value; });
    }

    var bodyReady = Promise.resolve(null);
    if (opts.data != null && method !== "GET" && method !== "HEAD") {
        // Request serialises every body type (string, Blob, FormData, URLSearchParams...) for us
        var req = new Request("https://larpcord.invalid/", { method: "POST", body: opts.data });
        var type = req.headers.get("content-type");
        if (type && !headers["content-type"]) headers["content-type"] = type;
        bodyReady = req.arrayBuffer().then(toBase64);
    }

    var fail = function (why) {
        var handler = why === "timeout" ? opts.ontimeout : opts.onerror;
        if (typeof handler === "function") handler({ error: why, status: 0 });
    };

    if (!bridge) {
        window.fetch(opts.url, { method: method, headers: headers, body: opts.data }).then(function (res) {
            return res.blob().then(function (blob) {
                var lines = [];
                res.headers.forEach(function (v, k) { lines.push(k + ": " + v); });
                opts.onload && opts.onload({ status: res.status, statusText: res.statusText, finalUrl: res.url, responseHeaders: lines.join("\r\n"), response: blob });
            });
        }).catch(function () { fail("error"); });
        return;
    }

    bodyReady.then(function (body) {
        return send("fetch", { method: method, url: opts.url, headers: headers, body: body, timeout: opts.timeout || 30000 });
    }).then(function (res) {
        if (res.error) return fail(res.error === "timeout" ? "timeout" : "error");
        var bytes = fromBase64(res.body);
        var response = opts.responseType === "arraybuffer" ? bytes.buffer
            : opts.responseType === "json" ? JSON.parse(new TextDecoder().decode(bytes))
                : opts.responseType === "text" ? new TextDecoder().decode(bytes)
                    : new Blob([bytes], { type: res.contentType || "" });
        opts.onload && opts.onload({
            status: res.status,
            statusText: res.statusText,
            finalUrl: res.finalUrl,
            responseHeaders: res.headers,
            response: response,
            responseText: opts.responseType ? undefined : new TextDecoder().decode(bytes)
        });
    }).catch(function () { fail("error"); });
};

// Android WebView has no Notification API. Discord only shows message notifications when
// it exists and is granted, so provide one that posts real Android notifications.
var notifications = new Map();
var notifyPermission = "default";

function LarpcordNotification(title, options) {
    options = options || {};
    this.title = String(title);
    this.body = options.body || "";
    this.tag = options.tag || "n" + (nextId++);
    this.icon = options.icon || "";
    this.onclick = null;
    this.onclose = null;
    notifications.set(this.tag, this);
    if (notifications.size > 50) notifications.delete(notifications.keys().next().value);
    if (bridge && notifyPermission === "granted") {
        bridge.postMessage(JSON.stringify({ type: "notify", title: this.title, body: this.body, tag: this.tag }));
    }
}
LarpcordNotification.prototype.close = function () {
    if (bridge) bridge.postMessage(JSON.stringify({ type: "notifyClose", tag: this.tag }));
};
LarpcordNotification.prototype.addEventListener = function (name, fn) {
    if (name === "click") this.onclick = fn;
    if (name === "close") this.onclose = fn;
};
LarpcordNotification.prototype.removeEventListener = function () { };
Object.defineProperty(LarpcordNotification, "permission", { get: function () { return notifyPermission; } });
LarpcordNotification.requestPermission = function (callback) {
    var done = bridge
        ? send("notifyPermission", { ask: true }).then(function (r) { return r.state; })
        : Promise.resolve("denied");
    return done.then(function (state) {
        notifyPermission = state;
        if (typeof callback === "function") callback(state);
        return state;
    });
};

if (bridge) {
    window.Notification = LarpcordNotification;
    send("notifyPermission", { ask: false }).then(function (r) { notifyPermission = r.state; });
}

// A short line in logcat so a release build can be checked without devtools.
setTimeout(function () {
    try {
        var plugins = Object.keys(window.Vencord.Plugins.plugins).length;
        console.info("[LarpcordAndroid] loaded, " + plugins + " plugins");
    } catch (e) {
        console.error("[LarpcordAndroid] failed to load: " + e);
    }
}, 4000);
