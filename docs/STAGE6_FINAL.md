# Stage 6 final - changes

1. Quest scoreboard (vanilla sidebar): `quest/QuestScoreboard`, `QuestBoardView`; hooks in `QuestService`
   (accept, progress, complete, abandon, reset, load after relog, unload); `quest-scoreboard` in config.yml;
   texts in lang `scoreboard.*`; optional `area:` in quest files; TAB: mode AUTO/NATIVE/TAB + placeholders.
2. Golden crystal: `AncientCore` (BlockDisplay body/heart/tips/shards, Interaction click box that follows it);
   `AwakeningListener` opens the menu on a click on the crystal; the floor is clickable only when no crystal exists.
3. Cinematic: `CameraRig`, `CameraMath`, `CameraSnapshot`; `RitualSession`/`AwakeningService` integration; skip via
   Shift, chat button, `/race skip`; recovery on end/quit/death/error/disable and after a crash (pending file);
   `/raceadmin altar preview [race]` plays it without rolling anything.
4. Subtitle: text display in front of the camera (only the ritual player sees it), 65% black background, fades;
   `SubtitleTimeline`.
5. Indonesian dialogue per race in awakening.yml `cinematic.dialogue`.
Not touched: the roll, the chances, the database, the race tables.
