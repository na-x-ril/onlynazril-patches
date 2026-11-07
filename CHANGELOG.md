## [1.6.0-dev.2](https://github.com/na-x-ril/onlynazril-patches/compare/v1.6.0-dev.1...v1.6.0-dev.2) (2026-11-07)

### 🐛 Bug Fixes

* **release:** Drop bogus closes and add Morphe link ([fc57eed](https://github.com/na-x-ril/onlynazril-patches/commit/fc57eed1dd48fc6b8786f2409b8bcb4d4435c8a4))


### [Add to Morphe](https://morphe.software/add-source?github=na-x-ril/onlynazril-patches)

## [1.6.0-dev.1](https://github.com/na-x-ril/onlynazril-patches/compare/v1.5.0...v1.6.0-dev.1) (2026-11-07)

### 🐛 Bug Fixes

* **TikTok/About:** Keep diagnostics text after copy ([0f6e7be](https://github.com/na-x-ril/onlynazril-patches/commit/0f6e7be94f5b7372786e9b91e7b92f74b5b7bc8f))

### ✨ New Features

* **TikTok/AMOLED:** One full patch, on by default ([8437a2a](https://github.com/na-x-ril/onlynazril-patches/commit/8437a2aec76406cdb41daf2885eb7a3e4d7114e1))
* **TikTok/Misc:** Add settings export and import ([af95b82](https://github.com/na-x-ril/onlynazril-patches/commit/af95b82bafbf6719be7957e3502c4af78e5eeee2))
* **TikTok/Pause image search:** Add a disable switch for both search surfaces ([3f7bf4d](https://github.com/na-x-ril/onlynazril-patches/commit/3f7bf4dd3882e0de858ac2b002022de78ecfe913)), closes [IPhotoSearchService#LJII](https://github.com/na-x-ril/IPhotoSearchService/issues/LJII)
* **TikTok/Refresh rate:** Add `App refresh rate` patch ([2d52929](https://github.com/na-x-ril/onlynazril-patches/commit/2d529294aa27af41b56a12e34e5beab37c7d9981)), closes [AwemeHostApplication#onCreate](https://github.com/na-x-ril/AwemeHostApplication/issues/onCreate) [Application#onCreate](https://github.com/na-x-ril/Application/issues/onCreate)
* **TikTok/Tweaks:** Slide each page as one unit ([39d44c3](https://github.com/na-x-ril/onlynazril-patches/commit/39d44c3454d0b98be96f44f216cd607ddab3c646))
* **TikTok/Tweaks:** Split the screen into category pages ([712ec96](https://github.com/na-x-ril/onlynazril-patches/commit/712ec96c736e272c2badbb0b0a599a0d98a79435))

### 🔧 Improvements

* **TikTok:** Resolve anchors from the patcher indexes ([763c920](https://github.com/na-x-ril/onlynazril-patches/commit/763c920b3d98f7f973a36689a2ccc108b7c536b1))

## [1.5.0](https://github.com/na-x-ril/onlynazril-patches/compare/v1.4.0...v1.5.0) (2026-11-05)

### 🐛 Bug Fixes

* **TikTok/Custom font:** Compose, Lynx and hybrid pages keep TikTok's own text ([b781efe](https://github.com/na-x-ril/onlynazril-patches/commit/b781efec61a6485c1c47526aa37b1cc8193409f9))
* **TikTok/Custom font:** Hybrid pages keep their own font ([c6745a6](https://github.com/na-x-ril/onlynazril-patches/commit/c6745a6db2499309d7445e4b2d7c564750b7b286))

### ✨ New Features

* **TikTok/AMOLED:** Add `AMOLED theme` patch ([b98f05a](https://github.com/na-x-ril/onlynazril-patches/commit/b98f05adfee202908c3e904f4de931cacc83ca04))
* **TikTok:** target 47.7.1 and 47.7.2 and open Tweaks from Home ([821adf9](https://github.com/na-x-ril/onlynazril-patches/commit/821adf9ce4e91cf048f62662253e957f755c1b63))

## [1.5.0-dev.1](https://github.com/na-x-ril/onlynazril-patches/compare/v1.4.0...v1.5.0-dev.1) (2026-11-05)

### 🐛 Bug Fixes

* **TikTok/Custom font:** Compose, Lynx and hybrid pages keep TikTok's own text ([b781efe](https://github.com/na-x-ril/onlynazril-patches/commit/b781efec61a6485c1c47526aa37b1cc8193409f9))
* **TikTok/Custom font:** Hybrid pages keep their own font ([c6745a6](https://github.com/na-x-ril/onlynazril-patches/commit/c6745a6db2499309d7445e4b2d7c564750b7b286))

### ✨ New Features

* **TikTok/AMOLED:** Add `AMOLED theme` patch ([b98f05a](https://github.com/na-x-ril/onlynazril-patches/commit/b98f05adfee202908c3e904f4de931cacc83ca04))
* **TikTok:** target 47.7.1 and 47.7.2 and open Tweaks from Home ([821adf9](https://github.com/na-x-ril/onlynazril-patches/commit/821adf9ce4e91cf048f62662253e957f755c1b63))

## [1.4.0](https://github.com/na-x-ril/onlynazril-patches/compare/v1.3.0...v1.4.0) (2026-10-11)

### ✨ New Features

* draw TikTok's text with an imported font ([98ff010](https://github.com/na-x-ril/onlynazril-patches/commit/98ff010e6fcf7e5495dd5cf4e7737de43f0f9a64))
* **TikTok/Captcha:** Add `Hide CAPTCHA popups` patch ([6e4d708](https://github.com/na-x-ril/onlynazril-patches/commit/6e4d7080e94ead3dc882b0dd75a223d99aaa5956))
* **TikTok/Comment copy:** Add `Copy comments without username` patch ([44f40ba](https://github.com/na-x-ril/onlynazril-patches/commit/44f40baedbbb7541e3bc32f2539876abe03b5cd6)), closes [Comment#getText](https://github.com/na-x-ril/Comment/issues/getText) [Comment#getUser](https://github.com/na-x-ril/Comment/issues/getUser)

## [1.4.0-dev.2](https://github.com/na-x-ril/onlynazril-patches/compare/v1.4.0-dev.1...v1.4.0-dev.2) (2026-10-11)

### ✨ New Features

* **TikTok/Captcha:** Add `Hide CAPTCHA popups` patch ([6e4d708](https://github.com/na-x-ril/onlynazril-patches/commit/6e4d7080e94ead3dc882b0dd75a223d99aaa5956))
* **TikTok/Comment copy:** Add `Copy comments without username` patch ([44f40ba](https://github.com/na-x-ril/onlynazril-patches/commit/44f40baedbbb7541e3bc32f2539876abe03b5cd6)), closes [Comment#getText](https://github.com/na-x-ril/Comment/issues/getText) [Comment#getUser](https://github.com/na-x-ril/Comment/issues/getUser)

## [1.4.0-dev.1](https://github.com/na-x-ril/onlynazril-patches/compare/v1.3.0...v1.4.0-dev.1) (2026-10-07)

### ✨ New Features

* draw TikTok's text with an imported font ([98ff010](https://github.com/na-x-ril/onlynazril-patches/commit/98ff010e6fcf7e5495dd5cf4e7737de43f0f9a64))

## [1.3.0](https://github.com/na-x-ril/onlynazril-patches/compare/v1.2.0...v1.3.0) (2026-10-04)

### 🐛 Bug Fixes

* keep the Tweaks screen clear of the system bars ([b9791b0](https://github.com/na-x-ril/onlynazril-patches/commit/b9791b034df9392de6d56b8aad69f82b4e684a17))
* record what the download feature measured on a device ([366e8ab](https://github.com/na-x-ril/onlynazril-patches/commit/366e8abd068e7000cae2d580ed66ddbfb859bece))
* the region survives the app writing the post time again ([3bffc83](https://github.com/na-x-ril/onlynazril-patches/commit/3bffc8346d54e228ccbe4080bb5032c835c9ad40)), closes [X.098X#invoke](https://github.com/na-x-ril/X.098X/issues/invoke)
* the reply line uses the handle the comment list already carried ([47f21ff](https://github.com/na-x-ril/onlynazril-patches/commit/47f21ffbdbbf03faf4ca2ef6b497770bd63de299))

### ✨ New Features

* one prompt component, and a log that keeps writing ([c8a928d](https://github.com/na-x-ril/onlynazril-patches/commit/c8a928db6056854a68f7b05b49c9230a394bd8b3))
* save the media from the share sheet, without the watermark ([4ed620b](https://github.com/na-x-ril/onlynazril-patches/commit/4ed620b67191a99a09c5ac8662cee2982ce4bcea)), closes [Video#getDownloadAddr](https://github.com/na-x-ril/Video/issues/getDownloadAddr)
* set a profile background on accounts the rollout has not reached ([4e165c5](https://github.com/na-x-ril/onlynazril-patches/commit/4e165c52a3ce61f002e7aebb2d109aef1f8eabcc))
* the download and profile background switches, and the 47.1.4 target ([5efd33b](https://github.com/na-x-ril/onlynazril-patches/commit/5efd33b3f8f0fa5310b7979a946044a982bc615f))

## [1.3.0-dev.3](https://github.com/na-x-ril/onlynazril-patches/compare/v1.3.0-dev.2...v1.3.0-dev.3) (2026-10-04)

### 🐛 Bug Fixes

* record what the download feature measured on a device ([366e8ab](https://github.com/na-x-ril/onlynazril-patches/commit/366e8abd068e7000cae2d580ed66ddbfb859bece))


## [1.3.0-dev.2](https://github.com/na-x-ril/onlynazril-patches/compare/v1.3.0-dev.1...v1.3.0-dev.2) (2026-10-02)

### ✨ New Features

* the download and profile background switches, and the 47.1.4 target ([5efd33b](https://github.com/na-x-ril/onlynazril-patches/commit/5efd33b3f8f0fa5310b7979a946044a982bc615f))

## [1.3.0-dev.1](https://github.com/na-x-ril/onlynazril-patches/compare/v1.2.1-dev.2...v1.3.0-dev.1) (2026-10-02)

### 🐛 Bug Fixes

* keep the Tweaks screen clear of the system bars ([b9791b0](https://github.com/na-x-ril/onlynazril-patches/commit/b9791b034df9392de6d56b8aad69f82b4e684a17))

### ✨ New Features

* one prompt component, and a log that keeps writing ([c8a928d](https://github.com/na-x-ril/onlynazril-patches/commit/c8a928db6056854a68f7b05b49c9230a394bd8b3))
* save the media from the share sheet, without the watermark ([4ed620b](https://github.com/na-x-ril/onlynazril-patches/commit/4ed620b67191a99a09c5ac8662cee2982ce4bcea)), closes [Video#getDownloadAddr](https://github.com/na-x-ril/Video/issues/getDownloadAddr)
* set a profile background on accounts the rollout has not reached ([4e165c5](https://github.com/na-x-ril/onlynazril-patches/commit/4e165c52a3ce61f002e7aebb2d109aef1f8eabcc))

## [1.2.1-dev.2](https://github.com/na-x-ril/onlynazril-patches/compare/v1.2.1-dev.1...v1.2.1-dev.2) (2026-09-30)

### 🐛 Bug Fixes

* the reply line uses the handle the comment list already carried ([47f21ff](https://github.com/na-x-ril/onlynazril-patches/commit/47f21ffbdbbf03faf4ca2ef6b497770bd63de299))

## [1.2.1-dev.1](https://github.com/na-x-ril/onlynazril-patches/compare/v1.2.0...v1.2.1-dev.1) (2026-09-30)

### 🐛 Bug Fixes

* the region survives the app writing the post time again ([3bffc83](https://github.com/na-x-ril/onlynazril-patches/commit/3bffc8346d54e228ccbe4080bb5032c835c9ad40)), closes [X.098X#invoke](https://github.com/na-x-ril/X.098X/issues/invoke)

## [1.2.0](https://github.com/na-x-ril/onlynazril-patches/compare/v1.1.0...v1.2.0) (2026-09-29)

### ✨ New Features

* filter the feed by ads and by view or like counts ([97874bc](https://github.com/na-x-ril/onlynazril-patches/commit/97874bc42c4c7a5e9efd3d7ba524e3c08268828e)), closes [FeedItemList#setItems](https://github.com/na-x-ril/FeedItemList/issues/setItems) [FeedItemList#clone](https://github.com/na-x-ril/FeedItemList/issues/clone) [X.07zq#getData](https://github.com/na-x-ril/X.07zq/issues/getData)

## [1.2.0-dev.1](https://github.com/na-x-ril/onlynazril-patches/compare/v1.1.0...v1.2.0-dev.1) (2026-09-29)

### ✨ New Features

* filter the feed by ads and by view or like counts ([97874bc](https://github.com/na-x-ril/onlynazril-patches/commit/97874bc42c4c7a5e9efd3d7ba524e3c08268828e)), closes [FeedItemList#setItems](https://github.com/na-x-ril/FeedItemList/issues/setItems) [FeedItemList#clone](https://github.com/na-x-ril/FeedItemList/issues/clone) [X.07zq#getData](https://github.com/na-x-ril/X.07zq/issues/getData)

## [1.1.0](https://github.com/na-x-ril/onlynazril-patches/compare/v1.0.0...v1.1.0) (2026-09-25)

### 🐛 Bug Fixes

* post time off now removes the time as well ([aa7506b](https://github.com/na-x-ril/onlynazril-patches/commit/aa7506b6e66131f04b852675c6afcadd65cd92c4))

### ✨ New Features

* ask for one restart after the app is patched ([d9fb024](https://github.com/na-x-ril/onlynazril-patches/commit/d9fb024d0c12c3f04597e71933a1da5c41e527da))
* draw the restart prompt from the screen's own palette ([c4029c5](https://github.com/na-x-ril/onlynazril-patches/commit/c4029c5a82299e77d69f2e3c1495d0427eaa4d80))
* restart with a fresh task, and a control for it ([e1c0f4b](https://github.com/na-x-ril/onlynazril-patches/commit/e1c0f4bc85dfc16ef4fa43dbaa1b41343c92554f))

## [1.1.0-dev.1](https://github.com/na-x-ril/onlynazril-patches/compare/v1.0.0...v1.1.0-dev.1) (2026-09-25)

### 🐛 Bug Fixes

* post time off now removes the time as well ([aa7506b](https://github.com/na-x-ril/onlynazril-patches/commit/aa7506b6e66131f04b852675c6afcadd65cd92c4))

### ✨ New Features

* ask for one restart after the app is patched ([d9fb024](https://github.com/na-x-ril/onlynazril-patches/commit/d9fb024d0c12c3f04597e71933a1da5c41e527da))
* draw the restart prompt from the screen's own palette ([c4029c5](https://github.com/na-x-ril/onlynazril-patches/commit/c4029c5a82299e77d69f2e3c1495d0427eaa4d80))
* restart with a fresh task, and a control for it ([e1c0f4b](https://github.com/na-x-ril/onlynazril-patches/commit/e1c0f4bc85dfc16ef4fa43dbaa1b41343c92554f))

# Changelog

All notable changes to this project are documented in this file. It is written by semantic-release
when a release is published — do not edit it by hand.
