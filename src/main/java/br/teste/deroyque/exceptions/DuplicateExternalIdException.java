package br.teste.deroyque.exceptions;

/** O externalId já foi usado por uma solicitação com conteúdo diferente (ou de outro consumidor). */
public class DuplicateExternalIdException extends RuntimeException {

    private final String externalId;

    public DuplicateExternalIdException(String externalId) {
        super("externalId already used with different content: " + externalId);
        this.externalId = externalId;
    }

    public String getExternalId() {
        return externalId;
    }
}
