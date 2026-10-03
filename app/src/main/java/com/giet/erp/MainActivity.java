package com.giet.erp;

import android.content.SharedPreferences;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
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

    private enum AppState {
        LOGGING_IN,   // Background login in progress; splash screen locked in front
        LOGGED_IN,    // Authentication complete; student dashboard active
        MANUAL_LOGIN  // First-time manual credential entry
    }

    private AppState currentState = AppState.MANUAL_LOGIN;

    private WebView webView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefresh;
    private View splashLayout;
    private SharedPreferences prefs;

    private boolean isAutoFilling = false;
    private boolean isSubmitted = false;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 3;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable safetyTimeoutRunnable;
    private static final long SAFETY_TIMEOUT_MS = 6000; // 6s fast fallback

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Soft keyboard & status/nav bar inset padding
        View mainRoot = findViewById(R.id.mainRoot);
        ViewCompat.setOnApplyWindowInsetsListener(mainRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()
            );
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });

        prefs = getSharedPreferences("GIET_ERP_PREFS", MODE_PRIVATE);

        // Initialize On-Device Neural CAPTCHA Solver
        CaptchaSolver.init(getApplicationContext());

        webView      = findViewById(R.id.webView);
        progressBar  = findViewById(R.id.progressBar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        splashLayout = findViewById(R.id.splashLayout);

        // Decide state immediately upon launch
        if (hasSavedCredentials()) {
            currentState = AppState.LOGGING_IN;
            splashLayout.setVisibility(View.VISIBLE);
            splashLayout.setAlpha(1.0f);
            webView.setVisibility(View.INVISIBLE);
            swipeRefresh.setEnabled(false);

            // Safety timeout: if ERP is down/slow, safely reveal the page so user isn't stuck
            safetyTimeoutRunnable = () -> {
                if (currentState == AppState.LOGGING_IN) {
                    Log.w(TAG, "Safety timeout reached -> Revealing page.");
                    revealDashboardOrLogin(false);
                }
            };
            mainHandler.postDelayed(safetyTimeoutRunnable, SAFETY_TIMEOUT_MS);
        } else {
            currentState = AppState.MANUAL_LOGIN;
            splashLayout.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            swipeRefresh.setEnabled(true);
        }

        swipeRefresh.setOnRefreshListener(() -> {
            isAutoFilling = false;
            isSubmitted = false;
            retryCount = 0;
            if (isLoginPage(webView.getUrl()) && hasSavedCredentials()) {
                currentState = AppState.LOGGING_IN;
                splashLayout.setVisibility(View.VISIBLE);
                splashLayout.setAlpha(1.0f);
                webView.setVisibility(View.INVISIBLE);
                swipeRefresh.setEnabled(false);
            }
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

    private boolean hasSavedCredentials() {
        if (prefs == null) return false;
        String user = prefs.getString("username", "").trim();
        String pass = prefs.getString("password", "").trim();
        return !user.isEmpty() && !pass.isEmpty();
    }

    /**
     * Smoothly hides the splash overlay and brings the WebView into view.
     */
    private void revealDashboardOrLogin(boolean isSuccess) {
        if (safetyTimeoutRunnable != null) {
            mainHandler.removeCallbacks(safetyTimeoutRunnable);
            safetyTimeoutRunnable = null;
        }

        currentState = isSuccess ? AppState.LOGGED_IN : AppState.MANUAL_LOGIN;

        runOnUiThread(() -> {
            webView.setVisibility(View.VISIBLE);
            if (splashLayout.getVisibility() == View.VISIBLE) {
                splashLayout.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction(() -> {
                        splashLayout.setVisibility(View.GONE);
                        splashLayout.setAlpha(1.0f);
                        swipeRefresh.setEnabled(true);
                        swipeRefresh.setRefreshing(false);
                    })
                    .start();
            } else {
                swipeRefresh.setEnabled(true);
                swipeRefresh.setRefreshing(false);
            }
        });
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

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // Expose JavaScript bridge to save credentials & solve CAPTCHA
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (currentState == AppState.LOGGING_IN) {
                    progressBar.setVisibility(View.GONE);
                    if (newProgress >= 35) {
                        checkPageAuthStatus();
                    }
                } else {
                    if (newProgress < 100) {
                        progressBar.setVisibility(View.VISIBLE);
                        progressBar.setProgress(newProgress);
                    } else {
                        progressBar.setVisibility(View.GONE);
                        swipeRefresh.setRefreshing(false);
                    }
                }

                if (isLoginPage(view.getUrl())) {
                    if (newProgress >= 20) {
                        injectCredentialCaptureAndAutofill();
                        prefillSavedCredentialsOnly();
                    }
                    if (newProgress >= 35) {
                        triggerCaptchaDetection();
                    }
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                isAutoFilling = false;
                isSubmitted = false;
                if (currentState == AppState.LOGGING_IN) {
                    webView.setVisibility(View.INVISIBLE);
                    splashLayout.setVisibility(View.VISIBLE);
                    swipeRefresh.setEnabled(false);
                }
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                if (currentState == AppState.LOGGING_IN) {
                    checkPageAuthStatus();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                Log.w(TAG, "SSL Warning encountered -> Proceeding seamlessly.");
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    String failingUrl = request.getUrl().toString();
                    Log.w(TAG, "Failed loading URL: " + failingUrl);

                    // Automatic HTTPS <-> HTTP fallback
                    if (failingUrl.startsWith("https://")) {
                        String httpFallback = failingUrl.replaceFirst("^https://", "http://");
                        view.post(() -> view.loadUrl(httpFallback));
                    } else if (failingUrl.startsWith("http://")) {
                        String httpsFallback = failingUrl.replaceFirst("^http://", "https://");
                        view.post(() -> view.loadUrl(httpsFallback));
                    }
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page Finished: " + url + " | State: " + currentState);
                CookieManager.getInstance().flush();

                isAutoFilling = false;

                if (currentState == AppState.LOGGING_IN) {
                    checkPageAuthStatus();
                } else {
                    if (isLoginPage(url)) {
                        injectCredentialCaptureAndAutofill();
                        triggerCaptchaDetection();
                    }
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
     * Checks whether the current page is an authenticated student dashboard vs the login form.
     */
    private void checkPageAuthStatus() {
        if (currentState != AppState.LOGGING_IN) return;
        webView.evaluateJavascript(
            "(function() {" +
            "   var userField = document.getElementById('textUser') || document.querySelector(\"input[name='vchUserName' i]\");" +
            "   var passField = document.getElementById('textPassword') || document.querySelector(\"input[name='vchPassword' i]\");" +
            "   var hasLoginForm = !!(userField && passField);" +
            "   var bodyLength = (document.body && document.body.innerHTML) ? document.body.innerHTML.length : 0;" +
            "   if (!hasLoginForm && bodyLength > 100) {" +
            "       return 'DASHBOARD';" +
            "   } else if (hasLoginForm) {" +
            "       return 'LOGIN';" +
            "   }" +
            "   return 'LOADING';" +
            "})();",
            result -> {
                if (result != null && result.contains("DASHBOARD")) {
                    Log.i(TAG, "Student Dashboard confirmed -> Revealing immediately!");
                    revealDashboardOrLogin(true);
                } else if (result != null && result.contains("LOGIN")) {
                    if (!isSubmitted) {
                        injectCredentialCaptureAndAutofill();
                        prefillSavedCredentialsOnly();
                        triggerCaptchaDetection();
                    }
                }
            }
        );
    }

    /**
     * Checks if current URL is the ERP login page.
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
            || clean.contains("login.aspx")
            || clean.contains("returnurl")
            || clean.contains("logout");
    }

    /**
     * Injects JavaScript to automatically capture and save credentials on manual login.
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
            "       var capField  = document.getElementById('CaptchaCode') || " +
            "                       document.querySelector(\"input[name*='captcha' i]\");" +
            "       var loginBtn  = document.getElementById('LoginButton') || " +
            "                       document.querySelector(\"button[id*='Login' i]\") || " +
            "                       document.querySelector(\"input[type='submit']\") || " +
            "                       document.querySelector(\"button[type='submit']\");" +
            "" +
            "       if (!userField && !passField && document.body && document.body.innerHTML.length > 150) {" +
            "           if (window.AndroidBridge && window.AndroidBridge.onDashboardDetected) {" +
            "               window.AndroidBridge.onDashboardDetected();" +
            "           }" +
            "       }" +
            "" +
            "       function scrollElementToView(elem) {" +
            "           if (!elem) return;" +
            "           setTimeout(function() {" +
            "               try {" +
            "                   elem.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });" +
            "               } catch (e) {" +
            "                   try { elem.scrollIntoView(false); } catch (err) {}" +
            "               }" +
            "           }, 250);" +
            "       }" +
            "" +
            "       if (userField) {" +
            "           userField.setAttribute('autocomplete', 'username');" +
            "           if (!userField._afHooked) {" +
            "               userField._afHooked = true;" +
            "               ['focus', 'click', 'touchstart'].forEach(function(evt) {" +
            "                   userField.addEventListener(evt, function() {" +
            "                       scrollElementToView(userField);" +
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
            "           if (!passField._afHooked) {" +
            "               passField._afHooked = true;" +
            "               ['focus', 'click', 'touchstart'].forEach(function(evt) {" +
            "                   passField.addEventListener(evt, function() {" +
            "                       scrollElementToView(passField);" +
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
            "       if (capField) {" +
            "           if (!capField._afHooked) {" +
            "               capField._afHooked = true;" +
            "               ['focus', 'click', 'touchstart'].forEach(function(evt) {" +
            "                   capField.addEventListener(evt, function() {" +
            "                       scrollElementToView(capField);" +
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
        if (isAutoFilling || isSubmitted) return;

        String jsCode =
            "(function() {" +
            "   var img = document.getElementById('img-captcha');" +
            "   var userField = document.getElementById('textUser');" +
            "   if (!img || !userField) return;" +
            "   function extract() {" +
            "       try {" +
            "           if (!img.complete || img.naturalWidth === 0 || img.naturalHeight === 0) {" +
            "               setTimeout(extract, 50);" +
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
            "           }" +
            "       } catch (e) {" +
            "           if (window.AndroidBridge) {" +
            "               window.AndroidBridge.onStatusUpdate('Capture Error: ' + e.message);" +
            "           }" +
            "       }" +
            "   }" +
            "   if (img.complete && img.naturalWidth > 0) {" +
            "       extract();" +
            "   } else {" +
            "       img.onload = extract;" +
            "       setTimeout(extract, 100);" +
            "   }" +
            "})();";

        runOnUiThread(() -> webView.evaluateJavascript(jsCode, null));
    }

    /**
     * Auto-fills saved credentials + CAPTCHA and submits the login form.
     */
    private void fillFormAndSubmit(String solvedCaptcha) {
        if (isSubmitted) return;
        isSubmitted = true;
        isAutoFilling = true;

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
            "       }, 30);" +
            "   }" +
            "})();";

        runOnUiThread(() -> webView.evaluateJavascript(jsFill, null));
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
                boolean hasSaved = hasSavedCredentials();

                if (captchaText != null && captchaText.trim().length() == 4) {
                    retryCount = 0;
                    if (hasSaved) {
                        fillFormAndSubmit(captchaText.trim());
                    } else {
                        fillCaptchaOnly(captchaText.trim());
                    }
                } else {
                    if (retryCount < MAX_RETRIES) {
                        retryCount++;
                        runOnUiThread(() -> {
                            isAutoFilling = false;
                            isSubmitted = false;
                            refreshCaptchaOnPage();
                            webView.postDelayed(() -> triggerCaptchaDetection(), 500);
                        });
                    } else {
                        // Exceeded retries -> reveal the page to the user
                        revealDashboardOrLogin(false);
                        if (captchaText != null && !captchaText.isEmpty()) {
                            if (hasSaved) {
                                fillFormAndSubmit(captchaText.trim());
                            } else {
                                fillCaptchaOnly(captchaText.trim());
                            }
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void onDashboardDetected() {
            runOnUiThread(() -> {
                if (currentState == AppState.LOGGING_IN) {
                    Log.i(TAG, "Dashboard detected via bridge -> Revealing immediately!");
                    revealDashboardOrLogin(true);
                }
            });
        }

        @JavascriptInterface
        public void onStatusUpdate(String message) {
            Log.d(TAG, "Status Update: " + message);
        }
    }
}
