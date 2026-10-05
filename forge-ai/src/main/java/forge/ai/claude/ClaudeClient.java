package forge.ai.claude;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Calls the Anthropic Messages API and returns a structured decision.
 * Uses HttpURLConnection (not java.net.http) so the same code runs on Android.
 */
public final class ClaudeClient {

    /** What Claude decided. {@code choice} for single picks, {@code choices} for multi-picks. */
    public static final class Decision {
        public final String reason;
        public final Integer choice;
        public final List<Integer> choices;

        Decision(String reason, Integer choice, List<Integer> choices) {
            this.reason = reason == null ? "" : reason;
            this.choice = choice;
            this.choices = choices;
        }

        /** All picked option numbers, whichever field Claude used. */
        public List<Integer> all() {
            List<Integer> out = new ArrayList<>();
            if (choices != null) out.addAll(choices);
            if (choice != null && !out.contains(choice)) out.add(choice);
            return out;
        }
    }

    public static final String SYSTEM_PROMPT =
        "You are playing Magic: The Gathering inside the Forge rules engine, as one of the players. "
      + "You play to win. Forge enforces the rules, pays mana automatically from your untapped sources, "
      + "and resolves anything you are not asked about.\n\n"
      + "At each decision you get the full visible game state and a numbered list of legal options. "
      + "Answer by calling the `decide` tool. Put a short reason (one or two sentences) in `reason` FIRST, "
      + "then the option number(s). Use `choice` for a single pick and `choices` for a set of picks "
      + "(an empty `choices` list means 'none').\n\n"
      + "Guidelines:\n"
      + "- Think about sequencing: play a land before casting spells when it helps, and usually cast "
      + "sorcery-speed threats in your first main phase only if they help in combat, otherwise main phase 2.\n"
      + "- Hold instants and flash cards for the opponent's turn or for combat when that is stronger.\n"
      + "- Passing priority is often correct. Do not waste removal or counterspells on low-value targets.\n"
      + "- In combat, count damage, consider blocks, tricks and the race; do not make attacks that just lose creatures.\n"
      + "- Cards are identified by #id. Option numbers are only valid for the current question.";

    private final ClaudeConfig cfg;

    public ClaudeClient(ClaudeConfig cfg) {
        this.cfg = cfg;
    }

    public boolean hasKey() {
        return cfg.apiKey != null && !cfg.apiKey.isEmpty();
    }

    private Map<String, Object> decideTool() {
        return MiniJson.obj(
            "name", "decide",
            "description", "Submit your decision for the current question.",
            "input_schema", MiniJson.obj(
                "type", "object",
                "properties", MiniJson.obj(
                    "reason", MiniJson.obj("type", "string", "description", "Brief reasoning, one or two sentences."),
                    "choice", MiniJson.obj("type", "integer", "description", "The chosen option number (single-choice questions)."),
                    "choices", MiniJson.obj("type", "array", "items", MiniJson.obj("type", "integer"),
                        "description", "Chosen option numbers (multi-choice questions). Empty list = none.")
                ),
                "required", MiniJson.arr("reason")
            )
        );
    }

    /**
     * Asks Claude a question. Returns null on any failure (caller falls back to the Forge AI).
     */
    // circuit breaker shared by all Claude players: after repeated failures, use the Forge AI for a while
    private static int consecutiveFailures = 0;
    private static long pausedUntil = 0;

    public static synchronized boolean isPaused() {
        return System.currentTimeMillis() < pausedUntil;
    }

    private static synchronized void recordResult(boolean ok) {
        if (ok) {
            consecutiveFailures = 0;
            return;
        }
        if (++consecutiveFailures >= 3) {
            pausedUntil = System.currentTimeMillis() + 60_000L;
            consecutiveFailures = 0;
        }
    }

    public Decision decide(String userPrompt) {
        if (!hasKey()) return null;
        if (isPaused()) {
            lastError = "API paused for a minute after repeated failures";
            return null;
        }

        String system = SYSTEM_PROMPT;
        if (!cfg.extraInstructions.isEmpty()) {
            system += "\n\nAdditional instructions from the player who set you up:\n" + cfg.extraInstructions;
        }

        Map<String, Object> body = MiniJson.obj(
            "model", cfg.model,
            "max_tokens", cfg.maxTokens + Math.max(0, cfg.thinkingBudget),
            "system", MiniJson.arr(MiniJson.obj("type", "text", "text", system,
                    "cache_control", MiniJson.obj("type", "ephemeral"))),
            "tools", MiniJson.arr(decideTool()),
            "messages", MiniJson.arr(MiniJson.obj("role", "user", "content", userPrompt))
        );
        if (cfg.thinkingBudget > 0) {
            // forced tool_choice isn't allowed together with extended thinking
            body.put("thinking", MiniJson.obj("type", "enabled", "budget_tokens", cfg.thinkingBudget));
            body.put("tool_choice", MiniJson.obj("type", "auto"));
        } else {
            body.put("tool_choice", MiniJson.obj("type", "tool", "name", "decide"));
        }
        String json = MiniJson.write(body);

        long t0 = System.currentTimeMillis();
        String err = null;
        for (int attempt = 0; attempt <= cfg.retries; attempt++) {
            try {
                HttpResult r = post(json);
                if (r.code == 200) {
                    Decision d = parse(r.body);
                    log("Q", userPrompt);
                    log("A", (d == null ? "(unparseable) " + r.body : describe(d))
                            + "   [" + (System.currentTimeMillis() - t0) + " ms]");
                    recordResult(d != null);
                    return d;
                }
                err = "HTTP " + r.code + ": " + r.body;
                // retry on rate limit / overload / server errors only
                if (r.code != 429 && r.code != 529 && r.code < 500) break;
                Thread.sleep(Math.min(20000L, 1500L * (1L << attempt)));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                err = "interrupted";
                break;
            } catch (Exception e) {
                err = e.toString();
                if (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException) {
                    if (attempt >= 1) break; // network is down: don't stall the game
                }
                try {
                    Thread.sleep(1000L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        lastError = err;
        recordResult(false);
        log("ERROR", err);
        System.err.println("[Claude] API call failed: " + err);
        return null;
    }

    public volatile String lastError;

    private static String describe(Decision d) {
        return "choice=" + d.choice + " choices=" + d.choices + " reason=" + d.reason;
    }

    private static final class HttpResult {
        final int code;
        final String body;
        HttpResult(int c, String b) { code = c; body = b; }
    }

    private HttpResult post(String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(cfg.apiUrl).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(cfg.timeoutSeconds * 1000);
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("x-api-key", cfg.apiKey);
        c.setRequestProperty("anthropic-version", "2023-06-01");
        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : readAll(in);
        c.disconnect();
        return new HttpResult(code, body);
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString(StandardCharsets.UTF_8.name());
        }
    }

    @SuppressWarnings("unchecked")
    static Decision parse(String responseBody) {
        Object root = MiniJson.parse(responseBody);
        if (!(root instanceof Map)) return null;
        Object content = ((Map<String, Object>) root).get("content");
        if (!(content instanceof List)) return null;

        StringBuilder text = new StringBuilder();
        for (Object block : (List<Object>) content) {
            if (!(block instanceof Map)) continue;
            Map<String, Object> b = (Map<String, Object>) block;
            if ("tool_use".equals(b.get("type")) && b.get("input") instanceof Map) {
                return fromMap((Map<String, Object>) b.get("input"));
            }
            if ("text".equals(b.get("type")) && b.get("text") != null) {
                text.append(b.get("text")).append('\n');
            }
        }
        // no tool call (possible when thinking is on): look for JSON in the text
        Map<String, Object> m = MiniJson.findLastObject(text.toString());
        return m == null ? null : fromMap(m);
    }

    private static Decision fromMap(Map<String, Object> m) {
        Integer choice = toInt(m.get("choice"));
        List<Integer> choices = null;
        Object cs = m.get("choices");
        if (cs instanceof List<?> l) {
            choices = new ArrayList<>();
            for (Object o : l) {
                Integer v = toInt(o);
                if (v != null) choices.add(v);
            }
        }
        Object r = m.get("reason");
        return new Decision(r == null ? null : r.toString(), choice, choices);
    }

    private static Integer toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private void log(String tag, String msg) {
        if (!cfg.logToFile) return;
        File f = cfg.logFile();
        synchronized (ClaudeClient.class) {
            try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))) {
                w.println("==== " + new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + tag);
                w.println(msg);
            } catch (Exception ignored) {
                // logging is best-effort
            }
        }
    }
}
