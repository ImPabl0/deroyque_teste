package br.teste.deroyque.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * @param apiKey credencial do provedor; vem de variável de ambiente/serviço de segredos e nunca é logada
 */
@ConfigurationProperties("provider")
public record ProviderProperties(
        URI baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        String apiKey) {

    @Override
    public String toString() {
        return "ProviderProperties[baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", apiKey=***]";
    }
}
