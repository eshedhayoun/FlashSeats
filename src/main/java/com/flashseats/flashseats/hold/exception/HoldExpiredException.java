package com.flashseats.flashseats.hold.exception;

public class HoldExpiredException extends HoldException {
    public HoldExpiredException(){
        super("Reservation has expired. Please re-enter the queue.");
    }
}
