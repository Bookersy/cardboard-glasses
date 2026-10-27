package com.stereocam.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.Surface;
import android.widget.FrameLayout;

/**
 * Holds the browser and the keyboard. Instead of drawing onto the phone screen, it draws
 * itself into a Surface that feeds an OpenGL texture, which is then shown on the floating panel.
 */
public class PanelHost extends FrameLayout {

    private Surface surface;
    private float drawScale = 1f;

    public PanelHost(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    /** Children lay out larger than the texture (for a desktop-width page); shrink them when drawing. */
    public void setDrawScale(float s) {
        drawScale = s;
        invalidate();
    }

    public synchronized void setSurface(Surface s) {
        if (surface != null && surface != s) surface.release();
        surface = s;
        invalidate();
    }

    @Override
    public void draw(Canvas screenCanvas) {
        // Draw nothing on the real screen; send everything to the panel texture.
        Surface s;
        synchronized (this) {
            s = surface;
        }
        if (s == null || !s.isValid()) return;
        Canvas c = null;
        try {
            c = s.lockHardwareCanvas();
            c.drawColor(Color.WHITE);
            c.save();
            c.scale(drawScale, drawScale);
            super.draw(c);
            c.restore();
        } catch (RuntimeException e) {
            // Surface went away mid-frame (app pausing); the next frame redraws.
        } finally {
            if (c != null) {
                try {
                    s.unlockCanvasAndPost(c);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
