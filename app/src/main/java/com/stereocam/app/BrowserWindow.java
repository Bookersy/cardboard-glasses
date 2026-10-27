package com.stereocam.app;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.os.Message;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

/**
 * One floating browser window: a title bar, the page, and a keyboard that slides out under it.
 * Its views draw into an OpenGL texture (see PanelHost) instead of onto the phone's screen.
 *
 * Texture layout, top to bottom, in texture pixels: title bar, page, keyboard.
 */
public class BrowserWindow {

    public static final int W = 1024, TITLE_H = 48, WEB_H = 640, KB_H = 300;
    public static final int BASE_H = TITLE_H + WEB_H;   // visible when the keyboard is hidden
    public static final int TEX_H = BASE_H + KB_H;

    public interface Listener {
        void onEditFocus(BrowserWindow w);

        void onTitleAction(BrowserWindow w, int action);

        /** A page asked for a new window (a link with target=_blank, window.open). */
        boolean onOpenWindow(BrowserWindow from, Message resultMsg);

        void onLoadError(BrowserWindow w, String message);
    }

    private static int nextId = 1;

    final int id = nextId++;
    final PanelHost host;
    final WebView web;
    final TitleBar title;
    final VrKeyboard keyboard;
    private final View loadingBar;
    private final float scale; // view pixels per texture pixel
    private final Listener listener;

    // UI thread
    boolean keyboardWanted;
    float kbAnim;                  // 0 hidden .. 1 shown
    volatile float visibleHeight = BASE_H; // texture pixels currently shown (grows with the keyboard)

    // Render thread only
    int texId;
    SurfaceTexture surfaceTexture;
    final float[] texMatrix = new float[16];
    boolean placed, following;
    BrowserWindow placeNextTo;     // where a new window should appear (beside this one)
    final float[] anchor = new float[3];  // centre of the title bar + page, in the world
    final float[] dir = new float[3];     // from your head toward the window
    final float[] pc = new float[3], pr = new float[3], pu = new float[3]; // as drawn this frame
    float halfW, halfH;
    int arAnchor = -1;
    boolean arPending;
    final float[] arLocalDir = new float[3];

    public BrowserWindow(Activity a, float scale, Listener listener, String url) {
        this.scale = scale;
        this.listener = listener;
        android.opengl.Matrix.setIdentityM(texMatrix, 0);

        host = new PanelHost(a);
        host.setDrawScale(1f / scale);

        title = new TitleBar(a);
        title.setListener(new TitleBar.Listener() {
            @Override
            public void onTitleAction(int action) {
                BrowserWindow.this.listener.onTitleAction(BrowserWindow.this, action);
            }
        });
        host.addView(title, new FrameLayout.LayoutParams(px(W), px(TITLE_H)));

        web = new WebView(a);
        setupWebView();
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(px(W), px(WEB_H));
        wl.topMargin = px(TITLE_H);
        host.addView(web, wl);

        keyboard = new VrKeyboard(a);
        keyboard.setListener(new VrKeyboard.Listener() {
            @Override
            public void onKey(int type, String text) {
                handleKey(type, text);
            }
        });
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(px(W), px(KB_H));
        kl.topMargin = px(BASE_H);
        host.addView(keyboard, kl);

        // Thin bar across the top of the page while it loads.
        loadingBar = new View(a);
        loadingBar.setBackgroundColor(0xFF4C7DF0);
        loadingBar.setPivotX(0f);
        loadingBar.setScaleX(0f);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(px(W), px(4));
        ll.topMargin = px(TITLE_H);
        host.addView(loadingBar, ll);

        if (url != null) web.loadUrl(url);
    }

    int px(float texturePx) {
        return Math.round(texturePx * scale);
    }

    /** Lays the host out at the size it draws (bigger than the texture; see PanelHost). */
    FrameLayout.LayoutParams hostLayout() {
        return new FrameLayout.LayoutParams(px(W), px(TEX_H));
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // Drawn off-screen into the panel, so draw the whole page, not only the "visible" part.
        s.setOffscreenPreRaster(true);
        // Links that open a new tab get their own floating window.
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // No saved-password or saved-form prompts from the browser itself.
        s.setSavePassword(false);
        s.setSaveFormData(false);
        NoAutofill.apply(web);

        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void editFocus() {
                web.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onEditFocus(BrowserWindow.this);
                    }
                });
            }
        }, "VRBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                injectFocusWatcher();
                updateTitle();
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                injectFocusWatcher();
            }

            @Override
            public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
                updateTitle();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    listener.onLoadError(BrowserWindow.this, "Page didn't load: " + err.getDescription());
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int progress) {
                loadingBar.setScaleX(progress / 100f);
                loadingBar.setVisibility(progress >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView v, String t) {
                updateTitle();
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean userGesture, Message resultMsg) {
                return listener.onOpenWindow(BrowserWindow.this, resultMsg);
            }

            @Override
            public void onCloseWindow(WebView v) {
                listener.onTitleAction(BrowserWindow.this, TitleBar.CLOSE);
            }
        });
    }

    private void updateTitle() {
        title.setAddress(web.getUrl());
        title.setNavState(web.canGoBack(), web.canGoForward());
    }

    /** Click on the address box: select the address and bring up the keyboard to type a new one. */
    void editAddress() {
        title.startEditing(web.getUrl());
        showKeyboard(true);
    }

    /** Goes to what was typed: a web address, or a Google search for anything else. */
    void navigate(String typed) {
        String t = typed.trim();
        if (t.isEmpty()) return;
        String url;
        if (t.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            url = t;
        } else if (!t.contains(" ") && t.matches("^[^\\s/]+\\.[^\\s]{2,}.*")) {
            url = "https://" + t;
        } else {
            try {
                url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                return;
            }
        }
        web.loadUrl(url);
        web.requestFocus();
    }

    /** Tells us when the page focuses a text field, so the keyboard can slide out. */
    private void injectFocusWatcher() {
        web.evaluateJavascript(
                "(function(){if(window.__vrkb)return;window.__vrkb=1;" +
                "function ed(e){if(!e)return false;if(e.isContentEditable)return true;" +
                "var t=e.tagName;if(t==='TEXTAREA')return true;" +
                "if(t==='INPUT'){var y=(e.getAttribute('type')||'text').toLowerCase();" +
                "return ['text','search','email','url','tel','password','number'].indexOf(y)>=0;}" +
                "return false;}" +
                "document.addEventListener('focusin',function(e){if(ed(e.target)&&window.VRBridge)VRBridge.editFocus();},true);" +
                "})();", null);
    }

    // ------------------------------------------------------------------ input

    /** A touch at (x, y) in texture pixels. Goes to whatever is there: title bar, page or keyboard. */
    void touch(int action, float x, float y, long downTime) {
        MotionEvent ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x * scale, y * scale, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        if (action == MotionEvent.ACTION_DOWN && y >= TITLE_H && y < BASE_H) web.requestFocus();
        host.dispatchTouchEvent(ev);
        ev.recycle();
    }

    /** Mouse-wheel style scroll of whatever is under (x, y) on the page. */
    void scroll(float x, float y, float v, float h) {
        long t = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x * scale;
        pc[0].y = (y - TITLE_H) * scale; // relative to the page
        pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, v);
        pc[0].setAxisValue(MotionEvent.AXIS_HSCROLL, h);
        MotionEvent ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, pp, pc,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        web.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    /** Highlights whatever the cursor is over; negative y clears it. */
    void hover(float x, float y) {
        title.setHover(y >= 0 && y < TITLE_H ? x * scale : -1);
        if (keyboardWanted && y >= BASE_H) keyboard.setHover(x * scale, (y - BASE_H) * scale);
        else keyboard.setHover(-1, -1);
    }

    /** After a tap on the page: a fallback for text fields the focus watcher can't see. */
    void checkTappedTextField() {
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                WebView.HitTestResult r = web.getHitTestResult();
                if (r != null && r.getType() == WebView.HitTestResult.EDIT_TEXT_TYPE) {
                    listener.onEditFocus(BrowserWindow.this);
                }
            }
        }, 200);
    }

    void showKeyboard(boolean show) {
        keyboardWanted = show;
        if (!show) keyboard.setHover(-1, -1);
    }

    /** Animates the keyboard; returns true while it's moving. */
    void animate(float dt) {
        float target = keyboardWanted ? 1f : 0f;
        kbAnim += (target - kbAnim) * (1f - (float) Math.exp(-dt * 12f));
        if (Math.abs(target - kbAnim) < 0.002f) kbAnim = target;
        visibleHeight = BASE_H + KB_H * kbAnim;
        // Redraw every frame: waiting for the page to say it changed missed updates.
        host.invalidate();
    }

    private void handleKey(int type, String text) {
        if (title.isEditing()) {
            // Typing into the address box, not the page.
            switch (type) {
                case VrKeyboard.CLOSE:
                    title.stopEditing();
                    showKeyboard(false);
                    return;
                case VrKeyboard.DEL:
                    title.backspace();
                    return;
                case VrKeyboard.ENTER:
                    title.stopEditing();
                    showKeyboard(false);
                    navigate(title.editedText());
                    return;
                default:
                    title.type(text);
                    return;
            }
        }
        web.requestFocus();
        switch (type) {
            case VrKeyboard.CLOSE:
                showKeyboard(false);
                return;
            case VrKeyboard.DEL:
                sendKey(KeyEvent.KEYCODE_DEL);
                return;
            case VrKeyboard.ENTER:
                sendKey(KeyEvent.KEYCODE_ENTER);
                return;
            case VrKeyboard.SPACE:
                typeText(" ");
                return;
            default:
                typeText(text);
        }
    }

    private void sendKey(int code) {
        long t = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private void typeText(String s) {
        KeyEvent[] evs = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(s.toCharArray());
        if (evs != null) {
            for (KeyEvent e : evs) web.dispatchKeyEvent(e);
        } else {
            // Characters with no key (e.g. some symbols): insert through the page instead.
            web.evaluateJavascript("document.execCommand('insertText',false," + JSONObject.quote(s) + ")", null);
        }
    }

    void goBack() {
        if (web.canGoBack()) web.goBack();
    }

    void goForward() {
        if (web.canGoForward()) web.goForward();
    }

    void setSurface(Surface s) {
        host.setSurface(s);
    }

    void destroy() {
        host.setSurface(null);
        host.removeAllViews();
        web.stopLoading();
        web.destroy();
    }
}
