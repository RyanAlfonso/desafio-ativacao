package com.example.activation.application;

import java.util.UUID;

public class RequestNotFoundException extends RuntimeException {

    public RequestNotFoundException(UUID id) {
        super("Solicitação " + id + " não encontrada");
    }
}
