CREATE TABLE activation_request (
    id              UUID         PRIMARY KEY,
    external_id     VARCHAR(64)  NOT NULL,
    client_id       VARCHAR(128) NOT NULL,
    customer_id     VARCHAR(64)  NOT NULL,
    product_code    VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      VARCHAR(255),
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL,
    CONSTRAINT uk_activation_request_external_id UNIQUE (external_id),
    CONSTRAINT ck_activation_request_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Listagem: sempre filtrada pelo consumidor (isolamento), ordenada por criação.
CREATE INDEX ix_activation_request_client_status
    ON activation_request (client_id, status, created_at DESC);
CREATE INDEX ix_activation_request_client_customer
    ON activation_request (client_id, customer_id, created_at DESC);

-- Dispatcher: só as linhas ainda não finalizadas, por ordem de vencimento.
CREATE INDEX ix_activation_request_due
    ON activation_request (next_attempt_at)
    WHERE status IN ('PENDING', 'PROCESSING');
