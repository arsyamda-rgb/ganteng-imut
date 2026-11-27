package com.veyrion.character.api.impl;

import com.veyrion.character.api.CharacterInfo;
import com.veyrion.character.api.CharacterPreviewFactory;
import com.veyrion.character.api.ExpGrant;
import com.veyrion.character.api.MoneyResult;
import com.veyrion.character.api.NpcInfo;
import com.veyrion.character.api.QuestInfo;
import com.veyrion.character.api.QuestResult;
import com.veyrion.character.api.RaceInfo;
import com.veyrion.character.api.VeyrionCharacterAPI;
import com.veyrion.character.awakening.PlayerDoubleFactory;
import com.veyrion.character.character.CharacterData;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.integration.CharacterCache;
import com.veyrion.character.npc.NpcData;
import com.veyrion.character.quest.ObjectiveType;
import com.veyrion.character.quest.PlayerQuests;
import com.veyrion.character.quest.Quest;
import com.veyrion.character.quest.QuestAvailability;
import com.veyrion.character.race.RaceDefinition;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/** The one implementation of {@link VeyrionCharacterAPI}. Internal: other plugins use the interface only. */
public final class ApiImpl implements VeyrionCharacterAPI {

    private final VeyrionCharacterPlugin plugin;

    public ApiImpl(VeyrionCharacterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    // ------------------------------------------------------------------------------------------- character

    @Override
    public Optional<CharacterInfo> character(UUID player) {
        Objects.requireNonNull(player, "player");
        CharacterCache.Entry entry = plugin.characterCache().get(player);
        return entry == null || entry.data() == null ? Optional.empty() : Optional.of(info(entry.data()));
    }

    @Override
    public CompletableFuture<Optional<CharacterInfo>> loadCharacter(UUID player) {
        Objects.requireNonNull(player, "player");
        return plugin.raceService().character(player).thenApply(found -> found.map(ApiImpl::info));
    }

    @Override
    public CompletableFuture<ExpGrant> grantExp(UUID player, long amount) {
        Objects.requireNonNull(player, "player");
        CompletableFuture<ExpGrant> out = new CompletableFuture<>();
        plugin.expService().grant(player, amount).whenComplete((result, error) -> {
            if (!plugin.isEnabled()) {
                out.completeExceptionally(error != null ? error : new IllegalStateException("plugin disabled"));
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    out.completeExceptionally(error);
                    return;
                }
                plugin.announceExp(player, result, amount); // cache refresh, events, player message
                out.complete(new ExpGrant(amount, result.oldExp(), result.newExp(), result.oldRank(),
                        result.newRank()));
            });
        });
        return out;
    }

    private static CharacterInfo info(CharacterData data) {
        return new CharacterInfo(data.uuid(), data.playerName(), data.race(), data.adventureExp(),
                data.adventureRank(), data.hasCompletedGacha());
    }

    // --------------------------------------------------------------------------------------------- races

    @Override
    public Collection<RaceInfo> races() {
        return plugin.races().settings().races().values().stream().map(ApiImpl::info).toList();
    }

    @Override
    public Optional<RaceInfo> race(String id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(plugin.races().settings().find(id)).map(ApiImpl::info);
    }

    private static RaceInfo info(RaceDefinition race) {
        return new RaceInfo(race.id(), race.displayName(), race.description(), race.enabled());
    }

    // --------------------------------------------------------------------------------------------- quests

    @Override
    public Collection<QuestInfo> quests() {
        return plugin.questService().registry().all().stream().map(ApiImpl::info).toList();
    }

    @Override
    public Optional<QuestInfo> quest(String id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(plugin.questService().registry().get(id)).map(ApiImpl::info);
    }

    private static QuestInfo info(Quest quest) {
        return new QuestInfo(quest.id(), quest.name(), quest.description(), quest.requiredRank(),
                quest.expReward(), quest.vyrReward(), quest.objectives().size(), quest.type().isRepeatable());
    }

    @Override
    public boolean isQuestActive(Player player, String questId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(questId, "questId");
        PlayerQuests state = plugin.questService().state(player.getUniqueId());
        return state != null && state.isActive(questId);
    }

    @Override
    public boolean hasCompletedQuest(Player player, String questId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(questId, "questId");
        PlayerQuests state = plugin.questService().state(player.getUniqueId());
        return state != null && state.completion(questId) != null;
    }

    @Override
    public void acceptQuest(Player player, String questId, Consumer<QuestResult> callback) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(questId, "questId");
        Objects.requireNonNull(callback, "callback");
        Quest quest = plugin.questService().registry().get(questId);
        if (quest == null) {
            callback.accept(QuestResult.UNKNOWN_QUEST);
            return;
        }
        plugin.questService().accept(player, quest, false, availability -> callback.accept(map(availability)));
    }

    static QuestResult map(QuestAvailability availability) {
        return switch (availability) {
            case AVAILABLE -> QuestResult.ACCEPTED;
            case ACTIVE -> QuestResult.ALREADY_ACTIVE;
            case COMPLETED -> QuestResult.ALREADY_COMPLETED;
            case ON_COOLDOWN -> QuestResult.ON_COOLDOWN;
            case LOCKED_PREREQUISITE -> QuestResult.PREREQUISITE_MISSING;
            case LOCKED_RANK -> QuestResult.RANK_TOO_LOW;
            case NOT_LOADED -> QuestResult.NOT_LOADED;
            case ERROR -> QuestResult.ERROR;
        };
    }

    @Override
    public void progressCustomObjective(Player player, String key, int amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");
        plugin.questService().progress(player,
                objective -> objective.type() == ObjectiveType.CUSTOM && key.equals(objective.customKey()), amount);
    }

    // ------------------------------------------------------------------------------------------- economy

    @Override
    public boolean economyAvailable() {
        return plugin.economyService().isActive();
    }

    @Override
    public OptionalDouble balance(UUID player) {
        Double value = plugin.economyService().balance(player);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    @Override
    public String formatMoney(double amount) {
        return plugin.economyService().format(amount);
    }

    @Override
    public boolean has(UUID player, double amount) {
        Objects.requireNonNull(player, "player");
        return plugin.economyService().hasFunds(player, amount);
    }

    @Override
    public MoneyResult withdraw(UUID player, double amount) {
        Objects.requireNonNull(player, "player");
        return plugin.economyService().withdrawChecked(player, amount);
    }

    // ------------------------------------------------------------------------------------------- previews

    @Override
    public Optional<CharacterPreviewFactory> previews() {
        PlayerDoubleFactory doubles = plugin.playerDoubles();
        if (doubles == null) {
            return Optional.empty();
        }
        return Optional.of((subject, at, showEquipment) -> {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(at, "at");
            return new PlayerDoublePreview(doubles, subject, at, showEquipment);
        });
    }

    // ----------------------------------------------------------------------------------------------- NPCs

    @Override
    public Collection<NpcInfo> npcs() {
        return plugin.npcs().all().stream().map(ApiImpl::info).toList();
    }

    @Override
    public Optional<NpcInfo> npc(String id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(plugin.npcs().get(id)).map(ApiImpl::info);
    }

    public static NpcInfo info(NpcData data) {
        return new NpcInfo(data.id(), data.displayName(), data.world(), data.x(), data.y(), data.z(),
                data.questId(), data.visible(), data.dialogue());
    }
}
