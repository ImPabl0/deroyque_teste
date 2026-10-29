package br.teste.deroyque.services;

import java.util.UUID;

/** Porta para o provedor externo de ativação. */
public interface ProviderGateway {

    ProviderResult activate(ActivationCall call);

    /**
     * @param requestId usado também como chave de idempotência no provedor
     */
    record ActivationCall(UUID requestId, String customerId, String productCode, String correlationId) {
    }
}
