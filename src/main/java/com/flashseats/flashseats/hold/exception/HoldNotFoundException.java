package com.flashseats.flashseats.hold.exception;

public class HoldNotFoundException extends HoldException {
    
    public HoldNotFoundException(String holdToken) {
        super("The requested hold token was not found: " + holdToken);
    }
    
}
