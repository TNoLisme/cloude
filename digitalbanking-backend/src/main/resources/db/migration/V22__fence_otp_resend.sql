-- Identity owns the resend generation and cooldown. Existing challenges start at generation zero.
ALTER TABLE banking.auth_challenge
    ADD COLUMN resend_count INTEGER NOT NULL DEFAULT 0 CHECK (resend_count >= 0),
    ADD COLUMN last_resend_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_auth_challenge_resend_time
        CHECK ((resend_count = 0) = (last_resend_at IS NULL));

-- Consent for optional transfer SMS is independent of mandatory authentication delivery.
ALTER TABLE banking.customer ADD COLUMN transfer_sms_allowed BOOLEAN NOT NULL DEFAULT FALSE;

-- Fail validation on duplicate historic outcomes instead of silently deleting records.
CREATE UNIQUE INDEX uq_notification_transfer_outcome
    ON banking.notification (transfer_id, customer_id, notification_type, channel)
    WHERE transfer_id IS NOT NULL AND notification_type = 'TRANSFER_RESULT';
