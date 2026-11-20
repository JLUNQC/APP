package co.jlunqc.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.CookieManager;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Música en segundo plano: notificación con carátula y controles (como Spotify),
 * controles en la pantalla bloqueada, auriculares y Bluetooth. Mantiene viva la
 * app mientras suena aunque salgas de ella o apagues la pantalla.
 */
public class MediaService extends Service {

    static final String CHANNEL = "musica";
    static final int NOTIF_ID = 7;
    static final String ACT_UPDATE = "co.jlunqc.tv.UPDATE";
    static final String ACT_CMD = "co.jlunqc.tv.CMD";

    /** Comando hacia la página (toggle, play, pause, next, prev, seek:ms). */
    interface Commander { void command(String c); }
    static Commander commander;
    static MediaService instance;
    static boolean appVisible = true;
    private static boolean islandDismissed = false;
    private Island island;
    private boolean isTv;

    private MediaSession session;
    private PowerManager.WakeLock wake;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String title = "", artist = "", album = "", artUrl = "", loadedArt = "";
    private boolean playing;
    private long pos, dur;
    private Bitmap art;
    private boolean foreground;

    // ------------------------------------------------------------------ API para MainActivity
    static void update(Context c, String title, String artist, String album, String artUrl, boolean playing, long pos, long dur) {
        Intent i = new Intent(c, MediaService.class).setAction(ACT_UPDATE)
                .putExtra("t", title).putExtra("a", artist).putExtra("al", album).putExtra("art", artUrl)
                .putExtra("p", playing).putExtra("pos", pos).putExtra("dur", dur);
        try {
            if (playing && Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
            // Android puede negar el servicio si la app no está visible y nunca sonó; se reintenta en el próximo cambio
        }
    }

    /** La app avisa si está en pantalla: la isla solo aparece cuando sales de ella. */
    static void setAppVisible(boolean v) {
        appVisible = v;
        if (v) islandDismissed = false;
        if (instance != null) instance.refreshIsland();
    }

    static boolean islandEnabled(Context c) {
        return Server.prefs(c).getBoolean("island", true);
    }

    static void setIslandEnabled(Context c, boolean on) {
        Server.prefs(c).edit().putBoolean("island", on).apply();
        if (instance != null) instance.refreshIsland();
    }

    private void refreshIsland() {
        if (island == null) return;
        boolean want = !isTv && islandEnabled(this) && !appVisible && !islandDismissed
                && (playing || island.isShown()) && Island.canDraw(this) && !title.isEmpty();
        if (want) island.show(); else island.hide();
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, MediaService.class));
    }

    // ------------------------------------------------------------------ ciclo de vida
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        isTv = MainActivity.detectTv(this);
        island = new Island(this, new Island.Listener() {
            @Override
            public void command(String c) {
                if ("close".equals(c)) {
                    islandDismissed = true;
                    island.hide();
                } else {
                    send(c);
                }
            }
        });
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Reproducción de música", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setDescription("Controles de la música que suena en JLUNQC");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        session = new MediaSession(this, "JLUNQC");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { send("play"); }
            @Override public void onPause() { send("pause"); }
            @Override public void onSkipToNext() { send("next"); }
            @Override public void onSkipToPrevious() { send("prev"); }
            @Override public void onStop() { send("pause"); }
            @Override public void onSeekTo(long p) { send("seek:" + p); }
        });
        if (Build.VERSION.SDK_INT < 26) {
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        session.setSessionActivity(PendingIntent.getActivity(this, 1, open, piFlags()));
        session.setActive(true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jlunqc:musica");
        wake.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String act = intent.getAction();
        if (ACT_CMD.equals(act)) {
            String c = intent.getStringExtra("c");
            if ("close".equals(c)) {
                send("pause");
                stopForegroundCompat(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            send(c);
            return START_NOT_STICKY;
        }
        if (ACT_UPDATE.equals(act)) {
            title = str(intent.getStringExtra("t"));
            artist = str(intent.getStringExtra("a"));
            album = str(intent.getStringExtra("al"));
            artUrl = str(intent.getStringExtra("art"));
            playing = intent.getBooleanExtra("p", false);
            pos = intent.getLongExtra("pos", 0);
            dur = intent.getLongExtra("dur", 0);
            publish();
            if (!artUrl.equals(loadedArt)) loadArt(artUrl);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Si cierran la app desde recientes estando en pausa, se quita la notificación
        if (!playing) stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (island != null) island.hide();
        if (instance == this) instance = null;
        if (wake.isHeld()) wake.release();
        session.setActive(false);
        session.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------------ notificación
    private void send(final String c) {
        ui.post(new Runnable() {
            @Override public void run() { if (commander != null) commander.command(c); }
        });
    }

    private static String str(String s) { return s == null ? "" : s; }

    private static int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private PendingIntent action(String c, int code) {
        Intent i = new Intent(this, MediaService.class).setAction(ACT_CMD).putExtra("c", c);
        return PendingIntent.getService(this, code, i, piFlags());
    }

    @SuppressWarnings("deprecation")
    private void publish() {
        if (island != null) {
            island.update(title, artist, art, playing, pos, dur);
            refreshIsland();
        }
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
        if (art != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(md.build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f)
                .build());

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(title.isEmpty() ? "JLUNQC" : title)
                .setContentText(artist)
                .setSubText(album)
                .setLargeIcon(art)
                .setContentIntent(session.getController().getSessionActivity())
                .setDeleteIntent(action("close", 9))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOngoing(playing)
                .setColor(0xFF0A84FF)
                .addAction(new Notification.Action.Builder(R.drawable.ic_prev, "Anterior", action("prev", 2)).build())
                .addAction(new Notification.Action.Builder(playing ? R.drawable.ic_pause : R.drawable.ic_play, playing ? "Pausar" : "Reproducir", action("toggle", 3)).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_next, "Siguiente", action("next", 4)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        Notification n = b.build();

        if (playing) {
            if (!wake.isHeld()) wake.acquire(6 * 60 * 60 * 1000L);
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, n);
            foreground = true;
        } else {
            if (wake.isHeld()) wake.release();
            // En pausa: la notificación queda, pero se puede deslizar para quitarla
            if (foreground) stopForegroundCompat(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat(boolean remove) {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        else stopForeground(remove);
        foreground = false;
    }

    private void loadArt(final String url) {
        loadedArt = url;
        if (url.isEmpty()) {
            art = null;
            publish();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                HttpURLConnection con = null;
                try {
                    con = (HttpURLConnection) new URL(url).openConnection();
                    con.setConnectTimeout(6000);
                    con.setReadTimeout(10000);
                    String ck = CookieManager.getInstance().getCookie(url);
                    if (ck != null) con.setRequestProperty("Cookie", ck);
                    if (con.getResponseCode() == 200) {
                        InputStream in = con.getInputStream();
                        bmp = BitmapFactory.decodeStream(in);
                        in.close();
                    }
                } catch (Exception ignored) {
                } finally {
                    if (con != null) con.disconnect();
                }
                final Bitmap res = bmp;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!url.equals(loadedArt)) return;
                        art = res;
                        publish();
                    }
                });
            }
        }).start();
    }
}
