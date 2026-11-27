package com.veyrion.character.api.impl;

import com.veyrion.character.api.CharacterPreview;
import com.veyrion.character.awakening.PlayerDouble;
import com.veyrion.character.awakening.PlayerDoubleFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * {@link CharacterPreview} on top of the packet-only {@link PlayerDouble}. It holds no ProtocolLib type. Refreshing
 * the skin replaces the double (a double reads the skin once, when it is made). Main thread only.
 */
final class PlayerDoublePreview implements CharacterPreview {

    private final PlayerDoubleFactory factory;
    private final Player subject;
    private final boolean equipment;
    private final Location location;
    private final Map<UUID, Player> viewers = new LinkedHashMap<>();
    private PlayerDouble body;
    private boolean active = true;

    PlayerDoublePreview(PlayerDoubleFactory factory, Player subject, Location at, boolean equipment) {
        this.factory = factory;
        this.subject = subject;
        this.equipment = equipment;
        this.location = at.clone();
        this.body = factory.create(subject, location, equipment);
    }

    @Override
    public UUID subject() {
        return subject.getUniqueId();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void show(Player viewer) {
        if (!active || !viewer.isOnline() || viewers.containsKey(viewer.getUniqueId())) {
            return;
        }
        viewers.put(viewer.getUniqueId(), viewer);
        body.show(viewer);
    }

    @Override
    public void refreshSkin() {
        if (!active) {
            return;
        }
        if (!subject.isOnline()) {
            remove();
            return;
        }
        PlayerDouble old = body;
        body = factory.create(subject, location, equipment); // reads the skin the subject wears now
        old.remove();
        for (Player viewer : viewers.values()) {
            if (viewer.isOnline()) {
                body.show(viewer);
            }
        }
    }

    @Override
    public void look(float yaw, float pitch) {
        if (!active) {
            return;
        }
        location.setYaw(yaw);
        location.setPitch(pitch);
        body.look(yaw, pitch);
    }

    @Override
    public void remove() {
        if (!active) {
            return;
        }
        active = false;
        viewers.clear();
        body.remove();
    }
}
