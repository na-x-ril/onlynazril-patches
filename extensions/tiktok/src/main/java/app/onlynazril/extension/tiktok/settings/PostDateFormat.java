package app.onlynazril.extension.tiktok.settings;

/**
 * How a post older than a week is dated. The ordinal is the value stored in prefs and in the
 * backup, so the order is part of the format's on-disk contract.
 *
 * `pattern` is the exact {@link java.text.SimpleDateFormat} pattern, or null for the automatic
 * shape: the compact current-year form, and a medium date once the post is from another year.
 */
public enum PostDateFormat {
    AUTO("Auto", null),
    MM_DD_YY("MM-dd-yy", "MM-dd-yy"),
    MMM_DD_YYYY("MMM dd yyyy", "MMM dd yyyy"),
    DD_MMM_YYYY("dd MMM yyyy", "dd MMM yyyy");

    public final String label;
    public final String pattern;

    PostDateFormat(String label, String pattern) {
        this.label = label;
        this.pattern = pattern;
    }

    /** The stored value, with anything out of range falling back to {@link #AUTO}. */
    public static PostDateFormat fromStored(int value) {
        PostDateFormat[] values = values();
        return value >= 0 && value < values.length ? values[value] : AUTO;
    }

    /** The labels the choice row shows, in the stored order. */
    public static String[] labels() {
        PostDateFormat[] values = values();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = values[i].label;
        return labels;
    }
}
