/* =========================================================
   ADB UTILITY - Native Bridge Shim
   Connects window.ADBBridge (Promise API used by app.js) to
   the native Android JavascriptInterface (window.NativeBridge),
   which only supports synchronous calls that return via
   evaluateJavascript() callbacks.
   ========================================================= */

(function () {
    "use strict";

    var pending = {};
    var counter = 0;

    function nextId() {
        counter += 1;
        return "cb_" + Date.now() + "_" + counter;
    }

    function callNative(nativeMethod, args) {
        return new Promise(function (resolve, reject) {
            if (!window.NativeBridge || typeof window.NativeBridge[nativeMethod] !== "function") {
                reject(new Error("Native method not available: " + nativeMethod));
                return;
            }

            var id = nextId();
            pending[id] = { resolve: resolve, reject: reject };

            try {
                window.NativeBridge[nativeMethod](JSON.stringify(args || {}), id);
            } catch (err) {
                delete pending[id];
                reject(err);
            }
        });
    }

    /* Called by native code: window.__adbResolve(id, jsonResultString) */
    window.__adbResolve = function (id, resultJson) {
        var entry = pending[id];
        if (!entry) return;
        delete pending[id];

        var parsed = resultJson;
        try {
            parsed = JSON.parse(resultJson);
        } catch (e) {
            /* leave as raw string */
        }
        entry.resolve(parsed);
    };

    /* Called by native code: window.__adbReject(id, errorMessage) */
    window.__adbReject = function (id, message) {
        var entry = pending[id];
        if (!entry) return;
        delete pending[id];
        entry.reject(new Error(message));
    };

    window.ADBBridge = {
        // ---- host device (root) ----
        getDeviceInfo: function () {
            return callNative("nativeGetDeviceInfo", {});
        },
        reboot: function () {
            return callNative("nativeReboot", {});
        },
        rebootBootloader: function () {
            return callNative("nativeRebootBootloader", {});
        },
        rebootRecovery: function () {
            return callNative("nativeRebootRecovery", {});
        },
        executeCommand: function (args) {
            return callNative("nativeExecuteCommand", args || {});
        },

        // ---- OTG target device (real ADB over USB) ----
        usbConnect: function () {
            return callNative("nativeUsbConnect", {});
        },
        usbDisconnect: function () {
            return callNative("nativeUsbDisconnect", {});
        },
        usbStatus: function () {
            return callNative("nativeUsbStatus", {});
        },
        usbShell: function (args) {
            return callNative("nativeUsbShell", args || {});
        }
    };

    window.FastbootBridge = {
        command: function (args) {
            return callNative("nativeFastbootCommand", args || {});
        },
        unlock: function () {
            return callNative("nativeFastbootUnlock", {});
        },
        lock: function () {
            return callNative("nativeFastbootLock", {});
        },
        rebootBootloader: function () {
            return callNative("nativeFastbootRebootBootloader", {});
        },
        reboot: function () {
            return callNative("nativeFastbootReboot", {});
        },
        erase: function (args) {
            return callNative("nativeFastbootErase", args || {});
        },
        flash: function (args) {
            return callNative("nativeFastbootFlash", args || {});
        }
    };

    /* Let native side know the page (and bridge) is ready */
    document.addEventListener("DOMContentLoaded", function () {
        if (window.NativeBridge && typeof window.NativeBridge.nativeOnReady === "function") {
            try {
                window.NativeBridge.nativeOnReady();
            } catch (e) { /* ignore */ }
        }
    });
})();
