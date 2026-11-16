-- BE-ID-IMP-11: approved role/privilege commands serialize against the target user's version.
-- The version is internal approval payload data, not a public role endpoint request field.
ALTER TABLE banking.app_user
    ADD COLUMN role_assignment_version BIGINT NOT NULL DEFAULT 0
        CONSTRAINT ck_app_user_role_assignment_version CHECK (role_assignment_version >= 0);
