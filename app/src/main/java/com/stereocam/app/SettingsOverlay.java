package com.stereocam.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Full-screen settings sheet opened from the gear button. Used with the phone out of the viewer. */
public class SettingsOverlay extends FrameLayout {

    public static final int MODE_3DOF = 0, MODE_6DOF = 1, MODE_FOLLOW = 2;

    public interface Callbacks {
        void onLensChanged(boolean on);

        void onEyeShiftChanged(float shift);

        void onTrackingChanged(int mode);

        void onCameraTrackingChanged(boolean on);

        void onClose();
    }

    private static final int BG = 0xFF1C2028, SEG_BG = 0xFF2A303B, ACCENT = 0xFF4C7DF0,
            TEXT = 0xFFF2F4F8, MUTED = 0xFF8D95A5, DISABLED = 0xFF5A6170;

    private final Callbacks cb;
    private boolean lens;
    private float eyeShift;
    private int mode;
    private boolean cameraTracking;
    private String arcoreUnavailable; // null when ARCore camera tracking can be used

    private TextView mode3, mode6, modeF, lensOn, lensOff, sepValue, note, camLabel, camOff, camOn;
    private LinearLayout camRow;
    private SeekBar sep;

    public SettingsOverlay(Context c, Callbacks cb) {
        super(c);
        this.cb = cb;
        setBackgroundColor(0xB3000000);
        setClickable(true); // swallow touches so they don't reach the view behind
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SettingsOverlay.this.cb.onClose();
            }
        });

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(20), dp(24), dp(20));
        card.setBackground(round(BG, dp(20)));
        card.setClickable(true); // taps inside the card don't close it

        LinearLayout titleRow = new LinearLayout(c);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Settings", 22, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = text("Done", 15, TEXT);
        done.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        done.setPadding(dp(18), dp(8), dp(18), dp(8));
        done.setBackground(round(ACCENT, dp(18)));
        done.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SettingsOverlay.this.cb.onClose();
            }
        });
        titleRow.addView(done);
        card.addView(titleRow);

        card.addView(label("HEAD TRACKING"));
        LinearLayout seg1 = segRow();
        mode3 = segItem("3DoF");
        mode6 = segItem("6DoF");
        modeF = segItem("Smooth follow");
        seg1.addView(mode3, segLp());
        seg1.addView(mode6, segLp());
        seg1.addView(modeF, new LinearLayout.LayoutParams(0, -2, 1.5f));
        card.addView(seg1);
        note = text("", 13, MUTED);
        note.setPadding(0, dp(6), 0, 0);
        card.addView(note);
        mode3.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mode = MODE_3DOF;
                refresh();
                SettingsOverlay.this.cb.onTrackingChanged(mode);
            }
        });
        modeF.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mode = MODE_FOLLOW;
                refresh();
                SettingsOverlay.this.cb.onTrackingChanged(mode);
            }
        });
        mode6.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mode = MODE_6DOF;
                refresh();
                SettingsOverlay.this.cb.onTrackingChanged(mode);
            }
        });

        // Only shown in 6DoF: optionally use the camera (ARCore) instead of the motion sensors.
        camLabel = label("CAMERA TRACKING (ARCORE)");
        card.addView(camLabel);
        camRow = segRow();
        camOff = segItem("Off \u00B7 motion sensors");
        camOn = segItem("On \u00B7 ARCore");
        camRow.addView(camOff, segLp());
        camRow.addView(camOn, segLp());
        card.addView(camRow);
        camOff.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cameraTracking = false;
                refresh();
                SettingsOverlay.this.cb.onCameraTrackingChanged(false);
            }
        });
        camOn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (arcoreUnavailable != null) {
                    note.setText(arcoreUnavailable);
                    note.setTextColor(0xFFFFB86B);
                    return;
                }
                cameraTracking = true;
                refresh();
                SettingsOverlay.this.cb.onCameraTrackingChanged(true);
            }
        });

        card.addView(label("LENS CORRECTION"));
        LinearLayout seg2 = segRow();
        lensOn = segItem("On");
        lensOff = segItem("Off");
        seg2.addView(lensOn, segLp());
        seg2.addView(lensOff, segLp());
        card.addView(seg2);
        lensOn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                lens = true;
                refresh();
                SettingsOverlay.this.cb.onLensChanged(true);
            }
        });
        lensOff.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                lens = false;
                refresh();
                SettingsOverlay.this.cb.onLensChanged(false);
            }
        });

        LinearLayout sepHead = new LinearLayout(c);
        sepHead.setGravity(Gravity.BOTTOM);
        TextView sepLabel = label("EYE SEPARATION");
        sepHead.addView(sepLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        sepValue = text("", 13, MUTED);
        sepHead.addView(sepValue);
        card.addView(sepHead);
        sep = new SeekBar(c);
        sep.setMax(160); // 0 .. 0.08 in steps of 0.0005
        sep.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                eyeShift = p * 0.0005f;
                refreshSep();
                SettingsOverlay.this.cb.onEyeShiftChanged(eyeShift);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        card.addView(sep, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(false);
        scroll.addView(card);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(440), -2, Gravity.CENTER);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        addView(scroll, lp);
    }

    public void setValues(boolean lens, float eyeShift, int mode, boolean cameraTracking, String arcoreUnavailable) {
        this.cameraTracking = cameraTracking && arcoreUnavailable == null;
        this.arcoreUnavailable = arcoreUnavailable;
        this.lens = lens;
        this.eyeShift = eyeShift;
        this.mode = mode;
        refresh();
    }

    private void refresh() {
        style(mode3, mode == MODE_3DOF, true);
        style(mode6, mode == MODE_6DOF, true);
        boolean six = mode == MODE_6DOF;
        camLabel.setVisibility(six ? VISIBLE : GONE);
        camRow.setVisibility(six ? VISIBLE : GONE);
        style(camOff, !cameraTracking, true);
        style(camOn, cameraTracking, arcoreUnavailable == null);
        style(modeF, mode == MODE_FOLLOW, true);
        note.setTextColor(MUTED);
        if (mode == MODE_FOLLOW) {
            note.setText("Tracks head turns. The browser glides back in front of you when you look away.");
        } else if (mode == MODE_6DOF && cameraTracking) {
            note.setText("Tracks turning and walking with the camera (ARCore). Needs light and a textured "
                    + "area to look at; the motion sensors cover short dropouts.");
        } else if (mode == MODE_6DOF) {
            note.setText("Tracks turning, leaning and small head movements with the motion sensors. "
                    + "Walking around isn't tracked: without the camera, a phone can't measure how far it moved.");
        } else {
            note.setText("Tracks head turns. The browser stays where it is; press Menu on the controller to bring it back.");
        }
        style(lensOn, lens, true);
        style(lensOff, !lens, true);
        sep.setProgress(Math.round(eyeShift / 0.0005f));
        refreshSep();
    }

    private void refreshSep() {
        sepValue.setText(String.format("%.1f%%", eyeShift * 200f));
    }

    private void style(TextView t, boolean selected, boolean enabled) {
        t.setBackground(selected ? round(ACCENT, dp(10)) : null);
        t.setTextColor(!enabled ? DISABLED : TEXT);
    }

    private LinearLayout segRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.setBackground(round(SEG_BG, dp(14)));
        return row;
    }

    private TextView segItem(String s) {
        TextView t = text(s, 15, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(10), dp(8), dp(10));
        return t;
    }

    private LinearLayout.LayoutParams segLp() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private TextView label(String s) {
        TextView t = text(s, 12, MUTED);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(18), 0, dp(8));
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** The round gear button that sits in the top-right corner. */
    public static class GearButton extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path gear = new Path();

        public GearButton(Context c) {
            super(c);
            setClickable(true);
            setContentDescription("Settings");
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) setAlpha(0.6f);
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) setAlpha(1f);
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x66000000);
            c.drawCircle(cx, cy, Math.min(w, h) / 2f, fill);

            float ro = Math.min(w, h) * 0.32f, ri = ro * 0.74f, hole = ro * 0.36f;
            int teeth = 8;
            gear.reset();
            for (int i = 0; i < teeth * 4; i++) {
                double a = Math.PI * 2 * i / (teeth * 4);
                float r = (i % 4 == 0 || i % 4 == 1) ? ro : ri;
                float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
                if (i == 0) gear.moveTo(x, y);
                else gear.lineTo(x, y);
            }
            gear.close();
            gear.addCircle(cx, cy, hole, Path.Direction.CCW);
            gear.setFillType(Path.FillType.EVEN_ODD);
            fill.setColor(0xFFFFFFFF);
            c.drawPath(gear, fill);
        }
    }

    /** Round button with a circular arrow, for putting the browser back in front of you. */
    public static class ResetButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path head = new Path();

        public ResetButton(Context c) {
            super(c);
            setClickable(true);
            setContentDescription("Reset screen position");
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) setAlpha(0.6f);
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) setAlpha(1f);
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, m = Math.min(w, h);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66000000);
            c.drawCircle(cx, cy, m / 2f, paint);

            float r = m * 0.24f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(m * 0.075f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xFFFFFFFF);
            android.graphics.RectF oval = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
            // Arc from about 1 o'clock, clockwise most of the way round, leaving a gap at the top.
            c.drawArc(oval, -60f, 300f, false, paint);

            // Arrowhead at the arc's start, pointing back along the circle (anticlockwise).
            double a = Math.toRadians(-60);
            float ex = cx + (float) Math.cos(a) * r, ey = cy + (float) Math.sin(a) * r;
            float s = m * 0.13f;
            paint.setStyle(Paint.Style.FILL);
            head.reset();
            head.moveTo(ex + s * 0.9f, ey - s * 0.15f);
            head.lineTo(ex - s * 0.35f, ey - s * 0.85f);
            head.lineTo(ex - s * 0.15f, ey + s * 0.65f);
            head.close();
            c.drawPath(head, paint);
        }
    }
}
