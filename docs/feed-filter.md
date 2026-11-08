# Feed filter

The `Feed filter` patch removes ads, promotional-music posts, and videos outside a view/like range
from the feed. It is a bytecode patch plus the `extensions/tiktok/feedfilter` code.

Version scope: 47.7.1 and 47.7.2.

## What is filtered where

Two filter sets run, because the lists are not all the feed:

| Surface | Filter | Why |
|---|---|---|
| Feed API return | ads + views + likes | the main feed |
| Feed pager storage | ads + views + likes | the list the feed renders from |
| `FeedItemList#setItems` / `#clone` | ads only | shared with profile, search and repost lists; a count range there would hide the profile owner's own posts |
| Feed panel insert callback | ads only | also carries live and survey cards |

The generic model being ads-only is the fix for "my own posts are missing from my profile". The
feed's own hooks still apply the view/like ranges.

## Why the storage lists are wrapped

The ad SDK can add to the pager's live `ArrayList` directly, without calling a setter or an insert
callback. Each storage's `ArrayList` field, and the adapter model's list behind it, is replaced at
construction with `AdFreeList`: an `ArrayList` subclass whose `add`, `addAll` and `set` refuse an
item the ad filter recognises. The field is found through the class hierarchy, because the actual
instance is often a subclass of the class that declares it.

The storage's `getList()` returns a copy, so it is never used to find the live field.

## Deep links and single items

A batch with one item is kept unless that item is an ad. Opening a shared video inserts a
single-item batch; removing it left the pager empty and the link could not open. A lone ad still
goes through the normal filter.

## Alternatives considered

The live-list wrapper is not the only possible design. It is the one that survived the paths seen
on 47.7.x:

| Approach | Strength | Cost |
|---|---|---|
| Block at the ad service or request | One clean point | No stable anchor; the SDK has many paths and AB flags |
| Hook every insert callback | Not invasive | Many insert paths, and they change between builds; this is what let ads through |
| Filter where the list is read | Simple | The pager count shifts, so it runs to *No more results* while items remain |
| Hide the cell at bind time | Always looks gone | Leaves an empty page; the pager still counts the ad |
| **Hook inserts and wrap the live lists** | Catches a direct `add` too, including paths no callback sees | Reflection into obfuscated fields; slightly invasive; fields must be found through subclasses |

The wrapper is the last line, not the only line. The fetch, page-model and panel-insert hooks stay
because each build reaches the list by a different route.

The weak point is field discovery: if TikTok renames the storage or adapter-model class, the wrapper
may not install. The log makes that visible: `storage list not wrapped for …` means the guard was
not placed, while `storage list wrapped field=…` means it was.

## Verifying on a device

```bash
adb logcat -d | grep 'feedfilter:'
```

Useful lines:

```
feedfilter: storage list wrapped field=09gj.LIZ size=0
feedfilter: storage refused ad@mastercard
feedfilter: generic-set ... ads=1 dropped=[ad@shopee_id]
feedfilter: store ... likes[2000..MAX]=1 dropped=[likes=343@user]
```

`09gf` / `09gj` are obfuscated and change per build; the line shape does not.

If an ad is still visible and no `storage refused` or `insert … ads=1` line appears for it, it is
rendered from a separate ad pager, not the feed's item lists. That needs a new hook at that pager,
not another list filter.
