package com.flashseats.flashseats.hold.exception;

public class InsufficientStockException extends HoldException {
    public InsufficientStockException() {
        super("Requested tickets are no longer available.");
    }
}
