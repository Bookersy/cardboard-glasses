package com.stereocam.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

/**
 * A window's top bar, like a browser's: back, forward and reload, the address box (click to
 * type a web address or search), a grip to drag the window by, then new-window and close.
 */
public class TitleBar extends View {

    public static final int NONE = 0, BACK = 1, FORWARD = 2, RELOAD = 3, DRAG = 4, NEW = 5, CLOSE = 6,
            ADDRESS = 7;

    public interface Listener {
        void onTitleAction(int action);
    }

    private Listener listener;
    private String address = "";
    private boolean active, canBack, canForward;
    private int pressed = NONE, hover = NONE;

    // Address editing (typed with the panel keyboard)
    private boolean editing, replaceOnType;
    private final StringBuilder edit = new StringBuilder();

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    public TitleBar(Context c) {
        super(c);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** The page's address, shown when not editing. */
    public void setAddress(String url) {
        String a = url == null ? "" : url.replaceFirst("^https?://(www\\.)?", "");
        if (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        if (!a.equals(address)) {
            address = a;
            invalidate();
        }
    }

    public void setActive(boolean a) {
        if (a != active) {
            active = a;
            invalidate();
        }
    }

    public void setNavState(boolean back, boolean forward) {
        if (back != canBack || forward != canForward) {
            canBack = back;
            canForward = forward;
            invalidate();
        }
    }

    // ------------------------------------------------------------------ address editing

    public boolean isEditing() {
        return editing;
    }

    /** Starts editing with the current address selected, so typing replaces it. */
    public void startEditing(String fullUrl) {
        editing = true;
        replaceOnType = true;
        edit.setLength(0);
        edit.append(fullUrl == null ? "" : fullUrl);
        invalidate();
    }

    public void stopEditing() {
        if (editing) {
            editing = false;
            invalidate();
        }
    }

    public void type(String s) {
        if (replaceOnType) edit.setLength(0);
        replaceOnType = false;
        edit.append(s);
        invalidate();
    }

    public void backspace() {
        if (replaceOnType) edit.setLength(0);
        else if (edit.length() > 0) edit.setLength(edit.length() - 1);
        replaceOnType = false;
        invalidate();
    }

    public String editedText() {
        return edit.toString();
    }

    // ------------------------------------------------------------------ layout

    private float gripW() {
        return getHeight() * 0.9f;
    }

    /** Which part of the bar is at x (this view's pixels). */
    public int regionAt(float x) {
        float h = getHeight(), w = getWidth();
        if (x < h) return BACK;
        if (x < 2 * h) return FORWARD;
        if (x < 3 * h) return RELOAD;
        if (x >= w - h) return CLOSE;
        if (x >= w - 2 * h) return NEW;
        if (x >= w - 2 * h - gripW()) return DRAG;
        return ADDRESS;
    }

    public void setHover(float x) {
        int h = x < 0 ? NONE : regionAt(x);
        if (h != hover) {
            hover = h;
            invalidate();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int region = regionAt(e.getX());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = region == DRAG ? NONE : region;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                int p = pressed;
                pressed = NONE;
                invalidate();
                if (p != NONE && p == region && listener != null) listener.onTitleAction(p);
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = NONE;
                invalidate();
                return true;
        }
        return true;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(active ? 0xFF2B3446 : 0xFF22262E);
        if (active) {
            bg.setColor(0xFF4C7DF0);
            c.drawRect(0, h - h * 0.06f, w, h, bg);
        }

        drawButton(c, BACK, 0, h, canBack);
        drawButton(c, FORWARD, h, h, canForward);
        drawButton(c, RELOAD, 2 * h, h, true);
        drawButton(c, NEW, w - 2 * h, h, true);
        drawButton(c, CLOSE, w - h, h, true);

        // Address box
        float left = 3 * h + h * 0.1f, right = w - 2 * h - gripW() - h * 0.1f;
        r.set(left, h * 0.14f, right, h * 0.86f);
        bg.setColor(editing ? 0xFF10141B : (hover == ADDRESS || pressed == ADDRESS ? 0xFF3A4252 : 0xFF181C24));
        c.drawRoundRect(r, h * 0.36f, h * 0.36f, bg);
        if (editing) {
            bg.setStyle(Paint.Style.STROKE);
            bg.setStrokeWidth(h * 0.04f);
            bg.setColor(0xFF4C7DF0);
            c.drawRoundRect(r, h * 0.36f, h * 0.36f, bg);
            bg.setStyle(Paint.Style.FILL);
        }

        text.setTextSize(h * 0.40f);
        float tx = left + h * 0.4f, maxW = right - left - h * 0.8f;
        float baseline = h / 2f - (text.descent() + text.ascent()) / 2f;
        if (editing) {
            String s = edit.toString();
            // Show the end of what's being typed, with a caret after it.
            String shown = s;
            while (shown.length() > 0 && text.measureText(shown) > maxW - h * 0.2f) shown = shown.substring(1);
            float sw = text.measureText(shown);
            if (replaceOnType && !shown.isEmpty()) {
                bg.setColor(0xFF3B5CB0); // selected, typing replaces it
                c.drawRect(tx - 2, h * 0.24f, tx + sw + 2, h * 0.76f, bg);
            }
            text.setColor(0xFFF2F4F8);
            c.drawText(shown, tx, baseline, text);
            if (!replaceOnType) {
                bg.setColor(0xFF7FA6FF);
                c.drawRect(tx + sw + 2, h * 0.26f, tx + sw + 2 + h * 0.04f, h * 0.74f, bg);
            }
        } else if (address.isEmpty()) {
            text.setColor(0xFF7D8596);
            c.drawText("Search or type a web address", tx, baseline, text);
        } else {
            text.setColor(0xFFD5DAE3);
            String s = TextUtils.ellipsize(address, text, maxW, TextUtils.TruncateAt.END).toString();
            c.drawText(s, tx, baseline, text);
        }

        // Grip: the handle you hold R2 on to move the window.
        float gx = w - 2 * h - gripW() / 2f, gy = h / 2f, d = h * 0.05f;
        bg.setColor(hover == DRAG ? 0xCCFFFFFF : 0x66FFFFFF);
        for (int i = -1; i <= 1; i++)
            for (int j = -1; j <= 1; j += 2)
                c.drawCircle(gx + j * d * 1.8f, gy + i * d * 3.2f, d, bg);
    }

    private void drawButton(Canvas c, int which, float x, float h, boolean enabled) {
        if (which == pressed || which == hover) {
            bg.setColor(which == CLOSE ? 0xFF8A3A3A : 0xFF3E4758);
            r.set(x + h * 0.1f, h * 0.1f, x + h * 0.9f, h * 0.9f);
            c.drawRoundRect(r, h * 0.2f, h * 0.2f, bg);
        }
        icon.setColor(enabled ? 0xFFE6E9EF : 0xFF5A6170);
        icon.setStrokeWidth(h * 0.07f);
        float cx = x + h / 2f, cy = h / 2f, s = h * 0.17f;
        path.reset();
        switch (which) {
            case BACK:
                path.moveTo(cx + s * 0.5f, cy - s);
                path.lineTo(cx - s * 0.5f, cy);
                path.lineTo(cx + s * 0.5f, cy + s);
                c.drawPath(path, icon);
                break;
            case FORWARD:
                path.moveTo(cx - s * 0.5f, cy - s);
                path.lineTo(cx + s * 0.5f, cy);
                path.lineTo(cx - s * 0.5f, cy + s);
                c.drawPath(path, icon);
                break;
            case RELOAD:
                r.set(cx - s, cy - s, cx + s, cy + s);
                c.drawArc(r, -60f, 290f, false, icon);
                double a = Math.toRadians(-60);
                float ex = cx + (float) Math.cos(a) * s, ey = cy + (float) Math.sin(a) * s;
                path.moveTo(ex - s * 0.6f, ey - s * 0.25f);
                path.lineTo(ex, ey);
                path.lineTo(ex - s * 0.15f, ey + s * 0.6f);
                c.drawPath(path, icon);
                break;
            case NEW:
                c.drawLine(cx - s, cy, cx + s, cy, icon);
                c.drawLine(cx, cy - s, cx, cy + s, icon);
                break;
            case CLOSE:
                c.drawLine(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f, icon);
                c.drawLine(cx - s * 0.8f, cy + s * 0.8f, cx + s * 0.8f, cy - s * 0.8f, icon);
                break;
        }
    }
}
