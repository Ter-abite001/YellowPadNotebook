# Yellow Pad Notebook

A native Android starter project for a simple calculation notebook.

## Included
- Yellow pad-style paper with horizontal rules only
- Handwrite mode (finger or stylus)
- Type mode
- Pen size, eraser, clear page
- New page, previous/next page, and a horizontally scrollable page strip for jumping to older pages
- Local automatic saving of page text and strokes

## Build an APK
1. Install Android Studio (current stable) on a computer.
2. Open this folder in Android Studio.
3. Let Gradle sync and install the Android SDK platform 35 if prompted.
4. Choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Install the generated APK on your Android phone.

Minimum Android version: Android 8.0 (API 26).

## Notes
This is a starter implementation, not a tested release build. The redo control is a basic placeholder in this first version; undo/redo history and stylus pressure handling should be refined before relying on it for important notes. Page content is stored locally in app preferences.
