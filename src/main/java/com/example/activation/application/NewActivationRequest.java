package com.example.activation.application;

public record NewActivationRequest(String externalId, String customerId, String productCode) {
}
