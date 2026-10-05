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
        /** New running notes for this game, or null to keep the old ones. */
        public final String notes;

        Decision(String reason, Integer choice, List<Integer> choices, String notes) {
            this.reason = reason == null ? "" : reason;
            this.choice = choice;
            this.choices = choices;
            this.notes = notes;
        }

        /** All picked option numbers, whichever field Claude used. */
        public List<Integer> all() {
            List<Integer> out = new ArrayList<>();
            if (choices != null) out.addAll(choices);
            if (choice != null && !out.contains(choice)) out.add(choice);
            return out;
        }
    }

    /** Runs a lookup tool (e.g. Scryfall) on Claude's behalf and returns the text result. */
    public interface ToolRunner {
        String run(String toolName, Map<String, Object> input);
    }

    public static final String SYSTEM_PROMPT =
        "You are playing Magic: The Gathering inside the Forge rules engine, as one of the players. "
      + "You play to win. Forge enforces the rules, pays mana automatically from your untapped sources, "
      + "and resolves anything you are not asked about.\n\n"
      + "At each decision you get the full visible game state and a numbered list of legal options. "
      + "Answer by calling the `decide` tool. Put a short reason (one or two sentences) in `reason` FIRST, "
      + "then the option number(s). Use `choice` for a single pick and `choices` for a set of picks "
      + "(an empty `choices` list means 'none').\n\n"
      + "You are working together with Forge's built-in AI, which is exact at arithmetic but weak at strategy:\n"
      + "- FORGE MATH lines are computed exactly by the engine (damage, lethal checks, who dies in a block). Trust them "
      + "over your own arithmetic.\n"
      + "- 'Forge AI suggests' is the built-in AI's own choice. Treat it as a second opinion: it is often reasonable "
      + "on routine plays and often wrong on strategy (timing, holding cards, racing). If you choose differently, "
      + "say why in your reason.\n"
      + "- 'sim' numbers are Forge's board evaluation change if you make that play now (best targets, stack resolved). "
      + "Roughly +2 = one life point in your favour, +5 = one card in hand. They are short-sighted (they ignore "
      + "what the opponent may hold and future turns) but good at catching plays that do nothing or backfire. "
      + "When it is still before combat on your turn, the sim also plays out this turn's combat the way the Forge AI "
      + "would attack. Land drops are not simulated.\n\n"
      + "Memory: the `notes` field of `decide` rewrites your running notes for this game, shown back to you at every "
      + "later decision. Use it for your plan and for things the state does not show (cards the opponent revealed, "
      + "tricks they likely hold, what they played around). Only include `notes` when they should change; keep them "
      + "under about 600 characters.\n\n"
      + "Guidelines:\n"
      + "- Think about sequencing: play a land before casting spells when it helps; cast creatures before combat only "
      + "if they matter in this combat, otherwise usually after combat.\n"
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

    // ------------------------------------------------------------------ tools

    private Map<String, Object> decideTool(boolean withNotes) {
        Map<String, Object> props = MiniJson.obj(
            "reason", MiniJson.obj("type", "string", "description", "Brief reasoning, one or two sentences."),
            "choice", MiniJson.obj("type", "integer", "description", "The chosen option number (single-choice questions)."),
            "choices", MiniJson.obj("type", "array", "items", MiniJson.obj("type", "integer"),
                "description", "Chosen option numbers (multi-choice questions). Empty list = none.")
        );
        if (withNotes) {
            props.put("notes", MiniJson.obj("type", "string",
                "description", "Optional: replaces your running notes for this game. Omit to keep them unchanged."));
        }
        return MiniJson.obj(
            "name", "decide",
            "description", "Submit your decision for the current question.",
            "input_schema", MiniJson.obj("type", "object", "properties", props, "required", MiniJson.arr("reason"))
        );
    }

    public static List<Object> scryfallTools() {
        return MiniJson.arr(
            MiniJson.obj(
                "name", "scryfall_search",
                "description", "Search Scryfall for Magic cards using Scryfall query syntax (e.g. 'c:u t:instant cmc<=2 o:counter'). "
                    + "Useful to think about what the opponent might hold. Returns up to 15 cards with their text.",
                "input_schema", MiniJson.obj("type", "object",
                    "properties", MiniJson.obj("query", MiniJson.obj("type", "string")),
                    "required", MiniJson.arr("query"))),
            MiniJson.obj(
                "name", "scryfall_card",
                "description", "Get the exact Oracle text and official rulings for one card by exact name.",
                "input_schema", MiniJson.obj("type", "object",
                    "properties", MiniJson.obj("name", MiniJson.obj("type", "string")),
                    "required", MiniJson.arr("name")))
        );
    }

    // ------------------------------------------------------------------ circuit breaker

    // shared by all Claude players: after repeated failures, use the Forge AI for a while
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
        if (++consecutiveFailures >= 2) {
            pausedUntil = System.currentTimeMillis() + 60_000L;
            consecutiveFailures = 0;
        }
    }

    public volatile String lastError;

    // ------------------------------------------------------------------ decisions

    /**
     * Asks Claude a question. {@code systemExtra} is appended to the system prompt (lessons etc.).
     * {@code lookups} may be null; when given, Claude may call Scryfall a few times before deciding.
     * Returns null on any failure (caller falls back to the Forge AI).
     */
    @SuppressWarnings("unchecked")
    public Decision decide(String systemExtra, String userPrompt, ToolRunner lookups) {
        if (!hasKey()) return null;
        if (isPaused()) {
            lastError = "API paused for a minute after repeated failures";
            return null;
        }
        String system = SYSTEM_PROMPT;
        if (systemExtra != null && !systemExtra.isEmpty()) system += "\n\n" + systemExtra;
        if (!cfg.extraInstructions.isEmpty()) {
            system += "\n\nAdditional instructions from the player who set you up:\n" + cfg.extraInstructions;
        }

        List<Object> messages = new ArrayList<>();
        messages.add(MiniJson.obj("role", "user", "content", userPrompt));
        boolean lookupTools = lookups != null && cfg.scryfallLookup && cfg.maxLookups > 0;
        int lookupsLeft = lookupTools ? cfg.maxLookups : 0;
        StringBuilder trace = new StringBuilder();
        long t0 = System.currentTimeMillis();
        long deadline = t0 + cfg.maxWaitSeconds * 1000L;

        // the tool list stays the same on every round (history may contain lookup calls); only tool_choice changes
        List<Object> tools = MiniJson.arr(decideTool(cfg.notes));
        if (lookupTools) tools.addAll(scryfallTools());
        while (true) {
            Map<String, Object> toolChoice;
            if (cfg.thinkingBudget > 0) {
                toolChoice = MiniJson.obj("type", "auto"); // forced tool use isn't allowed with extended thinking
            } else if (lookupsLeft > 0) {
                toolChoice = MiniJson.obj("type", "any");
            } else {
                toolChoice = MiniJson.obj("type", "tool", "name", "decide");
            }
            Map<String, Object> resp = call(cfg.model, cfg.maxTokens, system, messages, tools, toolChoice, cfg.thinkingBudget, deadline);
            if (resp == null) {
                log("Q", userPrompt);
                log("ERROR", lastError);
                return null;
            }
            List<Object> content = resp.get("content") instanceof List ? (List<Object>) resp.get("content") : new ArrayList<>();

            Decision d = null;
            List<Object> toolResults = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (Object block : content) {
                if (!(block instanceof Map)) continue;
                Map<String, Object> b = (Map<String, Object>) block;
                String type = String.valueOf(b.get("type"));
                if ("text".equals(type) && b.get("text") != null) text.append(b.get("text")).append('\n');
                if (!"tool_use".equals(type) || !(b.get("input") instanceof Map)) continue;
                Map<String, Object> input = (Map<String, Object>) b.get("input");
                String name = String.valueOf(b.get("name"));
                if ("decide".equals(name)) {
                    d = fromMap(input);
                } else if (lookups != null) {
                    String result;
                    try {
                        result = lookups.run(name, input);
                    } catch (RuntimeException e) {
                        result = "lookup failed: " + e;
                    }
                    trace.append("  [lookup ").append(name).append(' ').append(input).append("]\n");
                    toolResults.add(MiniJson.obj("type", "tool_result", "tool_use_id", b.get("id"), "content", result));
                }
            }
            if (d == null && toolResults.isEmpty()) {
                Map<String, Object> m = MiniJson.findLastObject(text.toString());
                if (m != null) d = fromMap(m);
            }
            if (d != null || toolResults.isEmpty() || lookupsLeft <= 0) {
                log("Q", userPrompt);
                log("A", trace + (d == null ? "(no decision) " + text : describe(d))
                        + "   [" + (System.currentTimeMillis() - t0) + " ms]");
                recordResult(d != null);
                return d;
            }
            // give the lookup results back and let Claude continue
            lookupsLeft--;
            messages.add(MiniJson.obj("role", "assistant", "content", content));
            messages.add(MiniJson.obj("role", "user", "content", toolResults));
        }
    }

    /** Forces a single call of a custom tool and returns its input (used for the post-game review). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callTool(String model, int maxTokens, String system, String userPrompt,
                                        Map<String, Object> tool) {
        if (!hasKey()) return null;
        List<Object> messages = MiniJson.arr(MiniJson.obj("role", "user", "content", userPrompt));
        Map<String, Object> resp = call(model, maxTokens, system, messages, MiniJson.arr(tool),
                MiniJson.obj("type", "tool", "name", tool.get("name")), 0,
                System.currentTimeMillis() + Math.max(120, cfg.maxWaitSeconds) * 1000L);
        if (resp == null || !(resp.get("content") instanceof List)) return null;
        for (Object block : (List<Object>) resp.get("content")) {
            if (block instanceof Map<?, ?> b && "tool_use".equals(b.get("type")) && b.get("input") instanceof Map) {
                return (Map<String, Object>) b.get("input");
            }
        }
        return null;
    }

    /** One Messages API request with retries. Returns the parsed response, or null (lastError set). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String model, int maxTokens, String system, List<Object> messages,
                                     List<Object> tools, Map<String, Object> toolChoice, int thinkingBudget,
                                     long deadline) {
        Map<String, Object> body = MiniJson.obj(
            "model", model,
            "max_tokens", maxTokens + Math.max(0, thinkingBudget),
            "system", MiniJson.arr(MiniJson.obj("type", "text", "text", system,
                    "cache_control", MiniJson.obj("type", "ephemeral"))),
            "tools", tools,
            "tool_choice", toolChoice,
            "messages", messages
        );
        if (thinkingBudget > 0) {
            body.put("thinking", MiniJson.obj("type", "enabled", "budget_tokens", thinkingBudget));
        }
        String json = MiniJson.write(body);

        String err = null;
        for (int attempt = 0; attempt <= cfg.retries; attempt++) {
            long left = deadline - System.currentTimeMillis();
            if (left < 2000) {
                if (err == null) err = "out of time";
                break;
            }
            try {
                HttpResult r = post(json, (int) Math.min(cfg.timeoutSeconds * 1000L, left));
                if (r.code == 200) {
                    Object o = MiniJson.parse(r.body);
                    if (o instanceof Map) return (Map<String, Object>) o;
                    err = "unparseable response: " + r.body;
                    break;
                }
                err = "HTTP " + r.code + ": " + r.body;
                // retry on rate limit / overload / server errors only
                if (r.code != 429 && r.code != 529 && r.code < 500) break;
                long wait = Math.min(20000L, 1500L * (1L << attempt));
                if (r.retryAfterSeconds > 0) wait = r.retryAfterSeconds * 1000L;
                if (System.currentTimeMillis() + wait > deadline - 2000) break; // can't wait that long
                Thread.sleep(wait);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                err = "interrupted";
                break;
            } catch (java.net.SocketTimeoutException e) {
                err = "timed out waiting for the API (" + e.getMessage() + ")";
                break; // a slow API won't get faster by asking again: let the Forge AI play this one
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
        System.err.println("[Claude] API call failed: " + err);
        return null;
    }

    private static String describe(Decision d) {
        return "choice=" + d.choice + " choices=" + d.choices + " reason=" + d.reason
                + (d.notes != null ? "\n  notes=" + d.notes : "");
    }

    private static final class HttpResult {
        final int code;
        final String body;
        final int retryAfterSeconds;
        HttpResult(int c, String b, int ra) { code = c; body = b; retryAfterSeconds = ra; }
    }

    private HttpResult post(String json, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(cfg.apiUrl).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(Math.min(15000, readTimeoutMs));
        c.setReadTimeout(Math.max(1000, readTimeoutMs));
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
        int retryAfter = 0;
        try {
            String ra = c.getHeaderField("retry-after");
            if (ra != null) retryAfter = Integer.parseInt(ra.trim());
        } catch (NumberFormatException ignored) {
            // HTTP-date form: ignore and use backoff
        }
        c.disconnect();
        return new HttpResult(code, body, retryAfter);
    }

    static String readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString(StandardCharsets.UTF_8.name());
        }
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
        Object n = m.get("notes");
        return new Decision(r == null ? null : r.toString(), choice, choices, n == null ? null : n.toString());
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

    public void log(String tag, String msg) {
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
