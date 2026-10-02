# Download (TikTok 47.1.4)

How the app decides whether a download is allowed and whether the file is watermarked, and what this
patch does about it.

## The app decides with one object

`com.ss.android.ugc.aweme.feed.model.ACLCommonShare` carries the whole decision:

| Getter | Carries |
|---|---|
| `getCode()` | the restriction on downloading |
| `getShowType()` | how the download entry is offered |
| `getTranscode()` | whether the file is watermarked on its way out |

All three are real-named on a real-named class, which makes them the cheapest anchors this bundle
has. Answering them is the whole patch: the app then offers its own row and downloads the file
itself, with its own progress and filename, for every kind of content.

## Where the approach comes from

It is the one ReVanced's TikTok download patch uses (patch `tiktok/interaction/downloads` at
https://gitlab.com/ReVanced/revanced-patches, GPLv3): `getCode()` answered 0, `getShowType()` answered
2, and `getTranscode()` answered 1. That patch targets 36.5.4; all three getters are still present,
still real-named, and still two instructions each on 47.1.4, which was checked before adopting it.

See **Credits** below for the full attribution.

## Anchors

| Anchor | Matched on |
|---|---|
| `ACLCommonShare#getCode()` | real-named getter, `() -> int` |
| `ACLCommonShare#getShowType()` | real-named getter, `() -> int` |
| `ACLCommonShare#getTranscode()` | real-named getter, `() -> int` |
| `Video#getDownloadAddr()` | real-named getter on the real-named model |

## What the patch does

1. **`getCode()`** answers 0 while the feature is on, which lifts the download restriction.
2. **`getShowType()`** answers 2, which is the unrestricted way the entry is offered.
3. **`getTranscode()`** answers 1 while the no-watermark part is on, which is the flag the app reads
   as "leave the file alone".
4. **`Video#getDownloadAddr()`** is redirected to the highest bitrate playback variant the item
   carries, so the file the app fetches is the best one it has rather than the watermarked address.

The first three are the reference approach; the fourth is this bundle's addition, and its log line
(`download: N variant(s), best X bps`) says whether the item carried variants at all.

## Two approaches that were tried first, and lost

Recorded because both looked reasonable and both were wrong.

**Hooking the share sheet's download handler.** The row is contributed by
`AwemePhotoDownloadShareAbilityHandler`, and a video never gets it. Widening that handler's
availability gate and advertised content types produced nothing: the log showed the gate was never
asked, because a sheet is built from a `ShareConfiguration` chosen per package and a video's
configuration has no download in it at all. The photo post's configuration
(`AwemePhotoDownloadShareConfiguration`) is the one that overrides the protocols carrying the row.

**Borrowing the photo configuration's protocols in the video's configuration.** This did put a row on
screen, and the extension then fetched the file itself. It failed on three counts: the fetch could
not tell which of the app's addresses were watermarked, so the saved file still had one; the bitrate
variants were not where they were expected, so the file was ordinary quality; and the handler's
action is a Kotlin suspend function the sheet also uses while it assembles itself, so returning early
from it left the panel with a third of its rows.

The lesson is the one the reference implementation already embodied: do not rebuild the app's
download, and do not answer a question the app answers elsewhere.

## Still open

- Best quality picks the highest quality class by the gear name (`original_*` first, then the class),
  not by the bitrate field, which is not trustworthy: an original variant of one item reported
  87 Mbps, and a variant with the field unset would have been skipped. The log prints every variant
  it saw, so the item's ceiling is visible (`download: 9 variant(s), best adapt_lower_720_1/720 of
  [...]`): an item carrying only 720p variants has nothing higher to take, and no rule here can raise
  it.
- Some items carry no variants at all (`no bitrate variants on the video`), and the app's own address
  is used for those.
- Nothing in this feature has been verified as correct on a device yet.

## Credits

The three ACL hooks this patch is built on are adapted from **ReVanced Patches** (GPLv3),
<https://gitlab.com/ReVanced/revanced-patches>, patch `tiktok/interaction/downloads`. The values it
answers them with are that project's; this bundle adds the `Video#getDownloadAddr()` redirect to the
highest quality variant, the settings switches, and the hooks that lift the restriction per surface.
This bundle is GPLv3, the same licence, so the reuse is permitted and the credit above is a courtesy
rather than a licence condition.
