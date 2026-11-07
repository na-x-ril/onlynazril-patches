package app.onlynazril.extension.tiktok.ui;

import android.content.Context;

/**
 * The control a choice row carries: an {@link ActionView} that shows the current label and opens
 * {@link ChoiceDialog} to change it.
 *
 * The value lives in the settings, and the label is what the dialog last saved; a control keeping
 * its own copy could disagree with what the feature reads.
 */
public final class ChoiceAction {
    public interface OnChoiceChangeListener {
        void onChoiceChanged(int index);
    }

    private ChoiceAction() {}

    public static ActionView create(
            Context context,
            String title,
            String[] labels,
            int selected,
            OnChoiceChangeListener save) {
        int[] current = {selected};
        ActionView[] button = new ActionView[1];
        button[0] = new ActionView(context, labels[current[0]], () -> ChoiceDialog.show(
                context, title, labels, current[0], index -> {
                    current[0] = index;
                    button[0].setText(labels[index]);
                    save.onChoiceChanged(index);
                }));
        return button[0];
    }
}
