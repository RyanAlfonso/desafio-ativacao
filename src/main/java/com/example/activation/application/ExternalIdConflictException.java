package com.example.activation.application;

public class ExternalIdConflictException extends RuntimeException {

    public ExternalIdConflictException(String externalId) {
        super("O externalId " + externalId + " já foi utilizado em outra solicitação com dados diferentes");
    }
}
