# CAPTCHA popups, TikTok 47.0.3 and 47.1.4

TikTok raises a risk-control puzzle (the slider / web CAPTCHA) from a handful of real-named entry
points. The patch guards each at its top and asks the extension whether this one may be hidden; when
it may, the callback the app passed is told the puzzle is over and the method returns before the web
view is ever built. A switch in Tweaks governs it.

## The entry points

| Anchor | build | what it carries |
|---|---|---|
| `com.ss.android.ugc.aweme.sec.SecApiImpl#popCaptchaV2(Activity, String, cb, Fragment, String)` | 47.0.3, 47.1.4 | the `riskInfo` blob; the five-parameter form is the one that does the work (the four-parameter one is a forwarder) |
| `com.ss.android.ugc.aweme.sec.SecApiImpl#popCaptcha(Activity, int, cb)` | 47.0.3, 47.1.4 | only the server's error code; the older form |
| `com.ss.android.ugc.aweme.live.livehostimpl.LiveHostUser#popCaptchaV2(Activity, String, cb, Fragment)` | 47.0.3, 47.1.4 | the LIVE host's copy of the same call |

`SecApiImpl#popCaptchaV2` forwards to `DmtSec#popCaptchaV2`, which drives `SecCaptcha` (the web
view). Guarding `SecApiImpl` stops the chain before `DmtSec` is called.

The callback is parameter 3 in all three shapes. Its type is obfuscated and renames every build
(`X.0z4o`/`X.1Jii` on 47.0.3, `X.1Dza`/`X.1UyS` on 47.1.4), so the patch reads it off the match and
checks the class declares the dismiss method before it assembles the call: `LIZJ` for the `sec`
entry points, `LIZIZ` for LIVE. The one on 47.0.3 is a class (`invoke-virtual`), the LIVE one an
interface (`invoke-interface`); the patch picks the invoke kind from the class.

## What the gate never hides

- **Account verification.** An activity under `com.ss.android.ugc.aweme.account.`, or a route or
  risk info naming `login`, `passport`, `sms` or `twice_verify`, is always shown. Hiding one of
  those would strand the account.
- **Everything, while the switch is off.**

## The one cost

A puzzle the server raised over a like, a follow or a comment is hidden like any other, so that
single action does not complete. That is the feature: the puzzle that interrupts browsing is the
thing being removed. The extension logs `captcha: hiding ...`, and the switch turns it all off.

## What is not hooked

The `com.tts.oecverify` verification service (`RiskControlService#execute`, `BdTuring#showVerifyDialog`)
is left alone. It carries the login, SMS and two-factor flows, and the reference implementation
resolves its scene through an obfuscated getter (`LJIIJ`) that renames every build. Hiding the
`SecApiImpl` / `LiveHostUser` popups covers the browsing puzzle; the verification service stays
exactly as TikTok built it.

## What is not proven here

The anchors were verified offline (the classes and method shapes above are what `CaptchaProbe` and
the fingerprints match, and the patch applies to 47.0.3 and 47.1.4). Whether a hidden puzzle leaves
the app on the screen it was on, and whether every "annoying" puzzle comes through these three
entry points rather than the `oecverify` service, needs a device: `captcha: hiding ...` in the log
says the hook fired, and what is on screen after it is the check.
