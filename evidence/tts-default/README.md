# System-default TTS patch — 8 September 2026

Installed in place on TCL T807D, without data reset or engine settings changes.
APK: `artifacts/beta-0.9.0-beta.1/reader-system-default-tts.apk`.
SHA-256: `6933b3774006d7e024d9dd052fb6ed2277931b40bf94df0b2c362379409b6c3b`.

Added the Android 11+ TTS service visibility query. Retained the system-default
TextToSpeech constructor. Removed the per-utterance UI-locale override so the
engine's configured voice/language is retained.

PASS: build, install and discovery of the system default `com.nekospeak.tts`.
Reader's platform logs show it requesting NekoSpeak rather than Google.
FAIL: complete playback. One connection succeeded but forcing eng-DEU caused
loadVoice ERROR. After removing that override, Android's TTS manager refused to
bind to NekoSpeak. This also occurred using normal Reader Listen outside the test
runner and after opening NekoSpeak once. The remaining binding failure's cause
is not established. No successful-audio claim, engine reset, default-setting change
or hidden-API workaround. Only the new speech check was exercised.

Reference: https://developer.android.com/reference/android/speech/tts/TextToSpeech
