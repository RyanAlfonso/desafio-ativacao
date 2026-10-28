package com.example.activation.domain;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(RequestStatus from, RequestStatus to) {
        super("Transição de status inválida: " + from + " -> " + to);
    }
}
