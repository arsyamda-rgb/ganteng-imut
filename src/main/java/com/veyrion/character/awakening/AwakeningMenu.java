package com.veyrion.character.awakening;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** The confirmation menu: Begin Awakening / Cancel. A plain 27-slot chest inventory, no resource pack. */
public final class AwakeningMenu {

    public static final int SLOT_BEGIN = 11;
    public static final int SLOT_INFO = 13;
    public static final int SLOT_CANCEL = 15;
    private static final int SIZE = 27;
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Marks inventories created by this plugin so clicks can be recognised safely. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private AwakeningMenu() {}

    public static void open(Player player, AwakeningSettings.Gui gui) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE, MINI.deserialize(gui.title()));
        holder.inventory = inventory;

        ItemStack filler = item(Material.PURPLE_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(SLOT_BEGIN, item(gui.begin().material(), gui.begin().name(), gui.begin().lore()));
        inventory.setItem(SLOT_INFO, item(gui.info().material(), gui.info().name(), gui.info().lore()));
        inventory.setItem(SLOT_CANCEL, item(gui.cancel().material(), gui.cancel().name(), gui.cancel().lore()));
        player.openInventory(inventory);
    }

    public static boolean isMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder(false) instanceof Holder;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
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
