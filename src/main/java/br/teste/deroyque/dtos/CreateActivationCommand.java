package br.teste.deroyque.dtos;

public record CreateActivationCommand(
        String externalId,
        String customerId,
        String productCode,
        String clientId,
        String correlationId) {
}
