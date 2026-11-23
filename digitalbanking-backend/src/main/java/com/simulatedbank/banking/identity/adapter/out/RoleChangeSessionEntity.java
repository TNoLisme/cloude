package com.simulatedbank.banking.identity.adapter.out;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Used only for the atomic JPQL revocation of the target user's active sessions. */
@Entity
@Table(name = "session", schema = "banking")
public class RoleChangeSessionEntity {
    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "logged_out_at")
    private Instant loggedOutAt;

    protected RoleChangeSessionEntity() {
    }
}
