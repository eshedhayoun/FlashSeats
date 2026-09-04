package com.flashseats.flashseats.hold.exception;

public class HoldException extends RuntimeException{
    public HoldException(String message) {
        super(message);
    }
    public HoldException(String message, Throwable cause) {
        super(message, cause); 
    }
}
