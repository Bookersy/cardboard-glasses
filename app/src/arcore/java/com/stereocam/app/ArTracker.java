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
    private Anchor anchor;

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
    public boolean setAnchor(float x, float y, float z) {
        Session s = session;
        if (s == null) return false;
        try {
            Anchor a = s.createAnchor(new Pose(new float[]{x, y, z}, new float[]{0f, 0f, 0f, 1f}));
            if (anchor != null) anchor.detach();
            anchor = a;
            return true;
        } catch (Exception e) {
            return false; // usually "not tracking yet"; the caller retries
        }
    }

    @Override
    public boolean getAnchor(float[] localDir, float[] outPos, float[] outDir) {
        Anchor a = anchor;
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
    public int update(int cameraTexture, int displayRotation,
                      float[] right, float[] up, float[] fwd, float[] pos,
                      float[] texMatrix, float[] camInfo) {
        Session s = session;
        if (s == null) return NO_FRAME;
        try {
            if (boundTexture != cameraTexture) {
                s.setCameraTextureName(cameraTexture);
                boundTexture = cameraTexture;
            }
            if (geometryRotation != displayRotation) {
                // Use the camera's own shape as the "view", so view coordinates cover the whole picture.
                Size ts = s.getCameraConfig().getTextureSize();
                int w = Math.max(ts.getWidth(), ts.getHeight()), h = Math.min(ts.getWidth(), ts.getHeight());
                s.setDisplayGeometry(displayRotation, w, h);
                geometryRotation = displayRotation;
            }

            Frame f = s.update();
            Camera cam = f.getCamera();

            // Map view coordinates (origin top-left, y down) onto the camera texture. We sample
            // with c = (x, y up), so view = (c.x, 1 - c.y). The mapping is affine; find it from 3 points.
            float[] view = {0f, 0f, 1f, 0f, 0f, 1f};
            float[] tex = new float[6];
            f.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, view, Coordinates2d.TEXTURE_NORMALIZED, tex);
            float t0x = tex[0], t0y = tex[1], t1x = tex[2], t1y = tex[3], t2x = tex[4], t2y = tex[5];
            // tex(c) = T2 + (T1 - T0) * c.x - (T2 - T0) * c.y   (column-major mat4)
            java.util.Arrays.fill(texMatrix, 0f);
            texMatrix[0] = t1x - t0x;
            texMatrix[1] = t1y - t0y;
            texMatrix[4] = -(t2x - t0x);
            texMatrix[5] = -(t2y - t0y);
            texMatrix[10] = 1f;
            texMatrix[12] = t2x;
            texMatrix[13] = t2y;
            texMatrix[15] = 1f;

            CameraIntrinsics in = cam.getTextureIntrinsics();
            float[] fl = in.getFocalLength();
            int[] dims = in.getImageDimensions();
            if (dims[0] >= dims[1]) {
                camInfo[0] = (dims[0] / 2f) / fl[0];
                camInfo[1] = (float) dims[0] / dims[1];
            } else {
                camInfo[0] = (dims[1] / 2f) / fl[1];
                camInfo[1] = (float) dims[1] / dims[0];
            }

            if (cam.getTrackingState() != TrackingState.TRACKING) {
                hint = describe(cam.getTrackingFailureReason());
                return CAMERA_ONLY;
            }
            hint = null;

            // Display-oriented pose: X right, Y up, -Z forward for how the screen is held.
            Pose p = cam.getDisplayOrientedPose();
            float[] x = p.getXAxis(), y = p.getYAxis(), z = p.getZAxis();
            for (int i = 0; i < 3; i++) {
                right[i] = x[i];
                up[i] = y[i];
                fwd[i] = -z[i];
            }
            pos[0] = p.tx();
            pos[1] = p.ty();
            pos[2] = p.tz();
            return TRACKING;
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return FAILED;
        }
    }
}
