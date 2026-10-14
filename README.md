# Widget Notes

An Android note-taking app whose home-screen widget can fill the whole screen and is fully interactive.

## Build & run
1. Open the `WidgetNotes` folder in Android Studio (Ladybug or newer) and let Gradle sync.
2. Run the `app` configuration on a phone or emulator (Android 8.0+, best on 12+ for Material You colours).
3. In the app tap **Add widget**, or long-press the home screen → Widgets → *Widget Notes*.
4. Drag the widget's resize handles to the screen edges to make it full screen.

## What you can do on the widget (without opening the app)
- Scroll through all notes
- Tick a note done / not done (done notes get struck through and sink to the bottom)
- Pin / unpin (pinned notes float to the top, highlighted)
- Delete, with an **Undo** banner
- Filter with **All / Open / Done** tabs (remembered per widget)
- Tap **+** or **Write a note…** to type a new note, or tap any note to edit it — a floating editor opens over the home screen; tap outside or press Back to save

## Structure
- `data/NoteRepository.kt` — single source of truth (JSON file in app storage), refreshes the widget on every change
- `widget/NotesWidget.kt` — the Glance widget UI
- `widget/WidgetActions.kt` — widget button handlers + receiver
- `EditNoteActivity.kt` — floating editor used by the widget and the app
- `MainActivity.kt` — in-app notes grid

## Get an APK without Android Studio (GitHub)
1. Create a new repository on github.com and upload the contents of this folder (including the hidden `.github` folder).
2. Open the repo's **Actions** tab — the "Build APK" job starts automatically (or press *Run workflow*).
3. When it turns green (~5 min), open the run and download **WidgetNotes-apk** under *Artifacts*.
4. Unzip it, copy `app-debug.apk` to your phone and open it (allow "Install unknown apps" when asked).
