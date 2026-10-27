package com.stereocam.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** An on-panel keyboard you click with the controller cursor. */
public class VrKeyboard extends View {

    public static final int CHAR = 0, SHIFT = 1, DEL = 2, ENTER = 3, SYM = 4, CLOSE = 5, SPACE = 6;

    public interface Listener {
        void onKey(int type, String text);
    }

    private static class Key {
        final int type;
        final String text;
        final float units;
        final RectF rect = new RectF();

        Key(int type, String text, float units) {
            this.type = type;
            this.text = text;
            this.units = units;
        }
    }

    private Listener listener;
    private boolean shift, symbols;
    private final List<List<Key>> rows = new ArrayList<>();
    private Key pressed, hover;
    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int laidOutW, laidOutH;

    public VrKeyboard(Context c) {
        super(c);
        textPaint.setColor(0xFFF2F4F8);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        build();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private void build() {
        rows.clear();
        rows.add(chars("1234567890"));
        if (!symbols) {
            rows.add(chars("qwertyuiop"));
            rows.add(chars("asdfghjkl"));
            List<Key> r3 = new ArrayList<>();
            r3.add(new Key(SHIFT, "⇧", 1.5f));
            r3.addAll(chars("zxcvbnm"));
            r3.add(new Key(DEL, "⌫", 1.5f));
            rows.add(r3);
        } else {
            rows.add(chars("@#$%&-+()/"));
            rows.add(chars("*\"':;!?_="));
            List<Key> r3 = new ArrayList<>();
            r3.addAll(chars("[]{}<>\\|~"));
            r3.add(new Key(DEL, "⌫", 1f));
            rows.add(r3);
        }
        List<Key> r4 = new ArrayList<>();
        r4.add(new Key(SYM, symbols ? "ABC" : "?123", 1.5f));
        r4.add(new Key(CHAR, ",", 1f));
        r4.add(new Key(SPACE, " ", 4f));
        r4.add(new Key(CHAR, ".", 1f));
        r4.add(new Key(ENTER, "↵", 1.25f));
        r4.add(new Key(CLOSE, "Close", 1.25f));
        rows.add(r4);
        laidOutW = -1;
        pressed = null;
        hover = null;
        invalidate();
    }

    private List<Key> chars(String s) {
        List<Key> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) out.add(new Key(CHAR, String.valueOf(s.charAt(i)), 1f));
        return out;
    }

    private void layoutKeys() {
        int w = getWidth(), h = getHeight();
        if (w == laidOutW && h == laidOutH) return;
        laidOutW = w;
        laidOutH = h;
        float pad = 8f * h / 300f; // keeps proportions at any layout size
        float rowH = (h - pad) / rows.size();
        float unit = (w - pad) / 10f;
        for (int r = 0; r < rows.size(); r++) {
            List<Key> row = rows.get(r);
            float total = 0;
            for (Key k : row) total += k.units;
            float x = pad + (10f - total) * unit / 2f;
            float y = pad + r * rowH;
            for (Key k : row) {
                k.rect.set(x, y, x + k.units * unit - pad, y + rowH - pad);
                x += k.units * unit;
            }
        }
        textPaint.setTextSize(rowH * 0.42f);
    }

    private Key keyAt(float x, float y) {
        layoutKeys();
        for (List<Key> row : rows)
            for (Key k : row)
                if (k.rect.contains(x, y)) return k;
        return null;
    }

    /** Cursor position in this view's coordinates, or negative to clear the highlight. */
    public void setHover(float x, float y) {
        Key k = (x < 0 || y < 0) ? null : keyAt(x, y);
        if (k != hover) {
            hover = k;
            invalidate();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        Key k = keyAt(e.getX(), e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = k;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                Key p = pressed;
                pressed = null;
                invalidate();
                if (p != null && p == k) fire(p);
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                invalidate();
                return true;
        }
        return true;
    }

    private void fire(Key k) {
        switch (k.type) {
            case SHIFT:
                shift = !shift;
                invalidate();
                return;
            case SYM:
                symbols = !symbols;
                shift = false;
                build();
                return;
            case CHAR:
                String t = shift ? k.text.toUpperCase() : k.text;
                if (shift) {
                    shift = false;
                    invalidate();
                }
                if (listener != null) listener.onKey(CHAR, t);
                return;
            default:
                if (listener != null) listener.onKey(k.type, k.text);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        layoutKeys();
        c.drawColor(0xFF1A1E26);
        float rr = 10f * getHeight() / 300f;
        for (List<Key> row : rows) {
            for (Key k : row) {
                int color;
                if (k == pressed) color = 0xFF4C7DF0;
                else if (k.type == CLOSE) color = k == hover ? 0xFF8A3A3A : 0xFF6A2E2E;
                else if (k.type == SHIFT && shift) color = 0xFF3F6BD6;
                else if (k.type != CHAR) color = k == hover ? 0xFF4A5363 : 0xFF363D4A;
                else color = k == hover ? 0xFF434B59 : 0xFF2B313C;
                keyPaint.setColor(color);
                c.drawRoundRect(k.rect, rr, rr, keyPaint);
                String label = k.type == SPACE ? "space"
                        : (k.type == CHAR && shift) ? k.text.toUpperCase() : k.text;
                float ty = k.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f;
                c.drawText(label, k.rect.centerX(), ty, textPaint);
            }
        }
    }
}
