package co.jlunqc.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * "Isla" flotante de música sobre las demás apps (estilo Dynamic Island):
 * una píldora negra con la carátula y ondas que se mueven al ritmo. Al tocarla
 * se expande con carátula grande, título, barra de tiempo y controles.
 * Se puede arrastrar. Necesita el permiso "Mostrar sobre otras apps".
 */
final class Island {

    interface Listener { void command(String c); }

    private final Context ctx;
    private final WindowManager wm;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout pill, card;
    private ImageView pillArt, cardArt;
    private TextView pillTitle, cardTitle, cardArtist, timeL, timeR;
    private ImageButton playBtn;
    private Bars bars;
    private ProgressBar prog;
    private WindowManager.LayoutParams lp;
    private boolean shown, expanded;

    private String title = "", artist = "";
    private Bitmap art;
    private boolean playing;
    private long pos, dur, at;
    private int accent = 0xFF30D158;

    Island(Context c, Listener l) {
        ctx = c.getApplicationContext();
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        listener = l;
    }

    static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static Intent permissionIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.getPackageName()));
    }

    boolean isShown() { return shown; }

    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ datos
    void update(String t, String a, Bitmap b, boolean p, long position, long duration) {
        boolean newArt = b != art;
        title = t == null ? "" : t;
        artist = a == null ? "" : a;
        art = b;
        playing = p;
        pos = position;
        dur = duration;
        at = System.currentTimeMillis();
        if (newArt) accent = accentOf(b);
        if (shown) paint();
    }

    private static int accentOf(Bitmap b) {
        if (b == null) return 0xFF30D158;
        try {
            int c = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0);
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.4f + 0.15f);
            hsv[2] = Math.max(0.85f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF30D158;
        }
    }

    private long curPos() {
        long p = pos + (playing ? System.currentTimeMillis() - at : 0);
        return dur > 0 ? Math.min(p, dur) : p;
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    // ------------------------------------------------------------------ mostrar / ocultar
    void show() {
        if (shown || !canDraw(ctx)) return;
        if (root == null) build();
        try {
            wm.addView(root, lp);
            shown = true;
            expanded = false;
            pill.setVisibility(View.VISIBLE);
            card.setVisibility(View.GONE);
            paint();
            root.setScaleX(0.4f);
            root.setScaleY(0.4f);
            root.setAlpha(0f);
            root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(new OvershootInterpolator(1.4f)).start();
            ui.post(tick);
        } catch (Exception e) {
            shown = false;
        }
    }

    void hide() {
        if (!shown) return;
        shown = false;
        ui.removeCallbacks(tick);
        bars.stop();
        final View r = root;
        r.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override
            public void run() {
                try { wm.removeView(r); } catch (Exception ignored) { }
            }
        }).start();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!shown) return;
            if (expanded && dur > 0) {
                long p = curPos();
                prog.setProgress((int) (p * 1000 / dur));
                timeL.setText(fmt(p));
                timeR.setText("-" + fmt(dur - p));
            }
            ui.postDelayed(this, 500);
        }
    };

    // ------------------------------------------------------------------ vista
    private GradientDrawable bg(float radiusDp, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void round(View v, final float radiusDp, final boolean oval) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                if (oval) o.setOval(0, 0, view.getWidth(), view.getHeight());
                else o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    private ImageButton ctrl(int icon, final String cmd, int sizeDp) {
        ImageButton b = new ImageButton(ctx);
        b.setImageResource(icon);
        b.setBackground(null);
        b.setColorFilter(Color.WHITE);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = dp(sizeDp / 5f);
        b.setPadding(p, p, p, p);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction(new Runnable() {
                    @Override
                    public void run() { v.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); }
                }).start();
                if ("toggle".equals(cmd)) { playing = !playing; at = System.currentTimeMillis(); paint(); }
                listener.command(cmd);
            }
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    private void build() {
        root = new FrameLayout(ctx);
        root.setClipChildren(false);

        // ---- Píldora
        pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(bg(22, 0xF2000000));
        pill.setPadding(dp(6), dp(6), dp(12), dp(6));
        pill.setElevation(dp(8));
        pillArt = new ImageView(ctx);
        pillArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        round(pillArt, 0, true);
        pill.addView(pillArt, new LinearLayout.LayoutParams(dp(30), dp(30)));
        pillTitle = new TextView(ctx);
        pillTitle.setTextColor(Color.WHITE);
        pillTitle.setTextSize(13);
        pillTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pillTitle.setSingleLine(true);
        pillTitle.setEllipsize(TextUtils.TruncateAt.END);
        pillTitle.setMaxWidth(dp(130));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(10);
        tl.rightMargin = dp(12);
        pill.addView(pillTitle, tl);
        bars = new Bars(ctx);
        pill.addView(bars, new LinearLayout.LayoutParams(dp(20), dp(18)));
        root.addView(pill, new FrameLayout.LayoutParams(-2, dp(42), Gravity.CENTER_HORIZONTAL));

        // ---- Tarjeta expandida
        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(bg(30, 0xF5000000));
        card.setPadding(dp(16), dp(16), dp(16), dp(12));
        card.setElevation(dp(16));
        card.setVisibility(View.GONE);
        card.setPivotY(0);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        cardArt = new ImageView(ctx);
        cardArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        round(cardArt, 12, false);
        cardArt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { openApp(); }
        });
        top.addView(cardArt, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        cardTitle = new TextView(ctx);
        cardTitle.setTextColor(Color.WHITE);
        cardTitle.setTextSize(16);
        cardTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        cardTitle.setSingleLine(true);
        cardTitle.setEllipsize(TextUtils.TruncateAt.END);
        cardArtist = new TextView(ctx);
        cardArtist.setTextColor(0xFFA1A1A6);
        cardArtist.setTextSize(14);
        cardArtist.setSingleLine(true);
        cardArtist.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(cardTitle);
        texts.addView(cardArtist);
        texts.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { openApp(); }
        });
        LinearLayout.LayoutParams txl = new LinearLayout.LayoutParams(0, -2, 1f);
        txl.leftMargin = dp(12);
        txl.rightMargin = dp(8);
        top.addView(texts, txl);
        ImageButton close = ctrl(R.drawable.ic_close, "close", 34);
        close.setAlpha(0.6f);
        top.addView(close);
        card.addView(top, new LinearLayout.LayoutParams(-1, -2));

        prog = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        prog.setMax(1000);
        prog.setIndeterminate(false);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(4));
        pl.topMargin = dp(16);
        card.addView(prog, pl);
        LinearLayout times = new LinearLayout(ctx);
        timeL = new TextView(ctx);
        timeR = new TextView(ctx);
        for (TextView tv : new TextView[]{timeL, timeR}) {
            tv.setTextColor(0xFF8E8E93);
            tv.setTextSize(11);
        }
        times.addView(timeL, new LinearLayout.LayoutParams(0, -2, 1f));
        timeR.setGravity(Gravity.END);
        times.addView(timeR, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams tml = new LinearLayout.LayoutParams(-1, -2);
        tml.topMargin = dp(4);
        card.addView(times, tml);

        LinearLayout ctrls = new LinearLayout(ctx);
        ctrls.setGravity(Gravity.CENTER);
        ctrls.addView(ctrl(R.drawable.ic_prev, "prev", 48));
        playBtn = ctrl(R.drawable.ic_pause, "toggle", 62);
        LinearLayout.LayoutParams ppl = new LinearLayout.LayoutParams(dp(62), dp(62));
        ppl.leftMargin = dp(26);
        ppl.rightMargin = dp(26);
        playBtn.setLayoutParams(ppl);
        ctrls.addView(playBtn);
        ctrls.addView(ctrl(R.drawable.ic_next, "next", 48));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(2);
        card.addView(ctrls, cl);

        root.addView(card, new FrameLayout.LayoutParams(dp(340), -2, Gravity.CENTER_HORIZONTAL));

        // ---- Ventana flotante
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : legacyType();
        lp = new WindowManager.LayoutParams(-2, -2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(10);

        // Toques: arrastrar la píldora, tocar para expandir, tocar fuera para cerrar
        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        pill.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            int sx, sy;
            boolean drag;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX();
                        dy = e.getRawY();
                        sx = lp.x;
                        sy = lp.y;
                        drag = false;
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                        if (!drag && (Math.abs(mx) > slop || Math.abs(my) > slop)) drag = true;
                        if (drag) {
                            lp.x = sx + Math.round(mx);
                            lp.y = Math.max(0, sy + Math.round(my));
                            try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                        if (!drag) expand(true);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                        return true;
                    default:
                        return false;
                }
            }
        });
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE && expanded) {
                    expand(false);
                    return true;
                }
                return false;
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static int legacyType() {
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void expand(boolean on) {
        if (on == expanded || !shown) return;
        expanded = on;
        if (on) {
            pill.setVisibility(View.GONE);
            card.setVisibility(View.VISIBLE);
            card.setScaleX(0.45f);
            card.setScaleY(0.3f);
            card.setAlpha(0f);
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(360).setInterpolator(new OvershootInterpolator(0.9f)).start();
            paint();
        } else {
            card.animate().scaleX(0.45f).scaleY(0.3f).alpha(0f).setDuration(200).setInterpolator(new DecelerateInterpolator()).withEndAction(new Runnable() {
                @Override
                public void run() {
                    card.setVisibility(View.GONE);
                    pill.setVisibility(View.VISIBLE);
                    pill.setScaleX(0.8f);
                    pill.setScaleY(0.8f);
                    pill.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(1.6f)).start();
                }
            }).start();
        }
    }

    private void openApp() {
        Intent i = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { ctx.startActivity(i); } catch (Exception ignored) { }
        expand(false);
    }

    private void paint() {
        if (root == null) return;
        pillTitle.setText(title.isEmpty() ? "JLUNQC" : title);
        cardTitle.setText(title.isEmpty() ? "JLUNQC" : title);
        cardArtist.setText(artist);
        if (art != null) {
            pillArt.setImageBitmap(art);
            cardArt.setImageBitmap(art);
        } else {
            pillArt.setImageResource(R.mipmap.ic_launcher);
            cardArt.setImageResource(R.mipmap.ic_launcher);
        }
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        bars.setColor(accent);
        if (playing) bars.start(); else bars.stop();
        if (Build.VERSION.SDK_INT >= 21) {
            prog.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            prog.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55FFFFFF));
        }
        if (dur > 0) prog.setProgress((int) (curPos() * 1000 / dur));
    }

    /** Ondas animadas de la píldora. */
    static final class Bars extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float[] phase = {0f, 1.3f, 2.4f, 0.7f};
        private ValueAnimator anim;
        private float t;

        Bars(Context c) {
            super(c);
            p.setColor(0xFF30D158);
        }

        void setColor(int c) {
            p.setColor(c);
            invalidate();
        }

        void start() {
            if (anim != null && anim.isRunning()) return;
            anim = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
            anim.setDuration(1100);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    t = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        void stop() {
            if (anim != null) anim.cancel();
            anim = null;
            t = -1;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int n = phase.length;
            float w = getWidth(), h = getHeight();
            float bw = w / (n * 2f - 1f);
            for (int i = 0; i < n; i++) {
                float k = t < 0 ? 0.25f : (float) (0.35 + 0.65 * Math.abs(Math.sin(t * (1 + i * 0.35) + phase[i])));
                float bh = Math.max(bw, h * k);
                float x = i * bw * 2f;
                r.set(x, (h - bh) / 2f, x + bw, (h + bh) / 2f);
                c.drawRoundRect(r, bw / 2f, bw / 2f, p);
            }
        }
    }
}
