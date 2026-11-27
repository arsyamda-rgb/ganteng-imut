# VeyrionCharacter Developer API

Package `com.veyrion.character.api` (+ `.event`). Stable within one `API_VERSION` (currently 1). Everything else is internal.

## Setup
plugin.yml of your plugin: `depend: [VeyrionCharacter]` (or `softdepend`). Compile against the jar.

```java
VeyrionCharacterAPI api = VeyrionCharacterAPI.get();            // throws if plugin not running
Optional<VeyrionCharacterAPI> maybe = VeyrionCharacterAPI.find(); // softdepend
```
Also registered in Bukkit's `ServicesManager`.

## Threads
Futures and callbacks complete on the **main thread**. Methods without a future answer from memory and never block.

## Methods
| Area | Method |
|---|---|
| Character | `character(uuid)` (cached, instant), `loadCharacter(uuid)` (DB), `grantExp(uuid, amount)` |
| Races | `races()`, `race(id)` |
| Quests | `quests()`, `quest(id)`, `isQuestActive(player, id)`, `hasCompletedQuest(player, id)`, `acceptQuest(player, id, cb)`, `progressCustomObjective(player, key, amount)` |
| Economy | `economyAvailable()`, `balance(uuid)`, `formatMoney(amount)` |
| NPC | `npcs()`, `npc(id)` |

Rank is always derived from EXP with the live rank table (never stale).

## Events (main thread)
- `CharacterExpGainEvent` : uuid, gained, old/new EXP.
- `CharacterRankChangeEvent` : uuid, old/new rank.
- `NpcInteractEvent` (cancellable) : player, NPC id, whether it was a Shift + Right Click editor open. Cancel to stop the NPC's own reaction.

## Example
```java
@EventHandler
void onRank(CharacterRankChangeEvent e) {
    Bukkit.broadcast(Component.text(e.uuid() + " is now " + e.newRank()));
}
api.grantExp(player.getUniqueId(), 100).thenAccept(g -> player.sendMessage("+" + g.gained()));
```
See the Javadoc in `VeyrionCharacterAPI` for exact semantics.

## NPCs (built in)
Commands `/vnpc create|edit|tp|remove|list|reload` (perm `veyrion.npc.admin`). Shift + Right Click opens the editor. Data in `plugins/VeyrionCharacter/npcs.yml`. NPC bodies are fake players sent as packets through **ProtocolLib** (softdepend; Paper 1.21.1 packet layout). Quest objective `TALK_TO_NPC` uses the NPC id.

## Notes (Stage 6 final)

* `acceptQuest` goes through the normal quest service, so the quest sidebar (vanilla scoreboard) appears for that player
  exactly as when an NPC offers the quest, and follows `progressCustomObjective` live. The API itself is unchanged
  (`API_VERSION` stays 1). Null arguments now fail fast with a `NullPointerException` naming the argument.
* Quest files accept an optional `area:` (place name shown on the sidebar). `QuestInfo` is unchanged.
* For a TAB scoreboard the placeholders `%veyrioncharacter_quest_active%`, `_quest_area`, `_quest_objective`,
  `_quest_progress` ("3/5"), `_quest_current` and `_quest_amount` are available.


## Additions for VeyrionWardrobe (no new API_VERSION: purely additive)

All three are `default` methods on `VeyrionCharacterAPI`, so plugins that already use or even implement the API
keep compiling and running unchanged.

### Money: `has` and `withdraw`

```java
boolean has(UUID player, double amount);          // a hint; false if the economy is unavailable or amount invalid
MoneyResult withdraw(UUID player, double amount); // SUCCESS | INSUFFICIENT_FUNDS | INVALID_AMOUNT | UNAVAILABLE | ERROR
```

* Money is still VYR through Vault; nothing is stored by VeyrionCharacter. No storage or balance logic changed.
* The amount is rounded to two decimals. Zero, negative, NaN, infinite and over-`Money.MAX` amounts are `INVALID_AMOUNT`.
* The balance check and the take are one step under a lock: the balance never goes negative and two concurrent
  calls cannot spend the same money (the provider is called once per `SUCCESS`, never on `INSUFFICIENT_FUNDS`).
* Main thread, like every call that touches the economy.
* There is deliberately no `deposit` here; nothing needs it yet.

### Character previews: `previews()`

```java
Optional<CharacterPreviewFactory> previews();   // empty when ProtocolLib is not available
CharacterPreview p = factory.create(subjectPlayer, location, showEquipment);
p.show(viewer);              // only that viewer sees it
p.look(yaw, pitch);          // idle animation is the caller's job
p.refreshSkin();             // after the subject's skin changed
p.remove();                  // idempotent; also removes it from every client
```

A preview is packets only: not a server entity, not in `getOnlinePlayers()`, no collision, no inventory/data/attribute
access. It copies the skin the subject wears now (their live profile, so a SkinsRestorer change is included once it
has been applied). The owner of a preview must call `remove()` (on GUI close, quit, world change, disable);
VeyrionCharacter also removes leftover `vd<hex>` scoreboard teams of crashed previews when it starts.
