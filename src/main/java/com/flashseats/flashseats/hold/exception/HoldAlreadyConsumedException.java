package com.flashseats.flashseats.hold.exception;

public class HoldAlreadyConsumedException extends HoldException {
    public HoldAlreadyConsumedException() {
        super("Hold token has already been consumed.");
    }
}
