package br.teste.deroyque.utils;

import org.slf4j.MDC;

/** Identificador de correlação propagado da requisição HTTP até a chamada ao provedor. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    /** Valor da requisição atual, ou {@code null} fora de uma requisição. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
