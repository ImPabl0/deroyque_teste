package br.teste.deroyque.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo do POST. Os três campos são obrigatórios, com até 64 caracteres entre letras, dígitos,
 * ponto, hífen e sublinhado (identificadores, não texto livre).
 */
public record CreateActivationRequestBody(
        @NotBlank(message = REQUIRED) @Size(max = 64, message = MAX_SIZE) @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String externalId,

        @NotBlank(message = REQUIRED) @Size(max = 64, message = MAX_SIZE) @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String customerId,

        @NotBlank(message = REQUIRED) @Size(max = 64, message = MAX_SIZE) @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE)
        String productCode) {

    static final String REQUIRED = "é obrigatório";
    static final String MAX_SIZE = "deve ter no máximo {max} caracteres";
    static final String IDENTIFIER = "^[A-Za-z0-9._-]*$";
    static final String IDENTIFIER_MESSAGE = "deve conter apenas letras, dígitos, '.', '-' ou '_'";
}
