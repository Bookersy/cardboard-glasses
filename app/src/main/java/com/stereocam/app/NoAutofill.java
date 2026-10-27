package com.stereocam.app;

import android.view.View;

/**
 * Keeps Android's autofill service (Google Password Manager and the like) out of this app, so
 * no "use saved password?" or "save password?" prompt pops up over the VR view when a login
 * field is focused. Those prompts are drawn by the system on the real screen, where you
 * can't see or reach them in a headset.
 *
 * The call only exists on Android 8+, and local builds compile against Android 6, so it's
 * made by name.
 */
final class NoAutofill {
    // View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    private static final int NO_EXCLUDE_DESCENDANTS = 0x8;

    private NoAutofill() {
    }

    static void apply(View v) {
        if (v == null) return;
        try {
            View.class.getMethod("setImportantForAutofill", int.class).invoke(v, NO_EXCLUDE_DESCENDANTS);
        } catch (Exception ignored) {
            // Older Android: no autofill service to block.
        }
    }
}
