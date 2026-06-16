package com.systemdesign.bitly.exception;

/**
 * Thrown when a client requests a custom alias that is already taken.
 * The controller maps this to HTTP 409 Conflict.
 */
public class AliasAlreadyExistsException extends RuntimeException {

    private final String alias;

    public AliasAlreadyExistsException(String alias) {
        super("Custom alias '" + alias + "' is already in use");
        this.alias = alias;
    }

    public String getAlias() {
        return alias;
    }
}
