# 🌀 Parallel

একই অ্যাপের একাধিক কপি (আলাদা অ্যাকাউন্ট) চালানোর Android অ্যাপ।
Package: `com.srizwan.parallel` · UI পুরোটা Kotlin কোডে (XML layout নেই, drawable নেই, আইকন = emoji)।

## Build (GitHub Actions)
1. প্রজেক্ট GitHub repo-তে push করুন।
2. **Actions → Android Build → Run workflow** → শেষে Releases-এ `app-debug.apk`।
3. Signed release চাইলে একবার **Generate Release Keystore** চালিয়ে ৪টা secret বসান, তারপর **Android Release Build (Signed)**।

লোকালে: JDK 21, Android SDK 35, NDK `29.0.13846066` লাগবে, তারপর `./gradlew assembleDebug`।

## কাঠামো
```
app/         শুধু আপনার অ্যাপ (ParallelApp, Engine, MainActivity, Ui) + AndroidManifest.xml
Bcore/       BlackBox engine (virtual space, hook, native)
black-reflection/, compiler/   engine-এর reflection সহায়ক
```
`Engine.kt` হলো engine-এর একমাত্র দরজা: clone, open, clear, remove।

## মনে রাখার বিষয়
- `targetSdk = 28` ইচ্ছাকৃত (engine-এর জন্য ভালো), Play Store-এর জন্য উপযুক্ত নয়, sideload চলবে।
- Android 11+ এ clone খোলার সময় "সব ফাইলে অ্যাক্সেস" না থাকলে ফাইল-সংক্রান্ত কাজ ব্যর্থ হতে পারে।
- Google login / Play Integrity নির্ভর অ্যাপ (ব্যাংকিং ইত্যাদি) ঠিকমতো না-ও চলতে পারে।
- অ্যাপের আইকন এখন Android-এর default, কারণ launcher icon-এর জন্য একটা ছবি (mipmap/drawable) লাগে।

বিস্তারিত পরিবর্তন: `NOTICE.md`
