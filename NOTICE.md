# Notice

## Engine
`Bcore`, `black-reflection`, `compiler` come from **BlackBox** (Apache License 2.0, see `LICENSE-BlackBox`),
taken from the maintained fork https://github.com/ALEX5402/NewBlackbox (commit `89b5983`, 2026-07-19).

## Changes made to the engine
1. **Log upload disabled.** The original engine can upload the full device logcat to a third-party server
   (`logs-sender-api.vercel.app`) when a cloned app crashes. `ClientConfiguration.getLogSenderChatId()` now returns
   an empty string, `LogSender.send()` always returns early and the URL is removed. `ParallelApp` also overrides it.
2. **Removed** `top.niunaijun.blackbox.closecode` (dead native-lib injection hook for a placeholder package).

## Removed from the uploaded RizwanTask project
All RizwanTask screens and logic, Supabase/Admin/TTS/Push code, fonts, images, SQL files, notes, the package-rename
workflow, `local.properties` (it contained real Supabase keys: rotate them if that file was ever shared) and the
Supabase secrets in the workflows. Kept: workflow structure (debug build, signed release, keystore generator).

## Build toolchain
AGP 8.13.2, Gradle 8.14.3, Kotlin 1.9.23, JDK 21, NDK 29.0.13846066 (matches the engine; AGP 9 is not compatible with it).
