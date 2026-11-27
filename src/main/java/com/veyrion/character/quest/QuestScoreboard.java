package com.veyrion.character.quest;

import com.veyrion.character.config.Messages;
import com.veyrion.character.config.PluginSettings;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.Nullable;

/**
 * The quest sidebar: the vanilla scoreboard on the right of the screen, one private {@link Scoreboard} per player
 * (so nobody else sees it and the main scoreboard is untouched). Main thread only.
 *
 * <pre>
 *      VEYRION
 * -------------
 * Ancient Ruins
 *
 * Objective:
 * Kalahkan Guardian
 *
 * Progress: 3/5
 * -------------
 * YOUR ADVENTURE
 * </pre>
 *
 * <p>It is driven by {@link QuestService}: {@link #refresh} is called when quest state is loaded (join, so it is
 * back after a relog), accepted, progressed, completed, abandoned or reset, and {@link #clear} on quit/disable.
 * The text lives in lang/*.yml under {@code scoreboard.*}.
 *
 * <p>TAB: TAB draws its own sidebar with packets. With {@code quest-scoreboard.mode: AUTO} this class steps aside
 * when the TAB plugin is enabled and its scoreboard feature is on (it never touches the sidebar then) and the same
 * data is offered as placeholders ({@code %veyrioncharacter_quest_*%}) for a TAB scoreboard. {@code NATIVE} always
 * draws it; {@code TAB} never does. When it draws, it remembers the player's previous scoreboard and puts it back,
 * and it does not touch a scoreboard that someone else installed after it.
 */
public final class QuestScoreboard {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String OBJECTIVE = "veyrion_quest";
    private static final int MAX_LINES = 15;

    /** One player's sidebar. */
    private static final class Board {
        final Scoreboard scoreboard;
        final Objective objective;
        final @Nullable Scoreboard previous;
        final List<Team> teams = new ArrayList<>();
        final List<String> entries = new ArrayList<>();
        List<Component> lines = List.of();

        Board(Scoreboard scoreboard, Objective objective, @Nullable Scoreboard previous) {
            this.scoreboard = scoreboard;
            this.objective = objective;
            this.previous = previous;
        }
    }

    private final VeyrionCharacterPlugin plugin;
    private final QuestService quests;
    private final Map<UUID, Board> boards = new HashMap<>();
    /** Latest view per player for placeholders (read from other threads). */
    private final Map<UUID, QuestBoardView> views = new ConcurrentHashMap<>();
    private volatile @Nullable Boolean tabOwnsSidebar;

    public QuestScoreboard(VeyrionCharacterPlugin plugin, QuestService quests) {
        this.plugin = plugin;
        this.quests = quests;
    }

    /** The tracked quest of a player, for placeholders. Safe from any thread. */
    public @Nullable QuestBoardView view(UUID uuid) {
        return views.get(uuid);
    }

    /** Plain text (no MiniMessage tags) for placeholders. */
    public static String plain(String miniMessage) {
        return MINI.stripTags(miniMessage);
    }

    // --------------------------------------------------------------------------------------------- settings

    /** Forget the cached TAB check (call after a reload). */
    public void reload() {
        tabOwnsSidebar = null;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refresh(player);
        }
    }

    private boolean drawsSidebar() {
        PluginSettings.QuestScoreboard settings = plugin.configManager().settings().questScoreboard();
        if (!settings.enabled()) {
            return false;
        }
        return switch (settings.mode()) {
            case NATIVE -> true;
            case TAB -> false;
            case AUTO -> !tabHandlesScoreboard();
        };
    }

    /** True if the TAB plugin is running with its own scoreboard feature switched on. */
    private boolean tabHandlesScoreboard() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("TAB")) {
            return false;
        }
        Boolean cached = tabOwnsSidebar;
        if (cached != null) {
            return cached;
        }
        boolean owns = false;
        try {
            File config = new File(plugin.getDataFolder().getParentFile(), "TAB/config.yml");
            if (config.isFile()) {
                owns = YamlConfiguration.loadConfiguration(config).getBoolean("scoreboard.enabled", false);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read TAB's config.yml; assuming TAB has no scoreboard.", e);
        }
        tabOwnsSidebar = owns;
        plugin.getLogger().info(owns
                ? "TAB scoreboard detected: the quest sidebar is left to TAB (use %veyrioncharacter_quest_*%)."
                : "TAB found without its scoreboard feature: the quest sidebar is drawn natively.");
        return owns;
    }

    // ------------------------------------------------------------------------------------------------ driving

    /** Recomputes and shows (or removes) the sidebar of this player from their current quest state. */
    public void refresh(Player player) {
        UUID id = player.getUniqueId();
        QuestBoardView view = QuestBoardView.of(quests.state(id), quests.registry());
        if (view == null) {
            views.remove(id);
        } else {
            views.put(id, view);
        }
        if (view == null || !player.isOnline() || !drawsSidebar()) {
            clear(player);
            return;
        }
        try {
            render(player, view);
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "Could not draw the quest scoreboard for " + player.getName(), e);
            clear(player);
        }
    }

    /** Removes the sidebar and gives the player their previous scoreboard back. Idempotent. */
    public void clear(Player player) {
        Board board = boards.remove(player.getUniqueId());
        if (board == null) {
            return;
        }
        release(player, board);
    }

    /** Quit: forget everything about the player. */
    public void forget(Player player) {
        clear(player);
        views.remove(player.getUniqueId());
    }

    /** Plugin disable: every sidebar is removed and every player's scoreboard restored. */
    public void shutdown() {
        for (Map.Entry<UUID, Board> entry : new ArrayList<>(boards.entrySet())) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                release(player, entry.getValue());
            } else {
                safeUnregister(entry.getValue());
            }
        }
        boards.clear();
        views.clear();
    }

    private void release(Player player, Board board) {
        try {
            // Only if the player still looks at OUR scoreboard: never undo what another plugin installed since.
            if (player.getScoreboard() == board.scoreboard) {
                Scoreboard back = board.previous;
                if (back == null || back == board.scoreboard) {
                    ScoreboardManager manager = Bukkit.getScoreboardManager();
                    back = manager == null ? null : manager.getMainScoreboard();
                }
                if (back != null) {
                    player.setScoreboard(back);
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not restore the scoreboard of " + player.getName(), e);
        }
        safeUnregister(board);
    }

    private static void safeUnregister(Board board) {
        try {
            board.objective.unregister();
        } catch (RuntimeException ignored) {
            // already gone; the private scoreboard is garbage anyway
        }
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private List<Component> buildLines(QuestBoardView view) {
        Messages m = plugin.messages();
        List<Component> lines = new ArrayList<>(9);
        lines.add(m.get("scoreboard.separator"));
        lines.add(m.get("scoreboard.area", Placeholder.parsed("area", view.area())));
        lines.add(Component.empty());
        lines.add(m.get("scoreboard.objective-label"));
        lines.add(m.get("scoreboard.objective", Placeholder.parsed("objective", view.objective())));
        lines.add(Component.empty());
        lines.add(m.get("scoreboard.progress",
                Placeholder.unparsed("progress", Integer.toString(view.progress())),
                Placeholder.unparsed("amount", Integer.toString(view.amount()))));
        lines.add(m.get("scoreboard.separator"));
        lines.add(m.get("scoreboard.footer"));
        return lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines;
    }

    private void render(Player player, QuestBoardView view) {
        UUID id = player.getUniqueId();
        List<Component> lines = buildLines(view);
        Board board = boards.get(id);
        if (board == null) {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) {
                return;
            }
            Scoreboard scoreboard = manager.getNewScoreboard();
            Objective objective = scoreboard.registerNewObjective(OBJECTIVE, Criteria.DUMMY,
                    plugin.messages().get("scoreboard.title"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            try {
                objective.numberFormat(NumberFormat.blank()); // hide the red score numbers
            } catch (RuntimeException | LinkageError e) {
                plugin.debug("Scoreboard number format not available: " + e);
            }
            board = new Board(scoreboard, objective, player.getScoreboard());
            boards.put(id, board);
        } else {
            board.objective.displayName(plugin.messages().get("scoreboard.title"));
        }
        if (board.lines.size() != lines.size()) {
            rebuild(board, lines);
        } else {
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).equals(board.lines.get(i))) {
                    board.teams.get(i).prefix(lines.get(i));
                }
            }
        }
        board.lines = List.copyOf(lines);
        if (player.getScoreboard() != board.scoreboard) {
            player.setScoreboard(board.scoreboard);
        }
    }

    /** Creates one team per line; the invisible team entry carries the score, the team prefix carries the text. */
    private static void rebuild(Board board, List<Component> lines) {
        for (Team team : board.teams) {
            team.unregister();
        }
        for (String entry : board.entries) {
            board.scoreboard.resetScores(entry);
        }
        board.teams.clear();
        board.entries.clear();
        for (int i = 0; i < lines.size(); i++) {
            String entry = "\u00a7" + Integer.toHexString(i) + "\u00a7r"; // unique and invisible
            Team team = board.scoreboard.registerNewTeam("vq" + i);
            team.addEntry(entry);
            team.prefix(lines.get(i));
            board.objective.getScore(entry).setScore(lines.size() - i);
            board.teams.add(team);
            board.entries.add(entry);
        }
    }
}
