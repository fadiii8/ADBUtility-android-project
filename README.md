# ADB Utility — Android App

Ye project tumhari `index.html` UI ko ek asal Android app (APK) mein wrap karta
hai, WebView + ek native Kotlin bridge (`NativeBridge.kt`) ke through.

## Project structure

```
ADBUtility/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── index.html      <- tumhari original UI (bridge.js injected)
│       │   └── bridge.js       <- Promise shim: window.ADBBridge -> Native
│       ├── java/com/adbutility/app/
│       │   ├── MainActivity.kt <- WebView host
│       │   └── NativeBridge.kt <- root (su) se commands chalata hai
│       └── res/
├── build.gradle.kts
└── settings.gradle.kts
```

## Kaise build karein (Android Studio)

1. Android Studio (Hedgehog ya newer) khol lo → **Open** → is `ADBUtility`
   folder ko select karo.
2. Gradle sync khud ho jayega (internet chahiye hoga dependencies ke liye).
3. `Build > Build Bundle(s) / APK(s) > Build APK(s)` — ya toolbar se Run ▶
   dabao apne rooted phone/emulator par.
4. APK yahan milegi: `app/build/outputs/apk/debug/app-debug.apk`

Mere sandbox mein Android SDK/Gradle na hone ki wajah se main khud APK
compile nahi kar saka — lekin ye poora project as-is Android Studio mein
bina kisi change ke build ho jana chahiye.

## Ye kaam kaise karta hai

- `MainActivity.kt` ek `WebView` banata hai aur `assets/index.html` load
  karta hai, plus ek Java object `NativeBridge` ko `window.NativeBridge`
  ke naam se inject karta hai.
- `bridge.js` (jo `index.html` mein sabse pehle load hota hai) ek
  **Promise-based** `window.ADBBridge` object banata hai — bilkul wahi
  shape jo tumhari original UI code (`bridgeAvailable()`,
  `bridge.executeCommand(...)`, etc.) expect karta hai.
- Android ka `addJavascriptInterface` sirf **synchronous** calls allow
  karta hai, isliye har call ek unique `callbackId` ke sath native side
  bheji jaati hai; native side background thread par kaam karke result
  wapas `window.__adbResolve(id, result)` ke through bhejta hai.
- `NativeBridge.kt` commands `su -c "<command>"` ke through chalata hai —
  matlab **device rooted hona chahiye** aur app ko root grant milna
  chahiye (Magisk waghera se). Ye asal "USB adb" protocol nahi hai — ye
  sirf usi device par chalta hai jis par app installed hai.

## Zaroori baatein (important)

- **Root access** zaroori hai — bina root ke `reboot`, `reboot bootloader`,
  `reboot recovery` aur terminal commands kaam nahi karenge (UI khud
  "Native Android bridge is unavailable" dikha dega).
- Ye app **Google Play Store policy** violate karti hai (root/system-level
  apps allowed nahi hain) — isko sirf **sideload** (APK install) karna
  hoga, Play Store pe publish nahi ho sakti.
- Arbitrary shell command execution ek powerful feature hai — sirf apne
  khud ke, trusted device par install karo.
- App icon abhi default hai — chaho to apna `ic_launcher` icon
  `res/mipmap/` mein daal kar `AndroidManifest.xml` mein
  `android:icon="@mipmap/ic_launcher"` add kar dena.

## Agla step (agar chaho)

- Signed release APK banane ke liye keystore generate karo aur
  `app/build.gradle.kts` mein `signingConfigs` add karo.
- Agar future mein **real desktop-style ADB** (Wi-Fi ke through kisi
  doosre device ko control karna) chahiye ho, wo ek bilkul alag
  implementation hai (Android khud dusre devices ko adb se control nahi
  kar sakta bina desktop tool ke) — bata dena, alag approach banani
  hogi.


## OTG implementation notes

The USB stack is implemented directly against Android `UsbManager` and `UsbDeviceConnection`; no ADB/Fastboot library is used. The ADB authentication code persists a 2048-bit RSA keypair, signs the 20-byte challenge with `SHA1withRSA`, and sends the ADB-specific Montgomery public-key structure. The handshake also handles the important second token after the public-key approval prompt.

The USB transport deliberately does **not** inject zero-length packets. ADB and Fastboot are message-framed protocols, and forcing a ZLP from the Android host can cause interoperability problems on some OTG controllers. Reads also validate ADB header magic, payload length, and checksum.

### Sparse / large images

Android sparse images are valid Fastboot download payloads and are passed through unchanged; the target bootloader/fastbootd is responsible for expanding them. A generic Fastboot USB client cannot safely split one sparse `flash:<partition>` transaction across multiple `download` commands because the standard protocol does not provide a portable partition-offset/append operation. Therefore the app now reports this limitation clearly instead of pretending that independently flashing chunks is safe. If the sparse file itself fits `max-download-size`, it can be flashed normally, including `super.img`.

### Build without Android Studio

A GitHub Actions workflow is included at `.github/workflows/build.yml`. Push this project to GitHub, open **Actions → Build APK**, and download the generated `app-debug.apk` artifact. This avoids requiring Android Studio on your phone/PC.

For a Play-independent signed release, use your own keystore; never commit the keystore or passwords to the repository. The workflow is intentionally debug-build based by default so it does not contain a shared signing key.
