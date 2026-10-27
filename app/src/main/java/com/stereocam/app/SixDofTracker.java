package com.stereocam.app;

import android.app.Activity;

/**
 * Positional (6DoF) head tracking that also supplies the camera picture.
 * The ARCore version lives in app/src/arcore and is only compiled when the ARCore library
 * is available (the GitHub build); otherwise 6DoF shows as unavailable.
 */
public interface SixDofTracker {

    int NO_FRAME = 0, CAMERA_ONLY = 1, TRACKING = 2, FAILED = -1;

    /** Null if this phone can do 6DoF (possibly after installing ARCore), else why not. */
    String unsupportedReason(Activity a);

    /**
     * Starts tracking (UI thread). Returns false if it had to send the user to install
     * Google Play Services for AR; call again from onResume when they come back.
     */
    boolean resume(Activity a) throws Exception;

    /** Stops tracking and frees the camera (UI thread, after rendering has stopped). */
    void pause();

    /**
     * Called on the GL thread every frame. Writes the head's right/up/forward axes and
     * position (metres, world Y is up), a texture matrix mapping our 0..1 view coordinates
     * (y up) onto the camera texture, and {tan(half horizontal FOV), width/height}.
     */
    int update(int cameraTexture, int displayRotation,
               float[] right, float[] up, float[] fwd, float[] pos,
               float[] texMatrix, float[] camInfo);

    /** Last error message, if update returned FAILED. */
    String lastError();
}
