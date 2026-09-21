package com.flashseats.bot.exception;

public class RecaptchaTransportException extends RuntimeException {

    public RecaptchaTransportException(String message, Throwable cause) {
        super(message, cause);
    }

    public RecaptchaTransportException(String message) {
        super(message);
    }
}