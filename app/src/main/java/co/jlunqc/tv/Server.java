package co.jlunqc.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dirección guardada del servidor JLUNQC y búsqueda automática en la red local.
 * El servidor responde en /api/info con {"ok": true, "name": "JLUNQC", "version": "7.0", ...}.
 */
final class Server {

    static final String DEFAULT_URL = "https://casa.jlunqc-app.online";
    /** Conexión directa: tus servidores con dominio fijo. */
    static final String[][] PRESETS = {
            {"Casa", "https://casa.jlunqc-app.online"},
            {"SENA", "https://sena.jlunqc-app.online"},
    };
    static final int DEFAULT_PORT = 8000;
    private static final String PREFS = "jlunqc";
    private static final String KEY_URL = "server_url";
    private static final String KEY_RECENT = "recent_urls";

    private Server() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String savedUrl(Context c) {
        return prefs(c).getString(KEY_URL, "");
    }

    static void save(Context c, String url) {
        String u = normalize(url);
        List<String> rec = recent(c);
        rec.remove(u);
        rec.add(0, u);
        while (rec.size() > 6) rec.remove(rec.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String r : rec) sb.append(r).append('\n');
        prefs(c).edit().putString(KEY_URL, u).putString(KEY_RECENT, sb.toString()).apply();
    }

    /** Direcciones usadas antes (IP de la casa, dominios de Cloudflare...). */
    static List<String> recent(Context c) {
        List<String> out = new ArrayList<>();
        for (String r : prefs(c).getString(KEY_RECENT, "").split("\n")) {
            if (!r.trim().isEmpty() && !out.contains(r.trim())) out.add(r.trim());
        }
        return out;
    }

    /** "192.168.1.5" -> "http://192.168.1.5:8000"; quita la barra final. */
    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return s;
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        // Si es una IP sin puerto, usa el puerto del servidor
        String hostPart = s.replaceFirst("^https?://", "");
        if (hostPart.matches("\\d{1,3}(\\.\\d{1,3}){3}") && s.startsWith("http://")) {
            s = s + ":" + DEFAULT_PORT;
        }
        return s;
    }

    static int portOf(String url) {
        try {
            URL u = new URL(url);
            return u.getPort() > 0 ? u.getPort() : (u.getProtocol().equals("https") ? 443 : 80);
        } catch (Exception e) {
            return DEFAULT_PORT;
        }
    }

    /** Resultado de una comprobación: null si no es un servidor JLUNQC. */
    static final class Info {
        final String url;
        final String name;
        final String version;

        Info(String url, String name, String version) {
            this.url = url;
            this.name = name;
            this.version = version;
        }

        String label() {
            String host = url.replaceFirst("^https?://", "");
            return name + (version.isEmpty() ? "" : " " + version) + "  ·  " + host;
        }
    }

    /** Pide /api/info. Bloqueante: llamar fuera del hilo principal. */
    static Info probe(String baseUrl, int timeoutMs) {
        HttpURLConnection con = null;
        try {
            URL u = new URL(baseUrl + "/api/info");
            con = (HttpURLConnection) u.openConnection();
            con.setConnectTimeout(timeoutMs);
            con.setReadTimeout(Math.max(timeoutMs * 3, 1500));
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("User-Agent", "JLUNQC-App/1.4");
            con.setUseCaches(false);
            if (con.getResponseCode() != 200) return null;
            InputStream in = con.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 512 * 1024) out.write(buf, 0, n);
            in.close();
            JSONObject o = new JSONObject(out.toString("UTF-8"));
            if (!o.optBoolean("ok", false)) return null;
            return new Info(baseUrl, o.optString("name", "JLUNQC"), o.optString("version", ""));
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /**
     * Para una dirección escrita a mano (IP o dominio): primero /api/info y, si el
     * servidor pide inicio de sesión o responde otra cosa, basta con que la página
     * principal conteste (200-499). Así sirve con el login y con túneles como Cloudflare.
     */
    static Info probeAny(String baseUrl, int timeoutMs) {
        Info info = probe(baseUrl, timeoutMs);
        if (info != null) return info;
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(baseUrl + "/").openConnection();
            con.setConnectTimeout(timeoutMs);
            con.setReadTimeout(Math.max(timeoutMs * 3, 4000));
            con.setInstanceFollowRedirects(true);
            con.setUseCaches(false);
            con.setRequestProperty("User-Agent", "JLUNQC-App/1.4");
            int code = con.getResponseCode();
            if (code >= 200 && code < 500) return new Info(baseUrl, "JLUNQC", "");
        } catch (Exception ignored) {
        } finally {
            if (con != null) con.disconnect();
        }
        return null;
    }

    /** IPv4 privadas de este dispositivo (Wi-Fi o cable). */
    static List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return out;
            for (NetworkInterface ni : Collections.list(ifs)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    interface Listener {
        void onFound(Info info);
        void onProgress(int done, int total);
        void onFinished(List<Info> all);
    }

    /**
     * Busca servidores JLUNQC en la red: primero la última IP conocida, luego toda la
     * subred /24 de cada interfaz. Corre en segundo plano; los avisos llegan desde
     * hilos de fondo (el que llama debe pasar a la interfaz con runOnUiThread).
     */
    static AtomicBoolean scan(final Context ctx, final Listener l) {
        final AtomicBoolean cancel = new AtomicBoolean(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Info> found = Collections.synchronizedList(new ArrayList<Info>());
                Set<Integer> ports = new LinkedHashSet<>();
                String saved = savedUrl(ctx);
                if (!saved.isEmpty()) ports.add(portOf(saved));
                ports.add(DEFAULT_PORT);

                Set<String> targets = new LinkedHashSet<>();
                for (String ip : localIps()) {
                    String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
                    for (int i = 1; i < 255; i++) {
                        String host = prefix + i;
                        if (host.equals(ip)) continue;
                        for (int p : ports) targets.add("http://" + host + ":" + p);
                    }
                }
                final int total = targets.size();
                if (total == 0) {
                    l.onFinished(found);
                    return;
                }
                ExecutorService pool = Executors.newFixedThreadPool(48);
                final int[] done = {0};
                for (final String t : targets) {
                    pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (cancel.get()) return;
                            Info info = probe(t, 450);
                            if (info != null) {
                                found.add(info);
                                l.onFound(info);
                            }
                            synchronized (done) {
                                done[0]++;
                                if (done[0] % 16 == 0 || done[0] == total) l.onProgress(done[0], total);
                            }
                        }
                    });
                }
                pool.shutdown();
                try {
                    pool.awaitTermination(90, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) { }
                l.onFinished(new ArrayList<>(found));
            }
        }, "jlunqc-scan").start();
        return cancel;
    }
}
