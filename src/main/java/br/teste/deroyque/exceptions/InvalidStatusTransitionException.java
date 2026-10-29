package br.teste.deroyque.exceptions;

import br.teste.deroyque.models.enums.ActivationStatus;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(ActivationStatus from, ActivationStatus to) {
        super("Invalid status transition: " + from + " -> " + to);
    }
}
