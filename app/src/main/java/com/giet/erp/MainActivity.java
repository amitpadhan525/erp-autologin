package com.giet.erp;

import android.content.SharedPreferences;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.autofill.AutofillManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GIET_ERP";
    private static final String ERP_PRIMARY_URL = "https://gietbbsrerp.in/";
    private static final String ERP_FALLBACK_URL = "http://gietbbsrerp.in/";

    private WebView webView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefresh;
    private View splashLayout;
    private SharedPreferences prefs;

    private boolean isAutoFilling = false;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // System bar padding for edge-to-edge layout
        View mainRoot = findViewById(R.id.mainRoot);
        ViewCompat.setOnApplyWindowInsetsListener(mainRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        prefs = getSharedPreferences("GIET_ERP_PREFS", MODE_PRIVATE);

        // Initialize On-Device Neural CAPTCHA Solver
        CaptchaSolver.init(getApplicationContext());

        webView      = findViewById(R.id.webView);
        progressBar  = findViewById(R.id.progressBar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        splashLayout = findViewById(R.id.splashLayout);

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

        // Load the ERP website (Primary HTTPS with automatic HTTP fallback)
        webView.loadUrl(ERP_PRIMARY_URL);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSaveFormData(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        );

        // Enable Android Autofill / Google Password Manager inside WebView
        webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // Expose JavaScript bridge to save credentials & solve CAPTCHA
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                    String username = prefs.getString("username", "").trim();
                    String password = prefs.getString("password", "").trim();
                    boolean hasSaved = !username.isEmpty() && !password.isEmpty();
                    if (!(isLoginPage(view.getUrl()) && hasSaved)) {
                        swipeRefresh.setRefreshing(false);
                    }
                }

                // Early injection & instant credential pre-fill as soon as DOM renders
                if (newProgress >= 20 && isLoginPage(view.getUrl())) {
                    injectCredentialCaptureAndAutofill();
                    prefillSavedCredentialsOnly();
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                String username = prefs.getString("username", "").trim();
                String password = prefs.getString("password", "").trim();
                boolean hasSaved = !username.isEmpty() && !password.isEmpty();
                
                if (isLoginPage(url) && hasSaved) {
                    webView.setVisibility(View.INVISIBLE);
                    splashLayout.setVisibility(View.VISIBLE);
                    swipeRefresh.post(() -> swipeRefresh.setRefreshing(false));
                } else {
                    webView.setVisibility(View.VISIBLE);
                    splashLayout.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Ensure page loads even if intermediate SSL certs have issues on college network
                Log.w(TAG, "SSL Warning encountered: " + error.toString() + " -> Proceeding seamlessly.");
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    String failingUrl = request.getUrl().toString();
                    Log.w(TAG, "Failed loading URL: " + failingUrl);

                    // If HTTPS fails or times out, fallback to HTTP (and vice versa)
                    if (failingUrl.startsWith("https://")) {
                        String httpFallback = failingUrl.replaceFirst("^https://", "http://");
                        Log.i(TAG, "Switching to HTTP fallback: " + httpFallback);
                        view.post(() -> view.loadUrl(httpFallback));
                    } else if (failingUrl.startsWith("http://")) {
                        String httpsFallback = failingUrl.replaceFirst("^http://", "https://");
                        Log.i(TAG, "Switching to HTTPS fallback: " + httpsFallback);
                        view.post(() -> view.loadUrl(httpsFallback));
                    }
                }
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                if (isLoginPage(url)) {
                    injectCredentialCaptureAndAutofill();
                    prefillSavedCredentialsOnly();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page Finished: " + url);

                // Flush cookies to ensure session persistence
                CookieManager.getInstance().flush();

                boolean isLogin = isLoginPage(url);
                isAutoFilling = false;
                
                if (!isLogin) {
                    webView.setVisibility(View.VISIBLE);
                    splashLayout.setVisibility(View.GONE);
                    swipeRefresh.setRefreshing(false);
                }

                if (isLogin) {
                    // 1. Inject credential auto-capture and Google Password Manager hooks
                    injectCredentialCaptureAndAutofill();
                    prefillSavedCredentialsOnly();

                    // Prompt Android Autofill / Google Password Manager
                    triggerAndroidAutofill();

                    // 2. Trigger CAPTCHA detection & solving (auto-login if saved, prefill if 1st time)
                    webView.postDelayed(() -> triggerCaptchaDetection(), 250);
                } else {
                    // When leaving login page upon successful login, notify AutofillManager to save credentials
                    commitAndroidAutofill();
                }
            }
        });
    }

    /**
     * Immediately fills saved username and password into the DOM without waiting for CAPTCHA.
     */
    private void prefillSavedCredentialsOnly() {
        String username = prefs.getString("username", "").trim();
        String password = prefs.getString("password", "").trim();
        if (username.isEmpty() && password.isEmpty()) return;

        String jsPrefill =
            "(function() {" +
            "   var u = '" + username.replace("'", "\\'") + "';" +
            "   var p = '" + password.replace("'", "\\'") + "';" +
            "   var userField = document.getElementById('textUser') || document.querySelector(\"input[name='vchUserName' i]\") || document.querySelector(\"input[type='text']\");" +
            "   var passField = document.getElementById('textPassword') || document.querySelector(\"input[name='vchPassword' i]\") || document.querySelector(\"input[type='password']\");" +
            "   if (userField && u && !userField.value) {" +
            "       userField.value = u;" +
            "       ['input', 'change', 'blur'].forEach(function(e) { userField.dispatchEvent(new Event(e, { bubbles: true })); });" +
            "   }" +
            "   if (passField && p && !passField.value) {" +
            "       passField.value = p;" +
            "       ['input', 'change', 'blur'].forEach(function(e) { passField.dispatchEvent(new Event(e, { bubbles: true })); });" +
            "   }" +
            "})();";

        runOnUiThread(() -> webView.evaluateJavascript(jsPrefill, null));
    }

    /**
     * Triggers Android Autofill (Google Password Manager) on the WebView.
     */
    private void triggerAndroidAutofill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                AutofillManager afm = getSystemService(AutofillManager.class);
                if (afm != null && afm.isEnabled()) {
                    afm.requestAutofill(webView);
                }
            } catch (Exception e) {
                Log.d(TAG, "Autofill request: " + e.getMessage());
            }
        }
    }

    /**
     * Commits Android Autofill session after successful login so Google Password Manager prompts to save.
     */
    private void commitAndroidAutofill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                AutofillManager afm = getSystemService(AutofillManager.class);
                if (afm != null && afm.isEnabled()) {
                    afm.commit();
                }
            } catch (Exception e) {
                Log.d(TAG, "Autofill commit: " + e.getMessage());
            }
        }
    }

    /**
     * Checks if current URL is the ERP login page (supports both HTTP & HTTPS).
     */
    private boolean isLoginPage(String url) {
        if (url == null || url.trim().isEmpty()) return true;
        String clean = url.toLowerCase().trim();
        return clean.equals("https://gietbbsrerp.in/")
            || clean.equals("https://gietbbsrerp.in")
            || clean.equals("http://gietbbsrerp.in/")
            || clean.equals("http://gietbbsrerp.in")
            || clean.contains("gietbbsrerp.in/login")
            || clean.contains("/login")
            || clean.contains("returnurl")
            || (clean.contains("gietbbsrerp.in") && (clean.endsWith("/") || clean.endsWith(".in")));
    }

    /**
     * Injects JavaScript to automatically capture and save credentials on manual login,
     * unlocks Google Password Manager autofill suggestions, and enables standard HTML autocomplete.
     */
    private void injectCredentialCaptureAndAutofill() {
        String jsCapture =
            "(function() {" +
            "   function setupAutofillAttributes() {" +
            "       var form = document.querySelector('form');" +
            "       if (form) {" +
            "           form.removeAttribute('autocomplete');" +
            "           form.setAttribute('autocomplete', 'on');" +
            "       }" +
            "       var userField = document.getElementById('textUser') || " +
            "                       document.querySelector(\"input[name='vchUserName' i]\") || " +
            "                       document.querySelector(\"input[type='text']\");" +
            "       var passField = document.getElementById('textPassword') || " +
            "                       document.querySelector(\"input[name='vchPassword' i]\") || " +
            "                       document.querySelector(\"input[type='password']\");" +
            "       var loginBtn  = document.getElementById('LoginButton') || " +
            "                       document.querySelector(\"button[id*='Login' i]\") || " +
            "                       document.querySelector(\"input[type='submit']\") || " +
            "                       document.querySelector(\"button[type='submit']\");" +
            "" +
            "       if (userField) {" +
            "           userField.setAttribute('autocomplete', 'username');" +
            "           userField.setAttribute('autofill-information', 'username');" +
            "           if (!userField._afHooked) {" +
            "               userField._afHooked = true;" +
            "               ['focus', 'click', 'touchstart'].forEach(function(evt) {" +
            "                   userField.addEventListener(evt, function() {" +
            "                       if (window.AndroidBridge && window.AndroidBridge.requestAutofill) {" +
            "                           window.AndroidBridge.requestAutofill();" +
            "                       }" +
            "                   });" +
            "               });" +
            "               ['input', 'change', 'blur', 'keyup', 'paste'].forEach(function(evt) {" +
            "                   userField.addEventListener(evt, function() {" +
            "                       if (window.AndroidBridge && window.AndroidBridge.saveField) {" +
            "                           window.AndroidBridge.saveField('user', userField.value);" +
            "                       }" +
            "                   });" +
            "               });" +
            "           }" +
            "       }" +
            "       if (passField) {" +
            "           passField.setAttribute('autocomplete', 'current-password');" +
            "           passField.setAttribute('autofill-information', 'password');" +
            "           if (!passField._afHooked) {" +
            "               passField._afHooked = true;" +
            "               ['focus', 'click', 'touchstart'].forEach(function(evt) {" +
            "                   passField.addEventListener(evt, function() {" +
            "                       if (window.AndroidBridge && window.AndroidBridge.requestAutofill) {" +
            "                           window.AndroidBridge.requestAutofill();" +
            "                       }" +
            "                   });" +
            "               });" +
            "               ['input', 'change', 'blur', 'keyup', 'paste'].forEach(function(evt) {" +
            "                   passField.addEventListener(evt, function() {" +
            "                       if (window.AndroidBridge && window.AndroidBridge.saveField) {" +
            "                           window.AndroidBridge.saveField('pass', passField.value);" +
            "                       }" +
            "                   });" +
            "               });" +
            "           }" +
            "       }" +
            "" +
            "       function captureCredentials() {" +
            "           var uEl = userField || document.getElementById('textUser');" +
            "           var pEl = passField || document.getElementById('textPassword');" +
            "           if (uEl && pEl) {" +
            "               var u = uEl.value ? uEl.value.trim() : '';" +
            "               var p = pEl.value ? pEl.value.trim() : '';" +
            "               if (u.length > 0 && p.length > 0) {" +
            "                   if (window.AndroidBridge && window.AndroidBridge.saveCredentials) {" +
            "                       window.AndroidBridge.saveCredentials(u, p);" +
            "                   }" +
            "               }" +
            "           }" +
            "       }" +
            "" +
            "       if (loginBtn && !loginBtn._afHooked) {" +
            "           loginBtn._afHooked = true;" +
            "           ['click', 'touchstart', 'pointerdown', 'mousedown'].forEach(function(evt) {" +
            "               loginBtn.addEventListener(evt, captureCredentials, true);" +
            "           });" +
            "       }" +
            "       if (form && !form._afHooked) {" +
            "           form._afHooked = true;" +
            "           form.addEventListener('submit', captureCredentials, true);" +
            "       }" +
            "   }" +
            "" +
            "   setupAutofillAttributes();" +
            "   var intervalCount = 0;" +
            "   var intervalId = setInterval(function() {" +
            "       setupAutofillAttributes();" +
            "       intervalCount++;" +
            "       if (intervalCount > 15) clearInterval(intervalId);" +
            "   }, 200);" +
            "   document.addEventListener('keydown', function(e) {" +
            "       if (e.key === 'Enter' || e.keyCode === 13) {" +
            "           setupAutofillAttributes();" +
            "       }" +
            "   }, true);" +
            "   window.addEventListener('beforeunload', function() {" +
            "       setupAutofillAttributes();" +
            "   });" +
            "})();";

        webView.evaluateJavascript(jsCapture, null);
    }

    /**
     * Executes JavaScript to extract the CAPTCHA image element and send base64 data to AndroidBridge.
     */
    private void triggerCaptchaDetection() {
        if (isAutoFilling) return;

        String jsCode =
            "(function() {" +
            "   var img = document.getElementById('img-captcha');" +
            "   var userField = document.getElementById('textUser');" +
            "   if (!img || !userField) {" +
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
            "               setTimeout(extract, 200);" +
            "           }" +
            "       } catch (e) {" +
            "           if (window.AndroidBridge) {" +
            "               window.AndroidBridge.onStatusUpdate('Capture Error: ' + e.message);" +
            "           }" +
            "       }" +
            "   }" +
            "   if (img.complete && img.naturalWidth > 0) {" +
            "       setTimeout(extract, 200);" +
            "   } else {" +
            "       img.onload = function() { setTimeout(extract, 200); };" +
            "       setTimeout(extract, 400);" +
            "   }" +
            "})();";

        runOnUiThread(() -> webView.evaluateJavascript(jsCode, null));
    }

    /**
     * Auto-fills saved credentials + CAPTCHA and submits the login form.
     */
    private void fillFormAndSubmit(String solvedCaptcha) {
        String username = prefs.getString("username", "").trim();
        String password = prefs.getString("password", "").trim();
        final String cleanCaptcha = (solvedCaptcha != null) ? solvedCaptcha.toUpperCase().trim() : "";

        String jsFill =
            "(function() {" +
            "   var userField = document.getElementById('textUser') || document.querySelector(\"input[name='vchUserName' i]\") || document.querySelector(\"input[type='text']\");" +
            "   var passField = document.getElementById('textPassword') || document.querySelector(\"input[name='vchPassword' i]\") || document.querySelector(\"input[type='password']\");" +
            "   var capField  = document.getElementById('CaptchaCode') || document.querySelector(\"input[name*='captcha' i]\");" +
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
            "           var btn = document.getElementById('LoginButton') || document.querySelector(\"input[type='submit']\") || document.querySelector(\"button[type='submit']\");" +
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
            webView.evaluateJavascript(jsFill, null);
            isAutoFilling = false;
        });
    }

    /**
     * Pre-fills the solved CAPTCHA for 1st-time users so they only need to enter Roll No & Password.
     */
    private void fillCaptchaOnly(String solvedCaptcha) {
        final String cleanCaptcha = (solvedCaptcha != null) ? solvedCaptcha.toUpperCase().trim() : "";

        String jsFill =
            "(function() {" +
            "   var capField = document.getElementById('CaptchaCode') || document.querySelector(\"input[name*='captcha' i]\");" +
            "   if (capField) {" +
            "       capField.value = '" + cleanCaptcha.replace("'", "\\'") + "';" +
            "       ['input', 'change', 'blur', 'keyup'].forEach(function(evt) {" +
            "           capField.dispatchEvent(new Event(evt, { bubbles: true }));" +
            "       });" +
            "   }" +
            "})();";

        runOnUiThread(() -> {
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

        /**
         * Automatically called in real-time as user types into Roll No or Password field.
         */
        @JavascriptInterface
        public void saveField(String field, String value) {
            if (field != null && value != null) {
                if ("user".equalsIgnoreCase(field) && !value.trim().isEmpty()) {
                    prefs.edit().putString("username", value.trim()).apply();
                    Log.d(TAG, "Realtime saved username: " + value.trim());
                } else if ("pass".equalsIgnoreCase(field) && !value.trim().isEmpty()) {
                    prefs.edit().putString("password", value.trim()).apply();
                    Log.d(TAG, "Realtime saved password");
                }
            }
        }

        /**
         * Automatically called when user enters credentials on the web page and submits.
         */
        @JavascriptInterface
        public void saveCredentials(String username, String password) {
            if (username != null && password != null && !username.trim().isEmpty() && !password.trim().isEmpty()) {
                prefs.edit()
                     .putString("username", username.trim())
                     .putString("password", password.trim())
                     .apply();
                Log.d(TAG, "Successfully stored login credentials for: " + username.trim());
            }
        }

        @JavascriptInterface
        public void onCaptchaExtracted(String base64Data) {
            isAutoFilling = true;

            CaptchaSolver.solveBase64(base64Data, captchaText -> {
                Log.d(TAG, "OCR Solved Text: " + captchaText);
                String username = prefs.getString("username", "").trim();
                String password = prefs.getString("password", "").trim();
                boolean hasSavedCredentials = !username.isEmpty() && !password.isEmpty();

                if (captchaText != null && captchaText.trim().length() == 4) {
                    retryCount = 0;
                    if (hasSavedCredentials) {
                        fillFormAndSubmit(captchaText.trim());
                    } else {
                        fillCaptchaOnly(captchaText.trim());
                    }
                } else {
                    if (retryCount < MAX_RETRIES) {
                        retryCount++;
                        runOnUiThread(() -> {
                            isAutoFilling = false;
                            refreshCaptchaOnPage();
                            webView.postDelayed(() -> triggerCaptchaDetection(), 500);
                        });
                    } else {
                        runOnUiThread(() -> {
                            isAutoFilling = false;
                            webView.setVisibility(View.VISIBLE);
                            splashLayout.setVisibility(View.GONE);
                            swipeRefresh.setRefreshing(false);
                            if (captchaText != null && !captchaText.isEmpty()) {
                                if (hasSavedCredentials) {
                                    fillFormAndSubmit(captchaText.trim());
                                } else {
                                    fillCaptchaOnly(captchaText.trim());
                                }
                            }
                        });
                    }
                }
            });
        }

        @JavascriptInterface
        public void requestAutofill() {
            triggerAndroidAutofill();
        }

        @JavascriptInterface
        public void onStatusUpdate(String message) {
            Log.d(TAG, "Status Update: " + message);
        }
    }
}
