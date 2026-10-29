package br.teste.deroyque.services;

import java.time.Duration;

/** Resultado de uma chamada ao provedor, já classificado quanto à possibilidade de nova tentativa. */
public sealed interface ProviderResult {

    record Success() implements ProviderResult {
    }

    /** O provedor recusou a solicitação; repetir não muda o resultado. */
    record PermanentFailure(String reason) implements ProviderResult {
    }

    /**
     * Falha que pode passar (5xx, 429, timeout, conexão).
     *
     * @param retryAfter prazo pedido pelo provedor via {@code Retry-After}; {@code null} se não informado
     */
    record TransientFailure(String reason, Duration retryAfter) implements ProviderResult {
    }
}
