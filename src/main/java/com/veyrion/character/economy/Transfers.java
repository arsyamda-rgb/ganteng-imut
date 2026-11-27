package com.veyrion.character.economy;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Player-to-player payment on top of an {@link EconomyBridge}. Pure Java. Order: check funds, withdraw from the
 * sender, deposit to the receiver; if the deposit fails the sender is refunded. Nothing is ever created from
 * nothing: money is only deposited after the same amount was withdrawn.
 */
public final class Transfers {

    public enum Outcome {
        OK,
        INSUFFICIENT,
        WITHDRAW_FAILED,
        DEPOSIT_FAILED_REFUNDED,
        /** Sender was charged and could not be refunded. Logged at SEVERE for manual repair. */
        DEPOSIT_FAILED_REFUND_FAILED,
        ERROR
    }

    private Transfers() {
    }

    public static Outcome transfer(EconomyBridge bridge, UUID from, UUID to, double amount, Consumer<String> severe) {
        if (!Double.isFinite(amount) || amount <= 0 || from.equals(to)) {
            return Outcome.ERROR;
        }
        try {
            if (!bridge.has(from, amount)) {
                return Outcome.INSUFFICIENT;
            }
            if (!bridge.withdraw(from, amount).success()) {
                return Outcome.WITHDRAW_FAILED;
            }
        } catch (RuntimeException | LinkageError e) {
            severe.accept("Payment aborted while withdrawing " + amount + " from " + from + ": " + e);
            return Outcome.ERROR;
        }

        boolean deposited;
        try {
            deposited = bridge.deposit(to, amount).success();
        } catch (RuntimeException | LinkageError e) {
            severe.accept("Deposit of " + amount + " to " + to + " threw: " + e);
            deposited = false;
        }
        if (deposited) {
            return Outcome.OK;
        }

        try {
            if (bridge.deposit(from, amount).success()) {
                return Outcome.DEPOSIT_FAILED_REFUNDED;
            }
        } catch (RuntimeException | LinkageError e) {
            severe.accept("Refund of " + amount + " to " + from + " threw: " + e);
        }
        severe.accept("MANUAL REPAIR NEEDED: " + amount + " was withdrawn from " + from + " but could neither be "
                + "deposited to " + to + " nor refunded.");
        return Outcome.DEPOSIT_FAILED_REFUND_FAILED;
    }
}
