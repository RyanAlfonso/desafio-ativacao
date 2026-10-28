package com.example.activation.api;

import com.example.activation.application.NewActivationRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo do POST. Todos os campos: obrigatórios, até 64 caracteres, apenas letras, dígitos,
 * '-', '_' e '.'.
 */
public record CreateActivationRequest(
        @NotBlank(message = REQUIRED) @Size(max = 64, message = MAX_64) @Pattern(regexp = IDENTIFIER, message = INVALID_CHARS)
        String externalId,

        @NotBlank(message = REQUIRED) @Size(max = 64, message = MAX_64) @Pattern(regexp = IDENTIFIER, message = INVALID_CHARS)
        String customerId,

        @NotBlank(message = REQUIRED) @Size(max = 64, message = MAX_64) @Pattern(regexp = IDENTIFIER, message = INVALID_CHARS)
        String productCode) {

    static final String IDENTIFIER = "^[A-Za-z0-9._-]*$";
    static final String REQUIRED = "é obrigatório";
    static final String MAX_64 = "deve ter no máximo 64 caracteres";
    static final String INVALID_CHARS = "deve conter apenas letras, dígitos, '.', '_' ou '-'";

    NewActivationRequest toCommand() {
        return new NewActivationRequest(externalId, customerId, productCode);
    }
}
