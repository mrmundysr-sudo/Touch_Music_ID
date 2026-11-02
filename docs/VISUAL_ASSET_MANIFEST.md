# Visual Asset Manifest

Touch Music ID is built so its look can be replaced with the studio's custom **720×1600 portrait** artwork **without
changing any screen logic**. This file is the contract. It is checked against the code: the table below is generated from
`design/AssetSlots.kt` (`AssetSlot` enum), and a unit test fails if a slot is added without being documented here.

## The rule
> Artwork is decoration. It never carries behaviour, navigation, text the user must read, or accessibility labels.

* Buttons, tabs, titles, status text, the DEMO labels and every `contentDescription` come from code.
* A background image never contains buttons or navigation. A control image (Listen button, nav icons) is only the *paint*:
  the tap target, size, ripple and semantics are owned by code.
* Every slot has a built-in default drawn in code, so the app is complete with **zero** image files.
* The Listen pulse rings, waveform animation, progress ring and the legibility scrim are drawn in code on top of/around art.

## How to replace a slot (no code change)
1. Export the image at the size in the table (PNG, sRGB; transparent where the table says so).
2. Name it exactly as in the *File* column, e.g. `slot_bg_listen.png`.
3. Put it in `app/src/main/res/drawable-nodpi/`. (`.webp` and vector `.xml` drawables with the same name also work.)
4. Build. If the drawable exists it is used; if it is absent the default is drawn. Delete the file to revert.

`res/raw/keep.xml` stops resource shrinking from removing slot files, because they are looked up by name at runtime.

## Slots
| Slot | File (`res/drawable-nodpi/`) | Size (px) | Format | Used for |
|---|---|---|---|---|
| `BG_LISTEN` | `slot_bg_listen.png` | 720×1600 | opaque | Full-screen background of the Listen screen |
| `BG_RESULT` | `slot_bg_result.png` | 720×1600 | opaque | Full-screen background of the result/discovery screen |
| `BG_LIBRARY` | `slot_bg_library.png` | 720×1600 | opaque | Background of History and Favorites |
| `BG_SETTINGS` | `slot_bg_settings.png` | 720×1600 | opaque | Background of Settings and Help |
| `LOGO` | `slot_logo.png` | 640×200 | transparent PNG | Wordmark/logo at the top of the Listen screen |
| `LISTEN_IDLE` | `slot_listen_idle.png` | 512×512 | transparent PNG | Round Listen control, resting state (shown at 168dp) |
| `LISTEN_ACTIVE` | `slot_listen_active.png` | 512×512 | transparent PNG | Round Listen control while listening/processing |
| `WAVEFORM` | `slot_waveform.png` | 720×200 | transparent PNG | Static waveform overlay; replaces the animated default if present |
| `RESULTS_FRAME` | `slot_results_frame.png` | 720×720 | transparent PNG | Decorative frame drawn over the album-art container |
| `ALBUM_PLACEHOLDER` | `slot_album_placeholder.png` | 512×512 | opaque | Album-art placeholder (no remote artwork is bundled) |
| `HISTORY_TILE` | `slot_history_tile.png` | 720×176 | transparent PNG | Background of one History/Favorites row (stretched horizontally) |
| `NAV_LISTEN` | `slot_nav_listen.png` | 96×96 | transparent PNG | Bottom-nav icon: Listen (unselected) |
| `NAV_LISTEN_ON` | `slot_nav_listen_on.png` | 96×96 | transparent PNG | Bottom-nav icon: Listen (selected) |
| `NAV_HISTORY` | `slot_nav_history.png` | 96×96 | transparent PNG | Bottom-nav icon: History (unselected) |
| `NAV_HISTORY_ON` | `slot_nav_history_on.png` | 96×96 | transparent PNG | Bottom-nav icon: History (selected) |
| `NAV_FAVORITES` | `slot_nav_favorites.png` | 96×96 | transparent PNG | Bottom-nav icon: Favorites (unselected) |
| `NAV_FAVORITES_ON` | `slot_nav_favorites_on.png` | 96×96 | transparent PNG | Bottom-nav icon: Favorites (selected) |
| `NAV_SETTINGS` | `slot_nav_settings.png` | 96×96 | transparent PNG | Bottom-nav icon: Settings (unselected) |
| `NAV_SETTINGS_ON` | `slot_nav_settings_on.png` | 96×96 | transparent PNG | Bottom-nav icon: Settings (selected) |
| `EMPTY_HISTORY` | `slot_empty_history.png` | 560×560 | transparent PNG | Empty-state illustration for History |
| `EMPTY_FAVORITES` | `slot_empty_favorites.png` | 560×560 | transparent PNG | Empty-state illustration for Favorites |
| `EMPTY_NO_ARTICLE` | `slot_empty_no_article.png` | 560×560 | transparent PNG | Illustration shown when no verified Wikipedia article exists |

All sizes are for a **720 px wide** design canvas (xhdpi, 360 dp). Phones taller or shorter than 9:20 are handled by
cropping backgrounds (`ContentScale.Crop`, centre-anchored), so keep the important art inside the **safe area** below.

## Layout guidance for 720×1600 backgrounds
* **Safe area:** keep key artwork inside x 40–680, y 160–1400. The top ~120 px sits under the status bar; the bottom ~170 px
  sits under the bottom navigation bar. Screens centre-crop on very tall or very short phones.
* **Legibility:** the app lays a dark gradient scrim over every background (`ScrimTop`/`ScrimBottom` in `design/Tokens.kt`).
  Art can be bright; text stays readable. Tune the scrim in tokens, not in the image.
* **No baked UI:** do not paint buttons, tab bars, text, the logo wordmark (use `slot_logo`), or the Listen control.
* **Listen control (512×512):** circular art centred in a square; it is shown at 168 dp and clipped to a circle. Keep
  the glow inside the circle or it will be cropped. Provide `slot_listen_active` for the listening/processing look.
* **Nav icons (96×96):** single-colour or full-colour; provide `_on` variants for the selected tab. Without `_on`,
  the default selected icon is used for that tab only.
* **History tile (720×176):** stretched horizontally to the row width and clipped to rounded corners. Avoid text/detail
  that would distort when stretched; use a subtle texture or gradient.
* **Album art:** no album artwork is bundled (copyright). `slot_album_placeholder` is only the placeholder shown until
  artwork support is added. `slot_results_frame` is drawn on top of whatever is in the art container.

## Changing colours, spacing, type, motion
`design/Tokens.kt` (colours, dimensions, motion timings) and `design/Theme.kt` (typography). Nothing in the screens hard-codes these.

## Not replaceable by image (by design)
Launcher icon is a vector in `res/drawable/ic_launcher_foreground.xml` and `ic_launcher_background.xml`; replace those files
(or add `mipmap-*` PNGs) following Android adaptive-icon rules (108 dp canvas, 66 dp safe zone). Splash uses the same foreground.

## Verifying a swap
1. Drop in a file, run `./gradlew testDebugUnitTest` (the UI-flow tests still pass: they don't depend on artwork).
2. Regenerate screenshots (written to `app/build/screenshots/`) and compare with `docs/screenshots/`.
