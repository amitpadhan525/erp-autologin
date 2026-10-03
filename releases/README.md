<div align="center">

# 📦 Release APKs & GitHub Releases

<br />

<a href="https://github.com/amitpadhan525/erp-autologin/releases/latest/download/erp-autologin-v3.0.apk">
  <img src="../assets/download_btn.svg" alt="Download APK" width="260" />
</a>

<br /><br />




</div>

This directory is designated for storing built APKs before uploading them to **GitHub Releases**.

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
Output location: `app/build/outputs/apk/release/app-release.apk`

---

## 🚀 How to Publish to GitHub Releases

### Option 1: Automated via GitHub Actions (Recommended)

1. **Automatic Tag Release:**
   Tag your commit and push it to GitHub:
   ```bash
   git tag v3.0
   git push origin v3.0
   ```
   GitHub Actions will automatically:
   - Build the release APK with Gradle & JDK 17
   - Name it `erp-autologin-v3.0.apk` & `erp-autologin.apk`
   - Calculate SHA256 checksums
   - Publish a new release in the **Releases** section with release notes and downloadable APK attachments.

2. **Manual Trigger (Actions Tab):**
   - Go to **Actions** → **Build & Release APK**
   - Click **Run workflow**
   - Optionally enter a version (defaults to `versionName` in `app/build.gradle`)
   - Click **Run workflow**

---

### Option 2: Using GitHub CLI (`gh`)
```bash
# Create a release tag and upload the APK manually
gh release create v2.1.0 releases/erp-autologin-v2.1.0.apk \
  --title "GIET ERP Auto-Login v2.1.0" \
  --notes "Automated login and neural CAPTCHA solver release."
```

### Option 3: Via GitHub Web Interface
1. Go to your repository on GitHub: `https://github.com/amitpadhan525/erp-autologin`
2. Click on **Releases** → **Draft a new release**.
3. Choose or create a tag (e.g., `v2.1.0`).
4. Drag and drop the `.apk` file from this `releases/` directory into the binaries attachment box.
5. Click **Publish release**.
