# KR Navigator release signing

All installable release APKs must be signed with the same persistent key.

Expected SHA-256 certificate fingerprint:

`B2:64:86:5D:B7:03:42:8C:69:34:34:6A:E0:C6:06:EC:7E:34:CF:C0:59:74:67:96:BB:3B:D8:D5:B5:83:03:F1`

GitHub Actions expects these repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Do not commit the keystore or secret values to this public repository.

The Build APK workflow uses `github.run_number` as `versionCode`, so each later Build APK run is installable as an Android update when signed with the same key.
