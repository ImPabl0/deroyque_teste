package br.teste.deroyque.responses;

import java.net.URI;

/** Valores de {@code type} dos Problem Details (RFC 9457) retornados pela API. */
public final class ProblemTypes {

    private static final String BASE = "https://api.deroyque.example/problems/";

    public static final URI VALIDATION_ERROR = URI.create(BASE + "validation-error");
    public static final URI NOT_FOUND = URI.create(BASE + "not-found");
    public static final URI DUPLICATE_REQUEST = URI.create(BASE + "duplicate-request");
    public static final URI CONCURRENT_UPDATE = URI.create(BASE + "concurrent-update");
    public static final URI UNAUTHORIZED = URI.create(BASE + "unauthorized");
    public static final URI FORBIDDEN = URI.create(BASE + "forbidden");
    public static final URI PAYLOAD_TOO_LARGE = URI.create(BASE + "payload-too-large");
    public static final URI INTEGRATION_ERROR = URI.create(BASE + "integration-error");
    public static final URI INTERNAL_ERROR = URI.create(BASE + "internal-error");

    private ProblemTypes() {
    }
}
