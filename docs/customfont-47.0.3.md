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

## The second path: Jetpack Compose

The factory is not the only way to a face. Jetpack Compose builds its own
`FontFamily` resolver instead of calling it:
`androidx.compose.ui.platform.AndroidComposeView` installs that resolver in
its constructor (and refreshes it on a configuration change), the resolver
resolves a `FontFamily` request through the app's own loader, and the loader
asks a second obfuscated class for the Typeface. That class is shaped
differently from the factory - an instance method taking the Context and the
app's font request and answering the face - and it is found the same way: it
reads the same bundled TikTok faces.

| Version | Class | Loader | Compose reaches it through |
|---|---|---|---|
| 47.0.3 | `X.0lNf` | `LIZ(Context,LX/0lNm;)Typeface` | `AndroidComposeView` → `X.0lNo` (font provider) |
| 47.1.4 | `X.05vZ` | `LIZ(Context,LX/01fp;)Typeface` | `AndroidComposeView` → `X.00Vh` → `X.01fq` (Compose's own loader) |

Both versions carry the same body: on API 26+ a variable-font request builds
the `DRKM`, `wght`, `opsz`, `slnt` and `wdth` axes and asks the app's font
cache for the face, and a static request picks one of the six bundled files by
weight. The factory's own tail ends in the same cache, so the cache is the
one place both paths meet - but it is keyed by the file name and is also the
app's named-font cache, so the hook sits at the two resolvers that speak in
faces rather than at the cache.

Hooking only the factory leaves every Compose surface on TikTok's own text.
The Settings and privacy screen is one: its list is built by
`SettingsComposeRvmpFragment` into a `ComposeView`, so its text never passes
through the factory.

## The third path: Lynx

A Lynx view draws through Lynx's own `TypefaceCache`, which reads the face
from the asset manager without asking either resolver: `X.0Fgd#LJFF` builds
`font/<name><suffix>.otf` and hands it to the app's asset loader, the one
static `(AssetManager, path) -> Typeface` method whose body calls
`Typeface.createFromAsset`. That method renames every build - `X.0VKh#t4` on
47.0.3, `X.00kn#s4` on 47.1.4 - and every other `(AssetManager, String) ->
Typeface` method in the bundle has a different body, so it is unique.

Hooking it needs a guard the face resolvers do not: the same method is also
asked for sticker and editor asset fonts, which are not TikTok's own. The
bridge is handed the path and answers only `font/TikTok…`.

## The fourth path: hybrid (H5) pages

Activity Center, Manage posts and the Creator Center are not native at all:
`aweme://pns/activity_center` is routed by `PowerPageInterceptor` to
`activity_center.html`, an H5 page. Chromium draws it, so there is no Java
`Typeface` to answer; the app only serves the page its font bytes through a
WebView client. `FontLoadManager$init$1#shouldInterceptRequest` - the name is
kept on both versions - answers a font request (a `.woff`/`.woff2`/`.otf`
URL, a `font` content type, or one of the named endpoints the page's CSS asks
for) by mapping it to a bundled `font/TikTok…` file and returning a
`WebResourceResponse`. Both overloads sit on the class.

The hook answers a font request with the imported file's bytes instead, and
falls through for every other request, so a page that asks for no font keeps
working exactly as it did.

## What the patch does

1. Discovers the four entry points - the native factory, the Compose loader,
   the asset loader and the WebView interceptor - by their shapes, and fails
   with a `PatchException` naming the candidates when any is not exactly one
   (the interceptor's two overloads count as one class).
2. Hooks the factory and the Compose loader to answer with the imported font
   while the Tweaks switch says so. The answer comes from
   `CustomFontBridge#typeface`, which reads `CustomFontSettings`
   (`custom_font_enabled`, off by default) and loads the selected font from
   the app's own `files/fonts/` directory (readable without a permission, and
   beside the log the extension already writes there). The face is
   cached against the file's timestamp, because the factory is asked
   for a face on every text the app lays out.
3. Hooks the asset loader to answer `CustomFontBridge#typefaceFor` with the
   requested path: only a `font/TikTok…` path is answered, so a sticker's or
   a Lynx page's own asset font keeps the app's resolution.
4. Hooks the WebView interceptor to answer `CustomFontBridge#webFont` with the
   request: only a font request is answered, with a `WebResourceResponse`
   built from the imported file; everything else falls through.
5. Hooks `BaseActivity#onActivityResult` (the foundation activity
   delivers every activity result through it, and the Tweaks screen's
   activity inherits it), so the document picker's answer arrives at
   `CustomFontBridge#onActivityResult`, which keeps only its own
   request code and copies the picked file into `files/fonts/`.
6. Answers `null` while the switch is off or nothing is imported, so
   the app's own resolution runs as it always did.

The patcher only rewrites the app's own classes, so the hooks sit at the
app's own entry points rather than at `Typeface` itself: a face the framework
creates on its own (an inflated default) is untouched, and everything the app
resolves - or serves a page - through one of the four is served the file's
face.

## Using it

Tweaks → Custom font: the switch, an **Import a font file** row that
opens the system's document picker (`.ttf`/`.otf`), and a list of the
imported fonts. A name runs at most seven tenths of the row's width,
so a long one runs into an ellipsis instead of pushing the trailing
controls out of view. Tapping a row picks the face the app draws with.
The screen itself draws in that face too: it is built from the
extension's own views, so it asks `CustomFontSettings` for the face
directly (`ui/Fonts`) instead of passing through the app's resolvers.

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

Reach a face that no app-side point carries. Chromium's own fallback faces,
a Lynx or sticker font that is not TikTok's, and anything the app asks the
framework for directly are left alone on purpose: the asset-loader and
WebView hooks are guarded to TikTok's own faces and font requests.

## Verified

- `tools/dexprobe/run.sh VerifyAnchors "<apk>"` checks the factory's shape on
  every release (`requireCustomFontFactory`), the Compose loader's shape
  (`requireComposeFontLoader`), the asset loader's
  (`requireAssetFontLoader`), the WebView interceptor's
  (`requireWebFontInterceptor`) and the activity-result anchor
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
