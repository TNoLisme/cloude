package com.simulatedbank.transfercandidate.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Test-only stand-in used to verify another provider's managed state is not detached. */
@Entity
@Table(schema = "banking", name = "account")
public class TestProviderAccountEntity {
    @Id @Column(name = "account_id") private Long id;
    @Column(name = "test_marker", length = 100) private String marker;
    protected TestProviderAccountEntity() { }
    public void setMarker(String marker) { this.marker = marker; }
}
