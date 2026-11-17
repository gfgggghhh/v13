# Veinfinder – Android

تحويل لبرنامج Veinfinder v1.0 (Windows / .NET + Emgu.CV) إلى تطبيق أندرويد (Kotlin + OpenCV).

## التشغيل
1. افتح المجلد في Android Studio (Koala أو أحدث) واعمل Sync.
2. وصّل الموبايل (USB debugging) واضغط Run.
3. كاميرا USB: وصّلها بكابل OTG، اختارها من القائمة (USB: ...)، واقبل صلاحية الـ USB.

## الإعدادات (نفس الـ .config الأصلي)
- k = 0.9 (من 0 إلى 1) و b = 10 (من 0 إلى 10): من زر ⚙ Settings.
- ثوابت الخوارزمية (sigma1=58.4، sigma2=29.6، sigma3=2.0) في VeinProcessor.kt.
- الصور بتتحفظ في Pictures/Veinfinder.


## الإصدار المطوَّر (تحسين الجودة + التثبيت الزمني)
راجع `docs/REPORT.md` للتفاصيل والأرقام. باختصار:
- **Display**: Original (خام) / Enhanced (تحسين مكاني) / Stabilized (تحسين + تثبيت زمني) — زر Display على الشاشة.
- **Enhancement level**: Legacy (الخوارزمية الأصلية بدون تغيير) / Low / Medium / High + Contrast + Noise reduction.
- **Temporal stabilization**: تشغيل/إيقاف + Strength. الحركة الكبيرة تُرفض (لا يتم دمجها).
- **Camera**: Resolution, Frame rate, Exposure lock, White-balance lock, Focus lock, Exposure compensation, Manual exposure/ISO, Manual focus — تظهر فقط حسب ما تدعمه الكاميرا فعلًا.
- **Save** يحفظ الصورة الأصلية (PNG بدون فقد) بجانب المعالجة. **Compare** يحفظ نفس الإطار بكل المستويات في مجلد واحد.
- اضغط على سطر الحالة (FPS/ms) لعرض تقرير إمكانيات الكاميرا (Camera report).
- الإعدادات تُحفظ وتُستعاد عند إعادة فتح التطبيق.
- اختبارات: `gradle testDebugUnitTest` (منطق الكاميرا/الإعدادات) و `tools/bench` (المعالجة على JVM).
