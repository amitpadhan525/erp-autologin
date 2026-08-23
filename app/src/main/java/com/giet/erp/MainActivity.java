package com.giet.erp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GIET_ERP";
    private static final String ERP_URL = "http://gietbbsrerp.in/";

    private WebView webView;
    private View topBarCard;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefresh;
    private TextView txtStatus;
    private Button btnAutoFill;
    private SharedPreferences prefs;

    private boolean isAutoFilling = false;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 3;

    private ActivityResultLauncher<Intent> settingsLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Fix status bar padding across Android versions
        View mainRoot = findViewById(R.id.mainRoot);
        ViewCompat.setOnApplyWindowInsetsListener(mainRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        prefs = getSharedPreferences("GIET_ERP_PREFS", MODE_PRIVATE);

        // Initialize On-Device Neural CAPTCHA Solver
        CaptchaSolver.init(getApplicationContext());

        topBarCard   = findViewById(R.id.topBarCard);
        webView      = findViewById(R.id.webView);
        progressBar  = findViewById(R.id.progressBar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        txtStatus    = findViewById(R.id.txtStatus);
        btnAutoFill  = findViewById(R.id.btnAutoFill);

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            settingsLauncher.launch(intent);
        });

        btnAutoFill.setOnClickListener(v -> {
            isAutoFilling = false;
            retryCount = 0;
            triggerCaptchaDetection();
        });

        settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    setTopBarVisibility(true);
                    setStatus("Credentials saved! Loading ERP...", "#38BDF8");
                    isAutoFilling = false;
                    retryCount = 0;
                    webView.reload();
                }
            }
        );

        swipeRefresh.setOnRefreshListener(() -> {
            isAutoFilling = false;
            retryCount = 0;
            webView.reload();
        });

        // Back button navigation in WebView history
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        setupWebView();

        // Load the ERP website directly on launch
        webView.loadUrl(ERP_URL);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        );

        // Enable Android Password Autofill in WebView
        webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // Expose JavaScript bridge
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                    swipeRefresh.setRefreshing(false);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page Finished: " + url);

                // Flush cookies to ensure session persistence
                CookieManager.getInstance().flush();

                // Enable HTML autocomplete attributes for password autofill
                enableWebAutofill();

                boolean isLogin = isLoginPage(url);
                setTopBarVisibility(isLogin);

                // Reset state for new page load
                isAutoFilling = false;

                if (!isLogin) {
                    // Successfully logged in / inside student portal -> Pure Fullscreen Website
                    Log.d(TAG, "Logged in: Website in 100% full screen mode");
                    return;
                }

                String username = prefs.getString("username", "").trim();
                String password = prefs.getString("password", "").trim();

                if (username.isEmpty() || password.isEmpty()) {
                    setStatus("Tap ⚙️ to set auto-login credentials or type above", "#F59E0B");
                    return;
                }

                // On login page with credentials -> Trigger CAPTCHA detection and auto-fill with 250ms debounce
                webView.postDelayed(() -> triggerCaptchaDetection(), 250);
            }
        });
    }

    /**
     * Checks if current URL is the ERP login page.
     */
    private boolean isLoginPage(String url) {
        if (url == null) return true;
        return url.equals("http://gietbbsrerp.in/")
            || url.equals("http://gietbbsrerp.in")
            || url.endsWith("/Login")
            || url.contains("/login")
            || url.contains("returnUrl");
    }

    /**
     * Toggles the app header bar. When logged in, hides the header so the website takes full screen.
     */
    private void setTopBarVisibility(boolean show) {
        runOnUiThread(() -> {
            if (topBarCard != null) {
                topBarCard.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
    }

    /**
     * Removes autocomplete="off" from the web form to allow keyboard password autofill.
     */
    private void enableWebAutofill() {
        String jsEnableAutofill =
            "(function() {" +
            "   var form = document.querySelector('form');" +
            "   if (form) { form.removeAttribute('autocomplete'); }" +
            "   var userField = document.getElementById('textUser');" +
            "   if (userField) { userField.setAttribute('autocomplete', 'username'); }" +
            "   var passField = document.getElementById('textPassword');" +
            "   if (passField) { passField.setAttribute('autocomplete', 'current-password'); }" +
            "})();";
        webView.evaluateJavascript(jsEnableAutofill, null);
    }

    /**
     * Executes resilient JavaScript to extract the CAPTCHA image element.
     */
    private void triggerCaptchaDetection() {
        if (isAutoFilling) return;

        String jsCode =
            "(function() {" +
            "   var img = document.getElementById('img-captcha');" +
            "   var userField = document.getElementById('textUser');" +
            "   if (!img || !userField) {" +
            "       window.AndroidBridge.onStatusUpdate('Portal Loaded (Not on Login Form)');" +
            "       return;" +
            "   }" +
            "   function extract() {" +
            "       try {" +
            "           if (!img.complete || img.naturalWidth === 0 || img.naturalHeight === 0) {" +
            "               setTimeout(extract, 150);" +
            "               return;" +
            "           }" +
            "           var canvas = document.createElement('canvas');" +
            "           canvas.width = img.naturalWidth || 100;" +
            "           canvas.height = img.naturalHeight || 40;" +
            "           var ctx = canvas.getContext('2d');" +
            "           ctx.fillStyle = '#FFFFFF';" +
            "           ctx.fillRect(0, 0, canvas.width, canvas.height);" +
            "           ctx.drawImage(img, 0, 0);" +
            "           var dataUrl = canvas.toDataURL('image/png');" +
            "           if (dataUrl && dataUrl.length > 50) {" +
            "               window.AndroidBridge.onCaptchaExtracted(dataUrl);" +
            "           } else {" +
            "               window.AndroidBridge.onStatusUpdate('Retrying CAPTCHA capture...');" +
            "               setTimeout(extract, 200);" +
            "           }" +
            "       } catch (e) {" +
            "           window.AndroidBridge.onStatusUpdate('Capture Error: ' + e.message);" +
            "       }" +
            "   }" +
            "   if (img.complete && img.naturalWidth > 0) {" +
            "       setTimeout(extract, 200);" +
            "   } else {" +
            "       img.onload = function() { setTimeout(extract, 200); };" +
            "       setTimeout(extract, 400);" +
            "   }" +
            "})();";

        runOnUiThread(() -> {
            setStatus("Detecting CAPTCHA on page...", "#38BDF8");
            webView.evaluateJavascript(jsCode, null);
        });
    }

    /**
     * Injects the credentials and recognized CAPTCHA text directly into the page fields.
     */
    private void fillFormAndSubmit(String solvedCaptcha) {
        String username = prefs.getString("username", "").trim();
        String password = prefs.getString("password", "").trim();
        final String cleanCaptcha = (solvedCaptcha != null) ? solvedCaptcha.toUpperCase().trim() : "";

        String jsFill =
            "(function() {" +
            "   var userField = document.getElementById('textUser');" +
            "   var passField = document.getElementById('textPassword');" +
            "   var capField  = document.getElementById('CaptchaCode');" +
            "   if (userField && passField && capField) {" +
            "       userField.value = '" + username.replace("'", "\\'") + "';" +
            "       passField.value = '" + password.replace("'", "\\'") + "';" +
            "       capField.value  = '" + cleanCaptcha.replace("'", "\\'") + "';" +
            "       ['input', 'change', 'blur', 'keyup'].forEach(function(evt) {" +
            "           userField.dispatchEvent(new Event(evt, { bubbles: true }));" +
            "           passField.dispatchEvent(new Event(evt, { bubbles: true }));" +
            "           capField.dispatchEvent(new Event(evt, { bubbles: true }));" +
            "       });" +
            "       setTimeout(function() {" +
            "           var btn = document.getElementById('LoginButton');" +
            "           if (btn) {" +
            "               btn.removeAttribute('disabled');" +
            "               btn.click();" +
            "           } else {" +
            "               var form = userField.closest('form') || document.forms[0];" +
            "               if (form) { form.submit(); }" +
            "           }" +
            "       }, 120);" +
            "   }" +
            "})();";

        runOnUiThread(() -> {
            setStatus("✅ Solved: '" + cleanCaptcha + "' — Auto-filling & Logging in...", "#22C55E");
            webView.evaluateJavascript(jsFill, null);
            isAutoFilling = false;
        });
    }


    /**
     * Refreshes the CAPTCHA image element on the page if recognition needs a retry.
     */
    private void refreshCaptchaOnPage() {
        String jsRefresh =
            "(function() {" +
            "   var img = document.getElementById('img-captcha');" +
            "   if (img) {" +
            "       img.src = '/get-captcha-image?r=' + Math.random();" +
            "   }" +
            "})();";
        webView.evaluateJavascript(jsRefresh, null);
    }

    // ─── JavaScript Interface ────────────────────────────────────────────────

    private class WebAppInterface {

        @JavascriptInterface
        public void onCaptchaExtracted(String base64Data) {
            isAutoFilling = true;
            runOnUiThread(() -> setStatus("Solving CAPTCHA with Neural Engine...", "#38BDF8"));

            CaptchaSolver.solveBase64(base64Data, captchaText -> {
                Log.d(TAG, "OCR Solved Text: " + captchaText);
                if (captchaText != null && captchaText.trim().length() == 4) {
                    retryCount = 0;
                    fillFormAndSubmit(captchaText.trim());
                } else {
                    if (retryCount < MAX_RETRIES) {
                        retryCount++;
                        runOnUiThread(() -> {
                            setStatus("Retrying clean CAPTCHA (" + retryCount + "/" + MAX_RETRIES + ")...", "#F59E0B");
                            isAutoFilling = false;
                            refreshCaptchaOnPage();
                            webView.postDelayed(() -> triggerCaptchaDetection(), 500);
                        });
                    } else {
                        runOnUiThread(() -> {
                            isAutoFilling = false;
                            if (captchaText != null && !captchaText.isEmpty()) {
                                fillFormAndSubmit(captchaText.trim());
                            } else {
                                setStatus("⚠️ Tap '⚡ Auto Fill' to retry.", "#F59E0B");
                            }
                        });
                    }
                }
            });
        }

        @JavascriptInterface
        public void onStatusUpdate(String message) {
            runOnUiThread(() -> setStatus(message, "#94A3B8"));
        }
    }

    private void setStatus(String message, String hexColor) {
        runOnUiThread(() -> {
            txtStatus.setText(message);
            try {
                if (hexColor != null && !hexColor.isEmpty()) {
                    txtStatus.setTextColor(Color.parseColor(hexColor));
                } else {
                    txtStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.status_pill_text));
                }
            } catch (Exception ignored) {
                txtStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.status_pill_text));
            }
        });
    }
}
