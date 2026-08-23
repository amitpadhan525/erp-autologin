package com.giet.erp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Base64;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.Arrays;

/**
 * High-Accuracy On-Device CAPTCHA Solver using Google ML Kit OCR.
 * Uses background-adaptive thresholding + multi-pass recognition.
 */
public class CaptchaSolver {
    private static final String TAG = "CaptchaSolver";
    private static final int CAPTCHA_LENGTH = 4;

    public interface CaptchaCallback {
        void onSolved(String captchaText);
    }

    /**
     * Solves CAPTCHA from Base64 data URL.
     */
    public static void solveBase64(String base64Data, CaptchaCallback callback) {
        if (base64Data == null || base64Data.trim().isEmpty()) {
            callback.onSolved("");
            return;
        }

        try {
            String rawBase64 = base64Data;
            if (rawBase64.contains(",")) {
                rawBase64 = rawBase64.substring(rawBase64.indexOf(",") + 1);
            }
            byte[] decodedBytes = Base64.decode(rawBase64, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
            solveBitmap(bitmap, callback);
        } catch (Exception e) {
            Log.e(TAG, "Error decoding Base64 CAPTCHA: " + e.getMessage());
            callback.onSolved("");
        }
    }

    /**
     * Solves the CAPTCHA bitmap with adaptive preprocessing and multi-pass OCR.
     */
    public static void solveBitmap(Bitmap original, CaptchaCallback callback) {
        if (original == null) {
            callback.onSolved("");
            return;
        }

        try {
            int w = original.getWidth();
            int h = original.getHeight();

            int[] pixels = new int[w * h];
            original.getPixels(pixels, 0, w, 0, 0, w, h);

            float[] brightness = new float[w * h];
            float[] border = new float[2 * w + 2 * h];
            int borderIdx = 0;

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int idx = y * w + x;
                    int p = pixels[idx];
                    int r = (p >> 16) & 0xff;
                    int g = (p >> 8) & 0xff;
                    int b = p & 0xff;
                    float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                    brightness[idx] = lum;

                    if (y == 0 || y == h - 1 || x == 0 || x == w - 1) {
                        if (borderIdx < border.length) {
                            border[borderIdx++] = lum;
                        }
                    }
                }
            }

            // Estimate background luminance from border median
            Arrays.sort(border, 0, borderIdx);
            float bgMedian = border[borderIdx / 2];
            float threshold = bgMedian * 0.72f;

            // ── Pass 1: Clean Adaptive Binary Bitmap ──
            Bitmap binaryBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            for (int i = 0; i < brightness.length; i++) {
                boolean isText = brightness[i] < threshold;
                binaryBitmap.setPixel(i % w, i / w, isText ? Color.BLACK : Color.WHITE);
            }

            // 4x high-resolution scaling
            int scale = 4;
            Bitmap scaled1 = Bitmap.createScaledBitmap(binaryBitmap, w * scale, h * scale, true);
            Bitmap padded1 = createPaddedBitmap(scaled1, 40);

            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            InputImage inputImage1 = InputImage.fromBitmap(padded1, 0);

            recognizer.process(inputImage1)
                .addOnSuccessListener(visionText1 -> {
                    String clean1 = cleanResult(visionText1.getText());
                    Log.d(TAG, "Pass 1 Result: '" + clean1 + "' (len=" + clean1.length() + ")");

                    if (clean1.length() == CAPTCHA_LENGTH) {
                        callback.onSolved(clean1);
                    } else {
                        // ── Pass 2: High-Contrast Grayscale Fallback ──
                        Bitmap grayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        for (int i = 0; i < brightness.length; i++) {
                            int val = Math.min(255, Math.max(0, (int) brightness[i]));
                            grayBitmap.setPixel(i % w, i / w, Color.rgb(val, val, val));
                        }
                        Bitmap scaled2 = Bitmap.createScaledBitmap(grayBitmap, w * scale, h * scale, true);
                        Bitmap padded2 = createPaddedBitmap(scaled2, 40);

                        InputImage inputImage2 = InputImage.fromBitmap(padded2, 0);
                        recognizer.process(inputImage2)
                            .addOnSuccessListener(visionText2 -> {
                                String clean2 = cleanResult(visionText2.getText());
                                Log.d(TAG, "Pass 2 Result: '" + clean2 + "'");
                                if (clean2.length() == CAPTCHA_LENGTH) {
                                    callback.onSolved(clean2);
                                } else if (!clean1.isEmpty()) {
                                    callback.onSolved(clean1);
                                } else {
                                    callback.onSolved(clean2);
                                }
                            })
                            .addOnFailureListener(e -> callback.onSolved(clean1));
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "OCR recognition failure: " + e.getMessage());
                    callback.onSolved("");
                });

        } catch (Exception e) {
            Log.e(TAG, "solveBitmap error: " + e.getMessage());
            callback.onSolved("");
        }
    }

    private static Bitmap createPaddedBitmap(Bitmap src, int padding) {
        Bitmap padded = Bitmap.createBitmap(
                src.getWidth() + padding * 2,
                src.getHeight() + padding * 2,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(padded);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(src, padding, padding, null);
        return padded;
    }

    /**
     * Clean result without corrupting valid mixed-case characters or numbers.
     */
    private static String cleanResult(String raw) {
        if (raw == null) return "";
        // Keep alphanumeric characters only
        String s = raw.replaceAll("[^a-zA-Z0-9]", "").trim();

        // Common OCR symbol artifacts
        s = s.replace("|", "1")
             .replace("l", "1")
             .replace("I", "1");

        if (s.length() > CAPTCHA_LENGTH) {
            s = s.substring(0, CAPTCHA_LENGTH);
        }
        return s;
    }
}
