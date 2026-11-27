package com.veyrion.character.npc;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * Owns every NPC at runtime. Main thread only.
 *
 * <p>An NPC is three things: the humanoid body (packets, only on the clients of players within {@link #SHOW}
 * blocks), an invisible Interaction entity that receives the clicks, and a text display with the name. The two
 * server entities are non-persistent: they never reach the world save and are spawned again, from
 * {@code npcs.yml}, whenever their chunk is loaded. One task, every second, decides who sees what; it exists as long as the
 * plugin runs and costs players x NPCs distance checks.
 *
 * <p>Without ProtocolLib there is no body factory: NPCs are still stored and listed, but nothing is spawned.
 */
public final class NpcManager {

    /** Distance (blocks) at which a body is sent to a player, and the larger one at which it is taken away. */
    static final double SHOW = 48.0;
    static final double HIDE = 56.0;
    static final String TAG = "veyrion_npc";
    public static final String ADMIN_PERMISSION = "veyrion.npc.admin";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public enum CreateResult { CREATED, INVALID_ID, EXISTS, TOO_MANY, UNAVAILABLE }

    /** Runtime state of one NPC. */
    private static final class Npc {
        NpcData data;
        @Nullable NpcBody body;
        @Nullable Interaction interaction;
        @Nullable TextDisplay label;

        Npc(NpcData data) {
            this.data = data;
        }
    }

    public static final int MAX_NPCS = 200;

    private final VeyrionCharacterPlugin plugin;
    private final NpcStore store;
    private final @Nullable NpcBodyFactory factory;
    private final NamespacedKey idKey;
    private final Map<String, Npc> npcs = new LinkedHashMap<>();
    private @Nullable BukkitTask task;
    /** False after a file that could not be read: saving would overwrite the owner's data with an empty file. */
    private boolean saving = true;

    public NpcManager(VeyrionCharacterPlugin plugin, NpcStore store, @Nullable NpcBodyFactory factory) {
        this.plugin = plugin;
        this.store = store;
        this.factory = factory;
        this.idKey = new NamespacedKey(plugin, "npc_id");
    }

    /** True when bodies can be shown (ProtocolLib is present). */
    public boolean bodiesAvailable() {
        return factory != null;
    }

    // ------------------------------------------------------------------------------------------- lifecycle

    /** Loads npcs.yml and starts the visibility task. */
    public void start() {
        load();
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        }
    }

    /** Re-reads npcs.yml (the file wins over memory). */
    public void reload() {
        load();
    }

    private void load() {
        despawnAll();
        npcs.clear();
        Map<String, NpcData> loaded = store.load();
        saving = loaded != null;
        if (loaded != null) {
            loaded.values().forEach(data -> npcs.put(data.id(), new Npc(data)));
        }
        plugin.getLogger().info(npcs.size() + " NPC(s) loaded" + (factory == null
                ? "; ProtocolLib is not installed, so they are not shown." : "."));
        if (task != null) {
            tick();
        }
    }

    /** Removes every body and entity and saves nothing. Call on disable. */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        despawnAll();
        store.close();
    }

    // ---------------------------------------------------------------------------------------------- queries

    public @Nullable NpcData get(String id) {
        Npc npc = npcs.get(id.toLowerCase(Locale.ROOT));
        return npc == null ? null : npc.data;
    }

    /** Immutable snapshot of every NPC, in creation order. */
    public List<NpcData> all() {
        List<NpcData> list = new ArrayList<>(npcs.size());
        npcs.values().forEach(npc -> list.add(npc.data));
        return List.copyOf(list);
    }

    /** The NPC an entity belongs to (its click box or its label), or null. */
    public @Nullable NpcData byEntity(Entity entity) {
        if (!(entity instanceof Interaction) && !(entity instanceof TextDisplay)) {
            return null;
        }
        String id = entity.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id == null ? null : get(id);
    }

    public static boolean isAdmin(Player player) {
        return player.hasPermission(ADMIN_PERMISSION);
    }

    // ---------------------------------------------------------------------------------------------- changes

    /** Creates an NPC at {@code at} facing the way {@code at} faces. */
    public CreateResult create(String id, String displayName, Location at) {
        String clean = id.toLowerCase(Locale.ROOT);
        if (!NpcData.validId(clean)) {
            return CreateResult.INVALID_ID;
        }
        if (npcs.containsKey(clean)) {
            return CreateResult.EXISTS;
        }
        if (npcs.size() >= MAX_NPCS) {
            return CreateResult.TOO_MANY;
        }
        if (!saving) {
            return CreateResult.UNAVAILABLE;
        }
        NpcData data = new NpcData(clean, displayName, null, null, null, null, List.of(), at.getWorld().getName(),
                at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(), true);
        Npc npc = new Npc(data);
        npcs.put(clean, npc);
        persist();
        refresh(npc);
        return CreateResult.CREATED;
    }

    /**
     * Applies a change, saves it and updates what players see. The function gets the current data and returns the
     * new one (it must keep the id).
     *
     * @return false if the NPC does not exist or the change was refused
     */
    public boolean update(String id, UnaryOperator<NpcData> change) {
        Npc npc = npcs.get(id.toLowerCase(Locale.ROOT));
        if (npc == null || !saving) {
            return false;
        }
        NpcData updated;
        try {
            updated = change.apply(npc.data);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "NPC change failed for " + id, e);
            return false;
        }
        if (updated == null || !updated.id().equals(npc.data.id())) {
            return false;
        }
        if (updated.equals(npc.data)) {
            return true;
        }
        npc.data = updated;
        persist();
        refresh(npc);
        return true;
    }

    public boolean delete(String id) {
        Npc npc = npcs.get(id.toLowerCase(Locale.ROOT));
        if (npc == null || !saving) {
            return false;
        }
        despawn(npc);
        npcs.remove(npc.data.id());
        persist();
        return true;
    }

    private void persist() {
        if (saving) {
            store.save(all());
        }
    }

    // ------------------------------------------------------------------------------------------ player events

    /** A player's client no longer has our bodies (quit, respawn, world change). */
    public void forget(Player player) {
        for (Npc npc : npcs.values()) {
            if (npc.body != null) {
                npc.body.forget(player);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ spawning

    /** Throws away everything shown for this NPC and shows it again from its data. */
    private void refresh(Npc npc) {
        despawn(npc);
        spawnEntities(npc);
        updateViewers(npc, collectViewers());
    }

    private void tick() {
        if (npcs.isEmpty()) {
            return;
        }
        Map<Player, Boolean> players = collectViewers();
        for (Npc npc : npcs.values()) {
            spawnEntities(npc);
            updateViewers(npc, players);
        }
    }

    private Map<Player, Boolean> collectViewers() {
        Map<Player, Boolean> players = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.put(player, isAdmin(player));
        }
        return players;
    }

    private @Nullable Location location(Npc npc) {
        World world = Bukkit.getWorld(npc.data.world());
        if (world == null) {
            return null;
        }
        return new Location(world, npc.data.x(), npc.data.y(), npc.data.z(), npc.data.yaw(), npc.data.pitch());
    }

    /** Spawns the click box and the label if they are missing and their chunk is loaded. */
    private void spawnEntities(Npc npc) {
        Location at = location(npc);
        if (at == null || factory == null) {
            return;
        }
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            return;
        }
        if (npc.interaction == null || !npc.interaction.isValid()) {
            npc.interaction = at.getWorld().spawn(at, Interaction.class, entity -> {
                entity.setPersistent(false);
                entity.setInteractionWidth(0.9f);
                entity.setInteractionHeight(1.95f);
                entity.setResponsive(true);
                tag(entity, npc.data.id());
            });
        }
        if (npc.label == null || !npc.label.isValid()) {
            npc.label = at.getWorld().spawn(at.clone().add(0, 2.15, 0), TextDisplay.class, entity -> {
                entity.setPersistent(false);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setSeeThrough(false);
                entity.setShadowed(true);
                entity.setViewRange(0.5f);
                entity.text(labelText(npc.data));
                tag(entity, npc.data.id());
            });
        }
    }

    private void tag(Entity entity, String id) {
        entity.addScoreboardTag(TAG);
        entity.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
    }

    static Component labelText(NpcData data) {
        Component name = MINI.deserialize(data.displayName());
        return data.visible() ? name : name.append(Component.text(" (hidden)", net.kyori.adventure.text.format.NamedTextColor.GRAY));
    }

    private void updateViewers(Npc npc, Map<Player, Boolean> players) {
        if (factory == null) {
            return;
        }
        Location at = location(npc);
        if (at == null) {
            return;
        }
        boolean needsBody = false;
        for (Map.Entry<Player, Boolean> entry : players.entrySet()) {
            if (wants(npc, at, entry.getKey(), entry.getValue(), false)) {
                needsBody = true;
                break;
            }
        }
        if (npc.body == null) {
            if (!needsBody) {
                return;
            }
            try {
                npc.body = factory.create(npc.data, at);
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().log(Level.WARNING, "Could not create the body of NPC " + npc.data.id(), e);
                return;
            }
        }
        NpcBody body = npc.body;
        for (Map.Entry<Player, Boolean> entry : players.entrySet()) {
            Player player = entry.getKey();
            boolean shown = body.isShownTo(player);
            boolean want = wants(npc, at, player, entry.getValue(), shown);
            if (want && !shown) {
                body.show(player);
            } else if (!want && shown) {
                body.hide(player);
            }
        }
    }

    private static boolean wants(Npc npc, Location at, Player player, boolean admin, boolean alreadyShown) {
        if (!npc.data.visible() && !admin) {
            return false;
        }
        Location where = player.getLocation();
        if (where.getWorld() == null || !where.getWorld().equals(at.getWorld())) {
            return false;
        }
        double range = alreadyShown ? HIDE : SHOW;
        return where.distanceSquared(at) <= range * range;
    }

    private void despawn(Npc npc) {
        if (npc.body != null) {
            try {
                npc.body.remove();
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().log(Level.WARNING, "Could not remove the body of NPC " + npc.data.id(), e);
            }
            npc.body = null;
        }
        if (npc.interaction != null) {
            npc.interaction.remove();
            npc.interaction = null;
        }
        if (npc.label != null) {
            npc.label.remove();
            npc.label = null;
        }
    }

    private void despawnAll() {
        npcs.values().forEach(this::despawn);
    }

    /** For /vnpc list. */
    public Collection<String> ids() {
        return List.copyOf(npcs.keySet());
    }
}
