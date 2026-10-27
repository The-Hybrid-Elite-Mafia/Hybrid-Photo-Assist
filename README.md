# Hybrid Photo Assist

Private Android photo editor for Steven's Galaxy S24 Ultra. All editing runs on the phone; photos are never uploaded.

## What works
- **Select:** tap a person or object (Google MediaPipe Interactive Segmenter, bundled in the app); fix edges with Brush + / Brush −; Clear removes the selection.
- **Erase:** removes the selection and fills the gap (LaMa fill model, Apache-2.0, downloaded once on first use, ~183 MB, SHA-256 checked).
- **Move:** lifts the selection out, fills the hole, then drag / pinch to place and resize it; Cancel (or Undo, or the Back key) calls the move off.
- **Background:** keeps the selection and swaps the rest for blur, a solid colour, or another photo.
- **Typed instructions** (box above the tools, tap **?** for examples). Simple keywords, not a chat AI:
  - "remove the selected object", "fill the gap"
  - "move it left / up a little / way right"
  - "make the selected area brighter", "a little darker", "make the whole photo brighter"
  - "blur the background", "make the background white / black / gray / lime"
  - "undo", "redo", "cancel", "deselect", "save"
  - You must select the object first; the app cannot find objects from words.
- **My phrases (Edit button):** starts empty. Add your own: *when I say* (anything) and *it does* (type it, or pick
  from the drop-down and change it; exact amounts work, e.g. "move left 7%", "brighter 35%"). Both can be edited
  later. "It does" is checked as you type, so a phrase can only do something the app really can. Saved on the phone.
- **Reference photos (Reference button):** add up to 30 (private copies inside the app, never uploaded), then:
  - *Match the look*: moves your photo's colours and brightness towards the reference (Light / Medium / Full).
  - *Match the scene / vibe*: the same plus the brightness curve, so contrast and shadows follow the reference.
  - Only the selection changes if something is selected; otherwise the whole photo. Plain colour statistics, not AI:
    it doesn't copy lighting direction, objects or style details.
  - *Copy something from it*: tap the object in the reference, Copy into my photo, then drag / pinch and Place it here.
- **Undo / Redo** for every edit (only real changes are recorded; memory-capped history).
- **Hold: Before** shows the original photo while held.
- **Save** to Pictures/Hybrid Photo Assist (original never overwritten); the saved file is read back and checked.
- Photos larger than 4096 px on the long side (50 / 200 MP shots) are edited at 4096 px.

## Not supported (the app says so instead of pretending)
- Face or body changes (smile, eyes, expression, skin, hair, body shape). There is no face-editing AI on the phone.
- "Remove the background" without saying what the new background should be.
- Finding objects from a description ("the man on the left"): select them by tapping.

## Network
The only network use is the one-time fill-model download from Hugging Face (`mlboydaisuke/LaMa-Dilated-LiteRT`),
checked against a fixed SHA-256 before use. After that, everything works offline.

## Build
GitHub Actions → "Build Hybrid Photo Assist" (manual). It checks the fill model's checksum and runs it on a test
picture, runs the unit tests and Android lint, builds the **unsigned** release APK and uploads it.
The APK is then signed off-GitHub with Steven's key. Signing key backup: Google Drive folder
"Hybrid Photo Assist - Signing Key" (the key is never committed or stored in GitHub).
