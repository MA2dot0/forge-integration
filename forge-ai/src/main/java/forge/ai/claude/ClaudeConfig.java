package forge.ai.claude;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Settings for the Claude player, read from claude.properties.
 *
 * Search order (first file found wins):
 *   1. -Dforge.claude.config=path
 *   2. env CLAUDE_FORGE_CONFIG
 *   3. any directory registered with {@link #addSearchDir} (the GUI registers Forge's user dir)
 *   4. the working directory (the Forge install folder on desktop)
 *   5. %APPDATA%/Forge, ~/.forge
 * The API key may also come from env ANTHROPIC_API_KEY (the file wins if both are set).
 */
public final class ClaudeConfig {
    private static final List<File> extraDirs = new ArrayList<>();
    private static ClaudeConfig cached;
    private static long cachedStamp = -1;

    public final String apiKey;
    public final String model;
    public final String apiUrl;
    public final int maxTokens;
    public final int thinkingBudget;
    public final int timeoutSeconds;
    public final int retries;
    /** "smart" = only ask at meaningful moments; "all" = every time priority is received with a playable option. */
    public final String priorityMode;
    public final boolean chooseTargets;
    public final boolean chooseTriggerTargets;
    public final boolean showReasoning;
    public final boolean logToFile;
    public final int logLines;
    public final String extraInstructions;
    public final File sourceFile;

    private ClaudeConfig(Properties p, File src) {
        String key = p.getProperty("api_key", "").trim();
        if (key.isEmpty()) {
            String env = System.getenv("ANTHROPIC_API_KEY");
            key = env == null ? "" : env.trim();
        }
        apiKey = key;
        model = p.getProperty("model", "claude-sonnet-5-5").trim();
        apiUrl = p.getProperty("api_url", "https://api.anthropic.com/v1/messages").trim();
        maxTokens = intProp(p, "max_tokens", 1024);
        thinkingBudget = intProp(p, "thinking_budget", 0);
        timeoutSeconds = intProp(p, "timeout_seconds", 90);
        retries = intProp(p, "retries", 3);
        priorityMode = p.getProperty("priority_mode", "smart").trim().toLowerCase();
        chooseTargets = boolProp(p, "choose_targets", true);
        chooseTriggerTargets = boolProp(p, "choose_trigger_targets", true);
        showReasoning = boolProp(p, "show_reasoning", true);
        logToFile = boolProp(p, "log_to_file", true);
        logLines = intProp(p, "log_lines", 15);
        extraInstructions = p.getProperty("extra_instructions", "").trim();
        sourceFile = src;
    }

    private static int intProp(Properties p, String k, int def) {
        try {
            return Integer.parseInt(p.getProperty(k, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean boolProp(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        if (v == null) return def;
        v = v.trim().toLowerCase();
        return v.equals("true") || v.equals("yes") || v.equals("1") || v.equals("on");
    }

    /** Lets the GUI layer (desktop or Android) add Forge's user data folder to the search path. */
    public static synchronized void addSearchDir(File dir) {
        if (dir != null && !extraDirs.contains(dir)) {
            extraDirs.add(dir);
            cachedStamp = -1;
        }
    }

    public static synchronized List<File> candidateFiles() {
        List<File> out = new ArrayList<>();
        String sys = System.getProperty("forge.claude.config");
        if (sys != null && !sys.isEmpty()) out.add(new File(sys));
        String env = System.getenv("CLAUDE_FORGE_CONFIG");
        if (env != null && !env.isEmpty()) out.add(new File(env));
        for (File d : extraDirs) out.add(new File(d, "claude.properties"));
        out.add(new File(System.getProperty("user.dir", "."), "claude.properties"));
        String appData = System.getenv("APPDATA");
        if (appData != null) out.add(new File(new File(appData, "Forge"), "claude.properties"));
        String home = System.getProperty("user.home");
        if (home != null) out.add(new File(new File(home, ".forge"), "claude.properties"));
        return out;
    }

    /** Returns the current settings, re-reading the file when it changes (so edits apply mid-game). */
    public static synchronized ClaudeConfig get() {
        File found = null;
        for (File f : candidateFiles()) {
            if (f.isFile()) { found = f; break; }
        }
        long stamp = found == null ? 0 : found.lastModified() ^ found.getAbsolutePath().hashCode();
        if (cached != null && stamp == cachedStamp) return cached;

        Properties p = new Properties();
        if (found != null) {
            try (InputStream in = new FileInputStream(found)) {
                p.load(in);
            } catch (Exception e) {
                System.err.println("[Claude] could not read " + found + ": " + e);
            }
        }
        cached = new ClaudeConfig(p, found);
        cachedStamp = stamp;
        return cached;
    }

    public File logFile() {
        File dir = sourceFile != null ? sourceFile.getAbsoluteFile().getParentFile()
                : new File(System.getProperty("user.dir", "."));
        return new File(dir, "claude-forge.log");
    }
}
