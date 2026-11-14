# MemoryLink app

MemoryLink turns an Android tablet into a photo frame that you fill and control
from your own phone or computer, through
[memorylink-server](https://github.com/rsnel/memorylink-server). The person who
has the frame doesn't need to do anything: it starts by itself and shows photos.

**Website:** https://rsnel.github.io/memorylink-app/

- Requires Android 7.0 or newer.
- Swipe left/right on the frame for the next/previous photo, pinch for a
  2×2 to 8×8 overview, tap a photo to open it. This also works without a
  server: the frame keeps a copy of every photo. The slideshow returns after
  3 minutes without a touch, counted only while the server is reachable.
  In the overview, tag chips at the top narrow the photos to one tag; a photo
  opened from the overview has a back arrow.
- Works in portrait and landscape; the splash screen (with the last photo in
  its frame) matches how the tablet is held.
- Debug builds are available as artifacts of the
  [GitHub Actions runs](https://github.com/rsnel/memorylink-app/actions).
- On first start, enter the HTTPS address of your server and choose
  **Set as Home app**.

The website lives in [`docs/`](docs/) and is served by GitHub Pages
(Settings → Pages → Deploy from a branch → `main` / `docs`).

License: GNU GPL v3.
