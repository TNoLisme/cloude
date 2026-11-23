-- BE-ID-IMP-10 approved verification extension. Registration/trust provisioning remains external.
-- Nullable material deliberately leaves legacy devices/challenges unable to prove FIDO ownership.
ALTER TABLE banking.device
    ADD COLUMN fido_public_key_cose TEXT NULL,
    ADD COLUMN fido_user_handle VARCHAR(86) NULL,
    ADD COLUMN fido_sign_count BIGINT NOT NULL DEFAULT 0
        CONSTRAINT ck_device_fido_sign_count CHECK (fido_sign_count BETWEEN 0 AND 4294967295);

ALTER TABLE banking.auth_challenge
    ADD COLUMN fido_nonce VARCHAR(86) NULL;
