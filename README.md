# Reel Blocker

Local DNS-filtering VPN for Android (no external server). Blocks selected
Facebook/Instagram media hosts by answering their DNS lookups with 0.0.0.0.

Build: push to GitHub -> Actions -> "Build APK" -> download artifact `ReelBlocker-debug-apk`.

Tuning: run `adb logcat -s ReelBlocker` while opening a reel to see which
hostnames are ALLOWed/BLOCKed, then edit `isDomainBlocked()`.
