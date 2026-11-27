package com.veyrion.character.quest;

import com.veyrion.character.character.CharacterRepository;
import com.veyrion.character.character.ExpService;
import com.veyrion.character.character.RankTable;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.economy.PendingVyrRepository;
import com.veyrion.character.economy.VyrPayouts;
import com.veyrion.character.quest.PlayerQuests.ActiveQuest;
import com.veyrion.character.quest.QuestRepository.QuestRow;
import com.veyrion.character.util.Durations;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Quest rules. All in-memory state is touched on the main thread only; database work is async and its
 * results are hopped back to the main thread before state or the Bukkit API is used.
 */
public final class QuestService {

    private final VeyrionCharacterPlugin plugin;
    private final QuestRegistry registry;
    private final QuestRepository repository;
    private final CharacterRepository characterRepository;
    private final ExpService expService;
    private final VyrPayouts vyrPayouts;
    private final Map<UUID, PlayerQuests> players = new HashMap<>();
    /** The quest sidebar; null until the plugin has created it. */
    private @Nullable QuestScoreboard board;

    public QuestService(VeyrionCharacterPlugin plugin, QuestRegistry registry, QuestRepository repository,
                        CharacterRepository characterRepository, ExpService expService, VyrPayouts vyrPayouts) {
        this.plugin = plugin;
        this.registry = registry;
        this.repository = repository;
        this.characterRepository = characterRepository;
        this.expService = expService;
        this.vyrPayouts = vyrPayouts;
    }

    /** Connects the quest sidebar; every change of quest state refreshes it. */
    public void attach(QuestScoreboard scoreboard) {
        this.board = scoreboard;
    }

    private void refreshBoard(Player player) {
        QuestScoreboard current = board;
        if (current != null && player.isOnline()) {
            current.refresh(player);
        }
    }

    public QuestRegistry registry() {
        return registry;
    }

    // ---------------------------------------------------------------- lifecycle

    /** Loads a joining player's quest state from the database. */
    public void load(Player player) {
        UUID uuid = player.getUniqueId();
        repository.load(uuid).whenComplete((data, error) -> onMain(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load quests for " + player.getName(), error);
                return;
            }
            if (!player.isOnline()) {
                return;
            }
            PlayerQuests state = new PlayerQuests();
            for (QuestRow row : data.rows()) {
                if (row.status() == QuestStatus.ACTIVE) {
                    state.addActive(row.questId(), row.acceptedAt(),
                            data.objectives().getOrDefault(row.questId(), Map.of()));
                }
                if (row.completionCount() > 0) {
                    state.setCompletion(row.questId(), row.completedAt(), row.completionCount());
                }
            }
            players.put(uuid, state);
            plugin.debug("Loaded " + state.active().size() + " active quest(s) for " + player.getName());
            refreshCollect(player);
            refreshBoard(player); // back after a relog
        }));
    }

    public void unload(UUID uuid) {
        players.remove(uuid);
        QuestScoreboard current = board;
        Player player = plugin.getServer().getPlayer(uuid);
        if (current != null && player != null) {
            current.forget(player);
        }
    }

    public @Nullable PlayerQuests state(UUID uuid) {
        return players.get(uuid);
    }

    // ---------------------------------------------------------------- availability

    public long cooldownRemainingSeconds(PlayerQuests state, Quest quest) {
        PlayerQuests.Completion done = state.completion(quest.id());
        if (done == null || quest.cooldownSeconds() <= 0) {
            return 0;
        }
        long remainingMillis = done.lastCompletedAt() + quest.cooldownSeconds() * 1000L - System.currentTimeMillis();
        return remainingMillis <= 0 ? 0 : (remainingMillis + 999) / 1000;
    }

    /** @param characterRank the player's current rank, or null to skip the rank check */
    public QuestAvailability availability(PlayerQuests state, Quest quest, @Nullable String characterRank) {
        if (state.isActive(quest.id())) {
            return QuestAvailability.ACTIVE;
        }
        if (state.completion(quest.id()) != null) {
            if (!quest.type().isRepeatable()) {
                return QuestAvailability.COMPLETED;
            }
            if (cooldownRemainingSeconds(state, quest) > 0) {
                return QuestAvailability.ON_COOLDOWN;
            }
        }
        for (String prerequisite : quest.prerequisites()) {
            if (state.completion(prerequisite) == null) {
                return QuestAvailability.LOCKED_PREREQUISITE;
            }
        }
        if (quest.requiredRank() != null && characterRank != null) {
            RankTable ranks = expService.ranks();
            int required = ranks.indexOf(quest.requiredRank());
            int current = Math.max(0, ranks.indexOf(characterRank));
            if (required >= 0 && current < required) {
                return QuestAvailability.LOCKED_RANK;
            }
        }
        return QuestAvailability.AVAILABLE;
    }

    // ---------------------------------------------------------------- accept / abandon / reset

    /**
     * Accepts a quest. {@code force} skips cooldown/prerequisite/rank checks (admin use).
     * The callback runs on the main thread; {@link QuestAvailability#AVAILABLE} means accepted.
     */
    public void accept(Player player, Quest quest, boolean force, Consumer<QuestAvailability> callback) {
        UUID uuid = player.getUniqueId();
        PlayerQuests state = players.get(uuid);
        if (state == null) {
            callback.accept(QuestAvailability.NOT_LOADED);
            return;
        }
        if (state.isActive(quest.id())) {
            callback.accept(QuestAvailability.ACTIVE);
            return;
        }
        if (!state.markPending(quest.id())) {
            return; // an accept for this quest is already in flight; ignore the duplicate click
        }

        if (!force && quest.requiredRank() != null) {
            characterRepository.find(uuid).whenComplete((character, error) -> onMain(() -> {
                if (error != null || character.isEmpty()) {
                    state.clearPending(quest.id());
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to read character for " + player.getName(), error);
                    }
                    callback.accept(QuestAvailability.ERROR);
                    return;
                }
                continueAccept(player, quest, state, false, character.get().adventureRank(), callback);
            }));
        } else {
            continueAccept(player, quest, state, force, null, callback);
        }
    }

    private void continueAccept(Player player, Quest quest, PlayerQuests state, boolean force,
                                @Nullable String rank, Consumer<QuestAvailability> callback) {
        UUID uuid = player.getUniqueId();
        QuestAvailability availability = force ? QuestAvailability.AVAILABLE : availability(state, quest, rank);
        if (availability != QuestAvailability.AVAILABLE) {
            state.clearPending(quest.id());
            callback.accept(availability);
            return;
        }
        long now = System.currentTimeMillis();
        repository.accept(uuid, quest.id(), now).whenComplete((ignored, error) -> onMain(() -> {
            state.clearPending(quest.id());
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to accept quest " + quest.id() + " for " + player.getName(), error);
                callback.accept(QuestAvailability.ERROR);
                return;
            }
            PlayerQuests current = players.get(uuid);
            if (current == null) {
                callback.accept(QuestAvailability.NOT_LOADED);
                return;
            }
            current.addActive(quest.id(), now, Map.of());
            refreshCollect(player);
            refreshBoard(player);
            callback.accept(QuestAvailability.AVAILABLE);
        }));
    }

    /** Callback receives true if the quest was abandoned. Runs on the main thread. */
    public void abandon(Player player, String questId, Consumer<Boolean> callback) {
        UUID uuid = player.getUniqueId();
        PlayerQuests state = players.get(uuid);
        ActiveQuest active = state == null ? null : state.activeQuest(questId);
        if (active == null || active.completing()) {
            callback.accept(false);
            return;
        }
        repository.abandon(uuid, questId).whenComplete((ignored, error) -> onMain(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to abandon quest " + questId + " for " + player.getName(), error);
                callback.accept(false);
                return;
            }
            PlayerQuests current = players.get(uuid);
            if (current != null) {
                current.removeActive(questId);
            }
            refreshBoard(player);
            callback.accept(true);
        }));
    }

    /** Wipes all quest data for one quest (works for offline players too). Callback gets true on success. */
    public void reset(UUID uuid, String questId, Consumer<Boolean> callback) {
        repository.reset(uuid, questId).whenComplete((ignored, error) -> onMain(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to reset quest " + questId + " for " + uuid, error);
                callback.accept(false);
                return;
            }
            PlayerQuests state = players.get(uuid);
            if (state != null) {
                state.clear(questId);
                Player online = plugin.getServer().getPlayer(uuid);
                if (online != null) {
                    refreshBoard(online);
                }
            }
            callback.accept(true);
        }));
    }

    // ---------------------------------------------------------------- progress

    /** Adds {@code amount} to every unfinished objective of the player's active quests that matches. */
    public void progress(Player player, Predicate<QuestObjective> matcher, int amount) {
        PlayerQuests state = players.get(player.getUniqueId());
        if (state == null || amount <= 0) {
            return;
        }
        for (ActiveQuest active : List.copyOf(state.active())) {
            Quest quest = registry.get(active.questId());
            if (quest == null || active.completing()) {
                continue;
            }
            boolean changed = false;
            for (QuestObjective objective : quest.objectives()) {
                if (!matcher.test(objective)) {
                    continue;
                }
                int current = active.progress(objective.id());
                if (current >= objective.amount()) {
                    continue;
                }
                setProgress(player, quest, active, objective, Math.min(objective.amount(), current + amount));
                changed = true;
            }
            if (changed) {
                checkCompletion(player, quest, active);
            }
        }
    }

    /** Called when something changed in a player's inventory: recomputes Collect Item objectives. */
    public void refreshCollect(Player player) {
        PlayerQuests state = players.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        for (ActiveQuest active : List.copyOf(state.active())) {
            Quest quest = registry.get(active.questId());
            if (quest == null || active.completing()) {
                continue;
            }
            boolean changed = false;
            for (QuestObjective objective : quest.objectives()) {
                if (objective.type() != ObjectiveType.COLLECT_ITEM || objective.material() == null) {
                    continue;
                }
                int value = Math.min(objective.amount(), countItems(player, objective.material()));
                if (value != active.progress(objective.id())) {
                    setProgress(player, quest, active, objective, value);
                    changed = true;
                }
            }
            if (changed) {
                checkCompletion(player, quest, active);
            }
        }
    }

    /** Periodic check for Explore Location objectives. */
    public void exploreTick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerQuests state = players.get(player.getUniqueId());
            if (state == null || state.active().isEmpty()) {
                continue;
            }
            progress(player, objective -> objective.type() == ObjectiveType.EXPLORE_LOCATION
                    && isInside(player, objective), 1);
        }
    }

    private static boolean isInside(Player player, QuestObjective objective) {
        if (objective.world() == null || !player.getWorld().getName().equals(objective.world())) {
            return false;
        }
        Location location = player.getLocation();
        double dx = location.getX() - objective.x();
        double dy = location.getY() - objective.y();
        double dz = location.getZ() - objective.z();
        return dx * dx + dy * dy + dz * dz <= objective.radius() * objective.radius();
    }

    private static int countItems(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private void setProgress(Player player, Quest quest, ActiveQuest active, QuestObjective objective, int value) {
        active.setProgress(objective.id(), value);
        repository.saveProgress(player.getUniqueId(), quest.id(), objective.id(), value).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save progress of " + quest.id() + " for " + player.getName(), error);
            }
        });
        refreshBoard(player);
        player.sendActionBar(plugin.messages().get("quest.progress",
                Placeholder.parsed("description", objective.description()),
                Placeholder.unparsed("progress", Integer.toString(value)),
                Placeholder.unparsed("amount", Integer.toString(objective.amount()))));
    }

    private void checkCompletion(Player player, Quest quest, ActiveQuest active) {
        for (QuestObjective objective : quest.objectives()) {
            if (active.progress(objective.id()) < objective.amount()) {
                return;
            }
        }
        complete(player, quest, ok -> { });
    }

    // ---------------------------------------------------------------- completion

    /**
     * Completes an active quest and pays rewards. EXP is written in the same database transaction that
     * flips the quest to COMPLETED, so a reward can never be paid twice (reconnects, restarts, repeated
     * events). Callback gets true only if this call completed the quest. Runs on the main thread.
     */
    public void complete(Player player, Quest quest, Consumer<Boolean> callback) {
        UUID uuid = player.getUniqueId();
        PlayerQuests state = players.get(uuid);
        ActiveQuest active = state == null ? null : state.activeQuest(quest.id());
        if (active == null || active.completing()) {
            callback.accept(false);
            return;
        }
        active.completing(true);
        long now = System.currentTimeMillis();

        repository.complete(uuid, quest.id(), now, quest.expReward(), quest.vyrReward(), expService.ranks())
                .whenComplete((result, error) -> onMain(() -> {
                    if (error != null) {
                        active.completing(false);
                        plugin.getLogger().log(Level.SEVERE, "Failed to complete quest " + quest.id() + " for " + player.getName(), error);
                        callback.accept(false);
                        return;
                    }
                    PlayerQuests current = players.get(uuid);
                    if (current != null) {
                        if (result.claimed()) {
                            current.completeActive(quest.id(), now);
                        } else {
                            current.removeActive(quest.id());
                        }
                    }
                    if (player.isOnline()) {
                        refreshBoard(player); // gone when completed; shows the next active quest if any
                    }
                    if (!result.claimed()) {
                        callback.accept(false);
                        return;
                    }
                    if (result.exp() != null) {
                        plugin.announceExp(uuid, result.exp(), quest.expReward());
                    }
                    if (player.isOnline()) {
                        plugin.messages().send(player, "quest.completed", Placeholder.parsed("name", quest.name()));
                        if (quest.expReward() > 0) {
                            plugin.messages().send(player, "quest.reward-exp",
                                    Placeholder.unparsed("exp", Long.toString(quest.expReward())));
                        }
                    }
                    // The ticket already exists (written with the claim), so this can only pay it, never create a second one.
                    if (result.pendingVyrId() > 0) {
                        vyrPayouts.pay(new PendingVyrRepository.Pending(result.pendingVyrId(), uuid, quest.id(),
                                quest.vyrReward()));
                    }
                    callback.accept(true);
                }));
    }

    // ---------------------------------------------------------------- messages

    /** Sends the player the message matching the outcome of {@link #accept}. */
    public void sendAcceptResult(Player player, Quest quest, QuestAvailability result) {
        PlayerQuests state = players.get(player.getUniqueId());
        long remaining = state == null ? 0 : cooldownRemainingSeconds(state, quest);
        plugin.messages().send(player, result.messageKey(),
                Placeholder.parsed("name", quest.name()),
                Placeholder.unparsed("rank", quest.requiredRank() == null ? "" : quest.requiredRank()),
                Placeholder.unparsed("time", Durations.format(remaining)));
    }

    private void onMain(Runnable task) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }
}
