# The OriginOS look — what was measured, and what the app does with it

An app that changes how a phone behaves should look like it belongs on that phone. This
note records what the official OriginOS 6 interface actually looks like — from vivo's own
material, measured rather than remembered — and which of those rules this app implements,
with the source file for each one. It also records what was **not** established, because a
design note that only lists conclusions is a mood board.

---

## 1. Sources

| Source | What it is good for |
| --- | --- |
| [vivo.com/en/originos](https://www.vivo.com/en/originos) — *OriginOS 6*, fetched 2026-09-17 | The platform's own design vocabulary, and the one sentence about corners |
| [Origin OS](https://en.wikipedia.org/wiki/Origin_OS) (Wikipedia) | The names for the launcher's pieces: the Klotski Grid, and *atomic components* |
| [`scripts/design-probe.html`](../scripts/design-probe.html) | The instrument: it fetches vivo's plates and measures them in the browser |

vivo's page is worth quoting for four phrases, because the app implements three of them:

> **Space system** — "a three-dimensional digital world that makes the interface more
> organized and textures more lifelike"
>
> **Translucent color** — "translucence in every detail… adding depth while keeping text
> and icons crisp, clear, and easy to read"
>
> **Card stacking** — "the new stacked card layout makes smarter use of spatial depth"
>
> "From **soft G2-rounded corners** to **structured card layouts**, from polished icons to
> precise data charts, every element is carefully crafted."

**G2** is the load-bearing word. It means curvature-continuous: a quarter circle meets the
straight edge at a tangent where curvature jumps from `0` to `1/r`, which the eye reads as
a corner filed off a rectangle; a G2 corner eases into the edge instead. That is the single
largest difference between this app's surfaces and stock Material's, and it is why
`ui/theme/G2Shape.kt` exists rather than a `RoundedCornerShape`.

The other two sources that matter are the plates themselves — the images vivo ships on that
page — and the home-screen capture on the Wikipedia article. The probe measures two of them.

---

## 2. What was measured

Method: a panel is a *flat, bright, connected* region — brighter than the plate's
background and without the local gradient a photograph has. Once its bounding box is found,
the corner is fitted with a circle by least squares across the corner's rows, averaged over
all four corners. `scripts/design-probe.html` draws the fitted arcs back over the crop at
3×, so a wrong number is visible rather than merely suspect.

| Element | Plate | Panel | Fitted radius | ÷ width | ÷ height |
| --- | --- | --- | --- | --- | --- |
| 2-column atomic component (*Device battery*) | home capture, 212×471 | 77×84 px | 10.0 px | **0.130** | 0.119 |
| System-app panel (*Private space*) | `pad/vivo-originos-6-with-private-space-1.png` | 165×62 px | 16.5 px | **0.100** | 0.266 |

Read in device units: the home capture is 212 px wide for a screen that is 1080 px (360 dp)
on the real phone, so 1 dp ≈ 0.59 px. The atomic component is therefore about 131 × 143 dp
with a corner of roughly **17 dp**.

### What that does and does not establish

- **Established.** Corners on real OriginOS cards span roughly **a tenth to an eighth of the
  element's own width** — `0.100` and `0.130` in the two samples that converged.
- **Established, as a floor.** Both numbers come from a *circular* fit, and a circular fit
  systematically under-reports a G2 corner: on the diagonal a G2 corner sits `0.225·r` from
  the corner point where a circle sits `0.414·r`, so the radius the design was drawn with is
  larger than the number fitted here — up to about 1.8× larger if the fit matched on the
  diagonal. Treat 17 dp as a lower bound.
- **Not established.** Both samples are cards about a third of the screen wide. Extrapolating
  the ratio to a full-bleed card would give 34–44 dp, which is far rounder than any system
  panel in those plates looks, and there is no full-bleed card in the sample to check it
  against. This is why the app does **not** scale its radius with width: it uses the absolute
  band the samples measure in dp.
- **Not established.** Three windows the probe could not measure, kept in the file as a
  comment rather than given a number: the media panel of `pad/vivo-originos-6-with-origin-island-function.png`
  (dark panel over an orange gradient — the flat-and-bright detector finds the wallpaper),
  the gallery tiles of `…immersive-photo-wall-mode.png` (tiles touch, so the connected
  component merges a whole row), and the launcher icons in the home capture (they sit on snow
  and sky, which is flatter and brighter than they are).

Two samples are evidence, not a specification. The app's radii are therefore *pinned to* the
measurement and then rounded to values that keep the existing layout working — see below.

---

## 3. The rules, and where they live

### G2 corners on every surface — `ui/theme/G2Shape.kt`, `ui/theme/Theme.kt`

`G2CornerShape` is a `CornerBasedShape` that draws each corner as a superellipse of exponent
4 (`|x|ⁿ + |y|ⁿ = rⁿ`), sampled at 24 points, with the same tangent points a circular corner
of radius `r` would have. Because a G2 corner reads *tighter* than a circle of the same
radius, the theme's numbers are a step above Material's:

| Token | Material default | Here | Used by |
| --- | --- | --- | --- |
| `extraSmall` | 4 dp | 12 dp | chips, small containers |
| `small` | 8 dp | 16 dp | badges, dense rows |
| `medium` | 12 dp | 24 dp | every `Card`, so most of the app |
| `large` | 16 dp | 30 dp | hero panels |
| `extraLarge` | 28 dp | 38 dp | dialogs, bottom sheets |

`medium` at 24 dp is the measured band (17 dp floor, ×~1.4 for the G2 correction) rounded to
a dp value the layout already used. The maths is unit-tested in `WidgetStateTest`, including
the property that *makes* it G2: the curve must sit `0.225·r` from the corner point on the
diagonal, against a circle's `0.414·r`.

### Translucent panels, hairline edges, no shadows — `ui/components/Components.kt`

"Translucent color" is why the app's panels are translucent fills (`surfaceContainer`) closed
by a 1 dp `outlineVariant` hairline, with `elevation = 0`: a drop shadow reads as stock
Material, and OriginOS surfaces are flat and outlined. The aura behind the page — two soft
radial glows, warm top-right and cool bottom-left — is the printable stand-in for an OriginOS
wallpaper, and it is faint enough to read as lighting on the panels rather than as a colour.

### Exactly one accent — `ui/theme/Theme.kt`

Controls are a single flat blue (`#1A6DFF` light, `#5B9DFF` dark) plus an error red plus the
three-step risk ramp. The brand sweep — the ring's six hues — is reserved for *identity*: the
wordmark, its underline, the mark itself. Nothing else competes, because that is how OriginOS
builds its own screens.

### Structured cards, and the header — `ui/screens/*`, `ui/AppNav.kt`

Every screen is a stack of titled cards with 16 dp of internal padding, and the header is the
system's own arrangement: the page name on the left, the product mark on the right, a hairline
under it. Both bars are transparent so the aura runs from the status bar to the gesture bar.

### The home-screen components — `widget/`

The app ships two *atomic components*, which is what OriginOS calls a home-screen widget:

| Component | Size | What it does |
| --- | --- | --- |
| Refresh rate | 2×2 | Shows the panel maximum, and a chip that pins/releases `force-max-refresh-rate` through the engine — journal and all |
| Status | 4×2 | Model, Android version, density, panel rate, applied count, access level — read-only |

They are `RemoteViews`, not Compose or Glance: a widget is inflated by the launcher, Compose
cannot draw there, and Glance would add a dependency to a tree this project keeps deliberately
lean.

**The one thing they cannot reproduce is the corner.** `RemoteViews` carries a layout and a
shape drawable, never a path, so the widget's panel is a `<shape>` with a circular
`<corners>` radius — set to 26 dp, the top of the measured band, precisely because a circular
corner has to be noticeably larger than a G2 one before it looks as round. The two shapes are
not identical; they are as close as the platform allows a widget to get, and the difference is
documented here rather than hidden.

### The launcher icon — `res/drawable/ic_launcher_*.xml`

The icon is an adaptive icon, so the *mask* is the launcher's, not the app's: the foreground
is drawn inside the 66 dp safe circle, which is why it survives whatever squircle OriginOS
applies. The monochrome layer gives Android 13+ themed icons a real silhouette instead of a
grey blob.

### Typography — nothing to do

vivo Sans is a free download from vivo, but it does not need bundling: on an OriginOS phone it
is the system font, so the app already renders in it. Shipping a copy would add a megabyte to
the APK to change the typeface on the phones where the app is *least* relevant.

---

## 4. Deliberately out of scope

- **Motion.** OriginOS 6's spring, morphing, one-shot and gradient-blur transitions are
  system-level effects; a toolkit that mixes its own 300 ms springs into them would feel like
  a foreign object, and none of them can be reproduced from an app anyway.
- **Reshaping other apps' icons.** The Klotski Grid is launcher behaviour; nothing an app can
  reach, with or without Shizuku, changes another package's icon.
- **Rewriting the system UI.** Same reason. This project changes settings and packages, and
  stops there.

---

## 5. Re-running the measurement

```console
$ start scripts/design-probe.html      # macOS: open, Linux: xdg-open
```

It fetches the plates from vivo's CDN in the browser, prints the fitted radius per corner,
and draws the arcs back over the crop. If you add a window, remember the detector finds only
*flat and bright* panels — a dark panel over a gradient needs a different instrument, which is
what the commented-out entries in `SPECS` record.
