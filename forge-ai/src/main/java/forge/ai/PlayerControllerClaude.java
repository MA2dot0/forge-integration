package forge.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import forge.LobbyPlayer;
import forge.ai.claude.ClaudeClient;
import forge.ai.claude.ClaudeConfig;
import forge.ai.claude.GameStateWriter;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.game.spellability.TargetRestrictions;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

/**
 * A player driven by Claude through the Anthropic API.
 *
 * Claude makes the strategic decisions: what to play and when, targets, attacks, blocks and mulligans.
 * Everything else (mana payment, ordering, "may" prompts, choices inside effects) falls through to the
 * normal Forge AI, which is also the fallback whenever the API is unavailable or an answer is illegal.
 */
public class PlayerControllerClaude extends PlayerControllerAi {

    /** Hard cap on questions per priority window, so a confused answer can never stall the game. */
    private static final int MAX_CALLS_PER_WINDOW = 8;

    private String windowKey = "";
    private int callsInWindow = 0;
    private final Set<SpellAbility> triedInWindow = new HashSet<>();
    private String failedStepKey = "";
    private final Set<SpellAbility> failedThisStep = new HashSet<>();
    private boolean warnedNoKey = false;
    private boolean warnedError = false;

    public PlayerControllerClaude(Game game, Player p, LobbyPlayer lp) {
        super(game, p, lp);
    }

    // ------------------------------------------------------------------ plumbing

    private ClaudeConfig cfg() {
        return ClaudeConfig.get();
    }

    /** Asks Claude; returns null (=> use Forge AI) if there is no key or the call fails. */
    private ClaudeClient.Decision ask(String question) {
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
        String prompt = GameStateWriter.describe(getGame(), player, cfg.logLines) + "\n== QUESTION ==\n" + question;
        if (ClaudeClient.isPaused()) return null;
        ClaudeClient.Decision d = client.decide(prompt);
        if (d == null && !warnedError) {
            warnedError = true;
            note("API call failed (" + GameStateWriter.oneLine(client.lastError, 200)
                    + "). Falling back to the Forge AI for this decision.");
        }
        return d;
    }

    private void note(String msg) {
        getGame().getGameLog().add(GameLogEntryType.INFORMATION, player.getName() + " (Claude): " + msg);
    }

    private void say(ClaudeClient.Decision d, String what) {
        if (d != null && cfg().showReasoning && !d.reason.isEmpty()) {
            note(what + " - " + GameStateWriter.oneLine(d.reason, 300));
        }
    }

    private static String saLabel(SpellAbility sa, Player me) {
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
                    dbg("cand", sa);
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

    private static final boolean DEBUG = Boolean.getBoolean("claude.debug");
    private static void dbg(String why, Object o) {
        if (DEBUG) System.err.println("[Claude] skip " + why + ": " + o);
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

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
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
            callsInWindow++;

            StringBuilder q = new StringBuilder();
            q.append("You have priority. Choose what to do (one option):\n");
            q.append("  0. Pass priority (do nothing now)\n");
            for (int i = 0; i < options.size(); i++) {
                q.append("  ").append(i + 1).append(". ").append(saLabel(options.get(i), player)).append('\n');
            }
            q.append(failNote);
            q.append("Mana is paid automatically. Answer with `choice`.");

            ClaudeClient.Decision d = ask(q.toString());
            if (d == null) {
                return super.chooseSpellAbilityToPlay();
            }
            Integer pick = d.choice != null ? d.choice
                    : (d.choices != null && !d.choices.isEmpty() ? d.choices.get(0) : Integer.valueOf(0));
            if (pick == null || pick <= 0 || pick > options.size()) {
                say(d, "passes");
                return null;
            }
            SpellAbility sa = options.get(pick - 1);
            String label = saLabel(sa, player).split(":")[0];
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
        try {
            // let the Forge AI do the fiddly setup (X value, default targets), exactly as it does for its own plays
            if (sa instanceof Spell sp) {
                Card alt = sp.canPlayFromHost();
                if (alt == null) return false;
                if (alt != host) sa.setHostCard(alt);
                alt.setCastSA(sa);
            }
            try {
                dec = getAi().canPlaySa(sa);
            } finally {
                if (sa.getHostCard() != host) sa.setHostCard(host);
                if (sa.isSpell()) host.setCastSA(null);
            }
        } catch (RuntimeException e) {
            dec = AiPlayDecision.CantPlaySa;
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
            return ComputerUtilCost.canPayCost(sa, player, false);
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

        StringBuilder q = new StringBuilder();
        Card host = root.getHostCard();
        q.append("Choose targets for ").append(host == null ? "?" : "#" + host.getId() + " " + host.getName())
         .append(": ").append(GameStateWriter.oneLine(root.toString(), 300)).append('\n');
        if (tr.getVTSelection() != null && !tr.getVTSelection().isEmpty()) {
            q.append("This step: ").append(GameStateWriter.oneLine(tr.getVTSelection(), 150)).append('\n');
        }
        q.append("Pick ").append(min == max ? "exactly " + min : "between " + min + " and " + max)
         .append(" target(s):\n");
        for (int i = 0; i < candidates.size(); i++) {
            GameEntity e = candidates.get(i);
            q.append("  ").append(i + 1).append(". ").append(describeTarget(e)).append('\n');
        }
        q.append(max == 1 ? "Answer with `choice`." : "Answer with `choices` (a list of option numbers).");

        ClaudeClient.Decision d = ask(q.toString());
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
        say(d, "targets " + chosen);
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
        if (attacker != player) {
            super.declareAttackers(attacker, combat);
            return;
        }
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

        StringBuilder q = new StringBuilder("Declare attackers. Options (creature -> what it attacks):\n");
        for (int i = 0; i < atk.size(); i++) {
            q.append("  ").append(i + 1).append(". ").append(GameStateWriter.shortCard(atk.get(i)))
             .append(" -> ").append(GameStateWriter.entityName(def.get(i), player)).append('\n');
        }
        q.append("Each creature can attack only one thing. Answer with `choices` (empty list = no attack).");

        ClaudeClient.Decision d = ask(q.toString());
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
        if (defender != player) {
            super.declareBlockers(defender, combat);
            return;
        }
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

        StringBuilder q = new StringBuilder("Declare blockers. Possible blocks (your blocker blocks attacker):\n");
        for (int i = 0; i < atk.size(); i++) {
            q.append("  ").append(i + 1).append(". ").append(GameStateWriter.shortCard(blk.get(i)))
             .append(" blocks ").append(GameStateWriter.shortCard(atk.get(i))).append('\n');
        }
        q.append("Normally each blocker blocks one attacker; several blockers may block the same attacker. "
                + "Answer with `choices` (empty list = no blocks).");

        ClaudeClient.Decision d = ask(q.toString());
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
        StringBuilder q = new StringBuilder();
        q.append("Mulligan decision. You are ").append(firstPlayer == player ? "on the PLAY" : "on the DRAW").append(". ");
        if (cardsToReturn > 0) {
            q.append("If you keep, you will put ").append(cardsToReturn).append(" card(s) from this hand on the bottom. ");
        }
        q.append("\n  1. Keep this hand\n  2. Mulligan\nAnswer with `choice`.");
        ClaudeClient.Decision d = ask(q.toString());
        if (d == null || d.choice == null || (d.choice != 1 && d.choice != 2)) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        say(d, d.choice == 1 ? "keeps" : "mulligans");
        return d.choice == 1;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        List<Card> cards = new ArrayList<>();
        for (Card c : hand) cards.add(c);
        StringBuilder q = new StringBuilder("You kept. Choose exactly ").append(cardsToReturn)
                .append(" card(s) to put on the bottom of your library:\n");
        for (int i = 0; i < cards.size(); i++) {
            q.append("  ").append(i + 1).append(". ").append(GameStateWriter.cardLine(cards.get(i), player, getGame(), true)).append('\n');
        }
        q.append("Answer with `choices`.");
        ClaudeClient.Decision d = ask(q.toString());
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
        return super.tuckCardsViaMulligan(hand, cardsToReturn);
    }

    /** Debug helper used by tests: render the state the way Claude sees it. */
    public String debugState() {
        return GameStateWriter.describe(getGame(), player, cfg().logLines);
    }
}
