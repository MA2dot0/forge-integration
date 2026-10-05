package forge.ai;

import java.util.Set;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import org.tinylog.Logger;

public class LobbyPlayerAi extends LobbyPlayer implements IGameEntitiesFactory {

    public static final String CLAUDE_PROFILE = "Claude";

    private String aiProfile = "";
    private boolean rotateProfileEachGame;
    private AIOption option;

    public LobbyPlayerAi(String name, Set<AIOption> options) {
        super(name);
        if (options != null && !options.isEmpty()) {
            option = options.iterator().next();
        }
    }

    public void setAiProfile(String profileName) {
        Logger.debug("[AI Preferences] " + name + " using profile " + profileName);
        aiProfile = profileName;
    }
    public String getAiProfile() {
        return aiProfile;
    }

    public boolean isClaudeProfile() {
        return CLAUDE_PROFILE.equalsIgnoreCase(aiProfile);
    }

    public void setRotateProfileEachGame(boolean rotateProfileEachGame) {
        this.rotateProfileEachGame = rotateProfileEachGame;
    }

    private PlayerControllerAi createControllerFor(Player ai) {
        // The "Claude" AI profile hands decisions to Claude via the Anthropic API (see PlayerControllerClaude).
        PlayerControllerAi result = isClaudeProfile()
                ? new PlayerControllerClaude(ai.getGame(), ai, this)
                : new PlayerControllerAi(ai.getGame(), ai, this);
        result.getAi().setUseSimulation(option);
        return result;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return createControllerFor(slave);
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player ai = new Player(getName(), game, id);
        ai.setFirstController(createControllerFor(ai));

        if (rotateProfileEachGame) {
            setAiProfile(AiProfileUtil.getRandomProfile());
        }
        return ai;
    }

    @Override
    public void hear(LobbyPlayer player, String message) { /* Local AI is deaf. */ }
}