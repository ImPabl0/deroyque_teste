package br.teste.deroyque.dtos;

import br.teste.deroyque.models.ActivationRequest;

/**
 * @param created {@code false} quando a solicitação já existia (retry idempotente do sistema de origem)
 */
public record CreationResult(ActivationRequest request, boolean created) {

    public static CreationResult created(ActivationRequest request) {
        return new CreationResult(request, true);
    }

    public static CreationResult existing(ActivationRequest request) {
        return new CreationResult(request, false);
    }
}
