package com.veyrion.character.npc;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import java.util.ArrayList;
import java.util.Collection;
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
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.Nullable;

/**
 * A fake player made of packets (ProtocolLib): custom skin, no server entity, same technique as the
 * AwakeningCutscene plugin's FakePlayer. Packet layout is the one of Paper/Minecraft 1.21.1; 1.21.2+ changed the
 * teleport packet. Main thread only.
 *
 * <p>The nametag is hidden with a scoreboard team; the NPC's display name is drawn by a text display that the
 * {@link NpcManager} owns.
 */
final class ProtocolNpcBody implements NpcBody {

    private static final AtomicInteger IDS = new AtomicInteger(1_700_000_000);

    private final Logger logger;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final int entityId = IDS.incrementAndGet();
    private final UUID uuid = UUID.randomUUID();
    private final String profileName = "vn" + Integer.toHexString(entityId & 0xFFFFFF);
    private final WrappedGameProfile profile;
    private final Map<UUID, Player> viewers = new LinkedHashMap<>();
    private @Nullable Team team;
    private Location location;

    ProtocolNpcBody(Logger logger, NpcData data, Location at) {
        this.logger = logger;
        this.location = at.clone();
        this.profile = new WrappedGameProfile(uuid, profileName);
        if (data.skinValue() != null) {
            profile.getProperties().put("textures", new WrappedSignedProperty("textures", data.skinValue(),
                    data.skinSignature() == null ? "" : data.skinSignature()));
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

    @Override
    public void show(Player player) {
        if (viewers.putIfAbsent(player.getUniqueId(), player) != null) {
            return;
        }
        PacketContainer info = new PacketContainer(PacketType.Play.Server.PLAYER_INFO);
        info.getPlayerInfoActions().write(0, EnumSet.of(EnumWrappers.PlayerInfoAction.ADD_PLAYER));
        info.getPlayerInfoDataLists().write(1, List.of(new PlayerInfoData(profile, 0,
                EnumWrappers.NativeGameMode.SURVIVAL, WrappedChatComponent.fromText(profileName))));
        send(player, info);

        PacketContainer spawn = new PacketContainer(PacketType.Play.Server.SPAWN_ENTITY);
        spawn.getIntegers().write(0, entityId);
        spawn.getUUIDs().write(0, uuid);
        spawn.getEntityTypeModifier().write(0, EntityType.PLAYER);
        spawn.getDoubles().write(0, location.getX()).write(1, location.getY()).write(2, location.getZ());
        spawn.getBytes().write(0, angle(location.getPitch())).write(1, angle(location.getYaw()))
                .write(2, angle(location.getYaw()));
        send(player, spawn);

        // Every skin layer (jacket, sleeves, hat ...) on.
        PacketContainer meta = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
        meta.getIntegers().write(0, entityId);
        List<WrappedDataValue> values = new ArrayList<>();
        values.add(new WrappedDataValue(17, WrappedDataWatcher.Registry.get(Byte.class), (byte) 0x7F));
        meta.getDataValueCollectionModifier().write(0, values);
        send(player, meta);

        sendHeadRotation(player, location.getYaw());
    }

    @Override
    public void hide(Player player) {
        if (viewers.remove(player.getUniqueId()) == null) {
            return;
        }
        if (player.isOnline()) {
            sendRemoval(player);
        }
    }

    @Override
    public void forget(Player player) {
        viewers.remove(player.getUniqueId());
    }

    @Override
    public boolean isShownTo(Player player) {
        return viewers.containsKey(player.getUniqueId());
    }

    @Override
    public Collection<Player> viewers() {
        return List.copyOf(viewers.values());
    }

    @Override
    public void move(Location to) {
        this.location = to.clone();
        PacketContainer teleport = new PacketContainer(PacketType.Play.Server.ENTITY_TELEPORT);
        teleport.getIntegers().write(0, entityId);
        teleport.getDoubles().write(0, to.getX()).write(1, to.getY()).write(2, to.getZ());
        teleport.getBytes().write(0, angle(to.getYaw())).write(1, angle(to.getPitch()));
        teleport.getBooleans().write(0, false);
        for (Player viewer : List.copyOf(viewers.values())) {
            send(viewer, teleport);
            sendHeadRotation(viewer, to.getYaw());
        }
    }

    @Override
    public void remove() {
        for (Player viewer : List.copyOf(viewers.values())) {
            if (viewer.isOnline()) {
                sendRemoval(viewer);
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

    private void sendRemoval(Player player) {
        PacketContainer destroy = new PacketContainer(PacketType.Play.Server.ENTITY_DESTROY);
        destroy.getIntLists().write(0, List.of(entityId));
        send(player, destroy);
        PacketContainer remove = new PacketContainer(PacketType.Play.Server.PLAYER_INFO_REMOVE);
        remove.getUUIDLists().write(0, List.of(uuid));
        send(player, remove);
    }

    private void sendHeadRotation(Player player, float yaw) {
        PacketContainer head = new PacketContainer(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        head.getIntegers().write(0, entityId);
        head.getBytes().write(0, angle(yaw));
        send(player, head);
    }

    private void send(Player player, PacketContainer packet) {
        try {
            protocol.sendServerPacket(player, packet);
        } catch (RuntimeException e) {
            logger.warning("Could not send an NPC packet to " + player.getName() + ": " + e);
        }
    }

    private static byte angle(float degrees) {
        return (byte) Math.floor(degrees * 256.0f / 360.0f);
    }
}
