package co.jlunqc.tv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/** Botones con el estilo JLUNQC y un foco bien visible para el control remoto. */
final class Ui {

    static final int ACCENT = 0xFF0A84FF;
    static final int SURFACE = 0xFF2C2C2E;

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable shape(Context c, int fill, int strokeColor, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(c, 14));
        g.setColor(fill);
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), strokeColor);
        return g;
    }

    static Button button(Context c, String text, boolean primary) {
        final Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 52));
        b.setPadding(dp(c, 26), dp(c, 10), dp(c, 26), dp(c, 10));
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        if (android.os.Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);

        int base = primary ? ACCENT : SURFACE;
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, shape(c, Color.WHITE, Color.WHITE, 0));
        bg.addState(new int[]{android.R.attr.state_pressed}, shape(c, primary ? 0xFF3D9BFF : 0xFF3A3A3C, 0, 0));
        bg.addState(new int[]{}, shape(c, base, 0, 0));
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{Color.BLACK, Color.WHITE}));

        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? 1.06f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(160).start();
            }
        });
        return b;
    }

    static LinearLayout.LayoutParams rowParams(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(c, 8);
        lp.rightMargin = dp(c, 8);
        return lp;
    }

    static LinearLayout.LayoutParams blockParams(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, topDp);
        return lp;
    }
}
