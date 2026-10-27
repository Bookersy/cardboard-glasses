package com.stereocam.app;

import android.app.Activity;
import android.util.Size;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;

/** 6DoF head tracking through Google's ARCore. Loaded by name from MainActivity. */
public class ArTracker implements SixDofTracker {

    private Session session;
    private boolean installRequested;
    private int boundTexture = -1;
    private int geometryRotation = -1;
    private volatile String error;
    private volatile String hint;
    private final java.util.HashMap<Integer, Anchor> anchors = new java.util.HashMap<>();
    private int nextHandle = 1;

    @Override
    public String unsupportedReason(Activity a) {
        ArCoreApk.Availability av = ArCoreApk.getInstance().checkAvailability(a);
        switch (av) {
            case UNSUPPORTED_DEVICE_NOT_CAPABLE:
                return "This phone doesn't support ARCore, so 6DoF isn't possible on it.";
            default:
                return null; // supported, needs install/update, or still checking
        }
    }

    @Override
    public boolean resume(Activity a) throws Exception {
        if (session == null) {
            ArCoreApk.InstallStatus st = ArCoreApk.getInstance().requestInstall(a, !installRequested);
            if (st == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true;
                return false;
            }
            session = new Session(a);
            Config cfg = new Config(session);
            cfg.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            cfg.setFocusMode(Config.FocusMode.AUTO);
            cfg.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
            cfg.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
            session.configure(cfg);
        }
        error = null;
        boundTexture = -1;
        geometryRotation = -1;
        session.resume();
        return true;
    }

    @Override
    public void pause() {
        if (session != null) session.pause();
    }

    @Override
    public String lastError() {
        return error;
    }

    @Override
    public String trackingHint() {
        return hint;
    }

    private static String describe(TrackingFailureReason r) {
        switch (r) {
            case INSUFFICIENT_LIGHT:
                return "Too dark for 6DoF tracking. Turn on a light.";
            case EXCESSIVE_MOTION:
                return "Moving too fast for 6DoF tracking. Slow down for a moment.";
            case INSUFFICIENT_FEATURES:
                return "Not enough detail to track. Look at something textured, not a plain wall.";
            case CAMERA_UNAVAILABLE:
                return "Another app is using the camera.";
            case BAD_STATE:
                return "6DoF tracking hit a problem. It should recover in a moment.";
            default:
                return "Starting 6DoF tracking. Look around slowly.";
        }
    }

    @Override
    public int createAnchor(float x, float y, float z) {
        Session s = session;
        if (s == null) return -1;
        try {
            Anchor a = s.createAnchor(new Pose(new float[]{x, y, z}, new float[]{0f, 0f, 0f, 1f}));
            int h = nextHandle++;
            anchors.put(h, a);
            return h;
        } catch (Exception e) {
            return -1; // usually "not tracking yet"; the caller retries
        }
    }

    @Override
    public boolean getAnchor(int handle, float[] localDir, float[] outPos, float[] outDir) {
        Anchor a = anchors.get(handle);
        if (a == null || a.getTrackingState() != TrackingState.TRACKING) return false;
        Pose p = a.getPose();
        outPos[0] = p.tx();
        outPos[1] = p.ty();
        outPos[2] = p.tz();
        float[] d = p.rotateVector(localDir);
        System.arraycopy(d, 0, outDir, 0, 3);
        return true;
    }

    @Override
    public void releaseAnchor(int handle) {
        Anchor a = anchors.remove(handle);
        if (a != null) a.detach();
    }

    @Override
    public void releaseAllAnchors() {
        for (Anchor a : anchors.values()) a.detach();
        anchors.clear();
    }
}
