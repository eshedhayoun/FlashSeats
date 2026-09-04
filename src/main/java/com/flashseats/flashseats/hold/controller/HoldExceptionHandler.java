package com.flashseats.flashseats.hold.controller;
import java.util.Map;
import com.flashseats.flashseats.hold.exception.HoldAlreadyConsumedException;
import com.flashseats.flashseats.hold.exception.HoldExpiredException;
import com.flashseats.flashseats.hold.exception.HoldNotFoundException;
import com.flashseats.flashseats.hold.exception.InsufficientStockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;


@RestControllerAdvice
public class HoldExceptionHandler {
    @ExceptionHandler(HoldNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(HoldNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }
    @ExceptionHandler(HoldExpiredException.class)
    public ResponseEntity<Map<String, String>> handleExpired(HoldExpiredException exception) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("message", exception.getMessage()));
    }
    @ExceptionHandler(HoldAlreadyConsumedException.class)
    public ResponseEntity<Map<String, String>> handleAlreadyConsumed(HoldAlreadyConsumedException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", exception.getMessage()));
    }
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientStock(InsufficientStockException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", exception.getMessage()));
    }
}
