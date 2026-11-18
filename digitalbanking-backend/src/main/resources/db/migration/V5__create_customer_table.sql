-- BE-CUS-IMP-01: Customer profile table per docs-common/04 §4.2.2.
-- V5 follows Identity migrations V2-V4; customer data is retained by RESTRICT foreign keys/status.
CREATE TABLE banking.customer (
    customer_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    full_name_enc BYTEA      NOT NULL,
    phone_enc    BYTEA       NULL,
    email_enc    BYTEA       NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_customer_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_user_id UNIQUE (user_id),
    CONSTRAINT fk_customer_user FOREIGN KEY (user_id)
        REFERENCES banking.app_user (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX ix_customer_status ON banking.customer (status);
