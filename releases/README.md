# 📦 Release APKs & GitHub Releases

This directory is designated for storing built APKs (e.g., debug or release builds) before uploading them to **GitHub Releases**.

> **Note:** `.apk` and `.aab` binary files in this folder are ignored by Git (via [releases/.gitignore](.gitignore)) to prevent bloating the Git repository history.

---

## 🛠️ How to Generate APKs

### Debug Build:
```bash
./gradlew assembleDebug
```
Output location: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build:
```bash
./gradlew assembleRelease
```
Output location: `app/build/outputs/apk/release/app-release-unsigned.apk` (or signed APK if signing config is provided)

You can copy the generated APK into this folder:
```bash
cp app/build/outputs/apk/debug/app-debug.apk releases/erp-autologin-v1.0.0.apk
```

---

## 🚀 How to Publish to GitHub Releases

### Option 1: Using GitHub CLI (`gh`)
```bash
# Create a release tag and upload the APK
gh release create v1.0.0 releases/erp-autologin-v1.0.0.apk \
  --title "GIET ERP Auto-Login v1.0.0" \
  --notes "Initial release with ML Kit on-device CAPTCHA solver and persistent login."
```

### Option 2: Via GitHub Web Interface
1. Go to your repository on GitHub: `https://github.com/amitpadhan525/erp-autologin`
2. Click on **Releases** → **Draft a new release**.
3. Choose or create a tag (e.g., `v1.0.0`).
4. Drag and drop the `.apk` file from this `releases/` directory into the binaries attachment box.
5. Click **Publish release**.
