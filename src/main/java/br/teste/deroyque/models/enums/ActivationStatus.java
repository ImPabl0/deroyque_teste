package br.teste.deroyque.models.enums;

/**
 * Ciclo de vida de uma solicitação de ativação.
 *
 * <pre>
 * PENDING ──► PROCESSING ──► COMPLETED
 *    ▲             │
 *    └─────────────┴────────► FAILED
 *  (falha temporária: volta para PENDING com nova tentativa agendada)
 * </pre>
 */
public enum ActivationStatus {

    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(ActivationStatus target) {
        return switch (this) {
            case PENDING -> target == PROCESSING;
            case PROCESSING -> target == COMPLETED || target == FAILED || target == PENDING;
            case COMPLETED, FAILED -> false;
        };
    }

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED;
    }
}
