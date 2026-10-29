CREATE TABLE activation_request (
    id              UUID                     NOT NULL,
    external_id     VARCHAR(64)              NOT NULL,
    customer_id     VARCHAR(64)              NOT NULL,
    product_code    VARCHAR(64)              NOT NULL,
    client_id       VARCHAR(128)             NOT NULL,
    correlation_id  VARCHAR(64)              NOT NULL,
    status          VARCHAR(16)              NOT NULL,
    attempts        INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    last_error      VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL,
    CONSTRAINT pk_activation_request PRIMARY KEY (id),
    -- Garantia final contra duplicidade: vale com requisicoes concorrentes e com varias instancias.
    CONSTRAINT uk_activation_request_external_id UNIQUE (external_id),
    CONSTRAINT ck_activation_request_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Listagem paginada: sempre filtrada pelo consumidor (isolamento), opcionalmente por status, ordenada por data.
CREATE INDEX ix_activation_request_client_status_created
    ON activation_request (client_id, status, created_at);

-- Filtro por cliente final dentro do consumidor.
CREATE INDEX ix_activation_request_client_customer_created
    ON activation_request (client_id, customer_id, created_at);

-- Dispatcher: busca PENDING vencidas e PROCESSING presas.
CREATE INDEX ix_activation_request_status_next_attempt
    ON activation_request (status, next_attempt_at);
