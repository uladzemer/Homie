# Performance pass — 2026-10-29

Implemented on branch `perf`, without new dependencies. Compilation and JVM tests pass.
Frame times, GPU load, Android focus traversal and actual bitmap residency still need verification
on the owner's box; no device performance measurements are claimed here.

## Changes

1. **HomeScreen.kt / HomeLayout.kt:** foundation `LazyColumn` and `LazyRow` replace eager scrolling
   containers. User rows use row IDs, grid lines use their first package, and tiles use package keys.
   The existing dock backing, spacing, grid sizing, labels, menu and move controls remain.
   `config.placed`, filtering, sorting, grid chunks and package positions are remembered per change.
   Each row keeps its scroll state and last focused package even while its composition is absent.
2. **CarouselScreen.kt:** animated transforms are read in `graphicsLayer`, overlap order in placement,
   with derived state updating
   the visible window and center only when their values change. Covers beyond a viewport-sized
   window are omitted. Reflections use a flipped image and a floor-colored gradient, without
   explicit offscreen compositing or `DstIn`; only the five central covers have reflections.
   Shelf slide/fade transitions remain, with viewport culling on both shelves and no reflections
   or focused motion on the outgoing shelf. Both visible windows briefly exist during transitions
   to preserve the existing animation. Static backdrop gradient brushes are cached.
3. **InstalledApps.kt / WallpaperSize.kt / MainActivity.kt:** wallpaper bounds are read first;
   `inSampleSize`, density scaling and `RGB_565` decode for the actual display metrics, retaining
   enough pixels for Crop and avoiding enlargement of small sources. A 1920×1080 JPEG uses about
   1.76 MiB of pixel storage at 720p instead of 3.96 MiB, excluding decoder/GPU overhead.
   The UI drops its previous bitmap reference after the 1.5-second crossfade or cancellation.
   Loading is keyed by photo and screen size, without a resume/reload counter; the ten-minute
   rotation remains. Activity recreation can still decode again, as expected.
4. **Motion.kt:** losing focus cancels tilt/sheen work and resets it; press motion requires focus.
   Lift/press release animations still settle smoothly. Motion stays in layer/draw lambdas.
   Gloss gradient brushes are cached and translated during drawing, instead of recreated per frame.
   The sheen was already a finite animation; the only infinite transition is move-mode wiggle,
   composed only while active. **UiKit.kt was audited and needs no edit:** controls already use
   `focusLift`, with one shadow layer and no idle infinite focus animation.

## Focus behavior and risks

- `HorizontalPivot` / `VerticalPivot` and the row's Left/Right `onExit` cancellation remain.
  Horizontal beyond-bounds focus search stays with foundation LazyRow.
- Home, menu return and move restoration scroll to an absent row/tile with `scrollToItem`, wait
  a frame, then request focus by package. Requests wait for the menu to close. Removed/hidden
  packages fall back to the first visible tile, or Settings on an empty home screen.
- Vertical navigation is explicit because lazy rows may not be composed yet: like the old
  non-lazy screen, it picks the tile closest by screen column in the next nonempty row (empty
  rows are skipped). Up from the first nonempty row uses native search into StatusBar; Down from
  StatusBar goes to the first nonempty row.
- Repeated vertical keys are consumed while a focus request is awaiting layout, so repeats do
  not continually cancel that request. This needs a held-key check on a slow box.
- Highest risks: lazy beyond-bounds horizontal search, moving the last tile out of a row,
  rebuilding grid lines after hiding/removing apps, and Home/menu requests during rapid input.
  Offscreen restoration can visibly jump before pivot scrolling settles. Unit tests cover the
  topology and package fallback, not Android's focus engine or on-device clipping.
- Reflections intentionally differ: an opaque fade overlay instead of an alpha mask, drawn only
  on the selected shelf; the highlight, motion and gloss also apply only to the selected shelf.
  Check their fade, the cover overlap order while holding Left/Right, and viewport edges at
  Small/Huge sizes.

## Owner checklist — D-pad on a real box

Use enough installed apps to fill several screens horizontally and vertically, including an empty
row and a row with one tile. Repeat navigation with Small and Huge tiles, at 720p and 1080p if available.

- Press **Home** from a far-right tile, a deep row, an open menu and move mode: menus/move mode close,
  the first tile becomes visible and focused. With no visible apps, Settings receives focus.
- Hold **Right** through a long row, then press Right again at its end. Repeat with **Left**.
  The row scrolls around the existing pivot; focus stops at its ends and never jumps to StatusBar.
- Focus a nonfirst tile, press **Down**, move sideways there, then press **Up**. Each row restores
  its last tile. Travel several rows away and back: restoration also works after offscreen disposal.
  Empty rows are skipped; the last row stops Down navigation.
- From the first nonempty row press **Up**: focus reaches the status buttons. Use **Left/Right**
  across Network, Power and Settings. Press **Down** from each: the first row regains focus.
- Hold **OK**, then release it: the tile menu stays open without launching anything. Close with
  **Back**, and separately choose **Cancel**: the same tile regains focus. Test the **Menu** key too.
- Choose **Move with arrows**. Hold **Left/Right** past the viewport; focus/wiggle follow the tile.
  Move **Up/Down** into empty and populated rows, including moving a source row's last tile.
  Finish with **OK** and separately **Back**; normal navigation and launch must work afterward.
- From the menu, move a tile into an offscreen row and add an unplaced app to a **new row**.
  The destination scrolls into view and that package receives focus.
- Remove a tile, hide an unplaced app and remove the last visible app. The grid closes its gaps,
  a valid tile (or Settings) receives focus, and the next arrow works without a lost-focus state.
- In **Classic**, hold **Left/Right**, then rapidly alternate **Up/Down** between shelves. Selection,
  launch, long OK, move mode, shelf transitions and cover overlap must still work. Check that
  covers appear cleanly at viewport edges and central reflections fade into the floor.
- Choose a fixed photo, change it rapidly, then enable rotating photos and wait **ten minutes**.
  Transitions finish without a retained old image. Launch an app and return several times before
  the next rotation: the same photo stays, without a new fade/decode caused by resume.
- Leave focus still for **30 seconds**, then move around for several minutes. Tilt/sheen settle;
  only the moving tile wiggles. Compare scrolling stutter with the prior build. If profiling is
  available, compare frame times, allocations and memory after crossfades settle; memory should
  not grow with visited rows or successive photos.

## Checks

Windows wrapper equivalent of `./gradlew :app:testDebugUnitTest :app:assembleDebug`:

```text
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

40 tests, zero failures/errors. Five new tests cover row filtering/keys, focus restoration and
fallback, empty-row navigation/move destinations, 720p/1080p sampling, Crop and small source images.
`git diff --check` passes. No new dependencies or build configuration changes.

Build tail:

```text
> Task :app:mergeDebugGlobalSynthetics
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:assembleDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 16s
42 actionable tasks: 8 executed, 34 up-to-date
Configuration cache entry reused.
```

APK: `app/build/outputs/apk/debug/homie-0.4.0-debug.apk`.

Remaining verification: the owner checklist and real-box frame-time/GPU/memory measurements.
