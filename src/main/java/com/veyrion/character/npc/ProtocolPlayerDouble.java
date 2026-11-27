package com.veyrion.character.npc;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.veyrion.character.awakening.PlayerDouble;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.Nullable;

/**
 * A packet-only copy of a real player: their skin (taken from their live profile), their facing and, optionally,
 * their armor and held items. No server entity. Same packet layout as {@link ProtocolNpcBody} (Paper 1.21.1).
 * Main thread only. The name tag is hidden with a scoreboard team.
 */
final class ProtocolPlayerDouble implements PlayerDouble {

    private static final AtomicInteger IDS = new AtomicInteger(1_800_000_000);

    private final Logger logger;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final int entityId = IDS.incrementAndGet();
    private final UUID uuid = UUID.randomUUID();
    private final String profileName = "vd" + Integer.toHexString(entityId & 0xFFFFFF);
    private final WrappedGameProfile profile;
    private final Location location;
    private final List<Pair<EnumWrappers.ItemSlot, ItemStack>> gear = new ArrayList<>();
    private final Map<UUID, Player> viewers = new LinkedHashMap<>();
    private @Nullable Team team;

    ProtocolPlayerDouble(Logger logger, Player source, Location at, boolean equipment) {
        this.logger = logger;
        this.location = at.clone();
        this.profile = new WrappedGameProfile(uuid, profileName);
        // The live profile carries the player's real, signed skin.
        profile.getProperties().putAll(WrappedGameProfile.fromPlayer(source).getProperties());
        if (equipment) {
            PlayerInventory inv = source.getInventory();
            gear.add(new Pair<>(EnumWrappers.ItemSlot.MAINHAND, copy(inv.getItemInMainHand())));
            gear.add(new Pair<>(EnumWrappers.ItemSlot.OFFHAND, copy(inv.getItemInOffHand())));
            gear.add(new Pair<>(EnumWrappers.ItemSlot.HEAD, copy(inv.getHelmet())));
            gear.add(new Pair<>(EnumWrappers.ItemSlot.CHEST, copy(inv.getChestplate())));
            gear.add(new Pair<>(EnumWrappers.ItemSlot.LEGS, copy(inv.getLeggings())));
            gear.add(new Pair<>(EnumWrappers.ItemSlot.FEET, copy(inv.getBoots())));
        }
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Team old = board.getTeam(profileName);
            if (old != null) {
                old.unregister();
            }
            team = board.registerNewTeam(profileName);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            team.addEntry(profileName);
        } catch (RuntimeException e) {
            team = null; // the profile name tag would be visible; harmless
        }
    }

    private static ItemStack copy(@Nullable ItemStack item) {
        return item == null ? new ItemStack(org.bukkit.Material.AIR) : item.clone();
    }

    @Override
    public void show(Player viewer) {
        if (viewers.putIfAbsent(viewer.getUniqueId(), viewer) != null) {
            return;
        }
        PacketContainer info = new PacketContainer(PacketType.Play.Server.PLAYER_INFO);
        info.getPlayerInfoActions().write(0, EnumSet.of(EnumWrappers.PlayerInfoAction.ADD_PLAYER));
        info.getPlayerInfoDataLists().write(1, List.of(new PlayerInfoData(profile, 0,
                EnumWrappers.NativeGameMode.SURVIVAL, WrappedChatComponent.fromText(profileName))));
        send(viewer, info);

        PacketContainer spawn = new PacketContainer(PacketType.Play.Server.SPAWN_ENTITY);
        spawn.getIntegers().write(0, entityId);
        spawn.getUUIDs().write(0, uuid);
        spawn.getEntityTypeModifier().write(0, EntityType.PLAYER);
        spawn.getDoubles().write(0, location.getX()).write(1, location.getY()).write(2, location.getZ());
        spawn.getBytes().write(0, angle(location.getPitch())).write(1, angle(location.getYaw()))
                .write(2, angle(location.getYaw()));
        send(viewer, spawn);

        // Every skin layer (jacket, sleeves, hat ...) on.
        PacketContainer meta = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
        meta.getIntegers().write(0, entityId);
        List<WrappedDataValue> values = new ArrayList<>();
        values.add(new WrappedDataValue(17, WrappedDataWatcher.Registry.get(Byte.class), (byte) 0x7F));
        meta.getDataValueCollectionModifier().write(0, values);
        send(viewer, meta);

        PacketContainer head = new PacketContainer(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        head.getIntegers().write(0, entityId);
        head.getBytes().write(0, angle(location.getYaw()));
        send(viewer, head);

        if (!gear.isEmpty()) {
            PacketContainer equipment = new PacketContainer(PacketType.Play.Server.ENTITY_EQUIPMENT);
            equipment.getIntegers().write(0, entityId);
            equipment.getSlotStackPairLists().write(0, gear);
            send(viewer, equipment);
        }
    }

    @Override
    public void look(float yaw, float pitch) {
        location.setYaw(yaw);
        location.setPitch(pitch);
        for (Player viewer : List.copyOf(viewers.values())) {
            if (!viewer.isOnline()) {
                continue;
            }
            PacketContainer look = new PacketContainer(PacketType.Play.Server.ENTITY_LOOK);
            look.getIntegers().write(0, entityId);
            look.getBytes().write(0, angle(yaw)).write(1, angle(pitch));
            look.getBooleans().write(0, true);
            send(viewer, look);
            PacketContainer head = new PacketContainer(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
            head.getIntegers().write(0, entityId);
            head.getBytes().write(0, angle(yaw));
            send(viewer, head);
        }
    }

    @Override
    public void remove() {
        for (Player viewer : List.copyOf(viewers.values())) {
            if (viewer.isOnline()) {
                PacketContainer destroy = new PacketContainer(PacketType.Play.Server.ENTITY_DESTROY);
                destroy.getIntLists().write(0, List.of(entityId));
                send(viewer, destroy);
                PacketContainer remove = new PacketContainer(PacketType.Play.Server.PLAYER_INFO_REMOVE);
                remove.getUUIDLists().write(0, List.of(uuid));
                send(viewer, remove);
            }
        }
        viewers.clear();
        Team old = team;
        team = null;
        if (old != null) {
            try {
                old.unregister();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
    }

    private void send(Player player, PacketContainer packet) {
        try {
            protocol.sendServerPacket(player, packet);
        } catch (RuntimeException e) {
            logger.warning("Could not send a player-double packet to " + player.getName() + ": " + e);
        }
    }

    private static byte angle(float degrees) {
        return (byte) Math.floor(degrees * 256.0f / 360.0f);
    }
}
