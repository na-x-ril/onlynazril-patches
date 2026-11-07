package app.onlynazril.extension.tiktok.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import app.onlynazril.extension.tiktok.CustomFontBridge;
import app.onlynazril.extension.tiktok.Surfaces;
import app.onlynazril.extension.tiktok.feedfilter.FeedFilterStats;
import app.onlynazril.extension.tiktok.internal.Activities;
import app.onlynazril.extension.tiktok.internal.Debug;
import app.onlynazril.extension.tiktok.internal.RestartPrompt;
import app.onlynazril.extension.tiktok.refreshrate.RefreshRate;
import app.onlynazril.extension.tiktok.settings.CaptchaSettings;
import app.onlynazril.extension.tiktok.settings.CommentCopySettings;
import app.onlynazril.extension.tiktok.settings.CustomFontSettings;
import app.onlynazril.extension.tiktok.settings.DownloadSettings;
import app.onlynazril.extension.tiktok.settings.FeedFilterSettings;
import app.onlynazril.extension.tiktok.settings.HandleSettings;
import app.onlynazril.extension.tiktok.settings.PauseImageSearchSettings;
import app.onlynazril.extension.tiktok.settings.ProfileBgSettings;
import app.onlynazril.extension.tiktok.settings.RefreshRateSettings;

/**
 * The Tweaks screen, split into a category list and one page per category.
 *
 * The root page is a menu, not a long settings list: Feed, Content & appearance, Privacy, Download,
 * Misc and About. Each category page carries the rows that used to sit in the single scroll, so a
 * feature is reached in one tap instead of by scrolling past every other feature.
 *
 * The screen owns its own back stack. {@link #goBack()} returns to the root before the activity is
 * finished, which is what the activity's hooked onBackPressed asks first.
 */
public final class TweaksScreen extends ScrollView {
    /** {surface id, title, summary} for the surface rows. */
    private static final String[][] SURFACE_ROWS = {
        {Surfaces.FEED, "Feed videos", "The author line, the region and the post time in the feed."},
        {Surfaces.COMMENTS, "Comments", "Comment authors and their region."},
    };

    private static final String[] RATE_LABELS = {"Default", "60 fps", "90 fps", "120 fps"};

    /** How far the outgoing page trails the incoming one during a page transition. */
    private static final float PARALLAX_FACTOR = 0.25f;
    private final int PAGE_ANIMATION_MS = getResources().getInteger(android.R.integer.config_shortAnimTime);

    private enum Category {
        FEED("Feed", "Handle, region, post time, feed filters and pause search."),
        CONTENT("Content & appearance", "Comment copy, custom font and profile background."),
        PRIVACY("Privacy", "The risk-control CAPTCHA puzzle."),
        DOWNLOAD("Download", "Watermark, share sheets and quality."),
        MISC("Misc", "Display refresh rate and room for future options."),
        ABOUT("About", "Build, diagnostics and restart.");

        final String title;
        final String summary;

        Category(String title, String summary) {
            this.title = title;
            this.summary = summary;
        }
    }

    private final Context context;
    private final FrameLayout host;
    private Category current;
    private View currentView;
    private boolean transitioning;

    public TweaksScreen(Context context) {
        super(context);
        this.context = context;
        setFillViewport(true);
        setBackgroundColor(Tokens.BACKGROUND);
        setOverScrollMode(OVER_SCROLL_NEVER);

        host = new FrameLayout(context);
        host.setBackgroundColor(Tokens.BACKGROUND);
        host.setLayoutParams(new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        addView(host);

        showRoot(false);
    }

    /** Returns to the category list; false when the list is already showing. */
    public boolean goBack() {
        if (current == null) return false;
        showRoot(true);
        return true;
    }

    private void showRoot(boolean animateBack) {
        current = null;
        LinearLayout page = page();
        page.addView(header());

        page.addView(categoryRow(Category.FEED, feedSummary()));
        page.addView(divider(context));
        page.addView(categoryRow(Category.CONTENT, Category.CONTENT.summary));
        page.addView(divider(context));
        page.addView(categoryRow(Category.PRIVACY, privacySummary()));
        page.addView(divider(context));
        page.addView(categoryRow(Category.DOWNLOAD, downloadSummary()));
        page.addView(divider(context));
        page.addView(categoryRow(Category.MISC, miscSummary()));
        page.addView(divider(context));
        page.addView(categoryRow(Category.ABOUT, Category.ABOUT.summary));
        show(page, !animateBack);
    }

    private void showCategory(Category category) {
        current = category;
        LinearLayout page = page();
        page.addView(categoryHeader(category));
        switch (category) {
            case FEED:
                addFeed(page);
                break;
            case CONTENT:
                addContent(page);
                break;
            case PRIVACY:
                addPrivacy(page);
                break;
            case DOWNLOAD:
                addDownload(page);
                break;
            case MISC:
                addMisc(page);
                break;
            case ABOUT:
                addAbout(page);
                break;
        }
        show(page, true);
    }

    /**
     * Replaces the visible page and animates the change with a 1/4 parallax: the outgoing page
     * trails the incoming one by a quarter of the screen. Forward moves the old page left; back
     * moves it right and lets the root return from the left.
     */
    private void show(LinearLayout page, boolean forward) {
        if (transitioning) return;
        scrollTo(0, 0);

        if (currentView == null) {
            currentView = page;
            host.addView(page, pageParams());
            return;
        }

        int distance = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        float trailing = distance * PARALLAX_FACTOR;
        float start = forward ? distance : -trailing;
        float endOld = forward ? -trailing : distance;

        final View outgoing = currentView;
        page.setTranslationX(start);
        if (forward) {
            // The new page enters above the old one, so it covers it as it slides in.
            host.addView(page, pageParams());
        } else {
            // On the way back the old page must stay above the root until it has slid out;
            // adding the root at index 0 keeps the outgoing page visible for the whole animation.
            host.addView(page, 0, pageParams());
        }

        transitioning = true;
        outgoing.animate()
                .translationX(endOld)
                .setDuration(PAGE_ANIMATION_MS)
                .start();
        page.animate()
                .translationX(0f)
                .setDuration(PAGE_ANIMATION_MS)
                .withEndAction(() -> {
                    host.removeView(outgoing);
                    outgoing.setTranslationX(0f);
                    currentView = page;
                    transitioning = false;
                })
                .start();
    }

    private static FrameLayout.LayoutParams pageParams() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Tokens.BACKGROUND);
        page.setPadding(0, 0, 0, Tokens.dp(context, Tokens.SPACE_8));
        // A short page (Privacy, Misc) would otherwise expose the page underneath it during the
        // parallax transition. Every page surface covers at least the viewport.
        page.setMinimumHeight(viewportHeight());
        return page;
    }

    private int viewportHeight() {
        int height = getHeight();
        return height > 0 ? height : getResources().getDisplayMetrics().heightPixels;
    }

    private View header() {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_2),
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_6));

        TextView title = new TextView(context);
        title.setText("Tweaks");
        title.setTextSize(Tokens.TITLE_SP);
        title.setTypeface(Fonts.face(true));
        title.setTextColor(Tokens.TEXT_PRIMARY);
        block.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setText("Choose a group. Every change is saved as you make it.");
        subtitle.setTextSize(Tokens.SUBTITLE_SP);
        subtitle.setTextColor(Tokens.TEXT_SECONDARY);
        subtitle.setTypeface(Fonts.face(false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = Tokens.dp(context, Tokens.SPACE_1);
        subtitle.setLayoutParams(params);
        block.addView(subtitle);
        return block;
    }

    private View categoryHeader(Category category) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_2),
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_4));

        TextView back = new TextView(context);
        back.setText("\u2039  Tweaks");
        back.setTextSize(Tokens.ACTION_SP);
        back.setTextColor(Tokens.ACCENT);
        back.setTypeface(Fonts.face(false));
        back.setPadding(0, Tokens.dp(context, Tokens.SPACE_2), 0, Tokens.dp(context, Tokens.SPACE_1));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(view -> showRoot(true));
        block.addView(back);

        TextView title = new TextView(context);
        title.setText(category.title);
        title.setTextSize(Tokens.TITLE_SP);
        title.setTypeface(Fonts.face(true));
        title.setTextColor(Tokens.TEXT_PRIMARY);
        block.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setText(category.summary);
        subtitle.setTextSize(Tokens.SUBTITLE_SP);
        subtitle.setTextColor(Tokens.TEXT_SECONDARY);
        subtitle.setTypeface(Fonts.face(false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = Tokens.dp(context, Tokens.SPACE_1);
        subtitle.setLayoutParams(params);
        block.addView(subtitle);
        return block;
    }

    private View categoryRow(Category category, String summary) {
        RowView row = new RowView(context, category.title, summary, chevron());
        row.setOnClickListener(view -> showCategory(category));
        return row;
    }

    private View chevron() {
        TextView view = new TextView(context);
        view.setText("\u203a");
        view.setTextSize(Tokens.PANEL_TITLE_SP);
        view.setTextColor(Tokens.TEXT_SECONDARY);
        view.setTypeface(Fonts.face(false));
        view.setPadding(Tokens.dp(context, Tokens.SPACE_2), 0, Tokens.dp(context, Tokens.SPACE_2), 0);
        return view;
    }

    private void addFeed(LinearLayout page) {
        page.addView(section(context, "Name"));
        boolean masterOn = HandleSettings.isEnabled(context);
        List<RowView> dependents = new ArrayList<>();

        ToggleView masterToggle = new ToggleView(context);
        masterToggle.setChecked(masterOn);
        page.addView(new RowView(
                context,
                "Handle stamp",
                "Only the @handle. The region and the post time have their own switches below.",
                masterToggle));

        for (int i = 0; i < SURFACE_ROWS.length; i++) {
            page.addView(divider(context));
            RowView row = new RowView(
                    context,
                    SURFACE_ROWS[i][1],
                    SURFACE_ROWS[i][2],
                    surfaceToggle(context, SURFACE_ROWS[i][0]));
            dependents.add(row);
            page.addView(row);
        }

        page.addView(divider(context));
        page.addView(new RowView(
                context,
                "Region \u00b7 CC",
                "Append the country code after the post time, on every surface that shows one.",
                toggle(context, HandleSettings.isRegionEnabled(context),
                        checked -> HandleSettings.setRegionEnabled(context, checked))));
        page.addView(divider(context));
        page.addView(new RowView(
                context,
                "Post time",
                "Show when the video was posted, even when TikTok hides it. Off removes the time, "
                        + "including TikTok's own.",
                toggle(context, HandleSettings.isPostTimeEnabled(context),
                        checked -> HandleSettings.setPostTimeEnabled(context, checked))));

        masterToggle.setOnCheckedChangeListener(checked -> {
            HandleSettings.setEnabled(context, checked);
            for (RowView row : dependents) row.setRowEnabled(checked);
        });
        for (RowView row : dependents) row.setRowEnabled(masterOn);

        long[] views = FeedFilterSettings.minMaxViews();
        long[] likes = FeedFilterSettings.minMaxLikes();
        page.addView(section(context, "Feed filter"));
        page.addView(new RowView(
                context,
                "Remove ads",
                "Drop ads and promotional-music posts from the feed.",
                toggle(context, FeedFilterSettings.isAdsEnabled(context),
                        checked -> FeedFilterSettings.setAdsEnabled(context, checked))));
        page.addView(divider(context));
        page.addView(new RowView(
                context,
                "Min/Max views",
                "Hide videos outside this play-count range. Set the range to turn it on.",
                RangeAction.create(context, "Min/Max views", views[0], views[1],
                        (min, max) -> FeedFilterSettings.setViewsRange(context, min, max))));
        page.addView(divider(context));
        page.addView(new RowView(
                context,
                "Min/Max likes",
                "Hide videos outside this digg-count range. Set the range to turn it on.",
                RangeAction.create(context, "Min/Max likes", likes[0], likes[1],
                        (min, max) -> FeedFilterSettings.setLikesRange(context, min, max))));

        page.addView(section(context, "Pause"));
        page.addView(new RowView(
                context,
                "Disable image search on pause",
                "Hide the image-search entries while a video is paused. Off restores TikTok's entries.",
                toggle(context, PauseImageSearchSettings.isDisabled(context),
                        checked -> PauseImageSearchSettings.setDisabled(context, checked))));
    }

    private void addContent(LinearLayout page) {
        page.addView(section(context, "Comments"));
        page.addView(new RowView(
                context,
                "Copy without username",
                "Copy a comment's own text and leave the username in front of it out.",
                toggle(context, CommentCopySettings.isEnabled(context),
                        checked -> CommentCopySettings.setEnabled(context, checked))));

        page.addView(section(context, "Profile background"));
        page.addView(new RowView(
                context,
                "Profile background",
                "Set a static image or a video as the profile background, on "
                        + "accounts TikTok has not rolled it out to. Others see it, not you: the "
                        + "background shows on your profile to everyone who opens it, so pick an "
                        + "image you are happy to publish, or choose one from your posts",
                toggle(context, ProfileBgSettings.isEnabled(context),
                        checked -> ProfileBgSettings.setEnabled(context, checked))));

        boolean fontOn = CustomFontSettings.isEnabled(context);
        List<RowView> fontDependents = new ArrayList<>();

        page.addView(section(context, "Custom font"));
        ToggleView fontMaster = new ToggleView(context);
        fontMaster.setChecked(fontOn);
        page.addView(new RowView(
                context,
                "Custom font",
                "Render TikTok's own text with an imported font file.",
                fontMaster));

        page.addView(divider(context));
        RowView importRow = new RowView(
                context,
                "Import a font file",
                "Pick a .ttf or .otf. It is copied into the app's own files.",
                new ActionView(context, "Pick", () -> pickFontFile(context)));
        fontDependents.add(importRow);
        page.addView(importRow);

        LinearLayout fontList = new LinearLayout(context);
        fontList.setOrientation(LinearLayout.VERTICAL);
        fillFontList(context, fontList);
        page.addView(fontList);
        for (RowView row : fontDependents) row.setRowEnabled(fontOn);
        setFontRowsEnabled(fontList, fontOn);

        fontMaster.setOnCheckedChangeListener(checked -> {
            CustomFontSettings.setEnabled(context, checked);
            for (RowView row : fontDependents) row.setRowEnabled(checked);
            setFontRowsEnabled(fontList, checked);
        });
    }

    private void addPrivacy(LinearLayout page) {
        page.addView(section(context, "Captcha"));
        page.addView(new RowView(
                context,
                "Hide CAPTCHA popups",
                "Skip the risk-control puzzle while browsing. Account verification (login, SMS, "
                        + "two-factor) is never hidden.",
                toggle(context, CaptchaSettings.isEnabled(context),
                        checked -> CaptchaSettings.setEnabled(context, checked))));
    }

    private void addDownload(LinearLayout page) {
        boolean downloadOn = DownloadSettings.isEnabled(context);
        List<RowView> dependents = new ArrayList<>();

        page.addView(section(context, "Download"));
        ToggleView master = new ToggleView(context);
        master.setChecked(downloadOn);
        page.addView(new RowView(
                context,
                "Download",
                "Fetch the media from the share sheet. Each part below has its own switch.",
                master));

        page.addView(divider(context));
        RowView noWatermarkRow = new RowView(
                context,
                "No watermark",
                "Take the address that carries no watermark.",
                toggle(context, DownloadSettings.isNoWatermarkEnabled(context),
                        checked -> DownloadSettings.setNoWatermarkEnabled(context, checked)));
        dependents.add(noWatermarkRow);
        page.addView(noWatermarkRow);

        page.addView(divider(context));
        RowView everySheetRow = new RowView(
                context,
                "Every share sheet",
                "Offer the row for a video as well, not only for a photo post.",
                toggle(context, DownloadSettings.isEverySheetEnabled(context),
                        checked -> DownloadSettings.setEverySheetEnabled(context, checked)));
        dependents.add(everySheetRow);
        page.addView(everySheetRow);

        page.addView(divider(context));
        RowView bestQualityRow = new RowView(
                context,
                "Best quality",
                "Take the highest quality among the variants this feed carries, not the highest "
                        + "the server has: a lower one is possible when the feed omits the top.",
                toggle(context, DownloadSettings.isBestQualityEnabled(context),
                        checked -> DownloadSettings.setBestQualityEnabled(context, checked)));
        dependents.add(bestQualityRow);
        page.addView(bestQualityRow);

        master.setOnCheckedChangeListener(checked -> {
            DownloadSettings.setEnabled(context, checked);
            for (RowView row : dependents) row.setRowEnabled(checked);
        });
        for (RowView row : dependents) row.setRowEnabled(downloadOn);
    }

    private void addMisc(LinearLayout page) {
        page.addView(section(context, "Refresh rate"));
        int rateIndex = rateIndex(RefreshRateSettings.fps(context));
        page.addView(new RowView(
                context,
                "App refresh rate",
                "Ask the app for a fixed rate. Default leaves the choice to Android and TikTok.",
                ChoiceAction.create(context, "App refresh rate", RATE_LABELS, rateIndex, index -> {
                    RefreshRateSettings.setFps(context, RefreshRateSettings.CHOICES[index]);
                    if (RefreshRate.isInstalled()) RefreshRate.applyNow(context);
                })));
    }

    private void addAbout(LinearLayout page) {
        page.addView(description(
                context,
                "Tweaks for TikTok 47.7.1 and 47.7.2. "
                        + "The display name is read, never rewritten."));
        page.addView(diagnostics(context));
        page.addView(actions(context));
    }

    private String feedSummary() {
        return Category.FEED.summary;
    }

    private String privacySummary() {
        return CaptchaSettings.isEnabled(context) ? "CAPTCHA hidden." : "CAPTCHA shown.";
    }

    private String downloadSummary() {
        return DownloadSettings.isEnabled(context)
                ? "On. Watermark, share sheets and quality."
                : "Off. Nothing is downloaded unless the share sheet asks for it.";
    }

    private String miscSummary() {
        return "Refresh rate: " + RATE_LABELS[rateIndex(RefreshRateSettings.fps(context))];
    }

    /**
     * The filter's own tally, tappable to copy.
     *
     * This is the one piece of state a device report needs, and reading it off a screenshot is not
     * the same as pasting it: the block is also written to the log on every tap, so the log carries
     * the state as it was when it was read.
     */
    private View diagnostics(Context context) {
        TextView view = new TextView(context);
        view.setText(body() + "\nTap to copy");
        view.setTextSize(Tokens.ROW_SUMMARY_SP);
        view.setTextColor(Tokens.TEXT_SECONDARY);
        view.setTypeface(Fonts.face(false));
        view.setLineSpacing(Tokens.dp(context, Tokens.SPACE_1), 1f);
        view.setPadding(
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_2),
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_4));
        view.setClickable(true);
        view.setOnClickListener(clicked -> {
            String text = body();
            Debug.print("state: " + text.replace('\n', ' '));
            copy(context, text);
            view.setText("Copied to clipboard");
            view.postDelayed(() -> view.setText(text + "\nTap to copy"), 1600);
        });
        return view;
    }

    /** What a report needs: which build, what the filter saw, and where the log went. */
    private static String body() {
        File sink = Debug.sink();
        return "Build " + Debug.BUILD + "\n"
                + "Feed filter: " + FeedFilterStats.summary() + "\n"
                + "Log: " + (sink == null ? "not written yet" : sink.getAbsolutePath());
    }

    private static void copy(Context context, String text) {
        ClipboardManager manager =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        manager.setPrimaryClip(ClipData.newPlainText("Tweaks", text));
    }

    /**
     * The screen's one control that acts on the app rather than on a setting, set at the bottom
     * right where a thumb reaches it. It is not a row: the list is the settings.
     */
    private static View actions(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_2),
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_6));
        row.addView(new ActionView(context, "Restart", () -> RestartPrompt.restartNow(context)));
        return row;
    }

    private static View section(Context context, String label) {
        TextView view = new TextView(context);
        view.setText(label.toUpperCase());
        view.setTextSize(Tokens.SECTION_SP);
        view.setLetterSpacing(0.14f);
        view.setTextColor(Tokens.TEXT_SECONDARY);
        view.setTypeface(Fonts.face(false));
        view.setPadding(
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_6),
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_2));
        return view;
    }

    private static View description(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(Tokens.ROW_SUMMARY_SP);
        view.setTextColor(Tokens.TEXT_SECONDARY);
        view.setTypeface(Fonts.face(false));
        view.setLineSpacing(Tokens.dp(context, Tokens.SPACE_1), 1f);
        view.setPadding(
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_2),
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_4));
        return view;
    }

    private static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(Tokens.HAIRLINE);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, Tokens.dp(context, 0.5f)));
        params.leftMargin = Tokens.dp(context, Tokens.SPACE_4);
        params.rightMargin = Tokens.dp(context, Tokens.SPACE_4);
        view.setLayoutParams(params);
        return view;
    }

    /**
     * The imported fonts, one row each, the one in use named in
     * its summary. Tapping a row picks that face, rebuilds the
     * list, and asks for the restart the text already on screen
     * needs. A name runs at most seven tenths of the row's width,
     * so a long one cannot push the trailing controls out of view.
     * Each row carries its own way out, and the bottom of the list
     * carries the way out of all of it.
     */
    private static void fillFontList(Context context, LinearLayout list) {
        list.removeAllViews();
        List<String> names = CustomFontSettings.names(context);
        if (names.isEmpty()) {
            RowView hint = new RowView(
                    context,
                    "No font imported yet",
                    "Pick a .ttf or .otf file to add one.",
                    null);
            hint.setRowEnabled(false);
            list.addView(hint);
            return;
        }
        String selected = CustomFontSettings.selected(context);
        for (String name : names) {
            list.addView(divider(context));
            boolean inUse = name.equals(selected);
            RowView row = new RowView(
                    context,
                    name,
                    inUse ? "In use" : null,
                    fontRowControls(context, name, list));
            row.capTitleWidth(0.7f);
            row.setOnClickListener(picked -> {
                CustomFontSettings.select(context, name);
                fillFontList(context, list);
                RestartPrompt.askNow(context);
            });
            list.addView(row);
        }
        list.addView(clearAllFonts(context, list));
    }

    /** What a font row carries on its right: the way out of the list for this one font. */
    private static View fontRowControls(
            Context context, String name, LinearLayout list) {
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        ActionView delete = new ActionView(context, "Delete", () -> {
            CustomFontSettings.deleteFont(context, name);
            fillFontList(context, list);
            RestartPrompt.askNow(context);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        controls.addView(delete, params);
        return controls;
    }

    /** The bottom of the list: the way out of every imported font at once. */
    private static View clearAllFonts(Context context, final LinearLayout list) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_1),
                Tokens.dp(context, Tokens.SPACE_4),
                Tokens.dp(context, Tokens.SPACE_4));
        row.addView(new ActionView(context, "Clear all fonts", () -> Prompt.show(
                context,
                "Clear all fonts",
                "Remove every imported font? The app then draws with its own faces.",
                "Remove all",
                () -> {
                    CustomFontSettings.clearFonts(context);
                    fillFontList(context, list);
                    RestartPrompt.askNow(context);
                })));
        return row;
    }

    private static void setFontRowsEnabled(LinearLayout list, boolean enabled) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof RowView) {
                ((RowView) child).setRowEnabled(enabled);
            }
        }
    }

    /**
     * The system's document picker, filtered to font files. The
     * picked file's answer comes back through the hooked
     * activity result, not through a callback here.
     */
    @SuppressWarnings("deprecation")
    private static void pickFontFile(Context context) {
        Activity activity = Activities.of(context);
        if (activity == null || activity.isFinishing()) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "font/ttf", "font/otf",
                "application/x-font-ttf", "application/x-font-otf",
                "application/octet-stream"});
        activity.startActivityForResult(pick, CustomFontBridge.REQUEST_PICK_FONT);
    }

    private static ToggleView surfaceToggle(Context context, String surface) {
        return toggle(context, HandleSettings.isSurfaceEnabled(context, surface),
                checked -> HandleSettings.setSurfaceEnabled(context, surface, checked));
    }

    /** The index of the saved rate in the option list, or the default option when it is unknown. */
    private static int rateIndex(int fps) {
        for (int i = 0; i < RefreshRateSettings.CHOICES.length; i++) {
            if (RefreshRateSettings.CHOICES[i] == fps) return i;
        }
        return 0;
    }

    private static ToggleView toggle(
            Context context, boolean initial, ToggleView.OnCheckedChangeListener listener) {
        ToggleView view = new ToggleView(context);
        view.setChecked(initial);
        view.setOnCheckedChangeListener(listener);
        return view;
    }
}
