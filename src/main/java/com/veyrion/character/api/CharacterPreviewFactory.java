package com.veyrion.character.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Makes {@link CharacterPreview}s. Get it from {@link VeyrionCharacterAPI#previews()}. Main thread only. */
public interface CharacterPreviewFactory {

    /**
     * @param subject       the player to copy; the skin is the one they currently wear
     * @param at            where the preview stands; yaw and pitch are its facing
     * @param showEquipment also copy the armor and held items of the subject
     * @return a preview nobody can see yet: call {@link CharacterPreview#show}
     */
    CharacterPreview create(Player subject, Location at, boolean showEquipment);
}
