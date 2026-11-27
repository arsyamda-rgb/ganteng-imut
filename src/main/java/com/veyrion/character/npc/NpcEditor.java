package com.veyrion.character.npc;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.quest.Quest;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

/**
 * The admin editor opened with Shift + Right Click on an NPC: a 27-slot chest GUI (name, skin, quest, dialogue,
 * location, visibility, delete). Text is typed in chat (type {@code cancel} to abort). Every change goes through
 * {@link NpcManager#update}, which saves it immediately, so nothing needs a "save" button. The permission is
 * checked again on every click and every typed answer. Main thread, except the chat event which hops to it.
 */
public final class NpcEditor implements Listener {

    static final int SLOT_NAME = 10;
    static final int SLOT_SKIN = 11;
    static final int SLOT_QUEST = 12;
    static final int SLOT_DIALOGUE = 13;
    static final int SLOT_LOCATION = 14;
    static final int SLOT_VISIBLE = 15;
    static final int SLOT_DELETE = 16;
    private static final int SIZE = 27;
    private static final long PROMPT_MS = 60_000;
    private static final long CONFIRM_MS = 5_000;
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private enum Kind { NAME, SKIN_NAME, DIALOGUE_ADD }

    private record Prompt(String npcId, Kind kind, long expiresAt) {}

    private record Armed(String npcId, long expiresAt) {}

    /** Marks the editor inventory so clicks are recognised safely. */
    static final class Holder implements InventoryHolder {
        final String npcId;
        Inventory inventory;

        Holder(String npcId) {
            this.npcId = npcId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final VeyrionCharacterPlugin plugin;
    private final NpcManager npcs;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, Armed> deleteArmed = new ConcurrentHashMap<>();

    public NpcEditor(VeyrionCharacterPlugin plugin, NpcManager npcs) {
        this.plugin = plugin;
        this.npcs = npcs;
    }

    // --------------------------------------------------------------------------------------------------- open

    public void open(Player player, String npcId) {
        if (!NpcManager.isAdmin(player)) {
            plugin.messages().send(player, "command.no-permission");
            return;
        }
        NpcData data = npcs.get(npcId);
        if (data == null) {
            plugin.messages().send(player, "npc.unknown", Placeholder.unparsed("id", npcId));
            return;
        }
        Holder holder = new Holder(data.id());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                MINI.deserialize("<dark_purple>NPC Editor <dark_gray>- <white>" + data.id()));
        holder.inventory = inventory;
        render(inventory, data);
        player.openInventory(inventory);
    }

    private void render(Inventory inventory, NpcData data) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(4, item(Material.PAPER, "<gold>" + data.id(),
                "<gray>Changes are saved automatically.", "<gray>World: <white>" + data.world(),
                "<gray>At: <white>" + round(data.x()) + ", " + round(data.y()) + ", " + round(data.z())));
        inventory.setItem(SLOT_NAME, item(Material.NAME_TAG, "<yellow>Name",
                "<gray>Now: <reset>" + data.displayName(), "", "<green>Click: <gray>type a new name in chat",
                "<dark_gray>MiniMessage works, e.g. <gold>&lt;gold&gt;Elder"));
        inventory.setItem(SLOT_SKIN, item(Material.PLAYER_HEAD, "<yellow>Skin",
                "<gray>Now: <white>" + (data.skinValue() == null ? "default" : data.skinSource() == null
                        ? "custom" : data.skinSource()), "",
                "<green>Left click: <gray>use your own skin", "<green>Right click: <gray>type a player name",
                "<green>Shift + Left: <gray>default skin"));
        Quest quest = data.questId() == null ? null : plugin.questService().registry().get(data.questId());
        inventory.setItem(SLOT_QUEST, item(Material.BOOK, "<yellow>Quest",
                "<gray>Now: <white>" + (data.questId() == null ? "none" : data.questId()
                        + (quest == null ? " <red>(does not exist)" : " <gray>(" + quest.name() + "<gray>)")), "",
                "<green>Left click: <gray>next quest", "<green>Right click: <gray>previous quest",
                "<green>Shift + Click: <gray>no quest"));
        List<String> dialogue = new ArrayList<>();
        dialogue.add("<gray>Lines: <white>" + data.dialogue().size() + "<gray>/" + NpcData.MAX_DIALOGUE_LINES);
        for (int i = 0; i < Math.min(6, data.dialogue().size()); i++) {
            dialogue.add("<dark_gray>" + (i + 1) + ". <reset>" + data.dialogue().get(i));
        }
        if (data.dialogue().size() > 6) {
            dialogue.add("<dark_gray>... and " + (data.dialogue().size() - 6) + " more");
        }
        dialogue.add("");
        dialogue.add("<green>Left click: <gray>add a line (chat)");
        dialogue.add("<green>Right click: <gray>remove the last line");
        dialogue.add("<green>Shift + Right: <gray>remove all lines");
        inventory.setItem(SLOT_DIALOGUE, item(Material.WRITABLE_BOOK, "<yellow>Dialogue", dialogue.toArray(new String[0])));
        inventory.setItem(SLOT_LOCATION, item(Material.COMPASS, "<yellow>Location",
                "<gray>Now: <white>" + round(data.x()) + ", " + round(data.y()) + ", " + round(data.z()), "",
                "<green>Click: <gray>move here, facing the way you face"));
        inventory.setItem(SLOT_VISIBLE, item(data.visible() ? Material.LIME_DYE : Material.GRAY_DYE,
                "<yellow>Visibility", "<gray>Now: " + (data.visible() ? "<green>visible" : "<red>hidden"),
                "<dark_gray>Hidden NPCs can still be seen and edited by admins.", "",
                "<green>Click: <gray>toggle"));
        inventory.setItem(SLOT_DELETE, item(Material.BARRIER, "<red>Delete NPC",
                "<gray>Removes the NPC for good.", "", "<red>Click twice within 5 seconds"));
    }

    // -------------------------------------------------------------------------------------------------- clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!NpcManager.isAdmin(player)) {
            closeNextTick(player);
            return;
        }
        NpcData data = npcs.get(holder.npcId);
        if (data == null) {
            closeNextTick(player);
            plugin.messages().send(player, "npc.unknown", Placeholder.unparsed("id", holder.npcId));
            return;
        }
        ClickType click = event.getClick();
        switch (event.getRawSlot()) {
            case SLOT_NAME -> ask(player, data, Kind.NAME, "npc.prompt-name");
            case SLOT_SKIN -> skin(player, data, click);
            case SLOT_QUEST -> quest(player, data, click);
            case SLOT_DIALOGUE -> dialogue(player, data, click);
            case SLOT_LOCATION -> {
                Location at = player.getLocation();
                apply(player, holder, d -> d.withLocation(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                        at.getYaw(), at.getPitch()), "npc.moved");
            }
            case SLOT_VISIBLE -> apply(player, holder, d -> d.withVisible(!d.visible()),
                    data.visible() ? "npc.hidden" : "npc.shown");
            case SLOT_DELETE -> delete(player, data);
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void skin(Player player, NpcData data, ClickType click) {
        if (click == ClickType.SHIFT_LEFT) {
            applyAndKeep(player, data, d -> d.withSkin(null, null, null), "npc.skin-cleared");
        } else if (click.isRightClick()) {
            ask(player, data, Kind.SKIN_NAME, "npc.prompt-skin");
        } else if (click.isLeftClick()) {
            ProfileProperty textures = textures(player.getPlayerProfile());
            if (textures == null) {
                plugin.messages().send(player, "npc.skin-missing", Placeholder.unparsed("player", player.getName()));
                return;
            }
            applyAndKeep(player, data, d -> d.withSkin(textures.getValue(), textures.getSignature(), player.getName()),
                    "npc.skin-set");
        }
    }

    private void quest(Player player, NpcData data, ClickType click) {
        if (click.isShiftClick()) {
            applyAndKeep(player, data, d -> d.withQuest(null), "npc.quest-cleared");
            return;
        }
        List<String> ids = plugin.questService().registry().all().stream().map(Quest::id)
                .sorted(Comparator.naturalOrder()).toList();
        if (ids.isEmpty()) {
            plugin.messages().send(player, "npc.no-quests");
            return;
        }
        // Order: (none), q1, q2 ... and round again.
        int index = data.questId() == null ? -1 : ids.indexOf(data.questId());
        int next = click.isRightClick() ? index - 1 : index + 1;
        if (next >= ids.size()) {
            next = -1;
        } else if (next < -1) {
            next = ids.size() - 1;
        }
        String chosen = next < 0 ? null : ids.get(next);
        applyAndKeep(player, data, d -> d.withQuest(chosen), chosen == null ? "npc.quest-cleared" : "npc.quest-set");
    }

    private void dialogue(Player player, NpcData data, ClickType click) {
        if (click == ClickType.SHIFT_RIGHT) {
            applyAndKeep(player, data, d -> d.withDialogue(List.of()), "npc.saved");
        } else if (click.isRightClick()) {
            applyAndKeep(player, data, NpcData::removeLastLine, "npc.saved");
        } else if (click.isLeftClick()) {
            if (data.dialogue().size() >= NpcData.MAX_DIALOGUE_LINES) {
                plugin.messages().send(player, "npc.dialogue-full", Placeholder.unparsed("max",
                        Integer.toString(NpcData.MAX_DIALOGUE_LINES)));
                return;
            }
            ask(player, data, Kind.DIALOGUE_ADD, "npc.prompt-dialogue");
        }
    }

    private void delete(Player player, NpcData data) {
        long now = System.currentTimeMillis();
        Armed armed = deleteArmed.get(player.getUniqueId());
        if (armed == null || !armed.npcId().equals(data.id()) || armed.expiresAt() < now) {
            deleteArmed.put(player.getUniqueId(), new Armed(data.id(), now + CONFIRM_MS));
            plugin.messages().send(player, "npc.delete-confirm", Placeholder.unparsed("id", data.id()));
            return;
        }
        deleteArmed.remove(player.getUniqueId());
        boolean removed = npcs.delete(data.id());
        closeNextTick(player);
        plugin.messages().send(player, removed ? "npc.deleted" : "npc.unknown", Placeholder.unparsed("id", data.id()));
    }

    /** Applies a change from a click and redraws the open editor. */
    private void apply(Player player, Holder holder, java.util.function.UnaryOperator<NpcData> change, String message) {
        if (npcs.update(holder.npcId, change)) {
            plugin.messages().send(player, message);
            NpcData fresh = npcs.get(holder.npcId);
            if (fresh != null) {
                render(holder.inventory, fresh);
            }
        } else {
            plugin.messages().send(player, "npc.storage-error");
        }
    }

    private void applyAndKeep(Player player, NpcData data, java.util.function.UnaryOperator<NpcData> change,
                              String message) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder(false) instanceof Holder holder) {
            apply(player, holder, change, message);
        } else if (npcs.update(data.id(), change)) {
            plugin.messages().send(player, message);
        }
    }

    // ------------------------------------------------------------------------------------------------ chat input

    private void ask(Player player, NpcData data, Kind kind, String messageKey) {
        prompts.put(player.getUniqueId(), new Prompt(data.id(), kind, System.currentTimeMillis() + PROMPT_MS));
        closeNextTick(player);
        plugin.messages().send(player, messageKey);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Prompt prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String text = PLAIN.serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> answer(player, prompt, text));
    }

    private void answer(Player player, Prompt prompt, String text) {
        if (!player.isOnline()) {
            return;
        }
        if (!NpcManager.isAdmin(player)) {
            plugin.messages().send(player, "command.no-permission");
            return;
        }
        if (prompt.expiresAt() < System.currentTimeMillis()) {
            plugin.messages().send(player, "npc.prompt-expired");
            return;
        }
        if (text.equalsIgnoreCase("cancel") || text.isEmpty()) {
            plugin.messages().send(player, "npc.prompt-cancelled");
            open(player, prompt.npcId());
            return;
        }
        switch (prompt.kind()) {
            case NAME -> {
                if (text.length() > NpcData.MAX_NAME_LENGTH) {
                    tooLong(player, NpcData.MAX_NAME_LENGTH);
                    return;
                }
                finish(player, prompt, d -> d.withDisplayName(text), "npc.saved");
            }
            case DIALOGUE_ADD -> {
                if (text.length() > NpcData.MAX_LINE_LENGTH) {
                    tooLong(player, NpcData.MAX_LINE_LENGTH);
                    return;
                }
                NpcData current = npcs.get(prompt.npcId());
                if (current != null && current.addLine(text) == null) {
                    plugin.messages().send(player, "npc.dialogue-full", Placeholder.unparsed("max",
                            Integer.toString(NpcData.MAX_DIALOGUE_LINES)));
                    return;
                }
                finish(player, prompt, d -> {
                    NpcData added = d.addLine(text);
                    return added == null ? d : added;
                }, "npc.saved");
            }
            case SKIN_NAME -> lookUpSkin(player, prompt, text);
        }
    }

    private void tooLong(Player player, int max) {
        plugin.messages().send(player, "npc.too-long", Placeholder.unparsed("max", Integer.toString(max)));
    }

    private void finish(Player player, Prompt prompt, java.util.function.UnaryOperator<NpcData> change, String message) {
        if (npcs.update(prompt.npcId(), change)) {
            plugin.messages().send(player, message);
            open(player, prompt.npcId());
        } else {
            plugin.messages().send(player, "npc.unknown", Placeholder.unparsed("id", prompt.npcId()));
        }
    }

    /** Skin of a player name: an online player is read directly; anyone else is looked up at Mojang, off-thread. */
    private void lookUpSkin(Player admin, Prompt prompt, String name) {
        if (!PLAYER_NAME.matcher(name).matches()) {
            plugin.messages().send(admin, "npc.skin-bad-name");
            return;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            useSkin(admin, prompt, online.getName(), textures(online.getPlayerProfile()));
            return;
        }
        plugin.messages().send(admin, "npc.skin-looking", Placeholder.unparsed("player", name));
        PlayerProfile profile = Bukkit.createProfile(name);
        profile.update().whenComplete((updated, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null || updated == null) {
                    plugin.getLogger().warning("Skin lookup for '" + name + "' failed: " + error);
                    plugin.messages().send(admin, "npc.skin-failed", Placeholder.unparsed("player", name));
                    return;
                }
                useSkin(admin, prompt, name, textures(updated));
            });
        });
    }

    private void useSkin(Player admin, Prompt prompt, String source, @Nullable ProfileProperty textures) {
        if (!admin.isOnline()) {
            return;
        }
        if (textures == null) {
            plugin.messages().send(admin, "npc.skin-missing", Placeholder.unparsed("player", source));
            return;
        }
        finish(admin, prompt, d -> d.withSkin(textures.getValue(), textures.getSignature(), source), "npc.skin-set");
    }

    private static @Nullable ProfileProperty textures(PlayerProfile profile) {
        for (ProfileProperty property : profile.getProperties()) {
            if (property.getName().equals("textures")) {
                return property;
            }
        }
        return null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId());
        deleteArmed.remove(event.getPlayer().getUniqueId());
    }

    // ----------------------------------------------------------------------------------------------- utilities

    private void closeNextTick(Player player) {
        // Never close or replace an inventory from inside its own click event.
        Bukkit.getScheduler().runTask(plugin, (Runnable) player::closeInventory);
    }

    private static String round(double value) {
        return Double.toString(Math.round(value * 10.0) / 10.0);
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MINI.deserialize(name).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(MINI.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        stack.setItemMeta(meta);
        return stack;
    }
}
