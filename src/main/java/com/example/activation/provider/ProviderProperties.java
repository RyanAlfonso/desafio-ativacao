package com.example.activation.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param apiKey credencial do provedor; vem de variável de ambiente / cofre de segredos, nunca do repositório
 */
@ConfigurationProperties("app.provider")
public record ProviderProperties(
        String baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout) {

    @Override
    public String toString() {
        return "ProviderProperties[baseUrl=" + baseUrl + ", apiKey=***, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
