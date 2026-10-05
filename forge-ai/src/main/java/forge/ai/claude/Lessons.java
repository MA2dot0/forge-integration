package forge.ai.claude;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Claude's long-term memory across games: a plain-text lessons file it writes after reviewing each game,
 * and a results file (claude-games.csv) for tracking the win rate.
 *
 * The lessons file is meant to be human-editable: one lesson per line starting with "- ". Lines starting
 * with "#" are comments. You can add your own tips; they are shown to Claude the same way.
 */
public final class Lessons {
    private Lessons() { }

    private static final List<Thread> pending = new ArrayList<>();

    /** All lessons (newest last), capped to the configured number. */
    public static List<String> load(ClaudeConfig cfg) {
        List<String> out = new ArrayList<>();
        File f = cfg.lessonsFile();
        if (!f.isFile()) return out;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (t.startsWith("- ")) t = t.substring(2);
                out.add(t);
            }
        } catch (Exception e) {
            System.err.println("[Claude] could not read lessons: " + e);
        }
        int max = Math.max(0, cfg.maxLessons);
        return out.size() > max ? new ArrayList<>(out.subList(out.size() - max, out.size())) : out;
    }

    /** Text block for the system prompt, or "" if there are no lessons. */
    public static String systemSection(ClaudeConfig cfg) {
        if (!cfg.lessons) return "";
        List<String> l = load(cfg);
        if (l.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("LESSONS from your previous games (written by you after reviewing them, "
                + "plus tips from the player who set you up). Apply them when relevant:\n");
        for (String s : l) sb.append("- ").append(s).append('\n');
        return sb.toString();
    }

    private static synchronized void append(File f, List<String> lines) {
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))) {
            for (String l : lines) w.println(l);
        } catch (Exception e) {
            System.err.println("[Claude] could not write " + f + ": " + e);
        }
    }

    /** Records the result line in claude-games.csv (date, my deck, opponent deck, result, turns, api questions). */
    public static void recordResult(ClaudeConfig cfg, String myDeck, String oppDeck, String result, int turns, int questions) {
        File f = new File(cfg.dataDir(), "claude-games.csv");
        List<String> lines = new ArrayList<>();
        if (!f.isFile()) lines.add("date,model,my_deck,opponent_deck,result,turns,questions");
        lines.add(String.join(",", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()), csv(cfg.model),
                csv(myDeck), csv(oppDeck), result, String.valueOf(turns), String.valueOf(questions)));
        append(f, lines);
    }

    private static String csv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /**
     * Starts the post-game review on a background thread: Claude reads the game log and its own decisions and
     * writes up to three new lessons into the lessons file.
     */
    public static void reviewAsync(ClaudeConfig cfg, String header, String gameLog, String decisions) {
        if (!cfg.lessons || !cfg.reviewAfterGame || cfg.apiKey == null || cfg.apiKey.isEmpty()) return;
        Thread t = new Thread(() -> review(cfg, header, gameLog, decisions), "Claude post-game review");
        t.setDaemon(false); // let it finish writing even if the match screen closes
        synchronized (pending) {
            pending.add(t);
        }
        t.start();
    }

    /** For tests and batch runs: waits for running reviews to finish. */
    public static void awaitReviews(long timeoutMs) {
        List<Thread> ts;
        synchronized (pending) {
            ts = new ArrayList<>(pending);
            pending.clear();
        }
        long end = System.currentTimeMillis() + timeoutMs;
        for (Thread t : ts) {
            try {
                t.join(Math.max(1, end - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void review(ClaudeConfig cfg, String header, String gameLog, String decisions) {
        List<String> existing = load(cfg);
        StringBuilder prompt = new StringBuilder();
        prompt.append(header).append("\n\n== GAME LOG (chronological) ==\n").append(gameLog)
              .append("\n\n== YOUR DECISIONS AND REASONS ==\n").append(decisions)
              .append("\n\n== EXISTING LESSONS (do not repeat these) ==\n");
        if (existing.isEmpty()) prompt.append("(none yet)\n");
        for (String s : existing) prompt.append("- ").append(s).append('\n');
        prompt.append("\nReview your play. Find the decisions that most affected the result (good or bad), and write "
                + "0 to 3 NEW lessons that would help you win future games. Each lesson must be one sentence, concrete "
                + "and reusable in other games (a principle, not a description of this game), e.g. 'Before attacking "
                + "with my only blocker, check whether the crack-back is lethal.' If you played well and there is "
                + "nothing new to learn, return an empty list. Call `record_lessons`.");

        String system = "You are reviewing a game of Magic: The Gathering that you (Claude) just played in the Forge "
                + "engine, to improve your future play. Be honest and specific about mistakes; do not invent events that "
                + "are not in the log.";
        Map<String, Object> tool = MiniJson.obj(
            "name", "record_lessons",
            "description", "Record the outcome of your review.",
            "input_schema", MiniJson.obj("type", "object",
                "properties", MiniJson.obj(
                    "summary", MiniJson.obj("type", "string", "description", "Two or three sentences: why the game was won or lost."),
                    "lessons", MiniJson.obj("type", "array", "items", MiniJson.obj("type", "string"),
                        "description", "0-3 new one-sentence lessons.")),
                "required", MiniJson.arr("summary", "lessons")));

        ClaudeClient client = new ClaudeClient(cfg);
        Map<String, Object> out = client.callTool(cfg.reviewModel, 1500, system, prompt.toString(), tool);
        if (out == null) {
            client.log("REVIEW ERROR", String.valueOf(client.lastError));
            return;
        }
        client.log("REVIEW", "summary: " + out.get("summary") + "\nlessons: " + out.get("lessons"));
        List<String> lines = new ArrayList<>();
        if (out.get("lessons") instanceof List<?> l) {
            for (Object o : l) {
                String s = GameStateWriter.oneLine(String.valueOf(o), 300);
                if (!s.isEmpty() && !existing.contains(s)) lines.add("- " + s);
            }
        }
        if (lines.isEmpty()) return;
        File f = cfg.lessonsFile();
        if (!f.isFile()) {
            lines.add(0, "# Lessons Claude wrote after reviewing its Forge games. One per line, starting with '- '.");
            lines.add(1, "# Edit freely: delete bad ones, add your own tips. Only the newest max_lessons are used.");
        }
        append(f, lines);
    }
}
