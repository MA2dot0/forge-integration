package forge.ai.claude;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.Multiset;

import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/** Turns the visible game state into compact text for Claude, from one player's point of view. */
public final class GameStateWriter {
    private GameStateWriter() { }

    public static String describe(Game game, Player me, int logLines) {
        StringBuilder sb = new StringBuilder();
        PhaseHandler ph = game.getPhaseHandler();
        Player active = ph.getPlayerTurn();

        sb.append("== GAME STATE ==\n");
        sb.append("Turn ").append(ph.getTurn()).append(", ")
          .append(active == me ? "YOUR turn" : (active == null ? "?" : active.getName() + "'s turn"))
          .append(". Step: ").append(ph.getPhase() == null ? "?" : ph.getPhase().nameForUi).append(".\n");

        // players
        appendPlayer(sb, me, me, true);
        for (Player p : game.getPlayers()) {
            if (p != me) appendPlayer(sb, p, me, false);
        }

        // hand
        sb.append("\nYOUR HAND:\n");
        CardCollectionView hand = me.getCardsIn(ZoneType.Hand);
        if (hand.isEmpty()) sb.append("  (empty)\n");
        for (Card c : hand) {
            sb.append("  ").append(cardLine(c, me, game, true)).append('\n');
        }

        // battlefields
        Combat combat = game.getPhaseHandler().inCombat() ? ph.getCombat() : null;
        appendBattlefield(sb, "YOUR BATTLEFIELD", me, me, game, combat);
        for (Player p : game.getPlayers()) {
            if (p != me) appendBattlefield(sb, p.getName().toUpperCase() + "'S BATTLEFIELD", p, me, game, combat);
        }

        // other zones (names only)
        for (Player p : game.getPlayers()) {
            String who = p == me ? "Your" : p.getName() + "'s";
            appendNames(sb, who + " graveyard", p.getCardsIn(ZoneType.Graveyard));
            appendNames(sb, who + " exile", p.getCardsIn(ZoneType.Exile));
            appendNames(sb, who + " command zone", p.getCardsIn(ZoneType.Command));
        }

        // stack
        sb.append("\nSTACK (top first):");
        if (game.getStack().isEmpty()) {
            sb.append(" empty\n");
        } else {
            sb.append('\n');
            int i = 0;
            for (SpellAbilityStackInstance si : game.getStack()) {
                SpellAbility sa = si.getSpellAbility();
                Player who = si.getActivatingPlayer();
                sb.append("  ").append(++i).append(". ")
                  .append(who == me ? "[yours] " : "[" + (who == null ? "?" : who.getName()) + "] ")
                  .append(oneLine(si.getStackDescription(), 300));
                if (sa != null && sa.getHostCard() != null) sb.append(" (#").append(sa.getHostCard().getId()).append(')');
                sb.append('\n');
            }
        }

        // combat
        if (combat != null && !combat.getAttackers().isEmpty()) {
            sb.append("\nCOMBAT (attacker -> defender [blockers]):\n");
            for (Card a : combat.getAttackers()) {
                GameEntity def = combat.getDefenderByAttacker(a);
                sb.append("  ").append(shortCard(a)).append(" -> ").append(entityName(def, me));
                CardCollectionView bl = combat.getBlockers(a);
                if (!bl.isEmpty()) {
                    sb.append(" blocked by ");
                    boolean first = true;
                    for (Card b : bl) {
                        if (!first) sb.append(", ");
                        first = false;
                        sb.append(shortCard(b));
                    }
                }
                sb.append('\n');
            }
        }

        // recent log
        if (logLines > 0) {
            List<GameLogEntry> entries = game.getGameLog().getLogEntries(GameLogEntryType.STACK_ADD);
            if (!entries.isEmpty()) {
                sb.append("\nRECENT EVENTS (newest last):\n");
                int n = Math.min(logLines, entries.size());
                for (int i = n - 1; i >= 0; i--) {
                    sb.append("  - ").append(oneLine(entries.get(i).message(), 200)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static void appendPlayer(StringBuilder sb, Player p, Player me, boolean isMe) {
        sb.append(isMe ? "YOU (" + p.getName() + ")" : "OPPONENT " + p.getName()).append(": life ").append(p.getLife());
        if (p.getPoisonCounters() > 0) sb.append(", poison ").append(p.getPoisonCounters());
        sb.append(", library ").append(p.getCardsIn(ZoneType.Library).size())
          .append(", hand ").append(p.getCardsIn(ZoneType.Hand).size())
          .append(", graveyard ").append(p.getCardsIn(ZoneType.Graveyard).size());
        if (isMe) {
            sb.append(", lands played this turn ").append(p.getLandsPlayedThisTurn()).append('/').append(p.getMaxLandPlays());
            try {
                sb.append(", untapped mana available ~").append(ComputerUtilMana.getAvailableManaEstimate(p, false));
            } catch (RuntimeException ignored) {
                // estimate is optional
            }
        }
        if (p.hasLost()) sb.append(" (LOST)");
        sb.append(".\n");
    }

    private static void appendBattlefield(StringBuilder sb, String title, Player owner, Player me, Game game, Combat combat) {
        sb.append('\n').append(title).append(":\n");
        CardCollectionView cards = owner.getCardsIn(ZoneType.Battlefield);
        if (cards.isEmpty()) {
            sb.append("  (nothing)\n");
            return;
        }
        // lands first, compact
        for (Card c : cards) {
            if (c.isLand() && !c.isCreature()) {
                sb.append("  ").append(cardLine(c, me, game, false)).append(combatTag(c, combat)).append('\n');
            }
        }
        for (Card c : cards) {
            if (!(c.isLand() && !c.isCreature())) {
                sb.append("  ").append(cardLine(c, me, game, false)).append(combatTag(c, combat)).append('\n');
            }
        }
    }

    private static String combatTag(Card c, Combat combat) {
        if (combat == null) return "";
        if (combat.isAttacking(c)) return " [ATTACKING]";
        if (combat.getAllBlockers().contains(c)) return " [BLOCKING]";
        return "";
    }

    private static void appendNames(StringBuilder sb, String title, CardCollectionView cards) {
        if (cards.isEmpty()) return;
        sb.append(title).append(" (").append(cards.size()).append("): ");
        boolean first = true;
        for (Card c : cards) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(c.isFaceDown() ? "face-down card" : c.getName());
        }
        sb.append('\n');
    }

    /** Full description of a card the player can see. */
    public static String cardLine(Card c, Player me, Game game, boolean inHand) {
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(c.getId()).append(' ');
        boolean hidden = c.isFaceDown() && c.getController() != me;
        if (hidden) {
            sb.append("face-down card");
        } else {
            sb.append(c.getName());
            if (c.isFaceDown()) sb.append(" (face down)");
        }
        if (!hidden && c.getManaCost() != null && !c.getManaCost().isNoCost() && !c.isLand()) {
            sb.append(' ').append(c.getManaCost().getSimpleString());
        }
        sb.append(" — ").append(c.getType().toString());
        if (c.isCreature()) {
            sb.append(' ').append(c.getNetPower()).append('/').append(c.getNetToughness());
            if (c.getDamage() > 0) sb.append(" (").append(c.getDamage()).append(" damage)");
        }
        if (c.isPlaneswalker()) sb.append(" loyalty ").append(c.getCurrentLoyalty());
        if (!inHand) {
            Set<String> flags = new LinkedHashSet<>();
            if (c.isTapped()) flags.add("tapped");
            // sickness only matters on its controller's own turn
            if (c.isCreature() && c.isSick() && game.getPhaseHandler().getPlayerTurn() == c.getController()) {
                flags.add("summoning sick");
            }
            if (c.isToken()) flags.add("token");
            if (c.isPhasedOut()) flags.add("phased out");
            if (c.getEntityAttachedTo() != null) flags.add("attached to " + entityName(c.getEntityAttachedTo(), me));
            Multiset<CounterType> counters = c.getCounters();
            if (counters != null) {
                for (Multiset.Entry<CounterType> e : counters.entrySet()) {
                    flags.add(e.getCount() + " " + e.getElement().getName() + " counter" + (e.getCount() == 1 ? "" : "s"));
                }
            }
            if (!flags.isEmpty()) sb.append(" (").append(String.join(", ", flags)).append(')');
        }
        if (!hidden && !(c.isLand() && c.getType().isBasic())) {
            String text = rulesText(c);
            if (!text.isEmpty()) sb.append(" | ").append(text);
            if (!inHand && c.isCreature()) {
                String kw = keywords(c);
                if (!kw.isEmpty()) sb.append(" | currently has: ").append(kw);
            }
        }
        return sb.toString();
    }

    public static String rulesText(Card c) {
        String t = null;
        try {
            t = c.getOracleText();
        } catch (RuntimeException ignored) {
            // fall through
        }
        if (t == null || t.trim().isEmpty()) {
            try {
                t = c.getAbilityText();
            } catch (RuntimeException ignored) {
                t = "";
            }
        }
        return oneLine(t, 450);
    }

    private static String keywords(Card c) {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (KeywordInterface k : c.getKeywords()) {
                String s = k.getOriginal();
                if (s == null || s.isEmpty() || s.length() > 40 || s.contains(":")) continue;
                out.add(s);
                if (out.size() >= 8) break;
            }
        } catch (RuntimeException ignored) {
            // keywords are a nice-to-have
        }
        return String.join(", ", out);
    }

    public static String shortCard(Card c) {
        if (c == null) return "?";
        String s = "#" + c.getId() + " " + (c.isFaceDown() ? "face-down" : c.getName());
        if (c.isCreature()) s += " " + c.getNetPower() + "/" + c.getNetToughness();
        return s;
    }

    public static String entityName(GameEntity e, Player me) {
        if (e == null) return "?";
        if (e instanceof Player p) return p == me ? "you" : p.getName();
        if (e instanceof Card c) return shortCard(c);
        return e.toString();
    }

    public static String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\r", "").replace("\\n", " / ").replace("\n", " / ").replaceAll("\\s+", " ").trim();
        if (t.length() > max) t = t.substring(0, max - 1) + "…";
        return t;
    }
}
