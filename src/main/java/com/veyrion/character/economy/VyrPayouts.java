package com.veyrion.character.economy;

import com.veyrion.character.core.VeyrionCharacterPlugin;
import com.veyrion.character.economy.PendingVyrRepository.Pending;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Pays quest VYR rewards through the economy provider exactly once.
 *
 * <ol>
 *   <li>The ticket is written in the same transaction that flips the quest to COMPLETED (only one call can do that).</li>
 *   <li>Before depositing, the ticket is claimed in the database (PENDING -> PAYING); only one caller can win that.</li>
 *   <li>After the provider accepts the deposit the ticket is deleted. If the provider refuses, it goes back to PENDING
 *       and is retried on join and every few minutes. If the server dies between deposit and delete the ticket stays
 *       PAYING and is never paid again.</li>
 * </ol>
 * All methods are called on the main thread.
 */
public final class VyrPayouts {

    private final VeyrionCharacterPlugin plugin;
    private final EconomyService economy;
    private final PendingVyrRepository repository;
    private final Set<Long> inFlight = new HashSet<>();

    public VyrPayouts(VeyrionCharacterPlugin plugin, EconomyService economy, PendingVyrRepository repository) {
        this.plugin = plugin;
        this.economy = economy;
        this.repository = repository;
    }

    /** Pays one ticket now if possible; otherwise leaves it PENDING and tells the player. */
    public void pay(Pending pending) {
        if (!inFlight.add(pending.id())) {
            return;
        }
        if (!economy.isActive()) {
            inFlight.remove(pending.id());
            notifyDeferred(pending);
            return;
        }
        repository.claim(pending.id(), System.currentTimeMillis()).whenComplete((owned, error) -> onMain(() -> {
            if (error != null) {
                inFlight.remove(pending.id());
                plugin.getLogger().log(Level.SEVERE, "Could not claim VYR reward #" + pending.id(), error);
                notifyDeferred(pending);
            } else if (!Boolean.TRUE.equals(owned)) {
                inFlight.remove(pending.id()); // someone else is paying it
            } else {
                deliver(pending);
            }
        }));
    }

    private void deliver(Pending pending) {
        // Written BEFORE the deposit: a "Paying" line without a later "Paid" line in latest.log means the outcome of
        // that ticket is unknown (see /veconomy pending).
        plugin.getLogger().info("Paying " + Money.plain(pending.amount()) + " VYR to " + pending.uuid()
                + " for quest " + pending.questId() + " (reward #" + pending.id() + ").");
        EconomyBridge.Result result = economy.deposit(pending.uuid(), pending.amount());
        if (result.success()) {
            plugin.getLogger().info("Paid " + Money.plain(pending.amount()) + " VYR to " + pending.uuid()
                    + " for quest " + pending.questId() + " (reward #" + pending.id() + ").");
            repository.delete(pending.id()).whenComplete((ignored, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "VYR reward #" + pending.id() + " was paid but its ticket "
                            + "could not be deleted. It stays PAYING and will NOT be paid again.", error);
                }
                onMain(() -> inFlight.remove(pending.id()));
            });
            Player player = Bukkit.getPlayer(pending.uuid());
            if (player != null) {
                plugin.messages().send(player, "quest.reward-vyr",
                        Placeholder.unparsed("amount", economy.format(pending.amount())));
            }
        } else {
            plugin.getLogger().warning("The economy provider refused VYR reward #" + pending.id() + " ("
                    + result.error() + "). It will be retried.");
            repository.release(pending.id()).whenComplete((ignored, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "VYR reward #" + pending.id()
                            + " could not be released back to PENDING; review it manually.", error);
                }
                onMain(() -> inFlight.remove(pending.id()));
            });
            notifyDeferred(pending);
        }
    }

    /** Retries PENDING tickets of online players ({@code only} = one player, null = everyone online). */
    public void flush(@Nullable UUID only) {
        if (!economy.isActive()) {
            return;
        }
        repository.listPending().whenComplete((tickets, error) -> onMain(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not read pending VYR rewards", error);
                return;
            }
            for (Pending ticket : tickets) {
                if ((only == null || only.equals(ticket.uuid())) && Bukkit.getPlayer(ticket.uuid()) != null) {
                    pay(ticket);
                }
            }
        }));
    }

    /** Lists tickets left in PAYING by a crash (called once at startup, when nothing can be in flight). */
    public void reportAmbiguous() {
        repository.listPaying().whenComplete((rows, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not read pending VYR tickets", error);
                return;
            }
            if (rows.isEmpty()) {
                return;
            }
            plugin.getLogger().warning(rows.size() + " VYR reward ticket(s) are in state PAYING: the server stopped "
                    + "while paying them. They are NOT paid again automatically. Check the provider's transaction "
                    + "log or the player's balance, then use /veconomy pending (paid <id> | retry <id> confirm).");
            for (PendingVyrRepository.Review row : rows) {
                plugin.getLogger().warning("  ticket #" + row.id() + " player=" + row.uuid() + " quest="
                        + row.questId() + " amount=" + Money.plain(row.amount()) + " claimed_at=" + row.claimedAt());
            }
        });
    }

    /** Result of a manual reconciliation step. */
    public enum Resolution { DONE, IN_FLIGHT, NOT_REVIEWABLE, ERROR }

    /** PAYING tickets that need a human decision, plus how many PENDING tickets wait for their player. Main thread. */
    public void review(java.util.function.BiConsumer<java.util.List<PendingVyrRepository.Review>, Integer> callback) {
        repository.listPaying().thenCombine(repository.countPending(), (rows, pending) -> {
            onMain(() -> {
                // A ticket this server is paying right now is PAYING too, but it is not a leftover.
                var stale = rows.stream().filter(row -> !inFlight.contains(row.id())).toList();
                callback.accept(stale, pending);
            });
            return null;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Could not read pending VYR tickets", error);
            onMain(() -> callback.accept(null, 0));
            return null;
        });
    }

    /** The admin verified the player WAS paid: delete the PAYING ticket without paying. */
    public void settle(long id, String admin, java.util.function.Consumer<Resolution> callback) {
        resolve(id, callback, repository.settle(id), "settled (marked as paid, nothing paid)", admin);
    }

    /** The admin verified the player was NOT paid: make the ticket PENDING so it is paid once on the next retry. */
    public void reopen(long id, String admin, java.util.function.Consumer<Resolution> callback) {
        resolve(id, callback, repository.release(id), "reopened (will be paid again)", admin);
    }

    private void resolve(long id, java.util.function.Consumer<Resolution> callback,
                         java.util.concurrent.CompletableFuture<Boolean> action, String what, String admin) {
        if (inFlight.contains(id)) {
            callback.accept(Resolution.IN_FLIGHT);
            return;
        }
        // Reserve the id while the database change runs so a flush cannot pick it up half-way.
        inFlight.add(id);
        action.whenComplete((changed, error) -> onMain(() -> {
            inFlight.remove(id);
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not change VYR ticket #" + id, error);
                callback.accept(Resolution.ERROR);
            } else if (!Boolean.TRUE.equals(changed)) {
                callback.accept(Resolution.NOT_REVIEWABLE); // gone, or not in state PAYING
            } else {
                plugin.getLogger().warning("VYR ticket #" + id + " " + what + " by " + admin + ".");
                callback.accept(Resolution.DONE);
                if (what.startsWith("reopened")) {
                    flush(null);
                }
            }
        }));
    }

    private void notifyDeferred(Pending pending) {
        Player player = Bukkit.getPlayer(pending.uuid());
        if (player != null) {
            plugin.messages().send(player, "quest.reward-vyr-pending",
                    Placeholder.unparsed("amount", economy.format(pending.amount())));
        }
    }

    private void onMain(Runnable task) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }
}
