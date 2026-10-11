# Comment copy, TikTok 47.0.3 and 47.1.4

TikTok puts the commenter's name in front of the comment's text before it copies it. The patch
leaves that name out, so the clipboard gets the comment's own text. Two routes do the joining, and
the patch answers both; which one a surface takes is read with
`tools/dexprobe/run.sh CommentCopyProbe "<apk>"`.

## Route one: the comment sheet's Copy (the builder)

The Copy in a comment's long-press panel builds its `ClipData` through one static method that takes
the three pieces apart:

```
Cls->LIZ(String prefix, String text, List emojiSpans) -> ClipData
```

It appends the prefix and the text, encodes custom emoji through the spans when the app's flag says
so, and hands the joined string to `ClipData.newPlainText` under the label `"copy_label"`. The patch
blanks the first argument at the top of that method, so the emoji encoding below is untouched and
reads the same prefix="" it would for a comment with no name.

| build | builder | called from (the Copy action) |
|---|---|---|
| 47.0.3 | `X.0jPl#LIZ` | `X.0ltS#LJIILLIIL` (logs `copy_comment`) |
| 47.1.4 | `X.19oe#LIZ` | `X.19jm#LJIILLIIL` (logs `copy_comment`) |

The caller confirms the argument order: it builds the prefix with
`getString(resId, [LX/0leu;->LJFF(user, ...)])`, reads `Comment#getText` and `Comment#getTextExtra`,
then calls `LIZ(prefix, text, spans)`. The builder's own prologue (`v8` = prefix, `v9` = text,
`v10` = spans) matches.

## Route two: the plain clipboard helper (Favorites → Comments, reply copy)

A second family of menus joins the name and the text itself and hands the single string to a plain
helper that wraps `ClipData.newPlainText`:

```
Cls->LIZ(String tag, String text, Context, Cert) -> void
```

| build | helper | comment-copy callers (read `Comment#getText` + `Comment#getUser`) |
|---|---|---|
| 47.0.3 | `X.0lTB#LIZ` | `X.0mPJ#LIZ(Context, Comment)`, `CommentCollectViewHolder#copy(Comment, Cert)`, `AwS436S0200000_19#invoke$109` |
| 47.1.4 | `X.1Gn0#LIZ` | `X.1Pvb#LIZ(Context, Comment)`, `CommentCollectViewHolder#copy(Comment, Cert)`, `AwS404S0200000_31_I1#invoke$91` |

There the patch finds each method by the pair of calls it makes (`Comment#getText` **and**
`Comment#getUser`) beside its call to the helper. At the helper call it takes the argument that
holds the string (argument 1), walks back to the `move-result-object` that wrote it, and inserts a
call to `CommentCopyBridge.sanitizeCopiedCommentText(copied, commentText)` there, replacing the
joined string with the comment's own text. The register that held `Comment#getText` is found from
the call itself, so no register number is written down; a method whose text is not kept in a
register makes the patch fail rather than guess.

## What the anchors rest on

Nothing is matched by name. The builder is the method that returns `ClipData`, takes
`(String, String, List)`, carries `"copy_label"` and builds with `ClipData.newPlainText`. The helper
is the `void (String, String, Context, Cert)` method that builds with the same call. The comment-copy
menus are the helper's callers that also read `Comment#getText` and `Comment#getUser`. The classes
rename every build (`X.0jPl`/`X.0lTB`/`X.0mPJ` → `X.19oe`/`X.1Gn0`/`X.1Pvb` between 47.0.3 and
47.1.4).

## What is not proven here

Both hooks were verified offline (the methods above are the ones the fingerprints match, and the
patch applies to 47.0.3 and 47.1.4). The clipboard's contents were **not** read back on a device:
`comment copy: menu prefix blanked` / `comment copy: name prefix dropped from the copied text` in
the log says the hook ran, and the paste is the last step.
