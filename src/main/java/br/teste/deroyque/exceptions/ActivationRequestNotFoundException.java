package br.teste.deroyque.exceptions;

import java.util.UUID;

public class ActivationRequestNotFoundException extends RuntimeException {

    public ActivationRequestNotFoundException(UUID id) {
        super("Activation request not found: " + id);
    }
}
