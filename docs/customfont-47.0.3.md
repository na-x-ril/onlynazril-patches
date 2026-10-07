# Custom font (TikTok)

How TikTok picks the face it draws text with, what the patch does about it,
and what was checked on every version before the patch was written.

## One factory resolves every face

TikTok bundles two families of its own faces, TikTok Display and TikTok
Text, and resolves every text style through one static factory. A request
carries a weight, a style id, an optional `Float` weight and a map of
variation axes; on API 26+ the factory builds a `FontVariationAxis` array
(`wght`, `wdth`, `slnt`, `opsz`) and asks the platform's `Typeface` builder,
and when the platform has none it falls back to the bundled static faces,
picked by weight:

| Request | Face served |
|---|---|
| weight > 600 | `font/TikTok-Display-Bold.otf` / `TikTok-Text-Bold.otf` |
| weight < 450 | `font/TikTok-Display-Regular.otf` / `TikTok-Text-Regular.otf` |
| otherwise | `font/TikTok-Display-Medium.otf` / `TikTok-Text-Medium.otf` |

The style id is a weight-class selector, not a `Typeface` style bitmask: the
chain above the factory maps a weight to an id (`700 → 2`, `500 → 7`,
anything else → `1`) before asking. That is why the patch answers with the
file's face as it is: one file carries one face, so a style that needs a
heavier one draws in the file's own.

## The factory, per version

The class and its method names are obfuscated and change with every build,
so the shape was checked on every version available here before anything was
written down:

| Version | Class | Factory | Callers |
|---|---|---|---|
| 46.2.3 | `X.0Wa5` | `LIZIZ(F,I,F,F,Float,F,I,Map,I)→Typeface` | 141 |
| 46.3.3 | `X.0VkW` | same | 141 |
| 46.5.3 | `X.05oe` | same | 140 |
| 46.9.3 | `X.05jr` | same | 149 |
| 47.0.3 | `X.0lNd` | same | 152 |
| 47.1.4 | `X.05o5` | same | 154 |

Every version carries the same six bundled font files inside the factory
method itself, and every other `Typeface` factory in the bundle takes fewer
parameters, so the pair of facts (a class that reads the bundled faces and
carries the nine-parameter resolver) is unique without a name.

Above the factory, the chain is `LX/0h54;` (the app's styling utility, 102
callers: view constructors, paints, the live SDK's gift cells) →
`LX/0h4u;` (the font provider held by `LX/0G29;->LIZ`) → `LX/0lNd;->LIZ` →
the factory. Hooking the factory catches all of it in one place, including
the callers that go straight to it.

## What the patch does

1. Discovers the factory holder by that shape, and fails with a
   `PatchException` naming the candidates when it is not exactly one.
2. Hooks the factory to answer with the imported font while the Tweaks
   switch says so. The answer comes from `CustomFontBridge#typeface`,
   which reads `CustomFontSettings` (`custom_font_enabled`, off by
   default) and loads the selected font from the app's own
   `files/fonts/` directory (readable without a permission, and
   beside the log the extension already writes there). The face is
   cached against the file's timestamp, because the factory is asked
   for a face on every text the app lays out.
3. Hooks `BaseActivity#onActivityResult` (the foundation activity
   delivers every activity result through it, and the Tweaks screen's
   activity inherits it), so the document picker's answer arrives at
   `CustomFontBridge#onActivityResult`, which keeps only its own
   request code and copies the picked file into `files/fonts/`.
4. Answers `null` while the switch is off or nothing is imported, so
   the app's own resolution runs as it always did.

The patcher only rewrites the app's own classes, so the hook sits at the
app's last shared step rather than at `Typeface` itself: a face the framework
creates on its own (an inflated default) is untouched, and everything the app
resolves through the factory is served the file's face.

## Using it

Tweaks → Custom font: the switch, an **Import a font file** row that
opens the system's document picker (`.ttf`/`.otf`), and a list of the
imported fonts. A name runs at most seven tenths of the row's width,
so a long one runs into an ellipsis instead of pushing the trailing
controls out of view. Tapping a row picks the face the app draws with.
Each row carries a **Delete** button for that one font, and the bottom
of the list carries a **Clear all fonts** button (behind a confirm)
for all of them; deleting the font in use moves the selection to the
newest import left. A font that will not load is deleted again and
never enters the list. Every change (an import, a pick, a delete)
asks for a restart, because the text already on screen was laid out
with the old face.

## What it cannot do

Keep the weights. The factory's callers ask for faces by weight, and one file
carries one face: a bold or medium style draws in the file's own face rather
than a heavier one. The app keeps its own text metrics, so layout is the
file's metrics from then on: a file with very different metrics will move
text.

## Verified

- `tools/dexprobe/run.sh VerifyAnchors "<apk>"` checks the factory's shape on
  every release (`requireCustomFontFactory`) and the activity-result anchor
  (`requireBaseActivityOnActivityResult`), mirroring the patch's discovery
  exactly.
- The per-version table above came from a method-level scan for the bundled
  font strings on every APK, then the nine-parameter resolver shape check on
  each class that reads one, run against 46.2.3, 46.3.3, 46.5.3, 46.9.3,
  47.0.3 and 47.1.4.
- `BaseActivity#onActivityResult(I,I,Intent)→V` was read off both supported
  builds (47.0.3 and 47.1.4): the class is a ByteDance foundation class
  that keeps its name, and the signature is the framework's own. It does
  not exist on 46.x, where the settings activity itself does not exist
  either. The patch only claims compatibility with 47.0.3 and 47.1.4.
- The style-id mapping was read out of the chain above the factory
  (`LX/0h54;->LIZJ` compares the weight against 700 and 500 before mapping
  it to an id), which is what settled the bridge answering with the plain
  face instead of a styled derivative.
