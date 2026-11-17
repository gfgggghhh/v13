# FINAL_FIX_HANDOVER

بيئة المراجعة: بلا Kotlin/Gradle/Android SDK/شبكة، وبلا مستودع Git (الأرشيف فقط) → **لم يُبنَ المشروع ولم يُشغَّل أي اختبار**. كل التعديلات مراجَعة ثابتًا فقط.

## التعديلات
| الملف | التعديل | التصنيف |
|---|---|---|
| AndroidManifest.xml | حذف `android:keepScreenOn` (غير صالحة على activity → خطأ aapt2 يكسر البناء) | ب |
| MainActivity.kt | `FLAG_KEEP_SCREEN_ON` بدلًا منها؛ `onFrame` آمن للاستثناءات (تحرير `Mat`)؛ حذف نسخة كاملة للإطار كل إطار (الإطار المعالَج نفسه يصبح latestRaw)؛ عرض «المطلوب→المستلم→المعالَج» في سطر الحالة | ب |
| PhoneCameraSource.kt | `AspectRatioStrategy` حسب النسبة المطلوبة (الافتراضي 4:3 كان يحوّل طلب 16:9 إلى حجم 4:3)؛ إعادة استخدام ByteArray؛ تحرير `Mat` عند الاستثناء | ب |
| UvcCameraSource.kt | رفض الإطار إذا حجمه أقل من المطلوب؛ إعادة استخدام المخزن؛ تحرير SurfaceTexture | ج (API المكتبة لم يُتحقق منه) |
| TemporalStabilizer.kt | `cur8/merged/newCnt/corners` تحت MatScope (لا تسرّب عند استثناء) — المنطق والقيم دون تغيير | ب |
| VeinProcessor.kt | `MatOfDouble` وخرج `toRgba` آمنان للاستثناء — الرياضيات دون تغيير (Legacy كما هو) | ب |

## لم يُغيَّر عمدًا (يحتاج قياسًا على الهاتف)
قيم المعالجة والتثبيت (EMA، عتبات الرفض، تخميد العرض ≤1.2% من العرض)، المستوى الافتراضي Medium، سقف المعالجة 960 عرضًا (أي دقة أعلى تُصغَّر).

## يحتاج عملًا لاحقًا
1. بناء Debug/Release (CI: `gradle testDebugUnitTest assembleDebug assembleRelease`) وإصلاح أي خطأ ترجمة في ملفات Android.
2. Gradle Wrapper: يحتاج `gradle wrapper --gradle-version 8.9` بشبكة (jar ثنائي).
3. اختبار يدوي على الهاتف: الدقة المستلمة (السطر الثاني في الشاشة)، FPS، تثبيت/ارتعاش/حركة سريعة، واجهة UVC.
