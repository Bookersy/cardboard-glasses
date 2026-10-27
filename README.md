# StereoCam

A Google Cardboard app for Android. It shows your phone's camera in both eyes and puts a floating web browser in front of you that you control with a Bluetooth gamepad (made for the Stadia controller).

## Features

- Camera passthrough, side by side, with Cardboard lens correction
- A floating browser panel, opening on Google, with an on-panel keyboard
- Head tracking modes: 3DoF (the panel stays put) and Smooth follow (it glides back in front of you). 6DoF is planned and needs ARCore.
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

The app builds without Gradle, using Ubuntu's Android SDK packages; `build.sh` has the whole pipeline. Every push runs the **Build APK** workflow. To download the APK, open the run in the Actions tab and get **StereoCam-apk** from the Artifacts section.

Updates only install over the existing app when they're signed with the same key. Store the key, base64-encoded, as a repository secret named `KEYSTORE_B64` (Settings → Secrets and variables → Actions). Never commit the keystore; this repo is public.

## Code

- `MainActivity.java`: camera, sensors, controller input, rendering (one fragment shader traces the panel into the camera view for each eye)
- `PanelHost.java`: draws the browser and keyboard into an OpenGL texture instead of the screen
- `VrKeyboard.java`: the on-panel keyboard
- `SettingsOverlay.java`: the settings sheet plus the gear and reset buttons

For 6DoF, `StereoRenderer.setHeadPosition(x, y, z)` is the hook a positional tracker such as ARCore should feed. The panel is already anchored in world space, so reset and Smooth follow use your head position.
