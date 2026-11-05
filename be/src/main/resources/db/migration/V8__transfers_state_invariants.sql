-- State machine invariants (ERD §2/§6): AWAITING_OTP requires otp_challenge_id;
-- expires_at is only valid while AWAITING_OTP. Forward-only addition; V1–V7 untouched.
ALTER TABLE transfers
    ADD CONSTRAINT transfers_awaiting_requires_otp
        CHECK (status <> 'AWAITING_OTP' OR otp_challenge_id IS NOT NULL),
    ADD CONSTRAINT transfers_expiry_only_awaiting
        CHECK (expires_at IS NULL OR status = 'AWAITING_OTP');
