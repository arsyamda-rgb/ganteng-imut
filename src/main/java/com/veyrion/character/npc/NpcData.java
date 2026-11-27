package com.veyrion.character.npc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Everything that is stored about one NPC. Immutable; every change returns a new copy. No Bukkit types, so
 * (de)serialisation can be checked without a server (dev-checks/NpcDataCheck.java).
 *
 * @param id            unique id, {@code [a-z0-9_-]{1,32}}; also the name used by quest objectives (TALK_TO_NPC)
 * @param displayName   MiniMessage text shown above the NPC
 * @param skinValue     Mojang "textures" property value, or null for the default skin
 * @param skinSignature signature of {@code skinValue}, or null
 * @param skinSource    human readable origin of the skin (for the editor only), or null
 * @param questId       quest this NPC offers, or null
 * @param dialogue      MiniMessage lines said on right-click
 * @param visible       false = hidden from everyone except admins
 */
public record NpcData(
        String id,
        String displayName,
        @Nullable String skinValue,
        @Nullable String skinSignature,
        @Nullable String skinSource,
        @Nullable String questId,
        List<String> dialogue,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        boolean visible) {

    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    public static final int MAX_DIALOGUE_LINES = 20;
    public static final int MAX_LINE_LENGTH = 256;
    public static final int MAX_NAME_LENGTH = 128;

    public NpcData {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(world, "world");
        dialogue = List.copyOf(dialogue);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid npc id: " + id);
        }
    }

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public NpcData withDisplayName(String name) {
        return new NpcData(id, name, skinValue, skinSignature, skinSource, questId, dialogue, world, x, y, z, yaw,
                pitch, visible);
    }

    public NpcData withSkin(@Nullable String value, @Nullable String signature, @Nullable String source) {
        return new NpcData(id, displayName, value, signature, source, questId, dialogue, world, x, y, z, yaw, pitch,
                visible);
    }

    public NpcData withQuest(@Nullable String quest) {
        return new NpcData(id, displayName, skinValue, skinSignature, skinSource, quest, dialogue, world, x, y, z,
                yaw, pitch, visible);
    }

    public NpcData withDialogue(List<String> lines) {
        return new NpcData(id, displayName, skinValue, skinSignature, skinSource, questId, lines, world, x, y, z, yaw,
                pitch, visible);
    }

    public NpcData withLocation(String worldName, double nx, double ny, double nz, float nyaw, float npitch) {
        return new NpcData(id, displayName, skinValue, skinSignature, skinSource, questId, dialogue, worldName, nx,
                ny, nz, nyaw, npitch, visible);
    }

    public NpcData withVisible(boolean value) {
        return new NpcData(id, displayName, skinValue, skinSignature, skinSource, questId, dialogue, world, x, y, z,
                yaw, pitch, value);
    }

    /** Appends a line; null when the dialogue is already full. */
    public @Nullable NpcData addLine(String line) {
        if (dialogue.size() >= MAX_DIALOGUE_LINES) {
            return null;
        }
        List<String> lines = new ArrayList<>(dialogue);
        lines.add(line);
        return withDialogue(lines);
    }

    public NpcData removeLastLine() {
        if (dialogue.isEmpty()) {
            return this;
        }
        return withDialogue(dialogue.subList(0, dialogue.size() - 1));
    }

    // ---------------------------------------------------------------------------------------- persistence

    /** Plain map (strings, numbers, booleans, lists) that YAML can store as is. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", displayName);
        if (skinValue != null) {
            map.put("skin-value", skinValue);
            if (skinSignature != null) {
                map.put("skin-signature", skinSignature);
            }
            if (skinSource != null) {
                map.put("skin-source", skinSource);
            }
        }
        if (questId != null) {
            map.put("quest", questId);
        }
        map.put("dialogue", new ArrayList<>(dialogue));
        map.put("world", world);
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("yaw", (double) yaw);
        map.put("pitch", (double) pitch);
        map.put("visible", visible);
        return map;
    }

    /**
     * Reads one entry. Returns null (never throws) when the entry is unusable: bad id, missing world, missing or
     * non-finite coordinates. Soft problems (too long text, too many lines) are trimmed.
     */
    public static @Nullable NpcData fromMap(String rawId, Map<String, Object> map) {
        String id = rawId == null ? "" : rawId.toLowerCase(Locale.ROOT);
        if (!validId(id)) {
            return null;
        }
        String world = text(map.get("world"));
        Double x = number(map.get("x"));
        Double y = number(map.get("y"));
        Double z = number(map.get("z"));
        if (world == null || world.isBlank() || x == null || y == null || z == null) {
            return null;
        }
        String name = text(map.get("name"));
        if (name == null || name.isBlank()) {
            name = id;
        }
        name = trim(name, MAX_NAME_LENGTH);
        List<String> lines = new ArrayList<>();
        if (map.get("dialogue") instanceof List<?> list) {
            for (Object entry : list) {
                String line = text(entry);
                if (line != null && !line.isBlank() && lines.size() < MAX_DIALOGUE_LINES) {
                    lines.add(trim(line, MAX_LINE_LENGTH));
                }
            }
        }
        Double yaw = number(map.get("yaw"));
        Double pitch = number(map.get("pitch"));
        String value = text(map.get("skin-value"));
        boolean hasSkin = value != null && !value.isBlank();
        String quest = text(map.get("quest"));
        return new NpcData(id, name,
                hasSkin ? value : null,
                hasSkin ? text(map.get("skin-signature")) : null,
                hasSkin ? text(map.get("skin-source")) : null,
                quest == null || quest.isBlank() ? null : quest,
                lines, world, x, y, z,
                yaw == null ? 0f : yaw.floatValue(), pitch == null ? 0f : pitch.floatValue(),
                !(map.get("visible") instanceof Boolean visible) || visible);
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static @Nullable Double number(@Nullable Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        return null;
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
