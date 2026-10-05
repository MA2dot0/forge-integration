package forge.ai.claude;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.ai.ComputerUtilCombat;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.GameStateEvaluator;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.ai.simulation.SimulationController;
import forge.ai.simulation.SpellAbilityPicker;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;

/**
 * Things the Forge engine and its AI are better at than Claude: exact combat arithmetic, the built-in AI's own
 * choices (as a second opinion) and short simulations of candidate plays. Everything here is read-only for the
 * real game, except where noted (the AI's attack/block suggestions are made on the live Combat and then undone).
 */
public final class ForgeAdvisor {
    private ForgeAdvisor() { }

    // ------------------------------------------------------------------ board math

    /** Lethal / race summary for the whole board, from {@code me}'s point of view. */
    public static String boardMath(Game game, Player me) {
        StringBuilder sb = new StringBuilder();
        PhaseHandler ph = game.getPhaseHandler();
        boolean myTurn = ph.getPlayerTurn() == me;
        boolean beforeMyAttacks = myTurn && ph.getPhase() != null && ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS);

        for (Player opp : me.getOpponents()) {
            if (opp.hasLost()) continue;
            // my offence: this turn if I can still attack, otherwise my next turn
            int dmg = 0;
            int poison = 0;
            int n = 0;
            for (Card c : me.getCreaturesInPlay()) {
                boolean can = beforeMyAttacks ? CombatUtil.canAttack(c, opp) : ComputerUtilCombat.canAttackNextTurn(c, opp);
                if (!can) continue;
                n++;
                dmg += safeDamage(c, opp);
                poison += safePoison(c, opp);
            }
            int blockers = 0;
            for (Card c : opp.getCreaturesInPlay()) {
                if (CombatUtil.canBlock(c, !beforeMyAttacks)) blockers++;
            }
            sb.append("- Your attack ").append(beforeMyAttacks ? "this turn" : "next turn").append(": ")
              .append(n).append(" creature(s) able to attack, ").append(dmg).append(" damage if unblocked");
            if (poison > 0) sb.append(" (+").append(poison).append(" poison)");
            sb.append(" vs ").append(opp.getName()).append("'s ").append(opp.getLife()).append(" life");
            if (n > 0 && dmg >= opp.getLife() && opp.canLoseLife()) sb.append(" => LETHAL if unblocked");
            sb.append(". They have ").append(blockers).append(" potential blocker(s).\n");

            // their offence: their next attack
            boolean theirTurnBeforeAttacks = ph.getPlayerTurn() == opp && ph.getPhase() != null
                    && ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS);
            int tdmg = 0;
            int tpoison = 0;
            int tn = 0;
            for (Card c : opp.getCreaturesInPlay()) {
                boolean can = theirTurnBeforeAttacks ? CombatUtil.canAttack(c, me) : ComputerUtilCombat.canAttackNextTurn(c, me);
                if (!can) continue;
                tn++;
                tdmg += safeDamage(c, me);
                tpoison += safePoison(c, me);
            }
            int myBlockers = 0;
            for (Card c : me.getCreaturesInPlay()) {
                if (CombatUtil.canBlock(c, !theirTurnBeforeAttacks)) myBlockers++;
            }
            sb.append("- ").append(opp.getName()).append("'s ").append(theirTurnBeforeAttacks ? "attack this turn" : "next attack")
              .append(": ").append(tn).append(" creature(s), ").append(tdmg).append(" damage if unblocked");
            if (tpoison > 0) sb.append(" (+").append(tpoison).append(" poison)");
            sb.append(" vs your ").append(me.getLife()).append(" life");
            if (tn > 0 && tdmg >= me.getLife() && me.canLoseLife()) sb.append(" => LETHAL against you if you don't block/answer");
            sb.append(". You have ").append(myBlockers).append(" potential blocker(s).\n");
        }
        return sb.toString();
    }

    private static int safeDamage(Card c, GameEntity target) {
        try {
            return ComputerUtilCombat.damageIfUnblocked(c, target, null, false);
        } catch (RuntimeException e) {
            return Math.max(0, c.getNetCombatDamage());
        }
    }

    private static int safePoison(Card c, Player target) {
        try {
            return ComputerUtilCombat.poisonIfUnblocked(c, target);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** One line per attacker option: unblocked damage and how it fares against each possible blocker. */
    public static String attackerFacts(Player me, Card attacker, GameEntity defender) {
        StringBuilder sb = new StringBuilder();
        sb.append("unblocked: ").append(safeDamage(attacker, defender)).append(" dmg");
        Player defPlayer = defender instanceof Player p ? p : (defender instanceof Card dc ? dc.getController() : null);
        if (defPlayer == null) return sb.toString();
        List<String> diesTo = new ArrayList<>();
        List<String> kills = new ArrayList<>();
        for (Card b : defPlayer.getCreaturesInPlay()) {
            if (!CombatUtil.canBlock(attacker, b)) continue;
            try {
                if (ComputerUtilCombat.canDestroyAttacker(me, attacker, b, null, true)) diesTo.add("#" + b.getId());
                if (ComputerUtilCombat.canDestroyBlocker(me, b, attacker, null, true)) kills.add("#" + b.getId());
            } catch (RuntimeException ignored) {
                // skip pairs the combat helpers can't evaluate without a live combat
            }
        }
        if (!diesTo.isEmpty()) sb.append("; dies if blocked by ").append(String.join(",", diesTo));
        if (!kills.isEmpty()) sb.append("; kills blocker ").append(String.join(",", kills));
        return sb.toString();
    }

    /** Outcome of a single block, without tricks: who dies. */
    public static String blockFacts(Player me, Card attacker, Card blocker, Combat combat) {
        try {
            boolean atkDies = ComputerUtilCombat.canDestroyAttacker(me, attacker, blocker, combat, true);
            boolean blkDies = ComputerUtilCombat.canDestroyBlocker(me, blocker, attacker, combat, true);
            if (atkDies && blkDies) return "both die";
            if (atkDies) return "attacker dies, your blocker survives";
            if (blkDies) return "your blocker dies, attacker survives";
            return "neither dies";
        } catch (RuntimeException e) {
            return "outcome unknown";
        }
    }

    /** Incoming damage summary while declaring blockers. */
    public static String incomingFacts(Player me, Combat combat) {
        int total = 0;
        int poison = 0;
        for (Card a : combat.getAttackersOf(me)) {
            total += safeDamage(a, me);
            poison += safePoison(a, me);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- If you block nothing you take ").append(total).append(" damage (life ").append(me.getLife())
          .append(" -> ").append(me.getLife() - total).append(")");
        if (poison > 0) sb.append(" and ").append(poison).append(" poison");
        if (total >= me.getLife() && me.canLoseLife()) sb.append(" => that is LETHAL, you must block or answer");
        sb.append(".\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ AI second opinions

    /** What the Forge AI would attack with. Runs the AI on the live combat, records, then clears it again. */
    public static Map<Card, GameEntity> aiAttackSuggestion(PlayerControllerAi ctrl, Player me, Combat combat) {
        Map<Card, GameEntity> out = new LinkedHashMap<>();
        try {
            ctrl.getAi().declareAttackers(me, combat);
            for (Card a : combat.getAttackers()) out.put(a, combat.getDefenderByAttacker(a));
        } catch (RuntimeException e) {
            // no suggestion
        } finally {
            combat.clearAttackers();
        }
        return out;
    }

    /** What the Forge AI would block with (blocker -> attacker). Assigns on the live combat, records, then undoes. */
    public static Map<Card, Card> aiBlockSuggestion(PlayerControllerAi ctrl, Player me, Combat combat) {
        Map<Card, Card> out = new LinkedHashMap<>();
        try {
            ctrl.getAi().declareBlockersFor(me, combat);
            for (Card a : combat.getAttackers()) {
                for (Card b : combat.getBlockers(a)) {
                    if (b.getController() == me) out.put(b, a);
                }
            }
        } catch (RuntimeException e) {
            // no suggestion
        } finally {
            for (Card b : new ArrayList<>(out.keySet())) combat.undoBlockingAssignment(b);
            // also undo anything the AI assigned that we didn't record
            for (Card b : me.getCreaturesInPlay()) {
                if (combat.isBlocking(b)) combat.undoBlockingAssignment(b);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ simulation

    /** Result of simulating each option: score change vs doing nothing. */
    public static final class SimResult {
        public final Map<SpellAbility, String> labels = new IdentityHashMap<>();
        public long millis;
        public int simulated;
    }

    /**
     * Simulates each option on a copy of the game (Forge's own simulation engine) and reports the board evaluation
     * change. Stops when the time budget runs out. Never touches the real game state.
     */
    public static SimResult simulate(Player me, List<SpellAbility> options, int depth, long budgetMs) {
        SimResult res = new SimResult();
        long t0 = System.currentTimeMillis();
        Game game = me.getGame();
        Score orig;
        try {
            orig = new GameStateEvaluator().getScoreForGameState(game, me);
        } catch (Throwable t) {
            return res;
        }
        SpellAbilityPicker picker = new SpellAbilityPicker(me);
        java.util.Random origRandom = forge.util.MyRandom.getRandom();
        try {
        for (int i = 0; i < options.size(); i++) {
            if (System.currentTimeMillis() - t0 > budgetMs) break;
            SpellAbility sa = options.get(i);
            // keep whatever targets are already set on the real SA (e.g. by the AI's suggestion)
            List<SpellAbility> chain = new ArrayList<>();
            List<TargetChoices> savedTargets = new ArrayList<>();
            for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
                chain.add(s);
                savedTargets.add(s.getTargets().clone());
            }
            try {
                Score s = picker.evaluateSa(new SimulationController(orig, Math.max(0, depth)), null, options, i);
                res.labels.put(sa, describe(s.value, orig.value));
                res.simulated++;
            } catch (Throwable t) {
                res.labels.put(sa, "sim n/a");
            } finally {
                for (int k = 0; k < chain.size(); k++) chain.get(k).setTargets(savedTargets.get(k));
            }
        }
        } finally {
            // evaluateSa swaps the global RNG and doesn't restore it if a simulation throws
            forge.util.MyRandom.setRandom(origRandom);
        }
        res.millis = System.currentTimeMillis() - t0;
        return res;
    }

    private static String describe(int value, int orig) {
        if (value == Integer.MIN_VALUE) return "sim: play fails";
        if (GameStateEvaluator.isWinning(value)) return "sim: WINS THE GAME (incl. this turn's combat)";
        long delta = (long) value - orig;
        if (delta < -1_000_000) return "sim: LOSES THE GAME";
        return "sim: " + (delta >= 0 ? "+" : "") + delta;
    }

    /** Builds a target description of what the AI picked, for showing as a suggestion. */
    public static String targetsOf(SpellAbility sa, Player me) {
        List<String> t = new ArrayList<>();
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (!s.usesTargeting()) continue;
            for (Object o : s.getTargets()) {
                t.add(o instanceof GameEntity ge ? GameStateWriter.entityName(ge, me) : String.valueOf(o));
            }
        }
        return t.isEmpty() ? "" : " targeting " + String.join(", ", t);
    }
}
