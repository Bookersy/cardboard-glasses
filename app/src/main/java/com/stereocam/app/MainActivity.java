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
import android.os.Message;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.SizeF;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Cardboard-style passthrough with floating web browser windows.
 *
 * The rear camera fills both eyes. Browser windows float in the room, placed using the rotation
 * sensor (or ARCore in 6DoF). A cursor floats in front of you: aim it with the left stick, and
 * it moves with your head.
 *
 * Controller (Stadia or any Bluetooth gamepad):
 *   Left stick   - aim the cursor              Right stick  - scroll (while moving a window: push / pull)
 *   R2 or A      - click (hold to drag-select) L2           - hold to grab and move the window you point at
 *   B            - close keyboard, or back     L1 / R1      - back / forward
 *   Y            - show / hide keyboard        X            - new window
 *   Menu (Start) - bring the windows back in front of you
 *   D-pad        - nudge the cursor
 * Each window's top bar: back, forward, reload, address box, grip (hold R2 on it to move the
 * window), new window, close.
 * Reset button (top left of the screen) and gear button (top right): as the Menu button, and
 * settings. Volume keys change eye separation.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQ_CAMERA = 1;
    private static final String HOME = "https://www.google.com";
    private static final int MAX_WINDOWS = 4;

    // Pages are laid out this many CSS pixels wide (like a small laptop), then scaled onto the panel.
    private static final float PAGE_CSS_WIDTH = 900f;
    private float layoutScale = 1f; // view pixels per panel-texture pixel

    private GLSurfaceView glView;
    private StereoRenderer renderer;
    private FrameLayout root;
    final CopyOnWriteArrayList<BrowserWindow> windows = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<BrowserWindow> closedWindows = new ConcurrentLinkedQueue<>();
    volatile BrowserWindow active;

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
    private boolean triggerDown, buttonADown, grabDown;
    private boolean pressing, dragging, dragByGrab;
    private BrowserWindow pressWin;
    private float lastPressX = -1, lastPressY = -1;
    private long touchDownTime;
    private float cursorYaw, cursorPitch; // radians, relative to where you're looking
    private long lastTickNanos;
    private boolean ticking;

    private final BrowserWindow.Listener windowListener = new BrowserWindow.Listener() {
        @Override
        public void onEditFocus(BrowserWindow w) {
            w.title.stopEditing();
            setActive(w);
            w.showKeyboard(true);
        }

        @Override
        public void onTitleAction(final BrowserWindow w, int action) {
            switch (action) {
                case TitleBar.BACK:
                    w.goBack();
                    break;
                case TitleBar.FORWARD:
                    w.goForward();
                    break;
                case TitleBar.RELOAD:
                    w.web.reload();
                    break;
                case TitleBar.ADDRESS:
                    setActive(w);
                    w.editAddress();
                    break;
                case TitleBar.NEW:
                    newWindow(HOME, w);
                    break;
                case TitleBar.CLOSE:
                    // Not from inside the view's own event handling.
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            closeWindow(w);
                        }
                    });
                    break;
            }
        }

        @Override
        public boolean onOpenWindow(BrowserWindow from, Message resultMsg) {
            BrowserWindow nw = newWindow(null, from);
            if (nw == null) return false;
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(nw.web);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onLoadError(BrowserWindow w, String message) {
            toast(message);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Never pop up the phone's own keyboard; the panels have their own.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        // No saved-password / autofill prompts: they appear on the real screen, out of sight.
        NoAutofill.apply(getWindow().getDecorView());

        DisplayMetrics dm = getResources().getDisplayMetrics();
        layoutScale = Math.max(1f, dm.density * PAGE_CSS_WIDTH / BrowserWindow.W);
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

        root = new FrameLayout(this);
        NoAutofill.apply(root);

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
        newWindow(HOME, null);

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

    // ------------------------------------------------------------------ windows

    /** Opens a window (url null = an empty one a page will fill). Appears just in front of you. */
    BrowserWindow newWindow(String url, BrowserWindow nextTo) {
        if (windows.size() >= MAX_WINDOWS) {
            toast("Up to " + MAX_WINDOWS + " windows. Close one first.");
            return null;
        }
        BrowserWindow w = new BrowserWindow(this, layoutScale, windowListener, url);
        NoAutofill.apply(w.host);
        root.addView(w.host, 0, w.hostLayout()); // behind the 3D view; it draws into a texture
        w.placeNextTo = nextTo;
        windows.add(w);
        setActive(w);
        return w;
    }

    void closeWindow(BrowserWindow w) {
        if (!windows.remove(w)) return;
        if (pressWin == w) {
            pressing = false;
            pressWin = null;
            renderer.pressWindow = null;
        }
        if (dragging && renderer.dragWindow == w) endDrag();
        root.removeView(w.host);
        w.destroy();
        closedWindows.add(w); // the render thread frees its texture
        if (active == w) setActive(windows.isEmpty() ? null : windows.get(windows.size() - 1));
        if (windows.isEmpty()) toast("Press X for a new window");
    }

    void setActive(BrowserWindow w) {
        active = w;
        for (BrowserWindow x : windows) x.title.setActive(x == w);
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
            float lt = Math.max(axis(e, MotionEvent.AXIS_LTRIGGER), axis(e, MotionEvent.AXIS_BRAKE));
            if (!grabDown && lt > 0.5f) {
                grabDown = true;
                updateGrab();
            } else if (grabDown && lt < 0.3f) {
                grabDown = false;
                updateGrab();
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

        BrowserWindow w = active;
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_R2:
                if (down != buttonADown && e.getRepeatCount() == 0) {
                    buttonADown = down;
                    updatePress();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_L2:
                if (down != grabDown && e.getRepeatCount() == 0) {
                    grabDown = down;
                    updateGrab();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (first && w != null) {
                    if (w.keyboardWanted) {
                        w.title.stopEditing();
                        w.showKeyboard(false);
                    } else {
                        w.goBack();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
                if (first && w != null) w.goBack();
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (first && w != null) w.goForward();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (first) newWindow(HOME, w);
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (first && w != null) {
                    if (w.keyboardWanted) w.title.stopEditing();
                    w.showKeyboard(!w.keyboardWanted);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
                if (first) resetScreenPosition();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) cursorYaw -= 0.02f;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) cursorYaw += 0.02f;
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) cursorPitch += 0.02f;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) cursorPitch -= 0.02f;
                return true;
        }
        return true; // swallow other controller buttons so they don't reach the page oddly
    }

    /** R2 / A: click whatever the cursor is on, or start moving a window from its grip. */
    private void updatePress() {
        boolean want = triggerDown || buttonADown;
        renderer.pressed = want;
        if (want && !pressing && !dragging) {
            BrowserWindow w = renderer.hitWindow;
            if (w == null) return;
            float x = renderer.hitX, y = renderer.hitY;
            setActive(w);
            if (y < BrowserWindow.TITLE_H && w.title.regionAt(x * layoutScale) == TitleBar.DRAG) {
                startDrag(w, false);
                return;
            }
            if (y >= BrowserWindow.TITLE_H && y < BrowserWindow.BASE_H && w.title.isEditing()) {
                // Clicked the page while typing an address: stop typing it.
                w.title.stopEditing();
                w.showKeyboard(false);
            }
            pressing = true;
            pressWin = w;
            renderer.pressWindow = w;
            touchDownTime = SystemClock.uptimeMillis();
            lastPressX = x;
            lastPressY = y;
            w.touch(MotionEvent.ACTION_DOWN, x, y, touchDownTime);
        } else if (!want) {
            if (dragging && !dragByGrab) endDrag();
            if (pressing) {
                pressing = false;
                BrowserWindow w = pressWin;
                pressWin = null;
                renderer.pressWindow = null;
                if (w != null) {
                    w.touch(MotionEvent.ACTION_UP, lastPressX, lastPressY, touchDownTime);
                    if (lastPressY >= BrowserWindow.TITLE_H && lastPressY < BrowserWindow.BASE_H) {
                        w.checkTappedTextField();
                    }
                }
            }
        }
    }

    /** L2: grab the window under the cursor and move it while held. */
    private void updateGrab() {
        if (grabDown && !dragging && !pressing) {
            BrowserWindow w = renderer.hitWindow;
            if (w != null) {
                setActive(w);
                startDrag(w, true);
            }
        } else if (!grabDown && dragging && dragByGrab) {
            endDrag();
        }
    }

    private void startDrag(BrowserWindow w, boolean byGrab) {
        dragging = true;
        dragByGrab = byGrab;
        renderer.dragPush = 0f;
        renderer.dragWindow = w;
    }

    private void endDrag() {
        dragging = false;
        renderer.dragWindow = null;
        renderer.dragPush = 0f;
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
        renderer.clearAnchors = true;
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

    /** Puts the windows back in front of you: the active one straight ahead, others beside it. */
    private void resetScreenPosition() {
        cursorYaw = 0f;
        cursorPitch = 0f;
        renderer.recenter = true;
        toast(windows.size() > 1 ? "Windows moved in front of you" : "Screen moved in front of you");
    }

    private static float shape(float v) {
        float dz = 0.15f;
        float a = Math.abs(v);
        if (a < dz) return 0f;
        float n = (a - dz) / (1f - dz);
        return Math.signum(v) * n * n;
    }

    /** Runs every display frame: aims the cursor, scrolls, moves windows, animates keyboards. */
    private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameNanos) {
            if (!ticking) return;
            float dt = lastTickNanos == 0 ? 0f : Math.min(0.05f, (frameNanos - lastTickNanos) / 1e9f);
            lastTickNanos = frameNanos;

            // Cursor: an angle away from where you're looking, so it travels with your head.
            float speed = 0.9f; // radians per second at full tilt
            cursorYaw += (shape(lx) * speed + hatX * 0.25f) * dt;
            cursorPitch -= (shape(ly) * speed + hatY * 0.25f) * dt;
            cursorYaw = Math.max(-0.36f, Math.min(0.36f, cursorYaw));
            cursorPitch = Math.max(-0.30f, Math.min(0.30f, cursorPitch));
            renderer.cursorYaw = cursorYaw;
            renderer.cursorPitch = cursorPitch;

            for (BrowserWindow w : windows) w.animate(dt);

            BrowserWindow hit = renderer.hitWindow;
            float hx = renderer.hitX, hy = renderer.hitY;
            for (BrowserWindow w : windows) {
                if (w == hit && !dragging) w.hover(hx, hy);
                else w.hover(-1, -1);
            }

            // Holding R2 on a page: keep the touch moving with the cursor (drag-select, sliders).
            if (pressing && pressWin != null) {
                float px = renderer.pressX, py = renderer.pressY;
                if (px != lastPressX || py != lastPressY) {
                    lastPressX = px;
                    lastPressY = py;
                    pressWin.touch(MotionEvent.ACTION_MOVE, px, py, touchDownTime);
                }
            }

            float sv = shape(-ry), sh = shape(rx);
            if (dragging) {
                renderer.dragPush = -shape(ry) * 1.2f; // push away / pull closer, metres per second
            } else if ((sv != 0f || sh != 0f) && hit != null && !pressing
                    && hy >= BrowserWindow.TITLE_H && hy < BrowserWindow.BASE_H) {
                hit.scroll(hx, hy, sv * 0.6f, sh * 0.6f);
            }

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
        for (BrowserWindow w : windows) w.web.onResume();
        if (wantAr()) startAr();
        else maybeOpenCamera();

        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor rv = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rv == null) rv = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) {
            sensors.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME);
        } else {
            toast("No rotation sensor on this phone, so the windows stay still");
        }

        ticking = true;
        lastTickNanos = 0;
        Choreographer.getInstance().postFrameCallback(tick);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ticking = false;
        if (pressing && pressWin != null) pressWin.touch(MotionEvent.ACTION_CANCEL, lastPressX, lastPressY, touchDownTime);
        pressing = false;
        pressWin = null;
        renderer.pressWindow = null;
        if (dragging) endDrag();
        if (sensors != null) sensors.unregisterListener(this);
        closeCamera();
        for (BrowserWindow w : windows) w.web.onPause();
        glView.onPause();       // stops the render thread first...
        if (arActive) {         // ...so ARCore can pause safely
            renderer.useAr = false;
            renderer.clearAnchors = true;
            tracker.pause();
            arActive = false;
        }
        for (BrowserWindow w : windows) w.setSurface(null);
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

    /** Called on the UI thread once the GL thread has made the camera texture. */
    void onCameraTextureReady(SurfaceTexture cam) {
        pendingTexture = cam;
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

            // Field of view, so the windows line up with the real world in the camera image.
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

        // Windows: metres per texture pixel (80 cm wide), and distance from your eyes.
        private static final float M_PER_PX = 0.80f / BrowserWindow.W;
        private static final float WINDOW_DIST = 1.6f;
        private static final float FOLLOW_SPEED = 3f;          // higher = catches up faster
        private static final float FOLLOW_START = 0.35f;       // radians away before it follows (~20 deg)
        private static final float FOLLOW_STOP = 0.04f;        // settles when this close
        private static final float EYE_HALF_IPD = 0.032f;

        // Set from the UI thread
        volatile boolean distortion = true;
        volatile int displayRotation = 1; // Surface.ROTATION_90
        volatile float eyeShift = 0.025f;   // fraction of camera width each eye is offset
        volatile float camAspect = 16f / 9f;
        volatile float tanHalfH = 0.62f;   // ~64 degrees horizontal, replaced by the real lens value
        volatile boolean pressed, recenter, clearAnchors;
        volatile boolean follow = true;    // smooth-follow mode; false = windows stay put
        volatile boolean useAr;            // 6DoF: ARCore supplies the camera and head pose
        volatile boolean arError;
        volatile float cursorYaw, cursorPitch, dragPush;
        volatile BrowserWindow pressWindow, dragWindow;

        // Published for the UI thread: what the cursor is on, in texture pixels.
        volatile BrowserWindow hitWindow;
        volatile float hitX, hitY, pressX, pressY;

        private int arTexId;
        private final float[] arRight = new float[3], arUp = new float[3], arFwd = new float[3],
                arPos = new float[3], arMatrix = new float[16], arCamInfo = {0.62f, 16f / 9f};
        private boolean arHavePose, arCameraLive, arLost;
        // Last good ARCore pose, with the rotation sensor's reading at that moment, so head turns
        // can still be followed (with the gyroscope) while ARCore has lost tracking.
        private final float[] arLastBasis = new float[9], sensorLastBasis = new float[9];
        private boolean arBridgeReady;
        private long lastHintMs;

        private final float[] rot = new float[9];
        private final float[] headPos = new float[3]; // metres; stays 0,0,0 in 3DoF
        private boolean haveRot;
        private long lastNanos;

        // This frame's head pose (render thread)
        private float[] hRight = {1, 0, 0}, hUp = {0, 0, 1}, hFwd = {0, 1, 0};
        private final float[] hPos = new float[3];
        private float[] worldUp = {0f, 0f, 1f};
        private final float[] cursorDir = new float[3], cursorWorld = new float[3];

        // Moving a window (render thread)
        private BrowserWindow dragging;
        private float grabLx, grabLy, grabDist;
        private BrowserWindow lastHitWin;
        private float lastHitLx, lastHitLy, lastHitT;

        private final float xdpi;
        private Prog camProg, winProg, curProg;
        private int texId;
        private SurfaceTexture surfaceTexture;
        private final float[] texMatrix = new float[16];
        private int width, height;
        private final FloatBuffer quad;

        StereoRenderer(float xdpi) {
            this.xdpi = xdpi > 0 ? xdpi : 400f;
            float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
            quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(v).position(0);
        }

        synchronized void setHeadRotation(float[] r) {
            System.arraycopy(r, 0, rot, 0, 9);
            haveRot = true;
        }

        /**
         * Head position in metres, in the same world frame as the rotation (X east, Y north,
         * Z up). Only a positional tracker would call this; in 3DoF the head stays at the origin.
         */
        synchronized void setHeadPosition(float x, float y, float z) {
            headPos[0] = x;
            headPos[1] = y;
            headPos[2] = z;
        }

        // ---------------------------------------------------------------- shaders

        private static final String VS =
                "attribute vec2 aPos;\n" +
                "varying vec2 vUv;\n" +
                "void main(){ vUv = aPos*0.5+0.5; gl_Position = vec4(aPos,0.0,1.0); }";

        private static final String PRECISION =
                "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
                "precision highp float;\n" +
                "#else\n" +
                "precision mediump float;\n" +
                "#endif\n";

        // Shared by every pass: lens pre-distortion (cancels the Cardboard lens's pincushion)
        // and turning a pixel into a view ray. vUv is 0..1 across this eye's half of the screen;
        // uCenter is where the lens centre sits in it.
        private static final String COMMON =
                "varying vec2 vUv;\n" +
                "uniform vec2 uCenter;\n" +
                "uniform float uAspect, uCrop, uShift, uDistort, uTanH, uTanV, uEye;\n" +
                "uniform vec3 uHR, uHU, uHF, uHead;\n" +
                "bool lensView(out vec2 c){\n" +
                "  vec2 p = vUv - uCenter;\n" +
                "  p.x *= uAspect;\n" +
                "  if (uDistort > 0.5) { float r2 = dot(p,p); p *= (1.0 + 0.22*r2 + 0.24*r2*r2) * 0.82; }\n" +
                "  p.x /= uAspect;\n" +
                "  vec2 uv = p + 0.5;\n" +
                "  c = vec2(0.5 + (uv.x-0.5)*uCrop + uShift, uv.y);\n" +
                "  return uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0;\n" +
                "}\n" +
                "vec3 viewDir(vec2 c){\n" +
                "  vec2 tn = vec2((c.x-0.5)*2.0*uTanH, (c.y-0.5)*2.0*uTanV);\n" +
                "  return normalize(tn.x*uHR + tn.y*uHU + uHF);\n" +
                "}\n" +
                "vec3 eyePos(){ return uHead + uEye*uHR; }\n";

        private static final String CAM_FS =
                "#extension GL_OES_EGL_image_external : require\n" + PRECISION + COMMON +
                "uniform samplerExternalOES sTex;\n" +
                "uniform mat4 uTexMatrix;\n" +
                "uniform float uRot;\n" +
                "void main(){\n" +
                "  vec2 c;\n" +
                "  if (!lensView(c)) { gl_FragColor = vec4(0.0,0.0,0.0,1.0); return; }\n" +
                "  vec3 col = vec3(0.0);\n" +
                "  if (c.x >= 0.0 && c.x <= 1.0) {\n" +
                "    vec2 tc = c;\n" +
                "    if (uRot > 0.5 && uRot < 1.5) tc = vec2(c.y, 1.0 - c.x);\n" +
                "    else if (uRot > 1.5 && uRot < 2.5) tc = 1.0 - c;\n" +
                "    else if (uRot > 2.5) tc = vec2(1.0 - c.y, c.x);\n" +
                "    col = texture2D(sTex, (uTexMatrix*vec4(tc,0.0,1.0)).xy).rgb;\n" +
                "  }\n" +
                "  gl_FragColor = vec4(col, 1.0);\n" +
                "}";

        // One window: trace this pixel's view ray into the world and hit the window's plane.
        private static final String WIN_FS =
                "#extension GL_OES_EGL_image_external : require\n" + PRECISION + COMMON +
                "uniform samplerExternalOES sWeb;\n" +
                "uniform mat4 uWebMatrix;\n" +
                "uniform vec3 uPC, uPR, uPU, uPN;\n" +
                "uniform vec2 uHalf;\n" +
                "uniform float uVis, uActive;\n" +
                "void main(){\n" +
                "  vec2 c;\n" +
                "  if (!lensView(c)) discard;\n" +
                "  vec3 dir = viewDir(c);\n" +
                "  vec3 o = eyePos();\n" +
                "  float den = dot(dir, uPN);\n" +
                "  if (den > -0.0001) discard;\n" +
                "  float t = dot(uPC - o, uPN) / den;\n" +
                "  if (t <= 0.0) discard;\n" +
                "  vec3 h = o + dir*t - uPC;\n" +
                "  vec2 l = vec2(dot(h, uPR), dot(h, uPU));\n" +
                "  float rad = 0.012;\n" +
                "  vec2 q = abs(l) - uHalf + rad;\n" +
                "  float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;\n" +
                "  float aa = 0.003 * t;\n" +
                "  float fill = 1.0 - smoothstep(-aa, aa, d);\n" +
                "  float edge = 1.0 - smoothstep(0.0, aa * 1.5, abs(d + 0.002) - 0.0015);\n" +
                "  if (fill <= 0.0 && edge <= 0.0) discard;\n" +
                "  float tx = clamp(l.x / (2.0*uHalf.x) + 0.5, 0.0, 1.0);\n" +
                "  float ty = clamp((uHalf.y - l.y) / (2.0*uHalf.y), 0.0, 1.0) * uVis;\n" +
                "  vec3 page = texture2D(sWeb, (uWebMatrix*vec4(tx, 1.0-ty, 0.0, 1.0)).xy).rgb;\n" +
                "  vec3 ec = mix(vec3(0.78, 0.92, 1.0), vec3(0.35, 0.55, 1.0), uActive);\n" +
                "  float ea = edge * mix(0.45, 0.95, uActive);\n" +
                "  gl_FragColor = vec4(mix(page, ec, ea), max(fill, ea));\n" +
                "}";

        // The cursor: a dot at a point in the world (on the window it's over, or in mid-air).
        private static final String CUR_FS =
                PRECISION + COMMON +
                "uniform vec3 uCursorPos;\n" +
                "uniform float uPress;\n" +
                "void main(){\n" +
                "  vec2 c;\n" +
                "  if (!lensView(c)) discard;\n" +
                "  vec3 dir = viewDir(c);\n" +
                "  vec3 n = normalize(uCursorPos - eyePos());\n" +
                "  if (dot(dir, n) <= 0.0) discard;\n" +
                "  float s = length(cross(dir, n));\n" + // angle from the cursor, in radians
                "  float a = 1.0 - smoothstep(0.0085, 0.0100, s);\n" +
                "  if (a <= 0.0) discard;\n" +
                "  float inner = 1.0 - smoothstep(0.0055, 0.0070, s);\n" +
                "  vec3 dotc = mix(vec3(1.0), vec3(0.3, 0.55, 1.0), uPress);\n" +
                "  gl_FragColor = vec4(mix(vec3(0.08, 0.1, 0.14), dotc, inner), a);\n" +
                "}";

        /** A linked shader program, with its uniform locations looked up once. */
        private class Prog {
            final int id, aPos;
            private final HashMap<String, Integer> locs = new HashMap<>();

            Prog(String fs) {
                id = link(VS, fs);
                aPos = GLES20.glGetAttribLocation(id, "aPos");
            }

            int u(String name) {
                Integer i = locs.get(name);
                if (i == null) {
                    i = GLES20.glGetUniformLocation(id, name);
                    locs.put(name, i);
                }
                return i;
            }
        }

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
            camProg = new Prog(CAM_FS);
            winProg = new Prog(WIN_FS);
            curProg = new Prog(CUR_FS);

            texId = newExternalTexture();
            arTexId = newExternalTexture();
            arHavePose = false;
            surfaceTexture = new SurfaceTexture(texId);
            // A new GL context: every window needs a fresh texture.
            for (BrowserWindow w : windows) {
                w.texId = 0;
                w.surfaceTexture = null;
            }
            final SurfaceTexture st = surfaceTexture;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    onCameraTextureReady(st);
                }
            });
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            width = w;
            height = h;
        }

        /** Gives a new window its texture, and frees the textures of closed ones. */
        private void manageWindowTextures() {
            BrowserWindow gone;
            while ((gone = closedWindows.poll()) != null) {
                if (gone.surfaceTexture != null) gone.surfaceTexture.release();
                if (gone.texId != 0) GLES20.glDeleteTextures(1, new int[]{gone.texId}, 0);
                if (gone.arAnchor >= 0 && tracker != null) tracker.releaseAnchor(gone.arAnchor);
                gone.texId = 0;
                gone.surfaceTexture = null;
            }
            for (final BrowserWindow w : windows) {
                if (w.texId != 0) continue;
                w.texId = newExternalTexture();
                w.surfaceTexture = new SurfaceTexture(w.texId);
                w.surfaceTexture.setDefaultBufferSize(BrowserWindow.W, BrowserWindow.TEX_H);
                final Surface s = new Surface(w.surfaceTexture);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (windows.contains(w)) w.setSurface(s);
                        else s.release();
                    }
                });
            }
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (surfaceTexture == null) return;

            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
            lastNanos = now;

            if (clearAnchors) {
                if (tracker != null) tracker.releaseAllAnchors();
                for (BrowserWindow w : windows) {
                    w.arAnchor = -1;
                    w.arPending = false;
                }
                clearAnchors = false;
            }
            manageWindowTextures();

            // Camera picture: from ARCore in 6DoF, otherwise from the camera directly.
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
            for (BrowserWindow w : windows) {
                if (w.surfaceTexture == null) continue;
                try {
                    w.surfaceTexture.updateTexImage();
                    w.surfaceTexture.getTransformMatrix(w.texMatrix);
                } catch (RuntimeException e) {
                    // no frame from this window yet
                }
            }

            boolean poseOk = computeHead();
            List<BrowserWindow> shown = new ArrayList<>();
            if (poseOk) {
                placeWindows(dt);
                for (BrowserWindow w : windows) {
                    if (w.placed && w.texId != 0) shown.add(w);
                }
                hitTest(shown);
            } else {
                hitWindow = null;
            }
            // Draw far windows first so nearer ones cover them.
            final float[] dist = new float[shown.size()];
            BrowserWindow[] order = shown.toArray(new BrowserWindow[0]);
            for (int i = 0; i < order.length; i++) dist[i] = dist2(order[i].pc, hPos);
            for (int i = 1; i < order.length; i++) {
                for (int j = i; j > 0 && dist[j] > dist[j - 1]; j--) {
                    float td = dist[j]; dist[j] = dist[j - 1]; dist[j - 1] = td;
                    BrowserWindow tw = order[j]; order[j] = order[j - 1]; order[j - 1] = tw;
                }
            }

            int half = width / 2;
            float eyeAspect = (float) half / height;
            // Show the camera's full height in each eye; crop the width to match the eye's shape.
            float crop = Math.min(1f, eyeAspect / camAspect);
            // Lens centres sit LENS_SEPARATION_MM apart around the middle of the screen.
            float lensPx = LENS_SEPARATION_MM / 25.4f * xdpi;
            float leftCx = Math.max(0.2f, Math.min(0.8f, (half - lensPx / 2f) / half));
            float rightCx = Math.max(0.2f, Math.min(0.8f, (lensPx / 2f) / half));

            for (int eye = 0; eye < 2; eye++) {
                if (eye == 0) GLES20.glViewport(0, 0, half, height);
                else GLES20.glViewport(half, 0, width - half, height);
                float cx = eye == 0 ? leftCx : rightCx;
                // Left eye: its "camera" sits to the left, so it looks at a window shifted left.
                float shift = eye == 0 ? -eyeShift : eyeShift;
                float eyeOff = eye == 0 ? -EYE_HALF_IPD : EYE_HALF_IPD;

                // 1. Camera picture
                GLES20.glDisable(GLES20.GL_BLEND);
                use(camProg, cx, eyeAspect, crop, shift, eyeOff);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, arCameraLive ? arTexId : texId);
                GLES20.glUniform1i(camProg.u("sTex"), 0);
                GLES20.glUniformMatrix4fv(camProg.u("uTexMatrix"), 1, false, arCameraLive ? arMatrix : texMatrix, 0);
                GLES20.glUniform1f(camProg.u("uRot"), arCameraLive ? 0f : (float) displayRotation); // ARCore's matrix already rotates
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                if (!poseOk) continue;
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

                // 2. Windows, far to near
                use(winProg, cx, eyeAspect, crop, shift, eyeOff);
                GLES20.glUniform1i(winProg.u("sWeb"), 1);
                for (BrowserWindow w : order) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, w.texId);
                    GLES20.glUniformMatrix4fv(winProg.u("uWebMatrix"), 1, false, w.texMatrix, 0);
                    GLES20.glUniform3f(winProg.u("uPC"), w.pc[0], w.pc[1], w.pc[2]);
                    GLES20.glUniform3f(winProg.u("uPR"), w.pr[0], w.pr[1], w.pr[2]);
                    GLES20.glUniform3f(winProg.u("uPU"), w.pu[0], w.pu[1], w.pu[2]);
                    GLES20.glUniform3f(winProg.u("uPN"), -w.dir[0], -w.dir[1], -w.dir[2]);
                    GLES20.glUniform2f(winProg.u("uHalf"), w.halfW, w.halfH);
                    GLES20.glUniform1f(winProg.u("uVis"), w.visibleHeight / BrowserWindow.TEX_H);
                    GLES20.glUniform1f(winProg.u("uActive"), w == active ? 1f : 0f);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                }

                // 3. Cursor, on top
                use(curProg, cx, eyeAspect, crop, shift, eyeOff);
                GLES20.glUniform3f(curProg.u("uCursorPos"), cursorWorld[0], cursorWorld[1], cursorWorld[2]);
                GLES20.glUniform1f(curProg.u("uPress"), pressed ? 1f : 0f);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        /** Selects a program and sets what every pass shares for this eye. */
        private void use(Prog p, float centerX, float eyeAspect, float crop, float shift, float eyeOff) {
            GLES20.glUseProgram(p.id);
            GLES20.glEnableVertexAttribArray(p.aPos);
            GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glUniform2f(p.u("uCenter"), centerX, 0.5f);
            GLES20.glUniform1f(p.u("uAspect"), eyeAspect);
            GLES20.glUniform1f(p.u("uCrop"), crop);
            GLES20.glUniform1f(p.u("uShift"), shift);
            GLES20.glUniform1f(p.u("uDistort"), distortion ? 1f : 0f);
            GLES20.glUniform1f(p.u("uTanH"), tanHalfH);
            GLES20.glUniform1f(p.u("uTanV"), tanHalfH / camAspect);
            GLES20.glUniform1f(p.u("uEye"), eyeOff);
            GLES20.glUniform3f(p.u("uHR"), hRight[0], hRight[1], hRight[2]);
            GLES20.glUniform3f(p.u("uHU"), hUp[0], hUp[1], hUp[2]);
            GLES20.glUniform3f(p.u("uHF"), hFwd[0], hFwd[1], hFwd[2]);
            GLES20.glUniform3f(p.u("uHead"), hPos[0], hPos[1], hPos[2]);
        }

        // ---------------------------------------------------------------- head pose

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

        /** Works out this frame's head pose. False if there's none yet. */
        private boolean computeHead() {
            float[] r = new float[9];
            float[] head = new float[3];
            boolean ok;
            synchronized (this) {
                ok = haveRot;
                System.arraycopy(rot, 0, r, 0, 9);
                System.arraycopy(headPos, 0, head, 0, 3);
            }
            boolean sensorOk = ok;
            boolean ar = useAr;
            if (ar) {
                ok = arHavePose;
                System.arraycopy(arPos, 0, head, 0, 3);
            }
            if (!ok) return false;

            float[][] sb = sensorBasis(r);
            float[] right = sb[0], up = sb[1], fwd = sb[2];
            // Which way is up in this world: +Z for the rotation sensor, +Y for ARCore.
            worldUp = ar ? new float[]{0f, 1f, 0f} : new float[]{0f, 0f, 1f};
            if (ar) {
                if (arLost && arBridgeReady && sensorOk) {
                    // ARCore lost tracking: turn its last pose by however far the gyroscope
                    // says the head has turned since, so windows don't stick to your face.
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
            hRight = right;
            hUp = up;
            hFwd = fwd;
            System.arraycopy(head, 0, hPos, 0, 3);

            // Cursor: an angle away from straight ahead.
            float ty = (float) Math.tan(cursorYaw), tp = (float) Math.tan(cursorPitch);
            for (int i = 0; i < 3; i++) cursorDir[i] = fwd[i] + right[i] * ty + up[i] * tp;
            norm(cursorDir);
            return true;
        }

        // ---------------------------------------------------------------- window placement

        /** Turns a direction to the right (negative = left) around the vertical. */
        private float[] turn(float[] d, float radians) {
            float[] right = cross(d, worldUp);
            if (len(right) < 0.05f) right = hRight.clone();
            norm(right);
            float c = (float) Math.cos(radians), s = (float) Math.sin(radians);
            float[] out = {d[0] * c + right[0] * s, d[1] * c + right[1] * s, d[2] * c + right[2] * s};
            norm(out);
            return out;
        }

        private void placeAt(BrowserWindow w, float[] d, float distance, boolean ar) {
            System.arraycopy(d, 0, w.dir, 0, 3);
            for (int i = 0; i < 3; i++) w.anchor[i] = hPos[i] + d[i] * distance;
            w.placed = true;
            w.following = false;
            if (w.arAnchor >= 0 && tracker != null) tracker.releaseAnchor(w.arAnchor);
            w.arAnchor = -1;
            w.arPending = ar && !follow; // pin it in ARCore's map so it doesn't slide
        }

        private void placeWindows(float dt) {
            boolean ar = useAr;
            BrowserWindow act = active;

            if (recenter) {
                // Active window straight ahead; the others fanned out to its sides.
                recenter = false;
                List<BrowserWindow> list = new ArrayList<>(windows);
                if (act != null && list.remove(act)) list.add(0, act);
                float[] offsets = {0f, 0.61f, -0.61f, 1.22f, -1.22f}; // 0, +-35, +-70 degrees
                for (int i = 0; i < list.size(); i++) {
                    placeAt(list.get(i), turn(hFwd, offsets[Math.min(i, offsets.length - 1)]), WINDOW_DIST, ar);
                }
            }
            for (BrowserWindow w : windows) {
                if (w.placed) continue;
                // New window: just in front of where you're looking, a little nearer than the
                // others so it shows on top.
                placeAt(w, hFwd, WINDOW_DIST - 0.15f, ar);
            }

            // Moving a window: keep the spot you grabbed under the cursor.
            BrowserWindow dw = dragWindow;
            if (dw != null && !windows.contains(dw)) dw = null;
            if (dw != null) {
                if (dragging != dw) {
                    dragging = dw;
                    if (lastHitWin == dw) {
                        grabLx = lastHitLx;
                        grabLy = lastHitLy;
                        grabDist = lastHitT;
                    } else {
                        grabLx = 0f;
                        grabLy = 0f;
                        grabDist = (float) Math.sqrt(dist2(dw.pc, hPos));
                    }
                    if (dw.arAnchor >= 0 && tracker != null) tracker.releaseAnchor(dw.arAnchor);
                    dw.arAnchor = -1;
                    dw.arPending = false;
                    dw.following = false;
                }
                grabDist = Math.max(0.5f, Math.min(4f, grabDist + dragPush * dt));
                float[] p = new float[3];
                for (int i = 0; i < 3; i++) p[i] = hPos[i] + cursorDir[i] * grabDist;
                float[] c = p.clone();
                for (int k = 0; k < 2; k++) {
                    float[] d = new float[3];
                    for (int i = 0; i < 3; i++) d[i] = c[i] - hPos[i];
                    norm(d);
                    float[][] axes = windowAxes(d);
                    for (int i = 0; i < 3; i++) c[i] = p[i] - axes[0][i] * grabLx - axes[1][i] * grabLy;
                }
                // c is the centre as drawn; the anchor is the centre without the keyboard.
                float drop = (dw.visibleHeight - BrowserWindow.BASE_H) * M_PER_PX / 2f;
                float[] d = new float[3];
                for (int i = 0; i < 3; i++) d[i] = c[i] - hPos[i];
                norm(d);
                float[] pu = windowAxes(d)[1];
                for (int i = 0; i < 3; i++) dw.anchor[i] = c[i] + pu[i] * drop;
                System.arraycopy(d, 0, dw.dir, 0, 3);
            } else if (dragging != null) {
                // Let go: in 6DoF, pin it where it was dropped.
                dragging.arPending = ar && !follow;
                dragging = null;
            }

            // Smooth follow: the active window glides back in front once you look well away.
            if (follow && act != null && act.placed && act != dw) {
                float[] toWin = new float[3];
                for (int i = 0; i < 3; i++) toWin[i] = act.anchor[i] - hPos[i];
                float distance = len(toWin);
                norm(toWin);
                float cos = toWin[0] * hFwd[0] + toWin[1] * hFwd[1] + toWin[2] * hFwd[2];
                float angle = (float) Math.acos(Math.max(-1f, Math.min(1f, cos)));
                if (angle > FOLLOW_START) act.following = true;
                if (act.following) {
                    float a = 1f - (float) Math.exp(-dt * FOLLOW_SPEED);
                    for (int i = 0; i < 3; i++) act.dir[i] += (hFwd[i] - act.dir[i]) * a;
                    norm(act.dir);
                    for (int i = 0; i < 3; i++) {
                        float target = hPos[i] + act.dir[i] * distance;
                        act.anchor[i] += (target - act.anchor[i]) * a;
                    }
                    if (angle < FOLLOW_STOP) act.following = false;
                }
            }

            // 6DoF: keep each window pinned to ARCore's map of the room.
            if (ar && !follow && tracker != null) {
                float[] p = new float[3], d = new float[3];
                for (BrowserWindow w : windows) {
                    if (w == dw) continue;
                    if (w.arPending && !arLost) {
                        int h = tracker.createAnchor(w.anchor[0], w.anchor[1], w.anchor[2]);
                        if (h >= 0) {
                            w.arAnchor = h;
                            System.arraycopy(w.dir, 0, w.arLocalDir, 0, 3);
                            w.arPending = false;
                        }
                    }
                    if (w.arAnchor >= 0 && tracker.getAnchor(w.arAnchor, w.arLocalDir, p, d)) {
                        System.arraycopy(p, 0, w.anchor, 0, 3);
                        System.arraycopy(d, 0, w.dir, 0, 3);
                        norm(w.dir);
                    }
                }
            }

            // Final shape and position of each window this frame.
            for (BrowserWindow w : windows) {
                if (!w.placed) continue;
                float[][] axes = windowAxes(w.dir);
                System.arraycopy(axes[0], 0, w.pr, 0, 3);
                System.arraycopy(axes[1], 0, w.pu, 0, 3);
                float vis = w.visibleHeight;
                w.halfW = BrowserWindow.W * M_PER_PX / 2f;
                w.halfH = vis * M_PER_PX / 2f;
                // Keep the top edge still while the keyboard slides out underneath.
                float drop = (vis - BrowserWindow.BASE_H) * M_PER_PX / 2f;
                for (int i = 0; i < 3; i++) w.pc[i] = w.anchor[i] - w.pu[i] * drop;
            }
        }

        /** A window's right and up directions: level with the horizon, facing back along d. */
        private float[][] windowAxes(float[] d) {
            float[] pr = cross(d, worldUp);
            if (len(pr) < 0.05f) pr = hRight.clone(); // looking straight up or down
            norm(pr);
            float[] pu = cross(pr, d);
            norm(pu);
            return new float[][]{pr, pu};
        }

        // ---------------------------------------------------------------- cursor

        /** Finds the nearest window under the cursor, and where on it, for the UI thread. */
        private void hitTest(List<BrowserWindow> shown) {
            BrowserWindow best = null;
            float bestT = Float.MAX_VALUE, bestLx = 0, bestLy = 0;
            BrowserWindow pw = pressWindow;
            for (BrowserWindow w : shown) {
                float[] n = {-w.dir[0], -w.dir[1], -w.dir[2]};
                float den = cursorDir[0] * n[0] + cursorDir[1] * n[1] + cursorDir[2] * n[2];
                if (den > -0.0001f) continue;
                float t = ((w.pc[0] - hPos[0]) * n[0] + (w.pc[1] - hPos[1]) * n[1] + (w.pc[2] - hPos[2]) * n[2]) / den;
                if (t <= 0f) continue;
                float[] h = new float[3];
                for (int i = 0; i < 3; i++) h[i] = hPos[i] + cursorDir[i] * t - w.pc[i];
                float lxw = h[0] * w.pr[0] + h[1] * w.pr[1] + h[2] * w.pr[2];
                float lyw = h[0] * w.pu[0] + h[1] * w.pu[1] + h[2] * w.pu[2];
                if (w == pw) {
                    // While pressing, keep tracking this window's surface even off its edge.
                    pressX = clamp((lxw / (2f * w.halfW) + 0.5f) * BrowserWindow.W, 0f, BrowserWindow.W - 1f);
                    pressY = clamp((w.halfH - lyw) / (2f * w.halfH) * w.visibleHeight, 0f, w.visibleHeight - 1f);
                }
                if (Math.abs(lxw) <= w.halfW && Math.abs(lyw) <= w.halfH && t < bestT) {
                    best = w;
                    bestT = t;
                    bestLx = lxw;
                    bestLy = lyw;
                }
            }
            if (best != null) {
                hitX = clamp((bestLx / (2f * best.halfW) + 0.5f) * BrowserWindow.W, 0f, BrowserWindow.W - 1f);
                hitY = clamp((best.halfH - bestLy) / (2f * best.halfH) * best.visibleHeight, 0f, best.visibleHeight - 1f);
                lastHitLx = bestLx;
                lastHitLy = bestLy;
                lastHitT = bestT;
                for (int i = 0; i < 3; i++) cursorWorld[i] = hPos[i] + cursorDir[i] * bestT;
            } else {
                for (int i = 0; i < 3; i++) cursorWorld[i] = hPos[i] + cursorDir[i] * WINDOW_DIST;
            }
            lastHitWin = best;
            hitWindow = best;
        }

        // ---------------------------------------------------------------- maths

        private float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        private float dist2(float[] a, float[] b) {
            float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
            return x * x + y * y + z * z;
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
