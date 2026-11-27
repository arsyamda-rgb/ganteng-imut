package com.veyrion.character.awakening;

import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

/** A MiniMessage title + subtitle with timings in ticks. Blank title and subtitle show nothing. */
public record TitleSpec(String title, String subtitle, int fadeIn, int stay, int fadeOut) {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public boolean empty() {
        return title.isBlank() && subtitle.isBlank();
    }

    public void show(Player player, TagResolver... resolvers) {
        if (empty()) {
            return;
        }
        Component main = title.isBlank() ? Component.empty() : MINI.deserialize(title, resolvers);
        Component sub = subtitle.isBlank() ? Component.empty() : MINI.deserialize(subtitle, resolvers);
        player.showTitle(Title.title(main, sub, Title.Times.times(
                Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
    }
}
