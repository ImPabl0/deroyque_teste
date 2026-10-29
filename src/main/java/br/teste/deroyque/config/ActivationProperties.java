package br.teste.deroyque.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param maxAttempts  total de chamadas ao provedor antes de marcar FAILED
 * @param initialBackoff espera base antes da 2ª tentativa; dobra a cada falha
 * @param maxBackoff   teto da espera calculada (o Retry-After do provedor é respeitado mesmo acima dele)
 * @param batchSize    solicitações processadas por ciclo do dispatcher
 * @param stuckAfter   tempo em PROCESSING após o qual a solicitação é considerada abandonada
 *                     (instância caiu no meio da chamada); deve ser maior que o timeout de leitura
 */
@ConfigurationProperties("activation")
public record ActivationProperties(
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("2s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("20") int batchSize,
        @DefaultValue("2m") Duration stuckAfter) {
}
