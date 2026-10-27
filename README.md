# StereoCam

A Google Cardboard app for Android. It shows your phone's camera in both eyes and puts a floating web browser in front of you that you control with a Bluetooth gamepad (made for the Stadia controller).

## Features

- Camera passthrough, side by side, with Cardboard lens correction
- A floating browser panel, opening on Google, with an on-panel keyboard
- Head tracking modes: 3DoF (the panel stays put), 6DoF with ARCore (you can walk around the panel; needs an ARCore-supported phone) and Smooth follow (it glides back in front of you)
- Reset button (top left) and settings (top right)

## Controller

| Control | Action |
|---|---|
| Left stick | Move the cursor (D-pad nudges it) |
| R2 or A | Click; hold R2 to drag |
| Right stick | Scroll |
| B or L1 / R1 | Back / forward |
| Y | Show or hide the keyboard |
| Menu | Put the screen back in front of you |

## Building

The app builds without Gradle; `build.sh` has the whole pipeline. It uses a full Android SDK when `ANDROID_HOME` has one, and includes ARCore when `ARCORE_AAR` points to the library. Otherwise it falls back to Ubuntu's Android packages and builds without 6DoF. Every push runs the **Build APK** workflow, which downloads ARCore. To download the APK, open the run in the Actions tab and get **StereoCam-apk** from the Artifacts section.

Updates only install over the existing app when they're signed with the same key. Store the key, base64-encoded, as a repository secret named `KEYSTORE_B64` (Settings → Secrets and variables → Actions). Never commit the keystore; this repo is public.

## Code

- `MainActivity.java`: camera, sensors, controller input, rendering (one fragment shader traces the panel into the camera view for each eye)
- `PanelHost.java`: draws the browser and keyboard into an OpenGL texture instead of the screen
- `VrKeyboard.java`: the on-panel keyboard
- `SettingsOverlay.java`: the settings sheet plus the gear and reset buttons

- `SixDofTracker.java`: interface for positional tracking; `app/src/arcore/.../ArTracker.java` is the ARCore implementation, loaded by name so builds without ARCore still work
- `tools/merge_manifest.py`: merges ARCore's manifest into the app's (Gradle normally does this)
