package com.example.activation.api;

import org.springframework.security.oauth2.jwt.Jwt;

/** Identifica o sistema consumidor a partir do token (client credentials). */
final class ClientIdentity {

    private ClientIdentity() {
    }

    static String of(Jwt jwt) {
        String clientId = jwt.getClaimAsString("client_id");
        if (clientId == null) {
            clientId = jwt.getClaimAsString("azp");
        }
        return clientId != null ? clientId : jwt.getSubject();
    }
}
