package app.onlynazril.extension.tiktokHandle.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.TextView;

/**
 * A pressable action, drawn to match the screen: hairline outline, no elevation, no platform
 * button chrome that would bring its own colour and shape between Android versions.
 */
public final class ActionView extends TextView {
    public ActionView(Context context, String label, Runnable action) {
        super(context);
        setText(label);
        setTextSize(Tokens.SUBTITLE_SP);
        setTextColor(Tokens.ACCENT);
        setGravity(Gravity.CENTER);
        setMinWidth(Tokens.dp(context, 96));
        setPadding(
                Tokens.dp(context, Tokens.SPACE_6),
                Tokens.dp(context, Tokens.SPACE_3),
                Tokens.dp(context, Tokens.SPACE_6),
                Tokens.dp(context, Tokens.SPACE_3));
        setBackground(outline(context));
        setClickable(true);
        setFocusable(true);
        setOnClickListener(view -> action.run());
    }

    /**
     * The mask is what bounds the ripple, so it carries the same rounded shape as the outline: a
     * rectangular mask lets the press colour run past the corners and out of the button.
     *
     * Public because a dialog button can wear the same shape, so the prompt and the screen do not
     * end up with two different kinds of control.
     */
    public static Drawable outline(Context context) {
        int radius = Tokens.dp(context, 1000);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(radius);
        shape.setColor(Tokens.SURFACE);
        shape.setStroke(Tokens.dp(context, 1), Tokens.HAIRLINE);

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(radius);
        mask.setColor(0xFFFFFFFF);

        return new RippleDrawable(ColorStateList.valueOf(Tokens.SURFACE_PRESSED), shape, mask);
    }
}
