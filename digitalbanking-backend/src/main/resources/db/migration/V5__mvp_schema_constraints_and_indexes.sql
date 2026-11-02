CREATE UNIQUE INDEX uq_transfers_otp_challenge
    ON transfers (otp_challenge_id)
    WHERE otp_challenge_id IS NOT NULL;

CREATE INDEX ix_transfers_source_completed_at
    ON transfers (source_account_id, completed_at DESC, id DESC)
    WHERE status = 'COMPLETED';

ALTER TABLE idempotency_records
    ADD CONSTRAINT ck_idempotency_completed_response
        CHECK (
            response_status IS NULL
            OR (response_body IS NOT NULL AND resource_id IS NOT NULL)
        );
