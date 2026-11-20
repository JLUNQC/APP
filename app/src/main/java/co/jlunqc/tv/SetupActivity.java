package co.jlunqc.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pantalla para elegir el servidor: búsqueda automática en la red o dirección escrita. */
public class SetupActivity extends Activity {

    static final String EXTRA_FIRST = "first";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText urlInput;
    private TextView status;
    private LinearLayout foundList;
    private Button scanBtn;
    private Button saveAnyway;
    private AtomicBoolean scanCancel;
    private final Set<String> shown = new HashSet<>();
    private boolean first;
    private Button[] presetBtns;
    private Runnable islandPainter;

    @Override
    protected void onResume() {
        super.onResume();
        if (islandPainter != null) islandPainter.run();   // al volver de dar el permiso
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        first = getIntent().getBooleanExtra(EXTRA_FIRST, false);
        boolean tv = MainActivity.detectTv(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, tv ? 56 : 24);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col, new ScrollView.LayoutParams(-1, -2));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        col.addView(box, new LinearLayout.LayoutParams(Math.min(Ui.dp(this, 640), getResources().getDisplayMetrics().widthPixels - 2 * pad), -2));

        TextView eyebrow = text(first ? "BIENVENIDO" : "AJUSTES", 13, 0xFF0A84FF, true);
        box.addView(eyebrow);
        TextView title = text("Conecta con tu servidor JLUNQC", tv ? 32 : 26, Color.WHITE, true);
        title.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(title);
        TextView sub = text("Toca tu servidor para entrar directo desde cualquier lugar (datos o Wi-Fi). "
                + "También busco servidores en esta red o puedes escribir otra dirección.", 16, 0xFFA1A1A6, false);
        sub.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(sub);

        // Conexión directa: Casa y SENA
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presetBtns = new Button[Server.PRESETS.length];
        for (int i = 0; i < Server.PRESETS.length; i++) {
            final String name = Server.PRESETS[i][0];
            final String url = Server.PRESETS[i][1];
            Button pb = Ui.button(this, (i == 0 ? "🏠  " : "🎓  ") + name, i == 0);
            pb.setTextSize(19);
            pb.setMinHeight(Ui.dp(this, 64));
            pb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    urlInput.setText(url);
                    connect(url, false);
                }
            });
            presetBtns[i] = pb;
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, -2, 1f);
            plp.leftMargin = i == 0 ? 0 : Ui.dp(this, 8);
            plp.rightMargin = i == Server.PRESETS.length - 1 ? 0 : Ui.dp(this, 8);
            presets.addView(pb, plp);
        }
        box.addView(presets, Ui.blockParams(this, 22));
        TextView other = text("Otra dirección", 13, 0xFF6E6E73, true);
        box.addView(other, Ui.blockParams(this, 24));

        // Dirección
        urlInput = new EditText(this);
        String saved = Server.savedUrl(this);
        urlInput.setText(saved.isEmpty() ? Server.DEFAULT_URL : saved);
        urlInput.setSingleLine(true);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(0xFF6E6E73);
        urlInput.setHint("http://192.168.1.10:8000");
        urlInput.setTextSize(19);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        int ip = Ui.dp(this, 16);
        urlInput.setPadding(ip, ip, ip, ip);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setCornerRadius(Ui.dp(this, 14));
        fieldBg.setColor(0xFF1C1C1E);
        fieldBg.setStroke(Ui.dp(this, 2), 0xFF3A3A3C);
        urlInput.setBackground(fieldBg);
        urlInput.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                ((GradientDrawable) v.getBackground()).setStroke(Ui.dp(SetupActivity.this, 2), has ? Ui.ACCENT : 0xFF3A3A3C);
            }
        });
        urlInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                connect(urlInput.getText().toString(), false);
                return true;
            }
        });
        box.addView(urlInput, Ui.blockParams(this, 8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button connectBtn = Ui.button(this, "Conectar", true);
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect(urlInput.getText().toString(), false);
            }
        });
        scanBtn = Ui.button(this, "Buscar en mi red", false);
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        LinearLayout.LayoutParams lp0 = Ui.rowParams(this);
        lp0.leftMargin = 0;
        row.addView(connectBtn, lp0);
        row.addView(scanBtn, Ui.rowParams(this));
        box.addView(row, Ui.blockParams(this, 16));

        status = text("", 15, 0xFFA1A1A6, false);
        box.addView(status, Ui.blockParams(this, 18));

        saveAnyway = Ui.button(this, "Guardar de todas formas", false);
        saveAnyway.setVisibility(View.GONE);
        saveAnyway.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect(urlInput.getText().toString(), true);
            }
        });
        LinearLayout.LayoutParams sa = new LinearLayout.LayoutParams(-2, -2);
        sa.topMargin = Ui.dp(this, 10);
        box.addView(saveAnyway, sa);

        java.util.List<String> recent = Server.recent(this);
        if (!recent.isEmpty()) {
            TextView recTitle = text("Usados recientemente", 13, 0xFF6E6E73, true);
            box.addView(recTitle, Ui.blockParams(this, 26));
            for (int i = 0; i < recent.size(); i++) {
                final String r = recent.get(i);
                Button rb = Ui.button(this, MainActivity.hostOf(r), false);
                rb.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                rb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        urlInput.setText(r);
                        connect(r, false);
                    }
                });
                box.addView(rb, Ui.blockParams(this, i == 0 ? 8 : 10));
            }
        }

        TextView foundTitle = text("Servidores en esta red", 13, 0xFF6E6E73, true);
        box.addView(foundTitle, Ui.blockParams(this, 26));
        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        box.addView(foundList, Ui.blockParams(this, 8));

        if (!first && !tv) {
            final Button isl = Ui.button(this, "", false);
            final Runnable paintIsl = new Runnable() {
                @Override
                public void run() {
                    boolean on = MediaService.islandEnabled(SetupActivity.this) && Island.canDraw(SetupActivity.this);
                    isl.setText(on ? "🎵  Isla flotante de música: activada" : "🎵  Isla flotante de música: desactivada");
                }
            };
            paintIsl.run();
            isl.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean on = MediaService.islandEnabled(SetupActivity.this) && Island.canDraw(SetupActivity.this);
                    if (on) {
                        MediaService.setIslandEnabled(SetupActivity.this, false);
                    } else {
                        MediaService.setIslandEnabled(SetupActivity.this, true);
                        if (!Island.canDraw(SetupActivity.this)) {
                            try { startActivity(Island.permissionIntent(SetupActivity.this)); } catch (Exception ignored) { }
                        }
                    }
                    paintIsl.run();
                }
            });
            islandPainter = paintIsl;
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-2, -2);
            il.topMargin = Ui.dp(this, 34);
            box.addView(isl, il);
        }

        if (!first) {
            Button clear = Ui.button(this, "Borrar caché y sesión de la app", false);
            clear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearData();
                }
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.topMargin = Ui.dp(this, 12);
            box.addView(clear, cl);
        }

        TextView foot = text("Atajos del control: Atrás cierra o vuelve · Menú abre esta pantalla · "
                + "Play/Pausa, adelantar y retroceder funcionan en videos y música.", 13, 0xFF6E6E73, false);
        box.addView(foot, Ui.blockParams(this, 30));

        setContentView(scroll);
        if (presetBtns.length > 0) presetBtns[0].requestFocus(); else connectBtn.requestFocus();
        checkPresets();
        // Si copiaste un dominio de Cloudflare, se pega solo
        try {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip().getItemCount() > 0) {
                CharSequence clip = cb.getPrimaryClip().getItemAt(0).getText();
                if (clip != null && clip.toString().trim().matches("https?://\\S+\\.trycloudflare\\.com/?")) {
                    urlInput.setText(clip.toString().trim());
                    status.setText("Pegué el dominio que tenías copiado. Toca Conectar.");
                }
            }
        } catch (Exception ignored) { }
        startScan();
    }

    @Override
    protected void onDestroy() {
        if (scanCancel != null) scanCancel.set(true);
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Revisa en paralelo qué servidores fijos responden; la primera vez, si solo uno responde, entra directo. */
    private void checkPresets() {
        final int n = Server.PRESETS.length;
        final Server.Info[] res = new Server.Info[n];
        final int[] done = {0};
        for (int i = 0; i < n; i++) {
            final int k = i;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Server.Info info = Server.probeAny(Server.PRESETS[k][1], 6000);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            res[k] = info;
                            presetBtns[k].setText((k == 0 ? "🏠  " : "🎓  ") + Server.PRESETS[k][0] + (info != null ? "  ·  en línea" : "  ·  apagado"));
                            presetBtns[k].setAlpha(info != null ? 1f : 0.6f);
                            done[0]++;
                            if (done[0] == n && first) {
                                int up = 0, idx = -1;
                                for (int j = 0; j < n; j++) if (res[j] != null) { up++; idx = j; }
                                if (up == 1 && Server.savedUrl(SetupActivity.this).isEmpty()) {
                                    status.setText("Conectando con " + Server.PRESETS[idx][0] + "…");
                                    choose(res[idx]);
                                }
                            }
                        }
                    });
                }
            }).start();
        }
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private void startScan() {
        if (scanCancel != null) scanCancel.set(true);
        if (Server.localIps().isEmpty()) {
            status.setText("Este equipo no está conectado a una red local (Wi-Fi o cable).");
            return;
        }
        scanBtn.setEnabled(false);
        scanBtn.setText("Buscando…");
        status.setText("Buscando servidores JLUNQC en tu red…");
        scanCancel = Server.scan(this, new Server.Listener() {
            @Override
            public void onFound(final Server.Info info) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        addFound(info);
                    }
                });
            }

            @Override
            public void onProgress(final int done, final int total) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (shown.isEmpty()) status.setText("Buscando servidores JLUNQC en tu red… " + (done * 100 / total) + "%");
                    }
                });
            }

            @Override
            public void onFinished(final List<Server.Info> all) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        scanBtn.setEnabled(true);
                        scanBtn.setText("Buscar otra vez");
                        if (all.isEmpty()) {
                            status.setText("No encontré servidores. Revisa que el PC del servidor esté prendido y en la misma red, "
                                    + "o escribe la dirección arriba.");
                        } else {
                            status.setText(all.size() == 1 ? "Encontré tu servidor." : "Encontré " + all.size() + " servidores. Elige uno.");
                            // Primera vez y uno solo: conectar sin preguntar
                            if (first && all.size() == 1) choose(all.get(0));
                        }
                    }
                });
            }
        });
    }

    private void addFound(final Server.Info info) {
        if (!shown.add(info.url)) return;
        Button b = Ui.button(this, info.label(), false);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose(info);
            }
        });
        LinearLayout.LayoutParams lp = Ui.blockParams(this, foundList.getChildCount() == 0 ? 0 : 10);
        foundList.addView(b, lp);
        if (foundList.getChildCount() == 1) b.requestFocus();
    }

    private boolean chosen = false;

    private void choose(Server.Info info) {
        if (chosen) return;
        chosen = true;
        urlInput.setText(info.url);
        Server.save(this, info.url);
        goMain();
    }

    private void connect(String raw, boolean force) {
        final String url = Server.normalize(raw);
        if (url.isEmpty()) {
            status.setText("Escribe la dirección del servidor.");
            return;
        }
        if (force) {
            Server.save(this, url);
            goMain();
            return;
        }
        urlInput.setText(url);
        status.setText("Probando " + MainActivity.hostOf(url) + "…");
        saveAnyway.setVisibility(View.GONE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Server.Info info = Server.probeAny(url, 6000);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (info != null) {
                            Server.save(SetupActivity.this, url);
                            goMain();
                        } else {
                            status.setText("No responde " + MainActivity.hostOf(url) + ". Revisa la dirección y que el servidor esté prendido.");
                            saveAnyway.setVisibility(View.VISIBLE);
                            saveAnyway.requestFocus();
                        }
                    }
                });
            }
        }).start();
    }

    private void goMain() {
        if (scanCancel != null) scanCancel.set(true);
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void clearData() {
        try {
            WebView w = new WebView(this);
            w.clearCache(true);
            w.destroy();
        } catch (Exception ignored) { }
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        Toast.makeText(this, "Listo: caché y sesión borradas", Toast.LENGTH_SHORT).show();
    }
}
