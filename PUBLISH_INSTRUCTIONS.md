# 🚀 دليل النشر السريع للتحديثات على GitHub

عندما يطلب المستخدم رفع أو نشر تحديث جديد للتطبيق، لا داعي لقراءة أو تعديل ملفات يدوياً.
يكفي تشغيل سكريبت النشر المباشر `publish.py` عبر سطر أوامر واحد:

---

### 1️⃣ لنشر تحديث إجباري تلقائي (Auto-increment Forced Update):
يقوم تلقائياً بزيادة رقم الإصدار (`versionCode + 1` و `versionName + 1`) وتجميع الـ APK وبناء Release ونشره:
```bash
python publish.py --force --notes "ملاحظات التحديث بالعربي هنا"
```

---

### 2️⃣ لنشر تحديث إجباري مع تحديد رقم إصدار معين:
```bash
python publish.py -v 1.3.0 -c 4 --force --notes "ملاحظات الإصدار الجديد"
```

---

### 3️⃣ لنشر تحديث اختياري (Optional Update):
```bash
python publish.py -v 1.3.0 -c 4 --optional --notes "ملاحظات التحديث الاختياري"
```

---

### ⚡ ماذا يفعل السكريبت تلقائياً بضغطة واحدة؟
1. يحدّث رقم الإصدار ورمز البناء داخل `app/build.gradle.kts`.
2. يبني نسخة الـ Release بواسطة `./gradlew assembleRelease`.
3. ينشئ Release جديد وتصنيف Tag على GitHub عبر الـ GitHub API.
4. يرفع ملف الـ APK باسم الإصدار المباشر (`AutoClicker-vX.X.X.apk`).
5. يحدّث ملف `version.json` مع رابط التنزيل المباشر ونوع التحديث (إجباري أو اختياري).
6. يقوم بعمل `git commit` و `git push` تلقائياً لمستودع GitHub.
