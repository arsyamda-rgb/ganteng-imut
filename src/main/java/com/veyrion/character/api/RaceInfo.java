package com.veyrion.character.api;

/** A race from races.yml. {@code displayName} is plain text. Disabled races cannot be rolled but can be set. */
public record RaceInfo(String id, String displayName, String description, boolean enabled) {}
