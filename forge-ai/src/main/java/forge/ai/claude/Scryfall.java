package forge.ai.claude;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scryfall access for the Claude player: official rulings (cached on disk, fetched in the background)
 * and card search for Claude's lookup tool. Follows Scryfall's API etiquette: a User-Agent and Accept
 * header, and at most ~10 requests per second.
 */
public final class Scryfall {
    // overridable for offline testing
    private static final String API = System.getProperty("claude.scryfall.url", "https://api.scryfall.com");
    private static final long MIN_GAP_MS = 110;

    private static Scryfall instance;

    private final File cacheFile;
    /** card name -> list of ruling texts (empty list = no rulings / not found). */
    private final Map<String, List<String>> rulings = new LinkedHashMap<>();
    private final Set<String> queued = new LinkedHashSet<>();
    private boolean dirty = false;
    private long lastRequest = 0;
    private Thread worker;

    private Scryfall(File cacheFile) {
        this.cacheFile = cacheFile;
        load();
    }

    public static synchronized Scryfall get(ClaudeConfig cfg) {
        File f = cfg.rulingsCacheFile();
        if (instance == null || !instance.cacheFile.equals(f)) instance = new Scryfall(f);
        return instance;
    }

    // ------------------------------------------------------------------ rulings cache

    @SuppressWarnings("unchecked")
    private void load() {
        if (!cacheFile.isFile()) return;
        try {
            Object o = MiniJson.parse(new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8));
            if (!(o instanceof Map)) return;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                List<String> l = new ArrayList<>();
                if (e.getValue() instanceof List<?> vals) {
                    for (Object v : vals) l.add(String.valueOf(v));
                }
                rulings.put(e.getKey(), l);
            }
        } catch (Exception e) {
            System.err.println("[Claude] rulings cache unreadable, starting fresh: " + e);
        }
    }

    private synchronized void save() {
        if (!dirty) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(cacheFile), StandardCharsets.UTF_8)) {
            w.write(MiniJson.write(rulings));
            dirty = false;
        } catch (Exception e) {
            System.err.println("[Claude] could not write rulings cache: " + e);
        }
    }

    /** Cached rulings for a card, or null if not fetched yet. Never blocks. */
    public synchronized List<String> cachedRulings(String name) {
        return rulings.get(name);
    }

    /** Queues names for background fetching (already cached names are skipped). */
    public synchronized void prefetch(Collection<String> names) {
        for (String n : names) {
            if (n != null && !n.isEmpty() && !rulings.containsKey(n)) queued.add(n);
        }
        if (!queued.isEmpty() && (worker == null || !worker.isAlive())) {
            worker = new Thread(this::drain, "Claude Scryfall prefetch");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void drain() {
        int failures = 0;
        while (true) {
            String next;
            synchronized (this) {
                if (queued.isEmpty()) break;
                next = queued.iterator().next();
                queued.remove(next);
                if (rulings.containsKey(next)) continue;
            }
            List<String> r = fetchRulings(next);
            if (r == null) { // network trouble: leave uncached so a later game retries
                if (++failures >= 3) {
                    synchronized (this) {
                        queued.clear();
                    }
                    break;
                }
                continue;
            }
            failures = 0;
            synchronized (this) {
                rulings.put(next, r);
                dirty = true;
            }
        }
        save();
    }

    /** Fetches rulings for one card (blocking). Returns null on network failure. */
    @SuppressWarnings("unchecked")
    private List<String> fetchRulings(String name) {
        Map<String, Object> card = getJson(API + "/cards/named?exact=" + enc(name));
        if (card == null) return null;
        if (!(card.get("rulings_uri") instanceof String uri)) return new ArrayList<>();
        Map<String, Object> rl = getJson(uri);
        List<String> out = new ArrayList<>();
        if (rl != null && rl.get("data") instanceof List<?> data) {
            for (Object o : data) {
                if (o instanceof Map<?, ?> m && m.get("comment") != null) out.add(String.valueOf(m.get("comment")));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ lookup tools for Claude

    /** Implements the scryfall_search / scryfall_card tools. */
    @SuppressWarnings("unchecked")
    public String runTool(String tool, Map<String, Object> input) {
        if ("scryfall_card".equals(tool)) {
            String name = String.valueOf(input.get("name"));
            Map<String, Object> card = getJson(API + "/cards/named?fuzzy=" + enc(name));
            if (card == null || card.isEmpty()) return "No card found named '" + name + "' (or Scryfall unreachable).";
            StringBuilder sb = new StringBuilder(cardText(card));
            List<String> r = fetchRulings(String.valueOf(card.get("name")));
            if (r != null) {
                synchronized (this) {
                    rulings.put(String.valueOf(card.get("name")), r);
                    dirty = true;
                }
                save();
                sb.append("\nRulings:");
                if (r.isEmpty()) sb.append(" none");
                for (String s : r) sb.append("\n- ").append(s);
            }
            return sb.toString();
        }
        if ("scryfall_search".equals(tool)) {
            String q = String.valueOf(input.get("query"));
            Map<String, Object> res = getJson(API + "/cards/search?order=edhrec&q=" + enc(q));
            if (res == null || !(res.get("data") instanceof List)) return "No cards match '" + q + "' (or Scryfall unreachable).";
            List<Object> data = (List<Object>) res.get("data");
            StringBuilder sb = new StringBuilder();
            sb.append(res.get("total_cards")).append(" cards match; showing up to 15 (most played first):\n");
            int n = 0;
            for (Object o : data) {
                if (!(o instanceof Map)) continue;
                sb.append("- ").append(GameStateWriter.oneLine(cardText((Map<String, Object>) o), 300)).append('\n');
                if (++n >= 15) break;
            }
            return sb.toString();
        }
        return "unknown tool " + tool;
    }

    @SuppressWarnings("unchecked")
    private static String cardText(Map<String, Object> c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.get("name"));
        if (c.get("mana_cost") != null && !String.valueOf(c.get("mana_cost")).isEmpty()) sb.append(' ').append(c.get("mana_cost"));
        sb.append(" — ").append(c.get("type_line"));
        if (c.get("power") != null) sb.append(' ').append(c.get("power")).append('/').append(c.get("toughness"));
        if (c.get("oracle_text") != null) {
            sb.append(" | ").append(c.get("oracle_text"));
        } else if (c.get("card_faces") instanceof List<?> faces) {
            for (Object f : faces) {
                if (f instanceof Map<?, ?> fm) {
                    sb.append(" | [").append(fm.get("name")).append("] ").append(fm.get("oracle_text"));
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ HTTP

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String url) {
        synchronized (Scryfall.class) {
            long wait = lastRequest + MIN_GAP_MS - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            lastRequest = System.currentTimeMillis();
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "ForgeClaudePlayer/1.0");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : ClaudeClient.readAll(in);
            c.disconnect();
            if (code == 404) return new LinkedHashMap<>(); // "not found" is an answer, not a failure
            if (code != 200) {
                System.err.println("[Claude] Scryfall HTTP " + code + " for " + url);
                return null;
            }
            Object o = MiniJson.parse(body);
            return o instanceof Map ? (Map<String, Object>) o : null;
        } catch (Exception e) {
            System.err.println("[Claude] Scryfall request failed: " + e);
            return null;
        }
    }
}
