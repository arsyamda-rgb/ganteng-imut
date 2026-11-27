package com.veyrion.character.api;

/** Outcome of {@link VeyrionCharacterAPI#withdraw}. Only {@link #SUCCESS} means money was taken. */
public enum MoneyResult {
    /** The amount was taken from the balance. */
    SUCCESS,
    /** The balance is lower than the amount. Nothing was taken. */
    INSUFFICIENT_FUNDS,
    /** The amount is not a finite number above zero (after rounding to two decimals), or is too large. */
    INVALID_AMOUNT,
    /** No usable economy right now (disabled, no Vault, no provider). Nothing was taken. */
    UNAVAILABLE,
    /** The economy provider failed or refused. Nothing was taken as far as this plugin can tell. */
    ERROR;

    public boolean success() {
        return this == SUCCESS;
    }
}
