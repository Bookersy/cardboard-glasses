package com.stereocam.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.input.InputManager;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.SizeF;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Cardboard-style passthrough with a floating web browser.
 *
 * The rear camera fills both eyes. A browser panel floats in front of you, anchored in the
 * world by the rotation sensor and gliding back in front when you look far away.
 *
 * Controller (Stadia or any Bluetooth gamepad):
 *   Left stick  - move the cursor        Right stick - scroll the page
 *   R2 or A     - click (hold to drag)   B or L1     - back        R1 - forward
 *   Y           - show / hide keyboard   Menu (Start) - bring the panel back in front
 *   D-pad       - nudge the cursor
 * Reset button (top left of the screen): put the browser back in front of you.
 * Gear button (top right of the screen): settings for head tracking, lens correction
 * and eye separation. Volume keys also change eye separation.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQ_CAMERA = 1;
    private static final String HOME = "https://www.google.com";

    // Panel texture layout, in pixels: the page on top, the keyboard underneath.
    static final int WEB_W = 1024, WEB_H = 640, KB_H = 300, TEX_H = WEB_H + KB_H;
    // Pages are laid out this many CSS pixels wide (like a small laptop), then scaled onto the panel.
    // Laying out at the phone's own density gave a ~370px-wide phone page with huge text.
    private static final float PAGE_CSS_WIDTH = 900f;
    private float layoutScale = 1f; // view pixels per panel-texture pixel

    private GLSurfaceView glView;
    private StereoRenderer renderer;
    private PanelHost host;
    private WebView web;
    private VrKeyboard keyboard;
    private View loadingBar;
    private SettingsOverlay settings;
    private View gear, resetButton;
    private SharedPreferences prefs;
    private int trackingMode = SettingsOverlay.MODE_FOLLOW;
    private SixDofTracker tracker;     // null when this build has no ARCore
    private boolean arActive;
    private String arUnsupported;

    private HandlerThread camThread;
    private Handler camHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private SurfaceTexture pendingTexture;
    private boolean resumed;
    private SensorManager sensors;
    private final float[] rotTmp = new float[9];
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Controller state (UI thread)
    private float lx, ly, rx, ry, hatX, hatY;
    private boolean triggerDown, buttonADown, touching;
    private long touchDownTime;
    private float cursorX = WEB_W / 2f, cursorY = WEB_H / 2f;
    private boolean keyboardWanted;
    private float kbAnim;          // 0 hidden .. 1 shown
    private long lastTickNanos;
    private boolean ticking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Never pop up the phone's own keyboard; the panel has its own.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        renderer = new StereoRenderer(dm.xdpi);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        renderer.distortion = prefs.getBoolean("lens", true);
        renderer.eyeShift = prefs.getFloat("eyeShift", 0.025f);
        try {
            tracker = (SixDofTracker) Class.forName("com.stereocam.app.ArTracker").newInstance();
        } catch (Throwable t) {
            tracker = null; // built without ARCore
        }
        if (tracker != null) {
            try {
                arUnsupported = tracker.unsupportedReason(this);
            } catch (Throwable t) {
                arUnsupported = "ARCore isn't working on this phone.";
            }
        } else {
            arUnsupported = "6DoF needs Google's ARCore, which isn't in this build.";
        }
        trackingMode = prefs.getInt("tracking", SettingsOverlay.MODE_FOLLOW);
        if (trackingMode == SettingsOverlay.MODE_6DOF && arUnsupported != null) trackingMode = SettingsOverlay.MODE_FOLLOW;
        renderer.follow = trackingMode == SettingsOverlay.MODE_FOLLOW;

        FrameLayout root = new FrameLayout(this);

        host = new PanelHost(this);
        web = new WebView(this);
        setupWebView();
        keyboard = new VrKeyboard(this);
        keyboard.setListener(new VrKeyboard.Listener() {
            @Override
            public void onKey(int type, String text) {
                handleKeyboardKey(type, text);
            }
        });
        layoutScale = Math.max(1f, getResources().getDisplayMetrics().density * PAGE_CSS_WIDTH / WEB_W);
        host.setDrawScale(1f / layoutScale);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(px(WEB_W), px(WEB_H));
        host.addView(web, wl);
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(px(WEB_W), px(KB_H));
        kl.topMargin = px(WEB_H);
        host.addView(keyboard, kl);
        // Thin bar across the top of the page while it loads.
        loadingBar = new View(this);
        loadingBar.setBackgroundColor(0xFF4C7DF0);
        loadingBar.setPivotX(0f);
        loadingBar.setScaleX(0f);
        host.addView(loadingBar, new FrameLayout.LayoutParams(px(WEB_W), px(5)));
        root.addView(host, new FrameLayout.LayoutParams(px(WEB_W), px(TEX_H)));

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        settings = new SettingsOverlay(this, new SettingsOverlay.Callbacks() {
            @Override
            public void onLensChanged(boolean on) {
                renderer.distortion = on;
                prefs.edit().putBoolean("lens", on).apply();
            }

            @Override
            public void onEyeShiftChanged(float shift) {
                renderer.eyeShift = shift;
                prefs.edit().putFloat("eyeShift", shift).apply();
            }

            @Override
            public void onTrackingChanged(int mode) {
                int old = trackingMode;
                trackingMode = mode;
                renderer.follow = mode == SettingsOverlay.MODE_FOLLOW;
                prefs.edit().putInt("tracking", mode).apply();
                if (mode == SettingsOverlay.MODE_6DOF && old != mode) startAr();
                else if (old == SettingsOverlay.MODE_6DOF && mode != old) stopAr();
            }

            @Override
            public void onClose() {
                settings.setVisibility(View.GONE);
                gear.setVisibility(View.VISIBLE);
                resetButton.setVisibility(View.VISIBLE);
            }
        });
        settings.setVisibility(View.GONE);
        root.addView(settings, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        gear = new SettingsOverlay.GearButton(this);
        int size = Math.round(44 * dm.density), margin = Math.round(16 * dm.density);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        gl.topMargin = margin;
        gl.rightMargin = margin;
        root.addView(gear, gl);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings.setValues(renderer.distortion, renderer.eyeShift, trackingMode, arUnsupported);
                settings.setVisibility(View.VISIBLE);
                gear.setVisibility(View.GONE);
                resetButton.setVisibility(View.GONE);
            }
        });

        resetButton = new SettingsOverlay.ResetButton(this);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START);
        rl.topMargin = margin;
        rl.leftMargin = margin;
        root.addView(resetButton, rl);
        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetScreenPosition();
            }
        });

        setContentView(root);

        // Taps on the picture itself (e.g. the Cardboard button) do nothing for now.
        glView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return true;
            }
        });

        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        im.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int id) {
                InputDevice d = InputDevice.getDevice(id);
                if (d != null && isGamepad(d)) toast(d.getName() + " connected");
            }

            @Override
            public void onInputDeviceRemoved(int id) {
            }

            @Override
            public void onInputDeviceChanged(int id) {
            }
        }, ui);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
        if (!anyGamepadConnected()) {
            toast("Pair your controller in Bluetooth settings to use the browser");
        }
    }

    /** Panel-texture pixels to view pixels. */
    private int px(float texturePx) {
        return Math.round(texturePx * layoutScale);
    }

    // ------------------------------------------------------------------ browser

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // The browser is laid out bigger than the phone's screen and drawn off-screen into the
        // panel, so make it draw the whole page rather than only the part it thinks is visible.
        s.setOffscreenPreRaster(true);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void editFocus() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showKeyboard(true);
                    }
                });
            }
        }, "VRBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                injectFocusWatcher();
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                injectFocusWatcher();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) toast("Page didn't load: " + err.getDescription());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int progress) {
                loadingBar.setScaleX(progress / 100f);
                loadingBar.setVisibility(progress >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        web.loadUrl(HOME);
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

    private void showKeyboard(boolean show) {
        keyboardWanted = show;
        if (!show) keyboard.setHover(-1, -1);
    }

    private void handleKeyboardKey(int type, String text) {
        web.requestFocus();
        switch (type) {
            case VrKeyboard.CLOSE:
                showKeyboard(false);
                cursorY = Math.min(cursorY, WEB_H - 1);
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

    // ------------------------------------------------------------------ controller

    private static boolean isGamepad(InputDevice d) {
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static boolean anyGamepadConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && !d.isVirtual() && isGamepad(d)) return true;
        }
        return false;
    }

    private static float axis(MotionEvent e, int a) {
        InputDevice d = e.getDevice();
        if (d == null || d.getMotionRange(a, e.getSource()) == null) return 0f;
        return e.getAxisValue(a);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            lx = axis(e, MotionEvent.AXIS_X);
            ly = axis(e, MotionEvent.AXIS_Y);
            rx = axis(e, MotionEvent.AXIS_Z);
            ry = axis(e, MotionEvent.AXIS_RZ);
            hatX = axis(e, MotionEvent.AXIS_HAT_X);
            hatY = axis(e, MotionEvent.AXIS_HAT_Y);
            float rt = Math.max(axis(e, MotionEvent.AXIS_RTRIGGER), axis(e, MotionEvent.AXIS_GAS));
            if (!triggerDown && rt > 0.5f) {
                triggerDown = true;
                updatePress();
            } else if (triggerDown && rt < 0.3f) {
                triggerDown = false;
                updatePress();
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && e.getRepeatCount() == 0;

        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (first) {
                float step = code == KeyEvent.KEYCODE_VOLUME_UP ? 0.005f : -0.005f;
                renderer.eyeShift = Math.max(0f, Math.min(0.08f, renderer.eyeShift + step));
                prefs.edit().putFloat("eyeShift", renderer.eyeShift).apply();
                toast(String.format("Eye separation %.1f%%", renderer.eyeShift * 200f));
            }
            return true;
        }

        boolean fromPad = (e.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || KeyEvent.isGamepadButton(code);
        if (!fromPad) return super.dispatchKeyEvent(e);

        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_R2:
                if (down != buttonADown && e.getRepeatCount() == 0) {
                    buttonADown = down;
                    updatePress();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BACK:
                if (first) {
                    if (keyboardWanted && code == KeyEvent.KEYCODE_BUTTON_B) showKeyboard(false);
                    else if (web.canGoBack()) web.goBack();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (first && web.canGoForward()) web.goForward();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (first) showKeyboard(!keyboardWanted);
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
                if (first) resetScreenPosition();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) cursorX -= 16;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) cursorX += 16;
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) cursorY -= 16;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) cursorY += 16;
                return true;
        }
        return true; // swallow other controller buttons so they don't reach the page oddly
    }

    private boolean wantAr() {
        return trackingMode == SettingsOverlay.MODE_6DOF && tracker != null && arUnsupported == null;
    }

    /** Hands the camera to ARCore for 6DoF tracking. */
    private void startAr() {
        if (!wantAr() || arActive || !resumed) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        closeCamera();
        // Give the camera a moment to be released before ARCore opens it.
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!resumed || !wantAr() || arActive) return;
                try {
                    if (tracker.resume(MainActivity.this)) {
                        arActive = true;
                        renderer.arError = false;
                        renderer.useAr = true;
                        renderer.recenter = true;
                    } else {
                        toast("Install Google Play Services for AR to use 6DoF");
                    }
                } catch (Exception e) {
                    toast("6DoF couldn't start: " + e.getMessage());
                    fallBackFromAr();
                }
            }
        }, 300);
    }

    /** Stops ARCore and goes back to the normal camera. */
    private void stopAr() {
        renderer.useAr = false;
        glView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // Runs between frames, so ARCore isn't mid-update when it pauses.
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (arActive) {
                            tracker.pause();
                            arActive = false;
                        }
                        renderer.recenter = true;
                        maybeOpenCamera();
                    }
                });
            }
        });
    }

    /** Called when 6DoF fails: switch to Smooth follow so the view keeps working. */
    void fallBackFromAr() {
        trackingMode = SettingsOverlay.MODE_FOLLOW;
        renderer.follow = true;
        prefs.edit().putInt("tracking", trackingMode).apply();
        stopAr();
    }

    /** Puts the browser straight in front of where you are and where you're looking. */
    private void resetScreenPosition() {
        renderer.recenter = true;
        toast("Screen moved in front of you");
    }

    /** Turns trigger / A presses into touch down and up on the panel. */
    private void updatePress() {
        boolean want = triggerDown || buttonADown;
        if (want && !touching) {
            touching = true;
            touchDownTime = SystemClock.uptimeMillis();
            sendTouch(MotionEvent.ACTION_DOWN);
        } else if (!want && touching) {
            touching = false;
            sendTouch(MotionEvent.ACTION_UP);
            if (cursorY < WEB_H) checkTappedTextField();
        }
        renderer.pressed = touching;
    }

    private void sendTouch(int action) {
        MotionEvent ev = MotionEvent.obtain(touchDownTime, SystemClock.uptimeMillis(), action,
                cursorX * layoutScale, cursorY * layoutScale, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        if (cursorY < WEB_H) web.requestFocus();
        host.dispatchTouchEvent(ev);
        ev.recycle();
    }

    /** Backup for pages where the focus watcher can't see the field (e.g. inside frames). */
    private void checkTappedTextField() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                WebView.HitTestResult r = web.getHitTestResult();
                if (r != null && r.getType() == WebView.HitTestResult.EDIT_TEXT_TYPE) showKeyboard(true);
            }
        }, 200);
    }

    private void sendScroll(float v, float h) {
        long t = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = cursorX * layoutScale;
        pc[0].y = cursorY * layoutScale;
        pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, v);
        pc[0].setAxisValue(MotionEvent.AXIS_HSCROLL, h);
        MotionEvent ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, pp, pc,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        web.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    private static float shape(float v) {
        float dz = 0.15f;
        float a = Math.abs(v);
        if (a < dz) return 0f;
        float n = (a - dz) / (1f - dz);
        return Math.signum(v) * n * n;
    }

    /** Runs every display frame: moves the cursor, scrolls, animates the keyboard. */
    private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameNanos) {
            if (!ticking) return;
            float dt = lastTickNanos == 0 ? 0f : Math.min(0.05f, (frameNanos - lastTickNanos) / 1e9f);
            lastTickNanos = frameNanos;

            float speed = 1100f; // pixels per second at full tilt
            float dx = shape(lx) * speed * dt + hatX * 300f * dt;
            float dy = shape(ly) * speed * dt + hatY * 300f * dt;

            float target = keyboardWanted ? 1f : 0f;
            kbAnim += (target - kbAnim) * (1f - (float) Math.exp(-dt * 12f));
            if (Math.abs(target - kbAnim) < 0.002f) kbAnim = target;
            float visH = WEB_H + KB_H * kbAnim;

            float ox = cursorX, oy = cursorY;
            cursorX = Math.max(0f, Math.min(WEB_W - 1f, cursorX + dx));
            cursorY = Math.max(0f, Math.min(visH - 1f, cursorY + dy));
            if (touching && (cursorX != ox || cursorY != oy)) sendTouch(MotionEvent.ACTION_MOVE);

            float sv = shape(-ry), sh = shape(rx);
            if ((sv != 0f || sh != 0f) && cursorY < WEB_H && !touching) sendScroll(sv * 0.6f, sh * 0.6f);

            if (keyboardWanted && cursorY >= WEB_H) keyboard.setHover(cursorX * layoutScale, (cursorY - WEB_H) * layoutScale);
            else keyboard.setHover(-1, -1);

            renderer.cursorU = cursorX / WEB_W;
            renderer.cursorV = cursorY / TEX_H;
            renderer.visibleFrac = visH / TEX_H;

            // Redraw the panel every frame. Waiting for the page to say it changed missed
            // updates, so clicks looked like they did nothing until something else redrew.
            host.invalidate();

            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (wantAr()) startAr();
                else maybeOpenCamera();
            } else {
                toast("Camera permission is needed to show your surroundings");
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        camThread = new HandlerThread("camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        glView.onResume();
        web.onResume();
        if (wantAr()) startAr();
        else maybeOpenCamera();

        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor rv = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rv == null) rv = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) {
            sensors.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME);
        } else {
            toast("No rotation sensor on this phone, so the panel stays still");
        }

        ticking = true;
        lastTickNanos = 0;
        Choreographer.getInstance().postFrameCallback(tick);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ticking = false;
        if (touching) {
            touching = false;
            sendTouch(MotionEvent.ACTION_CANCEL);
        }
        if (sensors != null) sensors.unregisterListener(this);
        closeCamera();
        web.onPause();
        glView.onPause();       // stops the render thread first...
        if (arActive) {         // ...so ARCore can pause safely
            renderer.useAr = false;
            tracker.pause();
            arActive = false;
        }
        host.setSurface(null);
        pendingTexture = null; // GL context is lost; new textures arrive on resume
        if (camThread != null) {
            camThread.quitSafely();
            camThread = null;
            camHandler = null;
        }
        super.onPause();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float[] v = e.values;
        // Some phones reject the optional 5th value; only the first 4 are needed.
        float[] q = v.length > 4 ? new float[]{v[0], v[1], v[2], v[3]} : v;
        SensorManager.getRotationMatrixFromVector(rotTmp, q);
        renderer.setHeadRotation(rotTmp);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void toastOnUi(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                toast(s);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ camera

    /** Called on the UI thread once the GL thread has made its textures. */
    void onTexturesReady(SurfaceTexture cam, SurfaceTexture webTex) {
        pendingTexture = cam;
        webTex.setDefaultBufferSize(WEB_W, TEX_H);
        host.setSurface(new Surface(webTex));
        if (wantAr()) startAr();
        else maybeOpenCamera();
    }

    private void maybeOpenCamera() {
        if (!resumed || pendingTexture == null || camera != null || camHandler == null) return;
        if (wantAr()) return; // ARCore owns the camera in 6DoF
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;

        CameraManager mgr = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String chosen = null;
            for (String id : mgr.getCameraIdList()) {
                Integer facing = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    chosen = id;
                    break;
                }
            }
            if (chosen == null) chosen = mgr.getCameraIdList()[0];

            CameraCharacteristics cc = mgr.getCameraCharacteristics(chosen);
            // Camera frames arrive already turned upright for the phone's natural (portrait)
            // orientation. Undo the screen's own rotation so the image is upright in landscape.
            renderer.displayRotation = getWindowManager().getDefaultDisplay().getRotation();

            // Field of view, so the panel lines up with the real world in the camera image.
            float[] focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (focals != null && focals.length > 0 && phys != null && focals[0] > 0) {
                renderer.tanHalfH = (Math.max(phys.getWidth(), phys.getHeight()) / 2f) / focals[0];
            }

            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = pickSize(map.getOutputSizes(SurfaceTexture.class));
            final SurfaceTexture st = pendingTexture;
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            renderer.camAspect = (float) size.getWidth() / size.getHeight();

            mgr.openCamera(chosen, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice dev) {
                    camera = dev;
                    startPreview(dev, st);
                }

                @Override
                public void onDisconnected(CameraDevice dev) {
                    dev.close();
                    camera = null;
                }

                @Override
                public void onError(CameraDevice dev, final int error) {
                    dev.close();
                    camera = null;
                    toastOnUi("Camera error " + error);
                }
            }, camHandler);
        } catch (CameraAccessException | SecurityException e) {
            toast("Couldn't open camera: " + e.getMessage());
        }
    }

    /** Largest 16:9-ish size no bigger than 1920x1080, so it stays smooth. */
    private static Size pickSize(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) continue;
            float a = (float) s.getWidth() / s.getHeight();
            if (Math.abs(a - 16f / 9f) > 0.05f) continue;
            if (best == null || s.getWidth() > best.getWidth()) best = s;
        }
        if (best != null) return best;
        for (Size s : sizes) {
            if (s.getWidth() <= 1920 && (best == null || s.getWidth() > best.getWidth())) best = s;
        }
        return best != null ? best : sizes[0];
    }

    private void startPreview(CameraDevice dev, SurfaceTexture st) {
        try {
            Surface surface = new Surface(st);
            final CaptureRequest.Builder rb = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(surface);
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            dev.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (camera == null) return;
                    session = s;
                    try {
                        s.setRepeatingRequest(rb.build(), null, camHandler);
                    } catch (final CameraAccessException e) {
                        toastOnUi("Preview failed: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    toastOnUi("Camera preview couldn't start");
                }
            }, camHandler);
        } catch (CameraAccessException e) {
            toast("Preview failed: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (session != null) {
            session.close();
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
    }

    // ------------------------------------------------------------------ rendering

    private class StereoRenderer implements GLSurfaceView.Renderer {
        // Distance between the centres of the two Cardboard lenses, in millimetres.
        private static final float LENS_SEPARATION_MM = 63.9f;

        volatile boolean distortion = true;
        volatile int displayRotation = 1; // Surface.ROTATION_90
        volatile float eyeShift = 0.025f;   // fraction of camera width each eye is offset
        volatile float camAspect = 16f / 9f;
        volatile float tanHalfH = 0.62f;   // ~64 degrees horizontal, replaced by the real lens value
        volatile float cursorU = 0.5f, cursorV = 0.3f, visibleFrac = (float) WEB_H / TEX_H;
        volatile boolean pressed, recenter;
        volatile boolean follow = true;    // smooth-follow mode; false = stays fixed (3DoF)
        volatile boolean useAr;            // 6DoF: ARCore supplies the camera and head pose
        volatile boolean arError;
        private int arTexId;
        private final float[] arRight = new float[3], arUp = new float[3], arFwd = new float[3],
                arPos = new float[3], arMatrix = new float[16], arCamInfo = {0.62f, 16f / 9f};
        private boolean arHavePose, arCameraLive, arLost;
        // Anchor: where the panel is pinned in ARCore's map, and its facing direction in anchor space.
        private boolean arAnchorPending;
        private final float[] anchorLocalDir = new float[3];
        // Last good ARCore pose, with the rotation sensor's reading at that moment, so head turns
        // can still be followed (with the gyroscope) while ARCore has lost tracking.
        private final float[] arLastBasis = new float[9], sensorLastBasis = new float[9];
        private boolean arBridgeReady;
        private long lastHintMs;

        // Panel: metres per texture pixel, and distance from your eyes.
        private static final float PANEL_DIST = 1.6f;
        private static final float M_PER_PX = 0.80f / WEB_W;   // panel is 80 cm wide
        private static final float FOLLOW_SPEED = 3f;          // higher = catches up faster
        private static final float FOLLOW_START = 0.35f;       // radians away before it follows (~20 deg)
        private static final float FOLLOW_STOP = 0.04f;        // settles when this close
        private static final float EYE_HALF_IPD = 0.032f;

        private final float[] rot = new float[9];
        private final float[] headPos = new float[3]; // metres; stays 0,0,0 in 3DoF
        private boolean haveRot;
        // The panel lives at a fixed spot in the room (anchor) and faces back along panelDir.
        private final float[] panelDir = new float[3];
        private final float[] anchor = new float[3];
        private boolean panelPlaced, following;
        private long lastNanos;

        private final float xdpi;
        private int program, texId, webTexId;
        private SurfaceTexture surfaceTexture, webTexture;
        private final float[] texMatrix = new float[16];
        private final float[] webMatrix = new float[16];
        private int width, height;
        private final FloatBuffer quad;

        private int aPos, uTexMatrix, uCenter, uAspect, uCrop, uShift, uDistort, uRot,
                uTanH, uTanV, uHR, uHU, uHF, uPC, uPR, uPU, uPN, uEye, uPanel, uHalf,
                uWebMatrix, uVis, uCursor, uPress, uHead, sTex, sWeb;

        StereoRenderer(float xdpi) {
            this.xdpi = xdpi > 0 ? xdpi : 400f;
            float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
            quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(v).position(0);
            android.opengl.Matrix.setIdentityM(webMatrix, 0);
        }

        synchronized void setHeadRotation(float[] r) {
            System.arraycopy(r, 0, rot, 0, 9);
            haveRot = true;
        }

        /**
         * Head position in metres, in the same world frame as the rotation (X east, Y north,
         * Z up). Only a 6DoF tracker calls this; in 3DoF the head stays at the origin.
         */
        synchronized void setHeadPosition(float x, float y, float z) {
            headPos[0] = x;
            headPos[1] = y;
            headPos[2] = z;
        }

        private static final String VS =
                "attribute vec2 aPos;\n" +
                "varying vec2 vUv;\n" +
                "void main(){ vUv = aPos*0.5+0.5; gl_Position = vec4(aPos,0.0,1.0); }";

        // vUv: 0..1 across this eye's half of the screen.
        // uCenter: where the lens centre sits inside that half.
        // The pre-distortion (barrel) cancels the pincushion the Cardboard lens adds.
        private static final String FS =
                "#extension GL_OES_EGL_image_external : require\n" +
                "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
                "precision highp float;\n" +
                "#else\n" +
                "precision mediump float;\n" +
                "#endif\n" +
                "varying vec2 vUv;\n" +
                "uniform samplerExternalOES sTex;\n" +
                "uniform samplerExternalOES sWeb;\n" +
                "uniform mat4 uTexMatrix;\n" +
                "uniform mat4 uWebMatrix;\n" +
                "uniform vec2 uCenter;\n" +
                "uniform float uAspect;\n" +
                "uniform float uCrop;\n" +
                "uniform float uShift;\n" +
                "uniform float uDistort;\n" +
                "uniform float uRot;\n" +
                "uniform float uTanH, uTanV, uEye, uPanel, uVis, uPress;\n" +
                "uniform vec3 uHR, uHU, uHF, uPC, uPR, uPU, uPN, uHead;\n" +
                "uniform vec2 uHalf, uCursor;\n" +
                "void main(){\n" +
                "  vec2 p = vUv - uCenter;\n" +
                "  p.x *= uAspect;\n" +
                "  if (uDistort > 0.5) {\n" +
                "    float r2 = dot(p,p);\n" +
                "    p *= (1.0 + 0.22*r2 + 0.24*r2*r2) * 0.82;\n" +
                "  }\n" +
                "  p.x /= uAspect;\n" +
                "  vec2 uv = p + 0.5;\n" +
                "  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { gl_FragColor = vec4(0.0,0.0,0.0,1.0); return; }\n" +
                "  vec2 c = vec2(0.5 + (uv.x-0.5)*uCrop + uShift, uv.y);\n" +
                "  vec3 col = vec3(0.0);\n" +
                "  if (c.x >= 0.0 && c.x <= 1.0) {\n" +
                "    vec2 tc = c;\n" +
                "    if (uRot > 0.5 && uRot < 1.5) tc = vec2(c.y, 1.0 - c.x);\n" +
                "    else if (uRot > 1.5 && uRot < 2.5) tc = 1.0 - c;\n" +
                "    else if (uRot > 2.5) tc = vec2(1.0 - c.y, c.x);\n" +
                "    col = texture2D(sTex, (uTexMatrix*vec4(tc,0.0,1.0)).xy).rgb;\n" +
                "  }\n" +
                // Floating panel: trace this pixel's view ray into the world and hit the panel plane.
                "  if (uPanel > 0.5) {\n" +
                "    vec2 tn = vec2((c.x-0.5)*2.0*uTanH, (c.y-0.5)*2.0*uTanV);\n" +
                "    vec3 dir = normalize(tn.x*uHR + tn.y*uHU + uHF);\n" +
                "    vec3 o = uHead + uEye*uHR;\n" +
                "    float den = dot(dir, uPN);\n" +
                "    if (den < -0.0001) {\n" +
                "      float t = dot(uPC - o, uPN) / den;\n" +
                "      if (t > 0.0) {\n" +
                "        vec3 h = o + dir*t - uPC;\n" +
                "        vec2 l = vec2(dot(h, uPR), dot(h, uPU));\n" +
                "        float rad = 0.02;\n" +
                "        vec2 q = abs(l) - uHalf + rad;\n" +
                "        float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;\n" +
                "        float aa = 0.003 * t;\n" +
                "        float fill = 1.0 - smoothstep(-aa, aa, d);\n" +
                "        if (fill > 0.0) {\n" +
                "          float tx = clamp(l.x / (2.0*uHalf.x) + 0.5, 0.0, 1.0);\n" +
                "          float ty = clamp((uHalf.y - l.y) / (2.0*uHalf.y), 0.0, 1.0) * uVis;\n" +
                "          vec3 page = texture2D(sWeb, (uWebMatrix*vec4(tx, 1.0-ty, 0.0, 1.0)).xy).rgb;\n" +
                "          vec2 dpx = (vec2(tx, ty) - uCursor) * vec2(" + WEB_W + ".0, " + TEX_H + ".0);\n" +
                "          float cd = length(dpx);\n" +
                "          page = mix(page, vec3(0.08, 0.1, 0.14), 1.0 - smoothstep(9.0, 11.0, cd));\n" +
                "          vec3 dotc = mix(vec3(1.0), vec3(0.3, 0.55, 1.0), uPress);\n" +
                "          page = mix(page, dotc, 1.0 - smoothstep(6.0, 8.0, cd));\n" +
                "          col = mix(col, page, fill);\n" +
                "        }\n" +
                "        float edge = 1.0 - smoothstep(0.0, aa * 1.5, abs(d + 0.002) - 0.0015);\n" +
                "        col = mix(col, vec3(0.78, 0.92, 1.0), 0.6 * edge);\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "  gl_FragColor = vec4(col, 1.0);\n" +
                "}";

        private int newExternalTexture() {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            return t[0];
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            program = link(VS, FS);
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
            uWebMatrix = GLES20.glGetUniformLocation(program, "uWebMatrix");
            uCenter = GLES20.glGetUniformLocation(program, "uCenter");
            uAspect = GLES20.glGetUniformLocation(program, "uAspect");
            uCrop = GLES20.glGetUniformLocation(program, "uCrop");
            uShift = GLES20.glGetUniformLocation(program, "uShift");
            uDistort = GLES20.glGetUniformLocation(program, "uDistort");
            uRot = GLES20.glGetUniformLocation(program, "uRot");
            uTanH = GLES20.glGetUniformLocation(program, "uTanH");
            uTanV = GLES20.glGetUniformLocation(program, "uTanV");
            uHR = GLES20.glGetUniformLocation(program, "uHR");
            uHU = GLES20.glGetUniformLocation(program, "uHU");
            uHF = GLES20.glGetUniformLocation(program, "uHF");
            uPC = GLES20.glGetUniformLocation(program, "uPC");
            uPR = GLES20.glGetUniformLocation(program, "uPR");
            uPU = GLES20.glGetUniformLocation(program, "uPU");
            uPN = GLES20.glGetUniformLocation(program, "uPN");
            uEye = GLES20.glGetUniformLocation(program, "uEye");
            uPanel = GLES20.glGetUniformLocation(program, "uPanel");
            uHalf = GLES20.glGetUniformLocation(program, "uHalf");
            uVis = GLES20.glGetUniformLocation(program, "uVis");
            uCursor = GLES20.glGetUniformLocation(program, "uCursor");
            uPress = GLES20.glGetUniformLocation(program, "uPress");
            uHead = GLES20.glGetUniformLocation(program, "uHead");
            sTex = GLES20.glGetUniformLocation(program, "sTex");
            sWeb = GLES20.glGetUniformLocation(program, "sWeb");

            texId = newExternalTexture();
            webTexId = newExternalTexture();
            arTexId = newExternalTexture();
            arHavePose = false;
            surfaceTexture = new SurfaceTexture(texId);
            webTexture = new SurfaceTexture(webTexId);
            final SurfaceTexture st = surfaceTexture, wt = webTexture;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    onTexturesReady(st, wt);
                }
            });
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            width = w;
            height = h;
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (surfaceTexture == null) return;
            arCameraLive = false;
            if (useAr && tracker != null && !arError) {
                int st = tracker.update(arTexId, displayRotation, arRight, arUp, arFwd, arPos, arMatrix, arCamInfo);
                if (st == SixDofTracker.FAILED) {
                    arError = true;
                    final String msg = tracker.lastError();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("6DoF stopped: " + msg);
                            fallBackFromAr();
                        }
                    });
                } else {
                    arCameraLive = st >= SixDofTracker.CAMERA_ONLY;
                    arLost = st == SixDofTracker.CAMERA_ONLY;
                    if (st == SixDofTracker.TRACKING) {
                        arHavePose = true;
                        rememberTrackedPose();
                    } else if (st == SixDofTracker.CAMERA_ONLY) {
                        showTrackingHint();
                    }
                }
            } else {
                try {
                    surfaceTexture.updateTexImage();
                    surfaceTexture.getTransformMatrix(texMatrix);
                } catch (RuntimeException e) {
                    // no camera frame yet
                }
            }
            if (arCameraLive) {
                tanHalfH = arCamInfo[0];
                camAspect = arCamInfo[1];
            }
            try {
                webTexture.updateTexImage();
                webTexture.getTransformMatrix(webMatrix);
            } catch (RuntimeException e) {
                // no browser frame yet
            }

            int half = width / 2;
            float eyeAspect = (float) half / height;
            // Show the camera's full height in each eye; crop the width to match the eye's shape.
            float crop = Math.min(1f, eyeAspect / camAspect);

            // Lens centres sit LENS_SEPARATION_MM apart around the middle of the screen.
            float lensPx = LENS_SEPARATION_MM / 25.4f * xdpi;
            float leftCx = (half - lensPx / 2f) / half;   // within the left half, 0..1
            float rightCx = (lensPx / 2f) / half;          // within the right half, 0..1
            leftCx = Math.max(0.2f, Math.min(0.8f, leftCx));
            rightCx = Math.max(0.2f, Math.min(0.8f, rightCx));

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, arCameraLive ? arTexId : texId);
            GLES20.glUniform1i(sTex, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, webTexId);
            GLES20.glUniform1i(sWeb, 1);
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, arCameraLive ? arMatrix : texMatrix, 0);
            GLES20.glUniformMatrix4fv(uWebMatrix, 1, false, webMatrix, 0);
            GLES20.glUniform1f(uAspect, eyeAspect);
            GLES20.glUniform1f(uCrop, crop);
            GLES20.glUniform1f(uDistort, distortion ? 1f : 0f);
            GLES20.glUniform1f(uRot, arCameraLive ? 0f : (float) displayRotation); // ARCore's matrix already rotates
            setPanelUniforms();
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);

            // Left eye: its "camera" sits to the left, so it looks at a window shifted left.
            GLES20.glViewport(0, 0, half, height);
            GLES20.glUniform2f(uCenter, leftCx, 0.5f);
            GLES20.glUniform1f(uShift, -eyeShift);
            GLES20.glUniform1f(uEye, -EYE_HALF_IPD);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // Right eye
            GLES20.glViewport(half, 0, width - half, height);
            GLES20.glUniform2f(uCenter, rightCx, 0.5f);
            GLES20.glUniform1f(uShift, eyeShift);
            GLES20.glUniform1f(uEye, EYE_HALF_IPD);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        /** Saves the ARCore pose and the rotation sensor's reading taken at the same moment. */
        private void rememberTrackedPose() {
            putBasis(arLastBasis, arRight, arUp, arFwd);
            float[] r = new float[9];
            boolean have;
            synchronized (this) {
                have = haveRot;
                System.arraycopy(rot, 0, r, 0, 9);
            }
            if (have) {
                float[][] b = sensorBasis(r);
                putBasis(sensorLastBasis, b[0], b[1], b[2]);
            }
            arBridgeReady = have;
        }

        private void showTrackingHint() {
            long now = SystemClock.uptimeMillis();
            if (now - lastHintMs < 8000) return;
            final String h = tracker.trackingHint();
            if (h == null) return;
            lastHintMs = now;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    toast(h);
                }
            });
        }

        /** Columns: right, up, back (= -forward). */
        private void putBasis(float[] m, float[] right, float[] up, float[] fwd) {
            for (int i = 0; i < 3; i++) {
                m[i] = right[i];
                m[3 + i] = up[i];
                m[6 + i] = -fwd[i];
            }
        }

        /** Head right / up / forward in the rotation sensor's world, from its rotation matrix. */
        private float[][] sensorBasis(float[] r) {
            // Phone axes (X right, Y up, Z out of the screen in its natural portrait pose),
            // re-labelled for how the screen is turned. The back camera looks along -Z.
            float[] X = {r[0], r[3], r[6]}, Y = {r[1], r[4], r[7]}, Z = {r[2], r[5], r[8]};
            float[] right, up;
            switch (displayRotation) {
                case Surface.ROTATION_0:   right = X;      up = Y;      break;
                case Surface.ROTATION_180: right = neg(X); up = neg(Y); break;
                case Surface.ROTATION_270: right = Y;      up = neg(X); break;
                default:                   right = neg(Y); up = X;      break; // ROTATION_90
            }
            return new float[][]{right, up, neg(Z)};
        }

        /** Works out where the head points, eases the panel toward it, and uploads both. */
        private void setPanelUniforms() {
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
            lastNanos = now;

            float[] r = new float[9];
            float[] head = new float[3];
            boolean ok;
            synchronized (this) {
                ok = haveRot;
                System.arraycopy(rot, 0, r, 0, 9);
                System.arraycopy(headPos, 0, head, 0, 3);
            }
            GLES20.glUniform1f(uTanH, tanHalfH);
            GLES20.glUniform1f(uTanV, tanHalfH / camAspect);

            float vis = visibleFrac;
            float halfW = WEB_W * M_PER_PX / 2f;
            float halfH = vis * TEX_H * M_PER_PX / 2f;
            // Keep the top edge still while the keyboard slides out underneath.
            float drop = halfH - WEB_H * M_PER_PX / 2f;
            GLES20.glUniform2f(uHalf, halfW, halfH);
            GLES20.glUniform1f(uVis, vis);
            GLES20.glUniform2f(uCursor, cursorU, cursorV);
            GLES20.glUniform1f(uPress, pressed ? 1f : 0f);
            boolean sensorOk = ok;
            boolean ar = useAr;
            if (ar) {
                ok = arHavePose;
                System.arraycopy(arPos, 0, head, 0, 3);
            }
            if (!ok) {
                GLES20.glUniform1f(uPanel, 0f);
                return;
            }

            float[][] sb = sensorBasis(r);
            float[] right = sb[0], up = sb[1], fwd = sb[2];
            // Which way is up in this world: +Z for the rotation sensor, +Y for ARCore.
            float[] worldUp = {0f, 0f, 1f};
            if (ar) {
                worldUp = new float[]{0f, 1f, 0f};
                if (arLost && arBridgeReady && sensorOk) {
                    // ARCore lost tracking: turn its last pose by however far the gyroscope
                    // says the head has turned since, so the panel doesn't stick to your face.
                    float[] sNow = new float[9];
                    putBasis(sNow, sb[0], sb[1], sb[2]);
                    float[] aNow = new float[9];
                    for (int j = 0; j < 3; j++) {
                        for (int i = 0; i < 3; i++) {
                            float d = sensorLastBasis[3 * i] * sNow[3 * j]
                                    + sensorLastBasis[3 * i + 1] * sNow[3 * j + 1]
                                    + sensorLastBasis[3 * i + 2] * sNow[3 * j + 2];
                            for (int k = 0; k < 3; k++) aNow[3 * j + k] += arLastBasis[3 * i + k] * d;
                        }
                    }
                    right = new float[]{aNow[0], aNow[1], aNow[2]};
                    up = new float[]{aNow[3], aNow[4], aNow[5]};
                    fwd = new float[]{-aNow[6], -aNow[7], -aNow[8]};
                } else {
                    right = arRight.clone();
                    up = arUp.clone();
                    fwd = arFwd.clone();
                }
            }

            if (!panelPlaced || recenter) {
                // Reset: put the panel straight ahead of wherever your head is now.
                System.arraycopy(fwd, 0, panelDir, 0, 3);
                for (int i = 0; i < 3; i++) anchor[i] = head[i] + fwd[i] * PANEL_DIST;
                panelPlaced = true;
                recenter = false;
                following = false;
                arAnchorPending = ar; // pin it in ARCore's map so it doesn't slide
            } else if (follow) {
                // Smooth follow: stay put while you look around the panel, glide back
                // once you look well away, and keep the same distance if you move (6DoF).
                float[] toPanel = new float[3];
                for (int i = 0; i < 3; i++) toPanel[i] = anchor[i] - head[i];
                norm(toPanel);
                float cos = toPanel[0] * fwd[0] + toPanel[1] * fwd[1] + toPanel[2] * fwd[2];
                float angle = (float) Math.acos(Math.max(-1f, Math.min(1f, cos)));
                if (angle > FOLLOW_START) following = true;
                float a = 1f - (float) Math.exp(-dt * FOLLOW_SPEED);
                if (following) {
                    for (int i = 0; i < 3; i++) panelDir[i] += (fwd[i] - panelDir[i]) * a;
                    norm(panelDir);
                    if (angle < FOLLOW_STOP) following = false;
                }
                for (int i = 0; i < 3; i++) {
                    float target = head[i] + panelDir[i] * PANEL_DIST;
                    anchor[i] += (target - anchor[i]) * a;
                }
            } else {
                // 3DoF / 6DoF: the panel stays where it is in the room.
                following = false;
            }

            if (ar && !follow) {
                if (arAnchorPending && !arLost
                        && tracker.setAnchor(anchor[0], anchor[1], anchor[2])) {
                    System.arraycopy(panelDir, 0, anchorLocalDir, 0, 3);
                    arAnchorPending = false;
                }
                // Follow the anchor as ARCore refines its map of the room.
                float[] p = new float[3], d = new float[3];
                if (!arAnchorPending && tracker.getAnchor(anchorLocalDir, p, d)) {
                    System.arraycopy(p, 0, anchor, 0, 3);
                    System.arraycopy(d, 0, panelDir, 0, 3);
                    norm(panelDir);
                }
            }

            // Keep the panel level with the horizon.
            float[] pr = cross(panelDir, worldUp);
            if (len(pr) < 0.05f) pr = right.clone(); // looking straight up or down
            norm(pr);
            float[] pu = cross(pr, panelDir);
            norm(pu);

            float[] pc = new float[3];
            for (int i = 0; i < 3; i++) pc[i] = anchor[i] - pu[i] * drop;

            GLES20.glUniform1f(uPanel, 1f);
            GLES20.glUniform3f(uHead, head[0], head[1], head[2]);
            GLES20.glUniform3f(uHR, right[0], right[1], right[2]);
            GLES20.glUniform3f(uHU, up[0], up[1], up[2]);
            GLES20.glUniform3f(uHF, fwd[0], fwd[1], fwd[2]);
            GLES20.glUniform3f(uPC, pc[0], pc[1], pc[2]);
            GLES20.glUniform3f(uPR, pr[0], pr[1], pr[2]);
            GLES20.glUniform3f(uPU, pu[0], pu[1], pu[2]);
            GLES20.glUniform3f(uPN, -panelDir[0], -panelDir[1], -panelDir[2]);
        }

        private float[] neg(float[] v) {
            return new float[]{-v[0], -v[1], -v[2]};
        }

        private float[] cross(float[] a, float[] b) {
            return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        }

        private float len(float[] v) {
            return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        }

        private void norm(float[] v) {
            float l = len(v);
            if (l > 1e-6f) {
                v[0] /= l;
                v[1] /= l;
                v[2] /= l;
            }
        }

        private int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException(GLES20.glGetShaderInfoLog(s));
            return s;
        }

        private int link(String vs, String fs) {
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs));
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs));
            GLES20.glLinkProgram(p);
            return p;
        }
    }
}
