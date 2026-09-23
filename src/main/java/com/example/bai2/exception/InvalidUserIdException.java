package com.example.bai2.exception;

public class InvalidUserIdException extends IllegalArgumentException {

    public InvalidUserIdException(String message) {
        super(message);
    }
}
