package com.veyrion.character.api.impl;

import com.veyrion.character.awakening.PlayerDouble;
import com.veyrion.character.awakening.PlayerDoubleFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Lifecycle check of PlayerDoublePreview (show, refresh, look, remove) with a fake PlayerDouble. See README.txt. */
public final class PreviewCheck {

    static int checks = 0;
    static int failures = 0;

    static void eq(String what, Object expected, Object actual) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static final class FakeDouble implements PlayerDouble {
        final List<UUID> shown = new ArrayList<>();
        final List<String> looks = new ArrayList<>();
        int removes;
        @Override public void show(Player viewer) { shown.add(viewer.getUniqueId()); }
        @Override public void look(float yaw, float pitch) { looks.add(yaw + "/" + pitch); }
        @Override public void remove() { removes++; }
    }

    static final class FakeFactory implements PlayerDoubleFactory {
        final List<FakeDouble> made = new ArrayList<>();
        @Override public PlayerDouble create(Player source, Location at, boolean equipment) {
            FakeDouble d = new FakeDouble();
            made.add(d);
            return d;
        }
    }

    static Player player(UUID id, boolean[] online) {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isOnline" -> online[0];
            case "hashCode" -> id.hashCode();
            case "equals" -> proxy == args[0];
            default -> null;
        };
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, h);
    }

    public static void main(String[] args) {
        boolean[] subjectOnline = {true};
        boolean[] viewerOnline = {true};
        Player subject = player(UUID.randomUUID(), subjectOnline);
        Player viewer = player(UUID.randomUUID(), viewerOnline);
        Player other = player(UUID.randomUUID(), new boolean[] {true});
        FakeFactory factory = new FakeFactory();

        PlayerDoublePreview preview = new PlayerDoublePreview(factory, subject, new Location(null, 1, 2, 3, 90f, 0f), false);
        eq("one body made", 1, factory.made.size());
        eq("active", true, preview.isActive());
        eq("subject id", subject.getUniqueId(), preview.subject());

        preview.show(viewer);
        preview.show(viewer); // idempotent
        eq("shown once", 1, factory.made.get(0).shown.size());
        preview.show(other);
        eq("two viewers", 2, factory.made.get(0).shown.size());

        preview.look(180f, 10f);
        eq("look forwarded", List.of("180.0/10.0"), factory.made.get(0).looks);

        // skin changed: a new body, old one removed, every viewer gets the new one
        preview.refreshSkin();
        eq("second body made", 2, factory.made.size());
        eq("old body removed once", 1, factory.made.get(0).removes);
        eq("new body shown to both", List.of(viewer.getUniqueId(), other.getUniqueId()), factory.made.get(1).shown);
        preview.look(200f, 0f);
        eq("look goes to the new body only", 1, factory.made.get(0).looks.size());
        eq("new body looks", List.of("200.0/0.0"), factory.made.get(1).looks);

        // a viewer who left is skipped on refresh
        viewerOnline[0] = false;
        preview.refreshSkin();
        eq("offline viewer skipped", List.of(other.getUniqueId()), factory.made.get(2).shown);

        // remove is safe and idempotent
        preview.remove();
        preview.remove();
        eq("inactive after remove", false, preview.isActive());
        eq("body removed once", 1, factory.made.get(2).removes);
        preview.show(viewer);
        preview.refreshSkin();
        preview.look(0f, 0f);
        eq("nothing happens after remove (no new body)", 3, factory.made.size());
        eq("nothing shown after remove", 1, factory.made.get(2).shown.size());

        // subject goes offline during refresh: the preview removes itself
        PlayerDoublePreview second = new PlayerDoublePreview(factory, subject, new Location(null, 0, 0, 0), true);
        second.show(other);
        subjectOnline[0] = false;
        second.refreshSkin();
        eq("self removed when subject offline", false, second.isActive());
        eq("no replacement body for offline subject", 4, factory.made.size());

        System.out.println(checks + " checks, " + failures + " failures");
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        if (failures != 0) System.exit(1);
    }
}
