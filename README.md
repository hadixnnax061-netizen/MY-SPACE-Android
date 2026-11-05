# MY SPACE — تطبيق Android

تحويل كامل لحزمة **MY SPACE v16.2 — Gemini Living Cosmos** من تطبيق ويب PWA يعتمد على خادم Python محلي إلى تطبيق Android أصلي بحزمة APK قابلة للتثبيت.

> **تنزيل APK:** [أحدث إصدار](https://github.com/hadixnnax061-netizen/MY-SPACE-Android/releases/latest/download/MY-SPACE-v16.2.apk)
> **المستودع:** https://github.com/hadixnnax061-netizen/MY-SPACE-Android

---

## ما هو التطبيق؟

MY SPACE تطبيق عربي (RTL) لتتبّع العادات والأهداف عبر «نجم» ثلاثي الأبعاد ينمو مع تقدّمك:

- **الرئيسية:** نجم تفاعلي ثلاثي الأبعاد، مراحل تطور، مزاج وذكريات، بطاقة ومشاركة.
- **المهام:** عادات يومية أو أهداف أسبوعية أو مهام لمرة واحدة، مع سلاسل إنجاز وخريطة حرارية.
- **المصغّرات:** ودجات قابلة للتخصيص على الشاشة الرئيسية.
- **الاستوديو:** تصميم النجم والشكل النهائي والطبقات والألوان والمواد، ورفع صورة خاصة.
- **الحوار:** محادثة حقيقية مع Gemini بصفتين مستقلتين: النجم والكوكب، ولكل شخصية سياقها وسجلها.
- **Texter:** مساحة نص واسعة للكتابة واللصق، مع عدّاد أحرف وقراءة صوتية عربية وحفظ الملاحظات.
- **خامات كوكبية:** عشر خامات إجرائية عالية التفاصيل (أرضي، غازي، جليدي، حمم، صحراوي، فضائي، سام، بلوري، معدني، سديمي) مع إضاءة نجمية حقيقية.
- **ودجت الشاشة الرئيسية:** كوكبك وتقدّمك كودجت Android مستقل، قابل للتثبيت من داخل التطبيق.
- **إحصاءات وملاحظة يومية:** بطاقة Insights لمهامك وسلاسلك، وملاحظة يومية تُحفظ محليًا.
- **خلفية OLED:** خلفية الفضاء والبطاقات سوداء بالكامل لتباين أعلى واستهلاك أقل.
- **الصوت:** تحويل رد Gemini إلى كلام عربي عبر Gemini TTS، مع الرجوع لصوت النظام عند التعذّر.
- **الإعدادات:** المظهر والتنقّل والنسخ الاحتياطي والاستيراد.

البيانات تُحفظ محلياً على الجهاز فقط (`localStorage` + `IndexedDB`)، ولا يوجد حساب أو تتبّع.

---

## لماذا لم نستخدم Cordova أو خادم Python داخل APK؟

الخادم الأصلي `server.py` موجود فقط لسببين: تجنّب CORS، وإخفاء مفتاح Gemini عن المتصفح.

في تطبيق Android استُبدل ذلك بجسر أصلي:

```
┌──────────────────────────────────────────────┐
│ APK                                          │
│  WebView (assets محلية، بلا تبعيات خارجية)   │
│   https://appassets.androidplatform.net/...  │
│        │                                     │
│        ├── /api/*  → android-bridge.js        │
│        │              ↓ (Promise + callback) │
│        │        GeminiBridge.java             │
│        │              ↓                      │
│        │        GeminiClient.java  ──────────┼──► Google Gemini REST
│        │                                     │
│        └── واجهة عربية RTL + Canvas 3D        │
│                                              │
│  Android Keystore ← مفتاح API مشفّر           │
└──────────────────────────────────────────────┘
```

- **لا خادم محلي** داخل التطبيق، ولا منفذ مفتوح، ولا CORS.
- **المفتاح لا يصل إلى JavaScript** ولا يُخزَّن في `localStorage` أو في ملف نصي.
- **طلبات Gemini تتم أصلياً** (`HttpsURLConnection`) وتعود إلى الصفحة عبر `window.__myspaceNativeResolve`.
- **`/api/*` الأربعة** تعمل كما هي: `status` و`config/key` و`test` و`chat` و`speech` دون تعديل منطق التطبيق الأصلي.
- **أصول الويب** تُقدَّم عبر معالج أصول مخصص داخل التطبيق على أصل HTTPS آمن، بلا أي مكتبات خارجية، لذلك تعمل الواجهة كما في المتصفح.

---

## البنية

```
.
├── web/                        # حزمة PWA الأصلية (كما هي، مع خادم Python للكمبيوتر)
│   ├── index.html
│   ├── manifest.webmanifest
│   ├── sw.js
│   ├── server.py
│   └── icons/
├── android/                    # مشروع Android (Java + AGP 8.7 + Gradle 8.9)
│   ├── app/
│   │   ├── src/main/java/com/myspace/app/
│   │   │   ├── MainActivity.java      # WebView، الأصول، الشريط/الحواف، الملفات، الرجوع
│   │   │   ├── GeminiBridge.java      # واجهة JS ← أدوات أصلية غير متزامنة
│   │   │   ├── GeminiClient.java      # Gemini REST + TTS + غلاف WAV
│   │   │   └── SecureKeyStore.java    # AES-256-GCM داخل Android Keystore
│   │   ├── src/main/web-shim/
│   │   │   └── android-bridge.js      # يُحقن وقت البناء فقط
│   │   └── src/main/res/              # الأيقونات والسمة
│   └── gradlew
├── .github/workflows/android.yml      # بناء APK تلقائياً + Artifact + Release
└── README.md
```

### كيف يُحقن الجسر؟

مهمة Gradle باسم `syncWebAssets` تنسخ `web/index.html` والأيقونات إلى
`android/app/build/generated/web/www/`، ثم:

1. تضيف `<script src="android-bridge.js"></script>` داخل `<head>`.
2. تحدّث نص إعدادات المفتاح ليعكس التخزين المشفّر داخل التطبيق.

لا يُعدَّل ملف `web/index.html` الأصلي، لذا تبقى نسخة PWA تعمل على الكمبيوتر كما هي.

### تفاصيل التنفيذ

| الجانب | التنفيذ |
|---|---|
| الأصل | `https://appassets.androidplatform.net/assets/www/index.html` عبر معالج أصول مخصص |
| الجسر | `@JavascriptInterface` غير متزامن + `ExecutorService` + `evaluateJavascript` |
| المفتاح | AES/GCM/NoPadding بمفتاح 256-bit غير قابل للتصدير في Android Keystore |
| الصوت | `Gemini TTS` عبر `interactions`، ثم نموذج TTS القياسي، ثم صوت المتصفح |
| Texter | محرر نص + قراءة Gemini TTS + ملاحظات محفوظة داخل بيانات التطبيق |
| الملفات | `ACTION_GET_CONTENT` للاستيراد و`ACTION_CREATE_DOCUMENT` للتصدير |
| الحافظة | `ClipboardManager` |
| الروابط الخارجية | تُفتح في المتصفح، وليست داخل WebView |
| الرجوع | يعود إلى «الرئيسية» داخل التطبيق قبل الخروج |
| الحواف | Edge-to-edge مع احترام `system bars` و`IME` على Android 11+ |
| الشبكة | HTTPS فقط، `usesCleartextTraffic=false` |

---

## التثبيت

1. نزّل `MY-SPACE-v16.2.apk` من [أحدث إصدار](https://github.com/hadixnnax061-netizen/MY-SPACE-Android/releases/latest).
2. على الهاتف اسمح بتثبيت التطبيقات من هذا المصدر (المتصفح أو مدير الملفات).
3. ثبّت التطبيق وافتحه.
4. من **الإعدادات → الحديث الذكي · Google Gemini** الصق مفتاح Gemini من
   [Google AI Studio](https://aistudio.google.com/app/apikey) واضغط **حفظ المفتاح واختباره**.
5. افتح **الحوار** واختر «التحدث مع النجم» أو «التحدث مع الكوكب».

- الحد الأدنى: **Android 7.0 (API 24)**.
- الحزمة: `com.myspace.app`.
- الصلاحيات المطلوبة: `INTERNET` و`ACCESS_NETWORK_STATE` للفحص، و`VIBRATE` للاستجابة اللمسية.

> **ملاحظة أمنية:** لا تضع المفتاح في محادثة أو Issue أو ملف في المستودع. المفتاح يُدخل من داخل التطبيق ويُخزَّن مشفّراً على الجهاز فقط.

---

## البناء محلياً

المتطلبات:

- JDK 17
- Android SDK مع `platforms;android-35` و`build-tools;35.0.0`
- اتصال إنترنت أول مرة لتنزيل Gradle وتبعيات AndroidX

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk

cd android
./gradlew assembleDebug      # APK للتجربة
./gradlew assembleRelease    # APK للإصدار
```

الملفات الناتجة:

```
android/app/build/outputs/apk/debug/app-debug.apk
android/app/build/outputs/apk/release/app-release.apk
```

بدون إعداد توقيع، يُوقَّع `release` بمفتاح debug تلقائياً ليبقى قابلاً للتثبيت. لتوقيع ثابت:

```bash
keytool -genkeypair -v -keystore android/keystore/release.jks \
  -alias myspace -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=MY SPACE, O=MY SPACE, C=LB"

cp android/keystore.properties.example android/keystore.properties
# عدّل storePassword و keyPassword محلياً فقط
```

---

## GitHub Actions

الملف `.github/workflows/android.yml` يعمل عند:

- `push` إلى `main` → يبني APK ويحدّث إصدار **latest** في Releases ويضيف Artifact.
- وسم `v*` → ينشئ Release مرقّماً مع ملاحظات الإصدار.
- `pull_request` → يبني ويتحقق فقط.
- `workflow_dispatch` → بناء يدوي.

### أسرار التوقيع (اختياري لكنه مستحسن لثبات التحديثات)

أضف هذه الأسرار في **Settings → Secrets and variables → Actions**:

| السر | القيمة |
|---|---|
| `MYSPACE_KEYSTORE_BASE64` | ناتج `base64 -w0 android/keystore/release.jks` |
| `MYSPACE_KEYSTORE_PASSWORD` | كلمة مرور المخزن |
| `MYSPACE_KEY_ALIAS` | `myspace` |
| `MYSPACE_KEY_PASSWORD` | كلمة مرور المفتاح |

إذا لم تُضف الأسرار، يبقى البناء ناجحاً ويُوقَّع APK بمفتاح debug **مؤقت**، ما يعني أن التحديثات اللاحقة قد تتطلب إزالة النسخة السابقة أولاً.

---

## الفروق عن نسخة الويب

| PWA | APK |
|---|---|
| يحتاج `server.py` وPython 3 | لا يحتاج أي خادم |
| المفتاح في `.env` على الكمبيوتر | المفتاح مشفّر في Android Keystore |
| Service Worker للتخزين المؤقت | معطّل داخل APK لأن الأصول مضمّنة |
| تنزيل JSON عبر المتصفح | حفظ عبر منتقي ملفات Android (SAF) |
| الروابط الخارجية في نفس التبويب | تُفتح في المتصفح |
| التثبيت كـ PWA | تثبيت APK عادي |

---

## استكشاف الأخطاء

| المشكلة | الحل |
|---|---|
| «لم يُضبط مفتاح Gemini بعد» | الإعدادات → الصق المفتاح → حفظ واختبار |
| `401/403` | تحقق من صلاحية المفتاح والمشروع في Google AI Studio |
| `429` | تجاوزت الحصة؛ انتظر أو راجع لوحة الاستخدام |
| «خدمة Google غير متاحة مؤقتاً» | خطأ مؤقت من Google؛ أعد المحاولة |
| لا يظهر الصوت | قد لا يدعم المفتاح TTS؛ يعود التطبيق لصوت النظام تلقائياً |
| فشل بناء بسبب SDK | ثبّت `platforms;android-35` و`build-tools;35.0.0` واقبل التراخيص |
| APK قديم بعد التحديث | أزل النسخة القديمة ثم ثبّت الجديدة (إذا تغيّر التوقيع) |

سجل أخطاء WebView متاح عند بناء `debug` عبر `chrome://inspect`.

---

## الخصوصية

- لا حسابات، لا تحليلات، لا إعلانات، لا تتبّع.
- بيانات العادات والنجوم تبقى في `localStorage`/`IndexedDB` داخل التطبيق.
- النسخ الاحتياطي معطّل على مستوى Android (`allowBackup=false`).
- مفتاح Gemini يُرسل فقط في ترويسة `x-goog-api-key` إلى `generativelanguage.googleapis.com`.
- محتوى المحادثة يُرسل إلى Google وفق شروط استخدام Gemini.

---

## المصدر

- التطبيق الأصلي: `MY_SPACE_v16_1_Gemini_Key_Paste_PWA.zip` (v16.1).
- إصدار Android: 16.2.0 (`versionCode 16020`).
