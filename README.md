# 🎓 GIET ERP Auto-Login & AI CAPTCHA Solver

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![ML Kit](https://img.shields.io/badge/OCR-Google%20ML%20Kit-orange.svg)](https://developers.google.com/ml-kit)
[![Python](https://img.shields.io/badge/Python-3.9%2B-blue.svg)](https://python.org)
[![FastAPI](https://img.shields.io/badge/Backend-FastAPI-teal.svg)](https://fastapi.tiangolo.com)

An automated persistent login assistant and intelligent CAPTCHA solver tailored for the **GIET BBS R ERP Portal** (`http://gietbbsrerp.in/`). The application eliminates the friction of entering credentials and solving distorted CAPTCHAs upon session expirations.

---

## 🌟 Key Features

### 📱 Android Application
* **⚡ One-Tap Auto-Login**: Automatically injects credentials (Roll No & Password) and solved CAPTCHA into the ERP portal.
* **🧠 On-Device OCR Solver**: Embedded **Google ML Kit** text recognition engine paired with adaptive luminance thresholding and multi-pass preprocessing for high-accuracy local CAPTCHA recognition.
* **🔒 Secure Local Storage**: User credentials are stored locally on-device using Android `SharedPreferences`.
* **🌐 Native Web Experience**: Embedded Chromium WebView with session cookies persistence, pull-to-refresh, back navigation, and edge-to-edge UI.
* **🎨 Modern UI**: Built with Material Design 3, including custom status indicators, smooth animations, and system Dark Mode support.

### 🤖 AI / Deep Learning Module (Optional / Research)
* **🚀 FastAPI CAPTCHA Microservice (`server.py`)**: Lightweight REST API solving CAPTCHA images via neural recognition (`ddddocr`).
* **🧠 CRNN Training Pipeline (`train_crnn.py`)**: End-to-end PyTorch training pipeline (CNN + BiLSTM + CTC Loss) with built-in synthetic CAPTCHA generation for training custom lightweight OCR models.

---

## 🏗️ Architecture & Workflow

```mermaid
sequenceDiagram
    autonumber
    actor Student
    participant AndroidApp as Android App (WebView)
    participant Solver as On-Device ML Solver
    participant ERP as GIET ERP Portal

    Student->>AndroidApp: Opens App / Enters Credentials
    AndroidApp->>ERP: Loads ERP Login Page
    ERP-->>AndroidApp: Returns HTML with CAPTCHA Image
    AndroidApp->>Solver: Extracts Base64 CAPTCHA Bitmap
    Note over Solver: Adaptive Thresholding<br/>& Multi-Pass ML Kit OCR
    Solver-->>AndroidApp: Returns Solved CAPTCHA Text
    AndroidApp->>ERP: Auto-fills Roll No, Password & CAPTCHA and Submits
    ERP-->>AndroidApp: Authenticates & Loads Student Dashboard
```

---

## 📂 Project Structure

```
erp-autologin/
├── app/                                 # Android Application Module
│   ├── src/main/
│   │   ├── java/com/giet/erp/
│   │   │   ├── MainActivity.java       # WebView integration & auto-login orchestration
│   │   │   ├── CaptchaSolver.java      # On-device ML Kit OCR & image processing
│   │   │   └── SettingsActivity.java   # Credential configuration & preferences
│   │   ├── res/                        # Layouts, drawables, themes, and color definitions
│   │   └── AndroidManifest.xml         # Android manifest & permissions
│   └── build.gradle                    # App-level dependencies (ML Kit, Material, etc.)
├── server.py                            # FastAPI CAPTCHA solving server (ddddocr)
├── train_crnn.py                        # PyTorch CRNN (CNN+BiLSTM+CTC) model training script
├── requirements.txt                     # Python dependencies for backend & ML
├── build.gradle                         # Root Gradle build script
├── settings.gradle                      # Gradle project settings
├── .gitignore                           # Git ignore rules for Android, Python & IDEs
└── LICENSE                              # MIT License
```

---

## 🚀 Getting Started

### Prerequisites

* **Android Development**: Android Studio Iguana (or newer), Android SDK 24+ (Android 7.0+), JDK 17.
* **Python Backend (Optional)**: Python 3.9+, `pip`.

---

### 1. Running the Android Application

1. Clone this repository:
   ```bash
   git clone https://github.com/amitpadhan525/erp-autologin.git
   cd erp-autologin
   ```
2. Open the project in **Android Studio**.
3. Allow Gradle to sync dependencies.
4. Connect an Android device (or launch an emulator) and click **Run** (`Shift + F10`).
5. Open the app settings (⚙️ icon) to configure your **Roll No** and **Password**.
6. The app will automatically handle logins on subsequent launches.

---

### 2. Running the Python CAPTCHA Server (Optional)

If using external API-based CAPTCHA solving:

1. Create and activate a Python virtual environment:
   ```bash
   python3 -m venv venv
   source venv/bin/activate    # On Windows: venv\Scripts\activate
   ```
2. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```
3. Start the FastAPI server:
   ```bash
   python server.py
   ```
   Server will start at `http://0.0.0.0:8000` with Swagger docs at `http://localhost:8000/docs`.

---

### 3. Training Custom CRNN Model (Optional)

To train a custom PyTorch CNN+BiLSTM model on synthetic GIET CAPTCHA images:

```bash
python train_crnn.py
```
* Generates synthetic training samples on the fly.
* Trains with Connectionist Temporal Classification (CTC) loss.
* Saves the best checkpoint to `crnn_best.pth`.

---

## 🛠️ Tech Stack

* **Android**: Java, Android SDK, WebView, AndroidX, Material Components
* **Machine Learning / OCR**: Google ML Kit Vision (Latin OCR), PyTorch, Torchvision, ddddocr
* **Backend**: FastAPI, Uvicorn, Pydantic
* **Build System**: Gradle 8.x

---

## 📄 License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

---

## 👤 Author

* **Amit Padhan** - [@amitpadhan525](https://github.com/amitpadhan525)
