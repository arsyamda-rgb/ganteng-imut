Standalone math check for RaceChanceCalculator (no Maven, no Paper needed). From the project root:

  mkdir -p /tmp/out
  javac -d /tmp/out src/main/java/com/veyrion/character/race/RaceChanceCalculator.java dev-checks/RaceChanceCheck.java
  java -cp /tmp/out com.veyrion.character.race.RaceChanceCheck

Expected last line: ALL CHECKS PASSED. This folder is outside src/, so Maven ignores it.

PlaceholderCheck (Stage 5: CharacterCache, PopulationCache, PlaceholderResolver; no Paper/PlaceholderAPI needed).
The classes use org.jetbrains.annotations.Nullable, so put that jar (or any stub of it) on the classpath:

  javac -cp <annotations.jar> -d /tmp/out src/main/java/com/veyrion/character/character/CharacterData.java \
    src/main/java/com/veyrion/character/race/PopulationSnapshot.java src/main/java/com/veyrion/character/race/PopulationReport.java \
    src/main/java/com/veyrion/character/integration/{CharacterCache,PopulationCache,PlaceholderResolver}.java dev-checks/PlaceholderCheck.java
  java -cp /tmp/out:<annotations.jar> com.veyrion.character.integration.PlaceholderCheck

Expected last line: ALL CHECKS PASSED.

EconomyCheck (Veyra economy: Money parsing/formatting, Transfers with a fake provider, balance placeholders).
Needs the same annotations stub/jar as PlaceholderCheck plus the Bukkit-free classes below:

  javac -cp <annotations.jar> -d /tmp/out src/main/java/com/veyrion/character/character/CharacterData.java \
    src/main/java/com/veyrion/character/race/PopulationSnapshot.java src/main/java/com/veyrion/character/race/PopulationReport.java \
    src/main/java/com/veyrion/character/integration/{CharacterCache,PopulationCache,PlaceholderResolver}.java \
    src/main/java/com/veyrion/character/economy/{Money,EconomyBridge,Transfers}.java dev-checks/EconomyCheck.java
  java -cp /tmp/out:<annotations.jar> com.veyrion.character.economy.EconomyCheck

Expected last line: ALL CHECKS PASSED.

--- Revision checks (stubs live in dev-checks/stubs; they are check-only and never shipped) ---

BalTopCheck, ConfigCheck, EconomyCheck, PlaceholderCheck compile together against the stubs:

  javac -d /tmp/out $(find dev-checks/stubs -name '*.java') src/main/java/com/veyrion/character/economy/*.java \
    src/main/java/com/veyrion/character/integration/{PlaceholderResolver,CharacterCache,PopulationCache}.java \
    src/main/java/com/veyrion/character/config/{PluginSettings,ConfigManager}.java src/main/java/com/veyrion/character/character/*.java \
    src/main/java/com/veyrion/character/database/*.java src/main/java/com/veyrion/character/race/{PopulationReport,PopulationSnapshot}.java \
    src/main/java/com/veyrion/character/command/{Pay,Balance,BalTop,EconomyAdmin}Command.java \
    src/main/java/com/veyrion/character/listener/EconomyListener.java \
    src/main/java/com/veyrion/character/quest/{QuestRepository,QuestStatus}.java \
    dev-checks/{BalTopCheck,ConfigCheck,EconomyCheck,PlaceholderCheck}.java
  java -cp /tmp/out com.veyrion.character.economy.BalTopCheck     # expected log noise: warnings from error scenarios
  java -cp /tmp/out com.veyrion.character.config.ConfigCheck

Each prints ALL CHECKS PASSED. BalTopCheck logs warnings/stack traces on purpose (corrupt account, failing source).

migration_check.py: SQLite v2 -> v3 upgrade and pending_vyr SQL, using Python's sqlite3 (not the Java JDBC driver):

  python3 dev-checks/migration_check.py <schema-v2 DatabaseManager.java> src/main/java/com/veyrion/character/database/DatabaseManager.java src/main/java/com/veyrion/character/economy/PendingVyrRepository.java

AltarProgressCheck (Race Altar pillars: pure animation clock, no Bukkit/Paper needed):

  mkdir -p /tmp/out
  javac -d /tmp/out src/main/java/com/veyrion/character/awakening/AltarProgress.java dev-checks/AltarProgressCheck.java
  java -cp /tmp/out com.veyrion.character.awakening.AltarProgressCheck

Expected last line: ALL CHECKS PASSED. It covers rise, smoothstep, reversal without jump, proportional return time and keyframes.
The Bukkit part (StonePillar, AltarVisual) cannot be checked without Paper; test it on a server.

PillarMathCheck (Race Altar maths: hidden shaft segments = the floating-plate fix, easing, looping, shockwave ring):

  mkdir -p /tmp/out
  javac -d /tmp/out src/main/java/com/veyrion/character/awakening/PillarMath.java dev-checks/PillarMathCheck.java
  java -cp /tmp/out com.veyrion.character.awakening.PillarMathCheck

Expected last line: ALL CHECKS PASSED. The display classes (StonePillar, AltarPlatform, AltarStage, AltarVisual) need Paper
and cannot be checked here; test them on a server (see the report of the altar revision).

--- Stage 6 checks ---
CoreMathCheck (Ancient Core geometry):
  javac -d /tmp/out src/main/java/com/veyrion/character/awakening/{PillarMath,CoreMath}.java dev-checks/CoreMathCheck.java
  java -cp /tmp/out com.veyrion.character.awakening.CoreMathCheck
NpcDataCheck (NPC storage format):
  javac -d /tmp/out dev-checks/stubs/org/jetbrains/annotations/*.java src/main/java/com/veyrion/character/npc/NpcData.java dev-checks/NpcDataCheck.java
  java -cp /tmp/out com.veyrion.character.npc.NpcDataCheck
RankCheck (stale-rank fix, pure):
  javac -d /tmp/out dev-checks/stubs/org/jetbrains/annotations/*.java src/main/java/com/veyrion/character/character/{CharacterData,CharacterRepository,ExpResult,RankTable}.java src/main/java/com/veyrion/character/database/*.java dev-checks/RankCheck.java
  java -cp /tmp/out com.veyrion.character.character.RankCheck
rank_reconcile_check.py (SQL of reconcileRanks against SQLite):
  python3 dev-checks/rank_reconcile_check.py src/main/java/com/veyrion/character/character/CharacterRepository.java
All print ALL CHECKS PASSED. Paper/ProtocolLib code (altar, NPC, API impl) cannot be built or run here: test on a server.

--- Stage 6 final (quest scoreboard, golden crystal, cinematic, subtitle) ---
CameraMathCheck (camera shots, yaw/pitch, subtitle fade, camera snapshot; pure):
  javac -d /tmp/out src/main/java/com/veyrion/character/awakening/{CameraMath,SubtitleTimeline,CameraSnapshot}.java dev-checks/CameraMathCheck.java
  java -cp /tmp/out com.veyrion.character.awakening.CameraMathCheck
QuestBoardCheck (which quest/objective/progress the sidebar shows; needs the Bukkit stubs for two enums):
  javac -proc:none -d /tmp/out $(find dev-checks/compile-stubs/paper-api -name '*.java') src/main/java/com/veyrion/character/util/Durations.java \
    src/main/java/com/veyrion/character/economy/Money.java src/main/java/com/veyrion/character/quest/{Quest,QuestObjective,ObjectiveType,QuestType,PlayerQuests,QuestBoardView,QuestRegistry}.java dev-checks/QuestBoardCheck.java
  java -cp /tmp/out com.veyrion.character.quest.QuestBoardCheck
PlaceholderCheck now also covers %veyrioncharacter_quest_*%.

compile-stubs/: hand-written, minimal stand-ins for the Paper/Adventure/JOML types that the awakening, quest, config, race,
character and database packages use. build-with-stubs.sh compiles those packages against them (bash required). This only proves
that the code is syntactically and type-consistent with the stubs; the stubs are written from memory and are NOT the real Paper
API, so it cannot prove that a method exists in Paper 1.21.1. Real verification = `mvn package` and a test server.
Never shipped (outside src/).

--- Wardrobe additions (has/withdraw, previews) ---
WithdrawCheck (enough / not enough money, invalid amounts, provider failures, 64 threads on one balance):
  mkdir -p /tmp/out && bash -c 'javac -d /tmp/out $(find dev-checks/stubs -name "*.java") src/main/java/com/veyrion/character/api/MoneyResult.java \
    src/main/java/com/veyrion/character/economy/*.java src/main/java/com/veyrion/character/config/{PluginSettings,ConfigManager}.java \
    src/main/java/com/veyrion/character/character/*.java src/main/java/com/veyrion/character/database/*.java \
    src/main/java/com/veyrion/character/race/{PopulationReport,PopulationSnapshot}.java \
    src/main/java/com/veyrion/character/integration/{PlaceholderResolver,CharacterCache,PopulationCache}.java \
    src/main/java/com/veyrion/character/command/{Pay,Balance,BalTop,EconomyAdmin}Command.java src/main/java/com/veyrion/character/listener/EconomyListener.java \
    src/main/java/com/veyrion/character/quest/{QuestRepository,QuestStatus}.java dev-checks/WithdrawCheck.java'
  java -cp /tmp/out com.veyrion.character.economy.WithdrawCheck      # expected: ALL CHECKS PASSED (the WARNING stack trace is on purpose)
  Removing the `synchronized (withdrawLock)` in EconomyService makes it fail (42 of 64 threads "succeed").
PreviewCheck (PlayerDoublePreview lifecycle with a fake PlayerDouble; uses compile-stubs):
  javac -proc:none -d /tmp/o3 $(find dev-checks/compile-stubs/paper-api -name '*.java') src/main/java/com/veyrion/character/awakening/{PlayerDouble,PlayerDoubleFactory}.java \
    src/main/java/com/veyrion/character/api/{CharacterPreview,CharacterPreviewFactory}.java src/main/java/com/veyrion/character/api/impl/PlayerDoublePreview.java dev-checks/PreviewCheck.java
  java -cp /tmp/o3 com.veyrion.character.api.impl.PreviewCheck
Not checkable here: ProtocolPlayerDouble.look (ENTITY_LOOK packet) and ApiImpl.previews(): test on a server with ProtocolLib.
