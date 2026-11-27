package com.veyrion.character.api;

import java.util.Collection;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Public API of VeyrionCharacter. Everything in {@code com.veyrion.character.api} (and
 * {@code com.veyrion.character.api.event}) is stable within one {@link #API_VERSION}; everything else in the plugin
 * is internal and may change.
 *
 * <h2>Getting it</h2>
 * Add {@code depend: [VeyrionCharacter]} (or {@code softdepend}) to your plugin.yml, compile against the jar, then:
 * <pre>{@code
 * VeyrionCharacterAPI api = VeyrionCharacterAPI.get();      // throws if the plugin is not running
 * Optional<VeyrionCharacterAPI> maybe = VeyrionCharacterAPI.find(); // for softdepend
 * }</pre>
 *
 * <h2>Threads</h2>
 * Unless a method says otherwise, call it from the main server thread. Every {@link CompletableFuture} and callback
 * returned or taken by this API completes on the <b>main server thread</b>, so it is safe to use the Bukkit API in
 * it. Methods that return a value without a future never block on the database: they answer from memory.
 */
public interface VeyrionCharacterAPI {

    /** Increases only when something in the API changes incompatibly. */
    int API_VERSION = 1;

    /** @return the API, or throws {@link IllegalStateException} when VeyrionCharacter is not enabled */
    static VeyrionCharacterAPI get() {
        return find().orElseThrow(() -> new IllegalStateException("VeyrionCharacter is not enabled"));
    }

    static Optional<VeyrionCharacterAPI> find() {
        RegisteredServiceProvider<VeyrionCharacterAPI> provider =
                Bukkit.getServicesManager().getRegistration(VeyrionCharacterAPI.class);
        return provider == null ? Optional.empty() : Optional.of(provider.getProvider());
    }

    /** The {@link #API_VERSION} this plugin implements. Compare it with the one you compiled against. */
    int apiVersion();

    // ------------------------------------------------------------------------------------------- character

    /**
     * The character from memory: always instant, no database access. Empty if nothing is cached yet (an
     * offline player that was never asked for; a background load is started, ask again a moment later, or use
     * {@link #loadCharacter}) or the player has no character. Online players are always cached.
     */
    Optional<CharacterInfo> character(UUID player);

    /** Reads the character from the database (never stale). Completes on the main thread. */
    CompletableFuture<Optional<CharacterInfo>> loadCharacter(UUID player);

    /**
     * Adds Adventure EXP (must be positive). The rank is recalculated; {@link
     * com.veyrion.character.api.event.CharacterExpGainEvent} and, when it changed, {@link
     * com.veyrion.character.api.event.CharacterRankChangeEvent} are fired and the player is told. The future fails
     * with an exception if the character does not exist. Completes on the main thread.
     */
    CompletableFuture<ExpGrant> grantExp(UUID player, long amount);

    // --------------------------------------------------------------------------------------------- races

    Collection<RaceInfo> races();

    Optional<RaceInfo> race(String id);

    // --------------------------------------------------------------------------------------------- quests

    Collection<QuestInfo> quests();

    Optional<QuestInfo> quest(String id);

    /** True while the quest is active for an ONLINE player (false for offline players and unknown quests). */
    boolean isQuestActive(Player player, String questId);

    /** True once an ONLINE player completed the quest at least once (false for offline players). */
    boolean hasCompletedQuest(Player player, String questId);

    /**
     * Offers a quest to a player with all normal rules (rank, prerequisites, cooldown). The callback runs on the
     * main thread. Nothing is sent to the player; use the result to tell them whatever you like. If an accept
     * of the same quest for the same player is already in flight, the duplicate is ignored and its callback is
     * not called.
     */
    void acceptQuest(Player player, String questId, Consumer<QuestResult> callback);

    /**
     * Adds progress to every unfinished CUSTOM objective with this key in the player's active quests
     * (quest objective type {@code CUSTOM}, key {@code key}).
     */
    void progressCustomObjective(Player player, String key, int amount);

    // ------------------------------------------------------------------------------------------- economy

    /** True when the Veyra economy works (Vault and an economy provider are present and enabled). */
    boolean economyAvailable();

    /** The balance, or empty when the economy is unavailable. Main thread. */
    OptionalDouble balance(UUID player);

    /** "2,500 VYR": the amount with the configured currency symbol. */
    String formatMoney(double amount);

    /**
     * Whether the player can pay {@code amount} right now. False when the economy is unavailable or the amount is
     * not valid (see {@link MoneyResult#INVALID_AMOUNT}). Main thread. Only a hint: use {@link #withdraw} to pay.
     * Added without a new {@link #API_VERSION}; the default keeps older implementations compiling.
     */
    default boolean has(UUID player, double amount) {
        return false;
    }

    /**
     * Takes {@code amount} (rounded to two decimals) from the player's balance, through the economy provider.
     * It checks the balance and takes the money as one step, so it never makes a balance negative, never takes
     * the money twice for one call, and two concurrent calls cannot both spend the same money. The result says
     * what happened. Main thread. Added without a new {@link #API_VERSION}; the default keeps older
     * implementations compiling and reports {@link MoneyResult#UNAVAILABLE}.
     */
    default MoneyResult withdraw(UUID player, double amount) {
        return MoneyResult.UNAVAILABLE;
    }

    // ----------------------------------------------------------------------------------------------- previews

    /**
     * Makes visual-only copies of players (for mirrors and the like). Empty when the packet backend
     * (ProtocolLib) is not available. Added without a new {@link #API_VERSION}.
     */
    default Optional<CharacterPreviewFactory> previews() {
        return Optional.empty();
    }

    // ----------------------------------------------------------------------------------------------- NPCs

    /** Every built-in NPC, in creation order (a snapshot). */
    Collection<NpcInfo> npcs();

    Optional<NpcInfo> npc(String id);
}
