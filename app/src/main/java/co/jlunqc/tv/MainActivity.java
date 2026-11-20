package co.jlunqc.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JLUNQC para Android TV y Android: abre el servidor multimedia en pantalla completa,
 * con aceleración por hardware, control remoto mapeado y reconexión automática.
 */
public class MainActivity extends Activity {

    private static final String APP_UA = "JLUNQC-App/1.4";

    // Tecla "atrás" -> Escape dentro de la página (cierra reproductor, menús, hojas).
    private static final String JS_BACK =
            "(function(){try{var t=document.activeElement||document.body||document;" +
            "var e=new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true});" +
            "Object.defineProperty(e,'keyCode',{get:function(){return 27}});" +
            "Object.defineProperty(e,'which',{get:function(){return 27}});" +
            "return t.dispatchEvent(e)?'0':'1';}catch(x){return '0';}})()";

    // Pantalla encendida solo mientras algo se reproduce.
    private static final String JS_AWAKE =
            "(function(){if(window.__jlApp)return;window.__jlApp=1;" +
            "function u(){try{var v=[].filter.call(document.querySelectorAll('video')," +
            "function(x){return !x.paused&&!x.ended&&x.readyState>1})[0];JLUNQCApp.keepAwake(!!v);" +
            "JLUNQCApp.video(!!v,v?v.videoWidth:0,v?v.videoHeight:0);}catch(e){}}" +
            "['play','playing','pause','ended','emptied','loadedmetadata'].forEach(function(n){document.addEventListener(n,u,true)});})()";

    // Miniatura flotante: solo el video ocupa la ventanita (lo demás se oculta)
    private static final String JS_PIP =
            "(function(on){var id='jl-pip-css';if(!document.getElementById(id)){var s=document.createElement('style');s.id=id;" +
            "s.textContent='html.jl-pip,html.jl-pip body{background:#000!important;overflow:hidden!important}" +
            "html.jl-pip *{visibility:hidden!important;transform:none!important;filter:none!important;-webkit-backdrop-filter:none!important;backdrop-filter:none!important;contain:none!important;transition:none!important;animation:none!important}" +
            "html.jl-pip video.jl-pipv{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;right:auto!important;bottom:auto!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;object-fit:contain!important;z-index:2147483647!important;background:#000!important;opacity:1!important}" +
            "html.jl-pip video.jl-pipv::cue{font-size:5vh}';document.head.appendChild(s);}" +
            "var v=[].filter.call(document.querySelectorAll('video'),function(x){return !x.paused})[0]||document.querySelector('video');" +
            "[].forEach.call(document.querySelectorAll('video.jl-pipv'),function(x){x.classList.remove('jl-pipv')});" +
            "document.documentElement.classList.toggle('jl-pip',!!on);if(on&&v)v.classList.add('jl-pipv');})";

    private WebView web;
    private FrameLayout fullscreenBox;
    private LinearLayout overlay;
    private TextView overlayTitle;
    private TextView overlayText;
    private ProgressBar overlaySpinner;
    private LinearLayout overlayButtons;
    private Button retryBtn;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private String baseUrl = "";
    private boolean pageReady = false;
    private boolean inError = false;
    private int failedProbes = 0;
    private long lastBack = 0;
    private AtomicBoolean scanCancel;
    private boolean isTv;
    private volatile boolean videoPlaying = false;
    private int videoW = 16, videoH = 9;
    private boolean wasInPip = false;
    private android.content.BroadcastReceiver pipReceiver;
    static final String ACT_PIP = "co.jlunqc.tv.PIP";

    // ------------------------------------------------------------------ ciclo de vida
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isTv = detectTv(this);
        baseUrl = Server.savedUrl(this);
        if (baseUrl.isEmpty()) {
            startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_FIRST, true));
            finish();
            return;
        }
        buildUi();
        configureWebView();
        MediaService.commander = new MediaService.Commander() {
            @Override
            public void command(String c) {
                if (web == null || c == null) return;
                web.evaluateJavascript("window.Music&&Music.nativeCmd&&Music.nativeCmd('" + c.replaceAll("[^a-z0-9:]", "") + "')", null);
            }
        };
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
            pageReady = true;
            hideOverlay();
        } else {
            load();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String now = Server.savedUrl(this);
        if (web != null && !now.isEmpty() && !now.equals(baseUrl)) {
            baseUrl = now;
            web.clearHistory();
            load();
        } else if (web != null && inError) {
            failedProbes = 0;
            load();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        MediaService.setAppVisible(true);
        if (Build.VERSION.SDK_INT < 24 || !isInPictureInPictureMode()) wasInPip = false;
        if (web != null) web.onResume();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        MediaService.setAppVisible(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        MediaService.commander = null;
        if (pipReceiver != null) { try { unregisterReceiver(pipReceiver); } catch (Exception ignored) { } pipReceiver = null; }
        MediaService.stop(getApplicationContext());
        if (scanCancel != null) scanCancel.set(true);
        if (web != null) {
            web.stopLoading();
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideSystemUi();
    }

    // ------------------------------------------------------------------ interfaz
    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        fullscreenBox = new FrameLayout(this);
        fullscreenBox.setBackgroundColor(Color.BLACK);
        fullscreenBox.setVisibility(View.GONE);
        root.addView(fullscreenBox, new FrameLayout.LayoutParams(-1, -1));

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setPadding(dp(32), dp(32), dp(32), dp(32));
        overlay.setClickable(true);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        overlay.addView(logo, new LinearLayout.LayoutParams(dp(isTv ? 96 : 84), dp(isTv ? 96 : 84)));

        overlayTitle = new TextView(this);
        overlayTitle.setText("JLUNQC");
        overlayTitle.setTextColor(Color.WHITE);
        overlayTitle.setTextSize(isTv ? 34 : 28);
        overlayTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        overlayTitle.setGravity(Gravity.CENTER);
        overlayTitle.setPadding(0, dp(18), 0, dp(4));
        overlay.addView(overlayTitle);

        overlayText = new TextView(this);
        overlayText.setTextColor(0xFFA1A1A6);
        overlayText.setTextSize(isTv ? 18 : 15);
        overlayText.setGravity(Gravity.CENTER);
        overlayText.setLineSpacing(0, 1.15f);
        overlayText.setMaxWidth(dp(560));
        overlay.addView(overlayText);

        overlaySpinner = new ProgressBar(this);
        overlaySpinner.setIndeterminate(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(36), dp(36));
        sp.topMargin = dp(26);
        overlay.addView(overlaySpinner, sp);

        overlayButtons = new LinearLayout(this);
        overlayButtons.setOrientation(LinearLayout.HORIZONTAL);
        overlayButtons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = dp(28);
        overlay.addView(overlayButtons, bp);

        retryBtn = Ui.button(this, "Reintentar", true);
        retryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                failedProbes = 0;
                load();
            }
        });
        Button change = Ui.button(this, "Cambiar servidor", false);
        change.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSetup();
            }
        });
        overlayButtons.addView(retryBtn, Ui.rowParams(this));
        overlayButtons.addView(change, Ui.rowParams(this));

        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        showSplash("Conectando con " + hostOf(baseUrl) + "…");
    }

    private void showSplash(String msg) {
        overlay.setAlpha(1f);
        overlay.setVisibility(View.VISIBLE);
        overlayTitle.setText("JLUNQC");
        overlayText.setText(msg);
        overlaySpinner.setVisibility(View.VISIBLE);
        overlayButtons.setVisibility(View.GONE);
    }

    private void showError(String title, String msg) {
        inError = true;
        overlay.setAlpha(1f);
        overlay.setVisibility(View.VISIBLE);
        overlayTitle.setText(title);
        overlayText.setText(msg);
        overlaySpinner.setVisibility(View.VISIBLE);
        overlayButtons.setVisibility(View.VISIBLE);
        retryBtn.requestFocus();
    }

    private void hideOverlay() {
        if (overlay.getVisibility() != View.VISIBLE) return;
        overlay.animate().alpha(0f).setDuration(280).withEndAction(new Runnable() {
            @Override
            public void run() {
                overlay.setVisibility(View.GONE);
                overlay.setAlpha(1f);
            }
        }).start();
        web.requestFocus();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------ WebView
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView() {
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        String ua = s.getUserAgentString();
        // "Android TV" activa el modo TV del servidor (letras grandes y navegación con flechas)
        s.setUserAgentString(ua + (isTv ? " Android TV " : " ") + APP_UA);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "JLUNQCApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
    }

    private void load() {
        inError = false;
        pageReady = false;
        showSplash("Conectando con " + hostOf(baseUrl) + "…");
        web.loadUrl(baseUrl + "/");
    }

    private boolean sameServer(Uri u) {
        Uri b = Uri.parse(baseUrl);
        return u.getHost() != null && u.getHost().equalsIgnoreCase(b.getHost()) && u.getPort() == b.getPort();
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return handleLink(req.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleLink(Uri.parse(url));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (inError) return;
            view.evaluateJavascript(JS_AWAKE, null);
            pageReady = true;
            failedProbes = 0;
            hideOverlay();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (Build.VERSION.SDK_INT >= 23 && req.isForMainFrame()) {
                mainFrameFailed(String.valueOf(err.getDescription()));
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
            // Un dominio de Cloudflare vencido responde 530/502/1033: tratarlo como "sin conexión"
            if (Build.VERSION.SDK_INT >= 23 && req.isForMainFrame() && resp.getStatusCode() >= 500) {
                mainFrameFailed("HTTP " + resp.getStatusCode());
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
            if (Build.VERSION.SDK_INT < 23) mainFrameFailed(desc);
        }
    }

    private boolean handleLink(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        if ((scheme.equals("http") || scheme.equals("https")) && sameServer(u)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            Toast.makeText(this, "No hay una app para abrir este enlace", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void mainFrameFailed(String why) {
        web.stopLoading();
        web.loadUrl("about:blank");
        boolean tunnel = baseUrl.startsWith("https://") || !baseUrl.matches("^https?://\\d.*");
        showError("Sin conexión con el servidor",
                "No responde " + hostOf(baseUrl) + ".\n" +
                (tunnel ? "Si el dominio de Cloudflare cambió, toca «Cambiar servidor» y pega el nuevo.\n"
                        : "¿Está prendido el PC del servidor y en la misma red?\n") +
                "Reintento automático cada 5 segundos…");
        ui.removeCallbacks(retryTask);
        ui.postDelayed(retryTask, 5000);
    }

    /** Reintenta: si la IP guardada no responde dos veces, busca el servidor en la red. */
    private final Runnable retryTask = new Runnable() {
        @Override
        public void run() {
            if (!inError || isFinishing()) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Server.Info info = Server.probeAny(baseUrl, 5000);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!inError || isFinishing()) return;
                            if (info != null) {
                                load();
                                return;
                            }
                            failedProbes++;
                            if (failedProbes == 2) searchNetwork();
                            ui.postDelayed(retryTask, 5000);
                        }
                    });
                }
            }).start();
        }
    };

    private void searchNetwork() {
        overlayText.setText("Buscando el servidor en la red (puede haber cambiado de IP)…");
        scanCancel = Server.scan(this, new Server.Listener() {
            @Override
            public void onFound(final Server.Info info) {
                if (scanCancel != null) scanCancel.set(true);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!inError || info.url.equals(baseUrl)) return;
                        Server.save(MainActivity.this, info.url);
                        baseUrl = info.url;
                        Toast.makeText(MainActivity.this, "Servidor encontrado en " + hostOf(info.url), Toast.LENGTH_LONG).show();
                        load();
                    }
                });
            }

            @Override
            public void onProgress(int done, int total) { }

            @Override
            public void onFinished(List<Server.Info> all) { }
        });
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int progress) {
            if (progress >= 80 && !inError && !pageReady && view.getUrl() != null && !view.getUrl().startsWith("about:")) {
                hideOverlay();
            }
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            fullscreenBox.addView(view, new FrameLayout.LayoutParams(-1, -1));
            fullscreenBox.setVisibility(View.VISIBLE);
            web.setVisibility(View.INVISIBLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            hideSystemUi();
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            fullscreenBox.removeView(customView);
            fullscreenBox.setVisibility(View.GONE);
            web.setVisibility(View.VISIBLE);
            customView = null;
            if (customCallback != null) customCallback.onCustomViewHidden();
            customCallback = null;
            web.requestFocus();
            hideSystemUi();
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            // Evita el ícono gris de "play" que pinta Android antes de cada video
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    /** Puente JavaScript -> app. */
    public final class Bridge {
        @JavascriptInterface
        public void keepAwake(final boolean on) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else if (customView == null) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        /** La página avisa qué canción suena: notificación con controles y pantalla bloqueada. */
        @JavascriptInterface
        public void media(String title, String artist, String album, String artUrl, boolean playing, double posMs, double durMs) {
            MediaService.update(getApplicationContext(), title, artist, album, artUrl, playing, (long) posMs, (long) durMs);
            if (playing) {
                askNotificationPermission();
                askIsland();
            }
        }

        /** Video sonando (para la miniatura flotante al salir de la app). */
        @JavascriptInterface
        public void video(final boolean playing, final int w, final int h) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    videoPlaying = playing;
                    if (w > 0 && h > 0) { videoW = w; videoH = h; }
                    updatePipParams();
                }
            });
        }

        @JavascriptInterface
        public void mediaStop() {
            MediaService.stop(getApplicationContext());
        }

        @JavascriptInterface
        public String app() {
            return APP_UA + (isTv ? " tv" : "");
        }
    }

    // ------------------------------------------------------------------ control remoto
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (web == null) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;

        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (up && !e.isCanceled()) handleBack();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                if (up && e.getRepeatCount() == 0) openSetup();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (down && e.getRepeatCount() == 0) media("toggle");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down && e.getRepeatCount() == 0) media("play");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (down && e.getRepeatCount() == 0) media("pause");
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) media("ff");
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) media("rw");
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (down && e.getRepeatCount() == 0) key("MediaTrackNext");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (down && e.getRepeatCount() == 0) key("MediaTrackPrevious");
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void handleBack() {
        if (customView != null) {
            new Chrome().onHideCustomView();
            return;
        }
        if (inError || !pageReady) {
            confirmExit();
            return;
        }
        web.evaluateJavascript(JS_BACK, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String handled) {
                if (handled != null && handled.contains("1")) return;   // la página lo usó
                if (web != null && web.canGoBack()) web.goBack();
                else confirmExit();
            }
        });
    }

    /** Atrás en la pantalla principal: salir o cambiar de servidor (útil en celulares, que no tienen tecla Menú). */
    private void confirmExit() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 700) {
            finish();
            return;
        }
        lastBack = now;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("JLUNQC · " + hostOf(baseUrl))
                .setItems(new CharSequence[]{"Cambiar servidor o dominio", "Recargar", "Salir de la app"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) openSetup();
                                else if (which == 1) load();
                                else finish();
                            }
                        })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void media(String action) {
        web.evaluateJavascript("(function(a){if(!document.querySelector('video')&&window.Music&&Music.nativeCmd){Music.nativeCmd({toggle:'toggle',play:'play',pause:'pause',ff:'fwd',rw:'back'}[a]);return;}var m=[].slice.call(document.querySelectorAll('video,audio'));" +
                "var v=m.filter(function(x){return !x.paused})[0]||m.filter(function(x){return x.currentSrc})[0];" +
                "if(!v)return;if(a=='toggle'){v.paused?v.play():v.pause();}else if(a=='play'){v.play();}" +
                "else if(a=='pause'){v.pause();}else if(a=='ff'){v.currentTime=Math.min(v.duration||1e9,v.currentTime+10);}" +
                "else if(a=='rw'){v.currentTime=Math.max(0,v.currentTime-10);}})('" + action + "')", null);
    }

    private void key(String name) {
        web.evaluateJavascript("(function(){var t=document.activeElement||document.body;" +
                "t.dispatchEvent(new KeyboardEvent('keydown',{key:'" + name + "',bubbles:true,cancelable:true}));})()", null);
    }

    private boolean askedNotif = false;

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || askedNotif) return;
        askedNotif = true;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
                }
            }
        });
    }

    // ------------------------------------------------------------------ miniatura flotante (PiP)
    private boolean pipSupported() {
        return Build.VERSION.SDK_INT >= 26 && !isTv
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    @android.annotation.TargetApi(26)
    private android.app.PictureInPictureParams pipParams() {
        float r = videoH > 0 ? (float) videoW / videoH : 16f / 9f;
        r = Math.max(1f / 2.39f, Math.min(2.39f, r));
        int rw = Math.round(r * 1000);
        java.util.ArrayList<android.app.RemoteAction> acts = new java.util.ArrayList<>();
        acts.add(pipAction(R.drawable.ic_rw, "Atrás 10 s", "rw", 11));
        acts.add(pipAction(videoPlaying ? R.drawable.ic_pause : R.drawable.ic_play, videoPlaying ? "Pausar" : "Reproducir", "toggle", 12));
        acts.add(pipAction(R.drawable.ic_ff, "Adelante 10 s", "ff", 13));
        android.app.PictureInPictureParams.Builder b = new android.app.PictureInPictureParams.Builder()
                .setAspectRatio(new android.util.Rational(rw, 1000))
                .setActions(acts);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(videoPlaying);
            b.setSeamlessResizeEnabled(true);
        }
        return b.build();
    }

    @android.annotation.TargetApi(26)
    private android.app.RemoteAction pipAction(int icon, String title, String cmd, int code) {
        Intent i = new Intent(ACT_PIP).setPackage(getPackageName()).putExtra("c", cmd);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, code, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        return new android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, icon), title, title, pi);
    }

    private void updatePipParams() {
        if (!pipSupported()) return;
        try { setPictureInPictureParams(pipParams()); } catch (Exception ignored) { }
    }

    private void enterPip() {
        if (!pipSupported() || !videoPlaying || web == null) return;
        web.evaluateJavascript(JS_PIP + "(true)", null);
        try { enterPictureInPictureMode(pipParams()); } catch (Exception ignored) { }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android 12+ entra solo (auto-enter); en versiones anteriores lo pedimos aquí
        if (Build.VERSION.SDK_INT < 31 && videoPlaying) enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration cfg) {
        super.onPictureInPictureModeChanged(inPip, cfg);
        if (web == null) return;
        if (inPip) {
            wasInPip = true;
            overlay.setVisibility(View.GONE);
            web.evaluateJavascript(JS_PIP + "(true)", null);
            registerPipReceiver();
        } else {
            web.evaluateJavascript(JS_PIP + "(false)", null);
            hideSystemUi();
        }
    }

    private void registerPipReceiver() {
        if (pipReceiver != null || Build.VERSION.SDK_INT < 26) return;
        pipReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String cmd = i.getStringExtra("c");
                if ("toggle".equals(cmd)) media("toggle");
                else if ("rw".equals(cmd)) media("rw");
                else if ("ff".equals(cmd)) media("ff");
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(ACT_PIP);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pipReceiver, f, 4 /* RECEIVER_NOT_EXPORTED */);
        else registerReceiver(pipReceiver, f);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Cerraron la miniatura flotante: pausar el video
        if (wasInPip && web != null) {
            web.evaluateJavascript("[].forEach.call(document.querySelectorAll('video'),function(v){v.pause()})", null);
            wasInPip = false;
        }
    }

    /** Una sola vez: ofrecer la isla flotante (necesita "Mostrar sobre otras apps"). */
    private void askIsland() {
        if (isTv || Island.canDraw(this) || Server.prefs(this).getBoolean("island_asked", false)) return;
        Server.prefs(this).edit().putBoolean("island_asked", true).apply();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Isla flotante de música")
                        .setMessage("Cuando salgas de la app, la canción queda en una isla flotante arriba de la pantalla: "
                                + "tócala para ver la carátula y los controles. Android te pedirá permitir \"Mostrar sobre otras apps\".")
                        .setPositiveButton("Activar", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                MediaService.setIslandEnabled(MainActivity.this, true);
                                try { startActivity(Island.permissionIntent(MainActivity.this)); } catch (Exception ignored) { }
                            }
                        })
                        .setNegativeButton("Ahora no", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                MediaService.setIslandEnabled(MainActivity.this, false);
                            }
                        })
                        .show();
            }
        }, 1500);
    }

    private void openSetup() {
        startActivity(new Intent(this, SetupActivity.class));
    }

    // ------------------------------------------------------------------ utilidades
    static String hostOf(String url) {
        return url == null ? "" : url.replaceFirst("^https?://", "");
    }

    @SuppressWarnings("deprecation")
    static boolean detectTv(Context c) {
        UiModeManager m = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        if (m != null && m.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        PackageManager pm = c.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || pm.hasSystemFeature("android.software.leanback")
                || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION);
    }
}
