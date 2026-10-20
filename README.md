# Hybrid Photo Assist

Private Android photo editor for Steven's Galaxy S24 Ultra. All AI runs on the phone.

- **Select:** tap a person or object (Google MediaPipe Interactive Segmenter); fix edges with Brush + / Brush −.
- **Erase:** removes the selection and fills the gap (LaMa fill model, Apache-2.0, downloaded once on first use, ~183 MB).
- **Move:** lifts the selection out, fills the hole, then drag / pinch to place and resize it.
- **Background:** keeps the selection and swaps the rest for blur, a solid colour, or another photo.
- Undo / redo, save to Pictures/Hybrid Photo Assist (original never overwritten).
- Photos larger than 4096 px on the long side (50 / 200 MP shots) are edited at 4096 px.

Build: GitHub Actions → "Build Hybrid Photo Assist" (manual). It checks the fill model on a test picture,
runs the unit tests, builds the signed release APK and uploads it as an artifact.
Signing key backup: Google Drive folder "Hybrid Photo Assist - Signing Key".
