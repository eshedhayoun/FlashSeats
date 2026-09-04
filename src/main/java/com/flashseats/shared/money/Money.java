package com.flashseats.shared.money;

/**
 * An amount in minor units with its currency, for DTOs and facade contracts.
 *
 * <p>Entities keep a primitive {@code long} column plus a {@code currency} column — a wrapper type
 * in the persistence layer would buy nothing. This type exists so a money value crossing a module
 * boundary can never lose its currency on the way.
 */
public record Money(long cents, String currency) {

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + cents);
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code: " + currency);
        }
    }

    public static Money of(long cents, String currency) {
        return new Money(cents, currency);
    }

    public Money times(int quantity) {
        return new Money(cents * quantity, currency);
    }
}
