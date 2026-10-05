package forge.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.google.common.eventbus.Subscribe;

import forge.LobbyPlayer;
import forge.ai.claude.ClaudeClient;
import forge.ai.claude.ClaudeConfig;
import forge.ai.claude.ForgeAdvisor;
import forge.ai.claude.GameStateWriter;
import forge.ai.claude.Lessons;
import forge.ai.claude.Scryfall;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.event.GameEventGameOutcome;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.spellability.TargetRestrictions;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

/**
 * A player driven by Claude through the Anthropic API, working together with the Forge AI.
 *
 * Claude makes the strategic decisions: what to play and when, targets, attacks, blocks and mulligans.
 * For each question it gets help from Forge: exact combat numbers, the built-in AI's own choice as a second
 * opinion, and simulated outcomes of each candidate play. It keeps running notes during a game, and after the
 * game it reviews its play and writes lessons that are shown to it in later games.
 *
 * Everything else (mana payment, ordering, "may" prompts, choices inside effects) falls through to the
 * normal Forge AI, which is also the fallback whenever the API is unavailable or an answer is illegal.
 */
public class PlayerControllerClaude extends PlayerControllerAi {

    /** Hard cap on questions per priority window, so a confused answer can never stall the game. */
    private static final int MAX_CALLS_PER_WINDOW = 8;
    private static final int MAX_NOTES_CHARS = 1200;
    private static final boolean DEBUG = Boolean.getBoolean("claude.debug");

    /** Games already reviewed (a mind-control controller for the same player must not review twice). */
    private static final Map<Game, Set<Integer>> REVIEWED = Collections.synchronizedMap(new WeakHashMap<>());

    private String windowKey = "";
    private int callsInWindow = 0;
    private final Set<SpellAbility> triedInWindow = new HashSet<>();
    private String failedStepKey = "";
    private final Set<SpellAbility> failedThisStep = new HashSet<>();
    private boolean warnedNoKey = false;
    private boolean warnedError = false;

    // memory for this game
    private String notes = "";
    private final List<String> decisions = new ArrayList<>();
    private int questions = 0;
    private String lessonsSection = null; // loaded once per game so the system prompt stays cacheable
    private boolean prefetched = false;

    public PlayerControllerClaude(Game game, Player p, LobbyPlayer lp) {
        super(game, p, lp);
        try {
            game.subscribeToEvents(new GameOverListener(this));
        } catch (RuntimeException e) {
            // without the listener there is just no post-game review
        }
    }

    // ------------------------------------------------------------------ plumbing

    private ClaudeConfig cfg() {
        return ClaudeConfig.get();
    }

    /**
     * Asks Claude; returns null (=> use Forge AI) if there is no key or the call fails.
     * {@code relevant} are the cards this question is about (their official rulings are attached).
     */
    private ClaudeClient.Decision ask(String question, Collection<Card> relevant) {
        ClaudeConfig cfg = cfg();
        ClaudeClient client = new ClaudeClient(cfg);
        if (!client.hasKey()) {
            if (!warnedNoKey) {
                warnedNoKey = true;
                note("no API key found, so the Forge AI is playing instead. Add api_key=... to claude.properties "
                        + "(searched: " + ClaudeConfig.candidateFiles() + ") or set ANTHROPIC_API_KEY.");
            }
            return null;
        }
        if (ClaudeClient.isPaused()) return null;
        if (!prefetched) {
            prefetched = true;
            prefetchRulings(cfg);
        }
        if (lessonsSection == null) lessonsSection = Lessons.systemSection(cfg);

        StringBuilder prompt = new StringBuilder(GameStateWriter.describe(getGame(), player, cfg.logLines));
        if (cfg.deckInfo) prompt.append('\n').append(GameStateWriter.library(player));
        if (cfg.combatMath) {
            try {
                prompt.append("\nFORGE MATH (exact, computed by the engine; ignores tricks still in hands):\n")
                      .append(ForgeAdvisor.boardMath(getGame(), player));
            } catch (RuntimeException e) {
                // optional
            }
        }
        if (cfg.rulings && relevant != null) {
            prompt.append(GameStateWriter.rulingsSection(relevantWithStack(relevant), Scryfall.get(cfg), cfg.rulingsPerCard));
        }
        if (cfg.notes) {
            prompt.append("\nYOUR NOTES (written by you earlier this game):\n")
                  .append(notes.isEmpty() ? "(none yet)" : notes).append('\n');
        }
        prompt.append("\n== QUESTION ==\n").append(question);

        ClaudeClient.ToolRunner lookups = cfg.scryfallLookup ? (name, input) -> Scryfall.get(cfg).runTool(name, input) : null;
        ClaudeClient.Decision d = client.decide(lessonsSection, prompt.toString(), lookups);
        questions++;
        if (d == null && !warnedError) {
            warnedError = true;
            note("API call failed (" + GameStateWriter.oneLine(client.lastError, 200)
                    + "). Falling back to the Forge AI for this decision.");
        }
        if (d != null && d.notes != null && cfg.notes) {
            String n = d.notes.trim();
            notes = n.length() > MAX_NOTES_CHARS ? n.substring(0, MAX_NOTES_CHARS) : n;
        }
        return d;
    }

    private Collection<Card> relevantWithStack(Collection<Card> relevant) {
        Set<Card> out = new LinkedHashSet<>(relevant);
        for (SpellAbilityStackInstance si : getGame().getStack()) {
            SpellAbility sa = si.getSpellAbility();
            if (sa != null && sa.getHostCard() != null) out.add(sa.getHostCard());
        }
        return out;
    }

    /** Starts fetching official rulings for every card in the game in the background (cached on disk). */
    private void prefetchRulings(ClaudeConfig cfg) {
        if (!cfg.rulings) return;
        try {
            Set<String> names = new LinkedHashSet<>();
            for (Player p : getGame().getPlayers()) {
                for (ZoneType z : new ZoneType[] {ZoneType.Library, ZoneType.Hand, ZoneType.Battlefield,
                        ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command}) {
                    for (Card c : p.getCardsIn(z)) {
                        if (!c.isToken() && !(c.isLand() && c.getType().isBasic())) names.add(c.getName());
                    }
                }
            }
            Scryfall.get(cfg).prefetch(names);
        } catch (RuntimeException e) {
            // rulings are optional
        }
    }

    private void note(String msg) {
        getGame().getGameLog().add(GameLogEntryType.INFORMATION, player.getName() + " (Claude): " + msg);
    }

    private void say(ClaudeClient.Decision d, String what) {
        if (d == null) return;
        String turn = "T" + getGame().getPhaseHandler().getTurn() + " "
                + (getGame().getPhaseHandler().getPhase() == null ? "" : getGame().getPhaseHandler().getPhase().nameForUi);
        decisions.add(turn + ": " + what + " - " + GameStateWriter.oneLine(d.reason, 300));
        if (cfg().showReasoning && !d.reason.isEmpty()) {
            note(what + " - " + GameStateWriter.oneLine(d.reason, 300));
        }
    }

    /** Mana/plan reservations the Forge AI makes while merely *considering* a play; they must not leak into Claude's plays. */
    private static final AiCardMemory.MemorySet[] RESERVATIONS = {
        AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2,
        AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_DECLBLK,
        AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK,
        AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL,
        AiCardMemory.MemorySet.CHOSEN_FOG_EFFECT,
    };
    private static final AiCardMemory.MemorySet[] COST_PLANS = {
        AiCardMemory.MemorySet.PAYS_TAP_COST,
        AiCardMemory.MemorySet.PAYS_SAC_COST,
    };

    private List<Set<Card>> saveMemory(AiCardMemory.MemorySet[] sets) {
        List<Set<Card>> out = new ArrayList<>();
        for (AiCardMemory.MemorySet m : sets) out.add(new HashSet<>(AiCardMemory.<Card>getMemorySet(player, m)));
        return out;
    }

    private void restoreMemory(AiCardMemory.MemorySet[] sets, List<Set<Card>> saved) {
        for (int i = 0; i < sets.length; i++) {
            Set<Card> live = AiCardMemory.getMemorySet(player, sets[i]);
            live.clear();
            live.addAll(saved.get(i));
        }
    }

    private static void dbg(String why, Object o) {
        if (DEBUG) System.err.println("[Claude] skip " + why + ": " + o);
    }

    private static String saLabel(SpellAbility sa) {
        Card host = sa.getHostCard();
        String name = host == null ? "?" : "#" + host.getId() + " " + host.getName();
        String desc;
        try {
            desc = sa.toString();
        } catch (RuntimeException e) {
            desc = sa.getDescription();
        }
        desc = GameStateWriter.oneLine(desc, 260);
        if (sa.isLandAbility()) {
            return "Play land " + name;
        }
        String zone = "";
        if (host != null && host.getZone() != null) {
            ZoneType z = host.getZone().getZoneType();
            if (z != ZoneType.Hand && z != ZoneType.Battlefield) zone = " (from " + z.name().toLowerCase() + ")";
        }
        if (sa.isSpell()) {
            String cost = host != null && host.getManaCost() != null ? " " + host.getManaCost().getSimpleString() : "";
            return "Cast " + name + cost + zone + ": " + desc;
        }
        return "Activate " + name + zone + ": " + desc;
    }

    private static String shortLabel(SpellAbility sa) {
        return saLabel(sa).split(":")[0];
    }

    // ------------------------------------------------------------------ priority: what to play

    private String currentWindowKey() {
        Game g = getGame();
        int bf = 0;
        int gy = 0;
        for (Player p : g.getPlayers()) {
            bf += p.getCardsIn(ZoneType.Battlefield).size();
            gy += p.getCardsIn(ZoneType.Graveyard).size();
        }
        SpellAbility top = g.getStack().isEmpty() ? null : g.getStack().peekAbility();
        return g.getPhaseHandler().getTurn() + ":" + g.getPhaseHandler().getPhase() + ":" + g.getStack().size() + ":"
                + System.identityHashCode(top) + ":" + player.getCardsIn(ZoneType.Hand).size() + ":" + bf + ":" + gy
                + ":" + player.getLandsPlayedThisTurn();
    }

    /** In "smart" mode only spend an API call at moments where a play is plausible. */
    private boolean worthAsking(List<SpellAbility> options) {
        if ("all".equals(cfg().priorityMode)) return true;
        Game g = getGame();
        PhaseType ph = g.getPhaseHandler().getPhase();
        boolean myTurn = g.getPhaseHandler().getPlayerTurn() == player;
        if (!g.getStack().isEmpty()) return true;
        if (myTurn && (ph == PhaseType.MAIN1 || ph == PhaseType.MAIN2)) return true;
        if (ph == PhaseType.COMBAT_DECLARE_BLOCKERS) return true;              // tricks after blocks, either side
        if (!myTurn && ph == PhaseType.COMBAT_DECLARE_ATTACKERS) return true;  // answer attackers before blocks
        if (!myTurn && ph == PhaseType.END_OF_TURN) return true;               // end-of-turn instants
        if (myTurn && ph == PhaseType.UPKEEP) {
            // only bother if something is not a land (e.g. an upkeep-relevant activation)
            for (SpellAbility sa : options) if (!sa.isLandAbility()) return true;
        }
        return false;
    }

    private List<SpellAbility> playableOptions() {
        Game g = getGame();
        CardCollection cards = ComputerUtilCard.dedupeCards(ComputerUtilAbility.getAvailableCards(g, player));
        List<SpellAbility> out = new ArrayList<>();
        Set<Card> landsSeen = new HashSet<>();
        for (Card c : cards) {
            List<SpellAbility> abilities;
            try {
                abilities = c.getAllPossibleAbilities(player, true);
            } catch (RuntimeException e) {
                continue;
            }
            for (SpellAbility sa : abilities) {
                try {
                    if (sa.isManaAbility() || triedInWindow.contains(sa)) continue;
                    sa.setActivatingPlayer(player);
                    if (sa.isLandAbility()) {
                        if (landsSeen.add(c)) out.add(sa);
                        continue;
                    }
                    if (sa.isSpell() && !sa.canCastTiming(player)) { dbg("timing", sa); continue; }
                    if (!canAfford(sa)) { dbg("cost", sa); continue; }
                    if (lacksTargets(sa)) { dbg("targets", sa); continue; }
                    out.add(sa);
                } catch (RuntimeException e) {
                    // a card whose checks throw is simply not offered
                }
            }
        }
        return out;
    }

    /** Cost check done the way the Forge AI does it (spells need their cast SA set on the host while checking). */
    private boolean canAfford(SpellAbility sa) {
        final Card host = sa.getHostCard();
        if (sa instanceof Spell sp) {
            Card alt = sp.canPlayFromHost();
            if (alt == null) return false;
            if (alt != host) sa.setHostCard(alt);
            alt.setCastSA(sa);
            try {
                return ComputerUtilCost.canPayCost(sa, player, false);
            } finally {
                if (sa.getHostCard() != host) sa.setHostCard(host);
                alt.setCastSA(null);
                host.setCastSA(null);
            }
        }
        return ComputerUtilCost.canPayCost(sa, player, false);
    }

    /** Index (1-based) of the AI's pick within the options, 0 for pass, -1 if it picked something not listed. */
    private static int indexOfAiPick(List<SpellAbility> options, List<SpellAbility> aiPick) {
        if (aiPick == null || aiPick.isEmpty() || aiPick.get(0) == null) return 0;
        SpellAbility a = aiPick.get(0);
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i) == a) return i + 1;
        }
        String la = saLabel(a);
        for (int i = 0; i < options.size(); i++) {
            if (saLabel(options.get(i)).equals(la)) return i + 1;
        }
        // lands: the AI returns the land abilities of the card it wants to play
        if (a.isLandAbility()) {
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).isLandAbility() && options.get(i).getHostCard() == a.getHostCard()) return i + 1;
            }
        }
        return -1;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        try {
            return choosePlay();
        } catch (RuntimeException e) {
            System.err.println("[Claude] error while choosing a play, Forge AI takes over: " + e);
            if (DEBUG) e.printStackTrace();
            return super.chooseSpellAbilityToPlay();
        }
    }

    private List<SpellAbility> choosePlay() {
        String key = currentWindowKey();
        if (!key.equals(windowKey)) {
            windowKey = key;
            callsInWindow = 0;
            triedInWindow.clear();
        }
        String stepKey = getGame().getPhaseHandler().getTurn() + ":" + getGame().getPhaseHandler().getPhase();
        if (!stepKey.equals(failedStepKey)) {
            failedStepKey = stepKey;
            failedThisStep.clear();
        }
        ClaudeConfig cfg = cfg();

        String failNote = "";
        for (int attempt = 0; attempt < 3; attempt++) {
            List<SpellAbility> options;
            try {
                options = playableOptions();
            } catch (RuntimeException e) {
                return super.chooseSpellAbilityToPlay();
            }
            options.removeIf(failedThisStep::contains);
            if (options.isEmpty() || !worthAsking(options) || callsInWindow >= MAX_CALLS_PER_WINDOW) {
                return null; // pass priority
            }
            if (!new ClaudeClient(cfg).hasKey() || ClaudeClient.isPaused()) {
                ask("", null); // shows the "no key" note once
                return super.chooseSpellAbilityToPlay();
            }
            callsInWindow++;

            // second opinion from the Forge AI (its choice also sets up X/targets on that SA)
            List<SpellAbility> aiPick = null;
            String aiLine = "";
            if (cfg.aiSuggestions) {
                List<Set<Card>> held = saveMemory(RESERVATIONS);
                List<Set<Card>> costs = saveMemory(COST_PLANS);
                try {
                    aiPick = super.chooseSpellAbilityToPlay();
                    int idx = indexOfAiPick(options, aiPick);
                    if (idx == 0) {
                        aiLine = "Forge AI suggests: 0 (pass).\n";
                    } else if (idx > 0) {
                        aiLine = "Forge AI suggests: " + idx + ForgeAdvisor.targetsOf(options.get(idx - 1), player) + ".\n";
                    } else {
                        aiLine = "Forge AI suggests: " + shortLabel(aiPick.get(0)) + " (not one of your listed options).\n";
                    }
                } catch (RuntimeException e) {
                    aiPick = null;
                } finally {
                    restoreMemory(RESERVATIONS, held);
                    restoreMemory(COST_PLANS, costs);
                }
            }

            // simulated outcome of each option
            ForgeAdvisor.SimResult sim = null;
            if (cfg.simulate) {
                // land drops are left out: the evaluator's mana-base term makes them look huge and says little
                List<SpellAbility> toSim = new ArrayList<>();
                for (SpellAbility o : options) if (!o.isLandAbility()) toSim.add(o);
                if (!toSim.isEmpty()) sim = ForgeAdvisor.simulate(player, toSim, cfg.simDepth, cfg.simTimeMs);
                if (DEBUG && sim != null) System.err.println("[Claude] simulated " + sim.simulated + "/" + toSim.size() + " in " + sim.millis + " ms");
            }

            Set<Card> relevant = new LinkedHashSet<>();
            StringBuilder q = new StringBuilder();
            q.append("You have priority. Choose what to do (one option):\n");
            q.append("  0. Pass priority (do nothing now)").append(sim != null && sim.simulated > 0 ? "   [sim: 0]" : "").append('\n');
            for (int i = 0; i < options.size(); i++) {
                SpellAbility sa = options.get(i);
                if (sa.getHostCard() != null) relevant.add(sa.getHostCard());
                q.append("  ").append(i + 1).append(". ").append(saLabel(sa));
                if (sim != null && sim.labels.containsKey(sa)) q.append("   [").append(sim.labels.get(sa)).append(']');
                q.append('\n');
            }
            if (sim != null && sim.labels.size() < options.stream().filter(o -> !o.isLandAbility()).count() && sim.simulated > 0) {
                q.append("(simulation time ran out before all options were scored)\n");
            }
            q.append(aiLine);
            q.append(failNote);
            q.append("Mana is paid automatically. Answer with `choice`.");

            ClaudeClient.Decision d = ask(q.toString(), relevant);
            if (d == null) {
                return aiPick != null ? aiPick : super.chooseSpellAbilityToPlay();
            }
            Integer pick = d.choice != null ? d.choice
                    : (d.choices != null && !d.choices.isEmpty() ? d.choices.get(0) : Integer.valueOf(0));
            if (pick == null || pick <= 0 || pick > options.size()) {
                say(d, "passes");
                return null;
            }
            SpellAbility sa = options.get(pick - 1);
            String label = shortLabel(sa);
            triedInWindow.add(sa);
            say(d, label);

            if (sa.isLandAbility() || prepareChosen(sa)) {
                List<SpellAbility> l = new ArrayList<>();
                l.add(sa);
                return l;
            }
            failedThisStep.add(sa);
            note("could not set up " + label + " legally (no legal targets or can't pay); choosing again.");
            failNote = "NOTE: your previous choice (" + label + ") could not be played legally right now "
                    + "(no legal targets, or the cost can't be paid). It has been removed from the list.\n";
        }
        return null;
    }

    /** Sets X / modes / targets for the chosen spell or ability. Returns false if it can't be played legally. */
    private boolean prepareChosen(SpellAbility sa) {
        sa.setActivatingPlayer(player);
        final Card host = sa.getHostCard();
        AiPlayDecision dec;
        List<Set<Card>> held = saveMemory(RESERVATIONS);
        Card alt = null;
        try {
            // let the Forge AI do the fiddly setup (X value, default targets), exactly as it does for its own plays
            if (sa instanceof Spell sp) {
                alt = sp.canPlayFromHost();
                if (alt == null) return false;
                if (alt != host) sa.setHostCard(alt);
                alt.setCastSA(sa);
            }
            dec = getAi().canPlaySa(sa);
        } catch (RuntimeException e) {
            dec = AiPlayDecision.CantPlaySa;
        } finally {
            if (sa.getHostCard() != host) sa.setHostCard(host);
            if (alt != null) alt.setCastSA(null);
            if (sa.isSpell()) host.setCastSA(null);
            // the AI may have reserved mana "for later" while judging the play; Claude has already decided
            restoreMemory(RESERVATIONS, held);
        }

        // the AI may have declined (it doesn't like the play) without choosing targets: force a legal setup
        if (dec != AiPlayDecision.WillPlay && needsTargets(sa)) {
            try {
                for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
                    if (s.usesTargeting()) s.resetTargets();
                }
                getAi().doTrigger(sa, true);
            } catch (RuntimeException ignored) {
                // validated below
            }
        }

        if (lacksTargets(sa)) return false;

        // Claude chooses the targets itself
        if (cfg().chooseTargets) {
            for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
                if (s.usesTargeting()) claudeChooseTargets(s, sa);
            }
        }

        if (needsTargets(sa)) return false;
        try {
            return canAfford(sa);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True if some targeting step has no legal target at all (so the play can't be legal). */
    private static boolean lacksTargets(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (!s.usesTargeting()) continue;
            if (s.getActivatingPlayer() == null) s.setActivatingPlayer(sa.getActivatingPlayer());
            if (s.getMinTargets() > 0 && s.getTargetRestrictions().getNumCandidates(s) == 0) return true;
        }
        return false;
    }

    private static boolean needsTargets(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting() && !s.isTargetNumberValid()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ targets

    /**
     * Lets Claude pick the targets for one targeting step. Keeps the existing (AI) targets if Claude's answer
     * is not legal. Returns true if Claude's targets were applied.
     */
    private boolean claudeChooseTargets(SpellAbility s, SpellAbility root) {
        TargetChoices before = s.getTargets().clone();
        try {
            return claudeChooseTargetsImpl(s, root);
        } catch (RuntimeException e) {
            System.err.println("[Claude] error while choosing targets, keeping the Forge AI's: " + e);
            s.resetTargets();
            s.setTargets(before);
            return false;
        }
    }

    private boolean claudeChooseTargetsImpl(SpellAbility s, SpellAbility root) {
        TargetRestrictions tr = s.getTargetRestrictions();
        if (tr == null || s.isDividedAsYouChoose()) return false;
        if (tr.getZone() != null && tr.getZone().contains(ZoneType.Stack)) return false; // spells as targets: AI

        TargetChoices saved = s.getTargets().clone();
        s.resetTargets();
        List<GameEntity> candidates;
        int min;
        int max;
        try {
            candidates = tr.getAllCandidates(s);
            min = s.getMinTargets();
            max = s.getMaxTargets();
        } catch (RuntimeException e) {
            s.setTargets(saved);
            return false;
        }
        // nothing to decide
        if (candidates.isEmpty() || max <= 0) {
            s.setTargets(saved);
            return false;
        }
        if (candidates.size() == 1 && min >= 1) {
            // only one legal target: take it (the AI may have refused to pick it when it disliked the play)
            s.setTargets(saved);
            if (!s.isTargetNumberValid()) {
                s.resetTargets();
                if (s.canTarget(candidates.get(0))) s.getTargets().add(candidates.get(0));
            }
            return false;
        }

        Set<Card> relevant = new LinkedHashSet<>();
        StringBuilder q = new StringBuilder();
        Card host = root.getHostCard();
        if (host != null) relevant.add(host);
        q.append("Choose targets for ").append(host == null ? "?" : "#" + host.getId() + " " + host.getName())
         .append(": ").append(GameStateWriter.oneLine(root.toString(), 300)).append('\n');
        if (tr.getVTSelection() != null && !tr.getVTSelection().isEmpty()) {
            q.append("This step: ").append(GameStateWriter.oneLine(tr.getVTSelection(), 150)).append('\n');
        }
        q.append("Pick ").append(min == max ? "exactly " + min : "between " + min + " and " + max)
         .append(" target(s):\n");
        List<Integer> aiIdx = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            GameEntity e = candidates.get(i);
            if (e instanceof Card c && relevant.size() < 10) relevant.add(c);
            if (saved.contains(e)) aiIdx.add(i + 1);
            q.append("  ").append(i + 1).append(". ").append(describeTarget(e)).append('\n');
        }
        if (cfg().aiSuggestions && !aiIdx.isEmpty()) q.append("Forge AI suggests: ").append(aiIdx).append(".\n");
        q.append(max == 1 ? "Answer with `choice`." : "Answer with `choices` (a list of option numbers).");

        ClaudeClient.Decision d = ask(q.toString(), relevant);
        if (d == null) {
            s.setTargets(saved);
            return false;
        }
        for (Integer idx : d.all()) {
            if (idx == null || idx < 1 || idx > candidates.size()) continue;
            if (s.getTargets().size() >= max) break;
            GameEntity e = candidates.get(idx - 1);
            if (!s.getTargets().contains(e) && s.canTarget(e)) s.getTargets().add(e);
        }
        if (!s.isTargetNumberValid()) {
            s.resetTargets();
            s.setTargets(saved);
            return false;
        }
        StringBuilder chosen = new StringBuilder();
        for (Object o : s.getTargets()) {
            if (chosen.length() > 0) chosen.append(", ");
            chosen.append(o instanceof GameEntity ge ? GameStateWriter.entityName(ge, player) : String.valueOf(o));
        }
        say(d, (host == null ? "" : host.getName() + " ") + "targets " + chosen);
        return true;
    }

    private String describeTarget(GameEntity e) {
        if (e instanceof Player p) {
            return (p == player ? "You (" + p.getName() + ")" : "Player " + p.getName()) + ", life " + p.getLife();
        }
        if (e instanceof Card c) {
            String owner = c.getController() == player ? "[yours] " : "[" + c.getController().getName() + "'s] ";
            String where = c.getZone() != null && c.getZone().getZoneType() != ZoneType.Battlefield
                    ? " (in " + c.getZone().getZoneType().name().toLowerCase() + ")" : "";
            return owner + GameStateWriter.cardLine(c, player, getGame(), false) + where;
        }
        return String.valueOf(e);
    }

    /** Triggered abilities: let the AI set things up, then let Claude re-pick the targets. */
    @Override
    protected boolean prepareSingleSa(final Card host, SpellAbility sa, boolean isMandatory) {
        boolean ok = super.prepareSingleSa(host, sa, isMandatory);
        if (!ok || !cfg().chooseTriggerTargets) return ok;
        SpellAbility root = sa instanceof WrappedAbility w ? w.getWrappedAbility() : sa;
        try {
            for (SpellAbility s = root; s != null; s = s.getSubAbility()) {
                if (s.usesTargeting() && s.getTargetingPlayer() == null) claudeChooseTargets(s, root);
            }
        } catch (RuntimeException e) {
            // keep whatever the AI picked
        }
        return ok;
    }

    // ------------------------------------------------------------------ combat

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        try {
            claudeAttack(attacker, combat);
        } catch (RuntimeException e) {
            System.err.println("[Claude] error while attacking, Forge AI takes over: " + e);
            combat.clearAttackers();
            super.declareAttackers(attacker, combat);
        }
    }

    private void claudeAttack(Player attacker, Combat combat) {
        if (attacker != player || !new ClaudeClient(cfg()).hasKey() || ClaudeClient.isPaused()) {
            super.declareAttackers(attacker, combat);
            return;
        }
        ClaudeConfig cfg = cfg();
        List<Card> atk = new ArrayList<>();
        List<GameEntity> def = new ArrayList<>();
        for (Card c : player.getCreaturesInPlay()) {
            if (!CombatUtil.canAttack(c)) continue;
            for (GameEntity d : combat.getDefenders()) {
                if (CombatUtil.canAttack(c, d)) {
                    atk.add(c);
                    def.add(d);
                }
            }
        }
        if (atk.isEmpty()) {
            super.declareAttackers(attacker, combat);
            return;
        }

        Map<Card, GameEntity> aiAtk = cfg.aiSuggestions ? ForgeAdvisor.aiAttackSuggestion(this, player, combat) : null;

        Set<Card> relevant = new LinkedHashSet<>(atk);
        StringBuilder q = new StringBuilder("Declare attackers. Options (creature -> what it attacks):\n");
        List<Integer> aiIdx = new ArrayList<>();
        for (int i = 0; i < atk.size(); i++) {
            Card c = atk.get(i);
            q.append("  ").append(i + 1).append(". ").append(GameStateWriter.shortCard(c))
             .append(" -> ").append(GameStateWriter.entityName(def.get(i), player));
            if (cfg.combatMath) q.append("   [").append(ForgeAdvisor.attackerFacts(player, c, def.get(i))).append(']');
            q.append('\n');
            if (aiAtk != null && def.get(i).equals(aiAtk.get(c))) aiIdx.add(i + 1);
        }
        for (GameEntity d : combat.getDefenders()) {
            if (d instanceof Player p) relevant.addAll(p.getCreaturesInPlay());
        }
        if (aiAtk != null) q.append("Forge AI suggests: ").append(aiIdx.isEmpty() ? "no attack" : aiIdx.toString()).append(".\n");
        q.append("Each creature can attack only one thing. Answer with `choices` (empty list = no attack).");

        ClaudeClient.Decision d = ask(q.toString(), relevant);
        if (d == null) {
            super.declareAttackers(attacker, combat);
            return;
        }
        Set<Card> used = new HashSet<>();
        StringBuilder desc = new StringBuilder();
        for (Integer idx : d.all()) {
            if (idx == null || idx < 1 || idx > atk.size()) continue;
            Card c = atk.get(idx - 1);
            if (!used.add(c)) continue;
            combat.addAttacker(c, def.get(idx - 1));
            if (desc.length() > 0) desc.append(", ");
            desc.append(c.getName());
        }
        removeUnpayableAttackers(combat);
        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            note("that attack wasn't legal, so the Forge AI declared attackers instead.");
            super.declareAttackers(attacker, combat);
            return;
        }
        say(d, desc.length() == 0 ? "no attack" : "attacks with " + desc);
    }

    /** Same as the AI's: drop attackers whose attack tax (Propaganda etc.) we can't pay. */
    private void removeUnpayableAttackers(Combat combat) {
        for (Card a : combat.getAttackers().threadSafeIterable()) {
            Cost attackCost = CombatUtil.getAttackCost(getGame(), a, combat.getDefenderByAttacker(a));
            if (attackCost == null) continue;
            SpellAbility fakeSA = new SpellAbility.EmptySa(a, a.getController());
            fakeSA.setCardState(a.getCurrentState());
            fakeSA.setPayCosts(attackCost);
            fakeSA.setSVar("X", "0");
            if (!ComputerUtilCost.canPayCost(attackCost, fakeSA, player, true)) {
                combat.removeFromCombat(a);
            }
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        try {
            claudeBlock(defender, combat);
        } catch (RuntimeException e) {
            System.err.println("[Claude] error while blocking, Forge AI takes over: " + e);
            for (Card b : defender.getCreaturesInPlay()) {
                if (combat.isBlocking(b)) combat.undoBlockingAssignment(b);
            }
            super.declareBlockers(defender, combat);
        }
    }

    private void claudeBlock(Player defender, Combat combat) {
        if (defender != player || !new ClaudeClient(cfg()).hasKey() || ClaudeClient.isPaused()) {
            super.declareBlockers(defender, combat);
            return;
        }
        ClaudeConfig cfg = cfg();
        List<Card> atk = new ArrayList<>();
        List<Card> blk = new ArrayList<>();
        CardCollection attackers = combat.getAttackers();
        for (Card b : player.getCreaturesInPlay()) {
            if (!CombatUtil.canBlock(b, combat)) continue;
            for (Card a : attackers) {
                if (CombatUtil.canBlock(a, b, combat)) {
                    atk.add(a);
                    blk.add(b);
                }
            }
        }
        if (atk.isEmpty()) {
            super.declareBlockers(defender, combat);
            return;
        }

        Map<Card, Card> aiBlk = cfg.aiSuggestions ? ForgeAdvisor.aiBlockSuggestion(this, player, combat) : null;

        Set<Card> relevant = new LinkedHashSet<>(attackers);
        relevant.addAll(blk);
        StringBuilder q = new StringBuilder();
        if (cfg.combatMath) q.append("FORGE MATH for this combat:\n").append(ForgeAdvisor.incomingFacts(player, combat));
        q.append("Declare blockers. Possible blocks (your blocker blocks attacker):\n");
        List<Integer> aiIdx = new ArrayList<>();
        for (int i = 0; i < atk.size(); i++) {
            q.append("  ").append(i + 1).append(". ").append(GameStateWriter.shortCard(blk.get(i)))
             .append(" blocks ").append(GameStateWriter.shortCard(atk.get(i)));
            if (cfg.combatMath) q.append("   [").append(ForgeAdvisor.blockFacts(player, atk.get(i), blk.get(i), combat)).append(']');
            q.append('\n');
            if (aiBlk != null && atk.get(i).equals(aiBlk.get(blk.get(i)))) aiIdx.add(i + 1);
        }
        if (aiBlk != null) q.append("Forge AI suggests: ").append(aiIdx.isEmpty() ? "no blocks" : aiIdx.toString()).append(".\n");
        q.append("Normally each blocker blocks one attacker; several blockers may block the same attacker. "
                + "Answer with `choices` (empty list = no blocks).");

        ClaudeClient.Decision d = ask(q.toString(), relevant);
        if (d == null) {
            super.declareBlockers(defender, combat);
            return;
        }
        List<Card> added = new ArrayList<>();
        StringBuilder desc = new StringBuilder();
        for (Integer idx : d.all()) {
            if (idx == null || idx < 1 || idx > atk.size()) continue;
            Card a = atk.get(idx - 1);
            Card b = blk.get(idx - 1);
            if (!CombatUtil.canBlock(a, b, combat)) continue;
            combat.addBlocker(a, b);
            added.add(b);
            if (desc.length() > 0) desc.append(", ");
            desc.append(b.getName()).append(" blocks ").append(a.getName());
        }
        if (CombatUtil.validateBlocks(combat, player) != null) {
            for (Card b : added) combat.undoBlockingAssignment(b);
            note("those blocks weren't legal, so the Forge AI declared blockers instead.");
            super.declareBlockers(defender, combat);
            return;
        }
        say(d, desc.length() == 0 ? "no blocks" : desc.toString());
    }

    // ------------------------------------------------------------------ mulligans

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        try {
            return claudeMulligan(firstPlayer, cardsToReturn);
        } catch (RuntimeException e) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
    }

    private boolean claudeMulligan(Player firstPlayer, int cardsToReturn) {
        StringBuilder q = new StringBuilder();
        q.append("Mulligan decision. You are ").append(firstPlayer == player ? "on the PLAY" : "on the DRAW").append(". ");
        if (cardsToReturn > 0) {
            q.append("If you keep, you will put ").append(cardsToReturn).append(" card(s) from this hand on the bottom. ");
        }
        q.append("\n  1. Keep this hand\n  2. Mulligan\n");
        if (cfg().aiSuggestions) {
            try {
                q.append("Forge AI suggests: ").append(ComputerUtil.wantMulligan(player, cardsToReturn) ? 2 : 1).append(".\n");
            } catch (RuntimeException ignored) {
                // no suggestion
            }
        }
        q.append("Answer with `choice`.");
        ClaudeClient.Decision d = ask(q.toString(), player.getCardsIn(ZoneType.Hand));
        if (d == null || d.choice == null || (d.choice != 1 && d.choice != 2)) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        say(d, d.choice == 1 ? "keeps" : "mulligans");
        return d.choice == 1;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        try {
            return claudeTuck(hand, cardsToReturn);
        } catch (RuntimeException e) {
            return super.tuckCardsViaMulligan(hand, cardsToReturn);
        }
    }

    private CardCollectionView claudeTuck(CardCollectionView hand, int cardsToReturn) {
        List<Card> cards = new ArrayList<>();
        for (Card c : hand) cards.add(c);
        StringBuilder q = new StringBuilder("You kept. Choose exactly ").append(cardsToReturn)
                .append(" card(s) to put on the bottom of your library:\n");
        for (int i = 0; i < cards.size(); i++) {
            q.append("  ").append(i + 1).append(". ").append(GameStateWriter.cardLine(cards.get(i), player, getGame(), true)).append('\n');
        }
        CardCollectionView aiTuck = null;
        if (cfg().aiSuggestions) {
            try {
                aiTuck = super.tuckCardsViaMulligan(hand, cardsToReturn);
                List<Integer> idx = new ArrayList<>();
                for (Card c : aiTuck) idx.add(cards.indexOf(c) + 1);
                q.append("Forge AI suggests: ").append(idx).append(".\n");
            } catch (RuntimeException e) {
                aiTuck = null;
            }
        }
        q.append("Answer with `choices`.");
        ClaudeClient.Decision d = ask(q.toString(), cards);
        if (d != null) {
            CardCollection out = new CardCollection();
            for (Integer idx : d.all()) {
                if (idx == null || idx < 1 || idx > cards.size()) continue;
                Card c = cards.get(idx - 1);
                if (!out.contains(c) && out.size() < cardsToReturn) out.add(c);
            }
            if (out.size() == cardsToReturn) {
                say(d, "bottoms " + out.size() + " card(s)");
                return out;
            }
        }
        return aiTuck != null ? aiTuck : super.tuckCardsViaMulligan(hand, cardsToReturn);
    }

    // ------------------------------------------------------------------ after the game

    /** Guava EventBus subscriber: reviews the game once it is over. */
    public static final class GameOverListener {
        private final PlayerControllerClaude owner;

        GameOverListener(PlayerControllerClaude owner) {
            this.owner = owner;
        }

        @Subscribe
        public void onOutcome(GameEventGameOutcome ev) {
            try {
                owner.gameOver(ev);
            } catch (RuntimeException e) {
                System.err.println("[Claude] post-game handling failed: " + e);
            }
        }
    }

    private void gameOver(GameEventGameOutcome ev) {
        Game game = getGame();
        if (questions == 0) return;
        Set<Integer> done = REVIEWED.computeIfAbsent(game, g -> Collections.synchronizedSet(new HashSet<>()));
        if (!done.add(player.getId())) return;
        ClaudeConfig cfg = cfg();

        String winner = ev.winningPlayerName();
        String result = winner == null ? "draw" : (winner.equals(player.getName()) ? "win" : "loss");
        String myDeck = deckName(player);
        StringBuilder opp = new StringBuilder();
        for (Player p : game.getRegisteredPlayers()) { // includes players who already lost
            if (p == player) continue;
            if (opp.length() > 0) opp.append(" & ");
            opp.append(p.getName()).append(" (").append(deckName(p)).append(')');
        }
        Lessons.recordResult(cfg, myDeck, opp.toString(), result, ev.lastTurnNumber(), questions);

        // chronological log without the noisy phase/mana lines
        List<String> lines = new ArrayList<>();
        for (GameLogEntry e : game.getGameLog().getAllEntries()) {
            if (e.type() == GameLogEntryType.PHASE || e.type() == GameLogEntryType.MANA) continue;
            if (e.message() != null && e.message().contains("(Claude):")) continue; // own reasons come separately
            lines.add(GameStateWriter.oneLine(e.toString(), 250));
        }
        if (lines.size() > 350) lines = lines.subList(lines.size() - 350, lines.size());
        List<String> dec = decisions.size() > 200 ? decisions.subList(decisions.size() - 200, decisions.size()) : decisions;

        String header = "You played as " + player.getName() + " with the deck '" + myDeck + "' against " + opp
                + ". Result: you " + ("win".equals(result) ? "WON" : "loss".equals(result) ? "LOST" : "DREW")
                + " on turn " + ev.lastTurnNumber() + ". " + String.join(" ", ev.outcomeStrings());
        Lessons.reviewAsync(cfg, header, String.join("\n", lines), String.join("\n", dec));
    }

    private static String deckName(Player p) {
        try {
            return p.getRegisteredPlayer().getDeck().getName();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Debug helper used by tests: render the state the way Claude sees it. */
    public String debugState() {
        return GameStateWriter.describe(getGame(), player, cfg().logLines);
    }
}
