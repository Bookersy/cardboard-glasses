# StereoCam

A Google Cardboard app for Android. It shows your phone's camera in both eyes and puts a floating web browser in front of you that you control with a Bluetooth gamepad (made for the Stadia controller).

## Features

- Camera passthrough, side by side, with Cardboard lens correction
- Up to 4 floating browser windows, each with back, forward, reload and an address bar, and an on-panel keyboard
- Drag windows around the room, and push them away or pull them closer
- Head tracking modes: 3DoF (windows stay put), 6DoF with ARCore (walk around them; needs an ARCore-supported phone) and Smooth follow (the active window glides back in front of you)
- No saved-password prompts: the app opts out of Android autofill, whose popups would appear out of sight on the real screen
- Reset button (top left) and settings (top right)

## Controller

The cursor floats in front of you and moves with your head; the left stick aims it.

| Control | Action |
|---|---|
| Left stick | Aim the cursor (D-pad nudges it) |
| R2 or A | Click; hold to drag-select |
| R2 on a window's grip (dots by the address bar) | Move the window |
| L2 (hold) | Grab and move the window under the cursor |
| Right stick | Scroll; while moving a window, push it away or pull it closer |
| X | New window |
| B | Close the keyboard, or go back |
| L1 / R1 | Back / forward |
| Y | Show or hide the keyboard |
| Menu | Put the windows back in front of you |

Click a window's address bar to type a web address or a search, then press Enter.

## Building

The app builds without Gradle; `build.sh` has the whole pipeline. It uses a full Android SDK when `ANDROID_HOME` has one, and includes ARCore when `ARCORE_AAR` points to the library. Otherwise it falls back to Ubuntu's Android packages and builds without 6DoF. Every push runs the **Build APK** workflow, which downloads ARCore. To download the APK, open the run in the Actions tab and get **StereoCam-apk** from the Artifacts section.

Updates only install over the existing app when they're signed with the same key. Store the key, base64-encoded, as a repository secret named `KEYSTORE_B64` (Settings → Secrets and variables → Actions). Never commit the keystore; this repo is public.

## Code

- `MainActivity.java`: camera, sensors, controller input, window placement, and rendering (shaders trace each window into the camera view for each eye)
- `BrowserWindow.java`: one window: title bar, page, keyboard
- `TitleBar.java`: back, forward, reload, address bar, grip, new window, close
- `PanelHost.java`: draws a window's views into an OpenGL texture instead of the screen
- `NoAutofill.java`: keeps Android's password/autofill prompts out of the app
- `VrKeyboard.java`: the on-panel keyboard
- `SettingsOverlay.java`: the settings sheet plus the gear and reset buttons

- `SixDofTracker.java`: interface for positional tracking; `app/src/arcore/.../ArTracker.java` is the ARCore implementation, loaded by name so builds without ARCore still work
- `tools/merge_manifest.py`: merges ARCore's manifest into the app's (Gradle normally does this)
