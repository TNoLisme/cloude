package com.bank.simulator.audit.application;

import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.pagination.CursorCodec;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ListAuditEventsServiceTest {
    @Test
    void filtersEventsAndEmitsCursorOnlyWhenAnotherPageExists() {
        UUID actorId = UUID.randomUUID();
        var repository = mock(AuditEventRepository.class);
        var service = new ListAuditEventsService(repository, new CursorCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
        var query = new AuditQuery(1, null, "TRANSFER_COMPLETED", actorId,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
        var first = event(Instant.parse("2026-01-01T12:00:00Z"), UUID.randomUUID());
        var extra = event(Instant.parse("2026-01-01T11:00:00Z"), UUID.randomUUID());
        when(repository.find(query, null, 2)).thenReturn(List.of(first, extra));

        var page = service.list(query, new AuthenticatedActor(actorId, Set.of("AUDITOR")));

        assertThat(page.items()).containsExactly(first);
        assertThat(page.nextCursor()).isNotBlank();
    }

    @Test
    void deniesActorOutsideAuditRoles() {
        var service = new ListAuditEventsService(mock(AuditEventRepository.class),
                new CursorCodec(new com.fasterxml.jackson.databind.ObjectMapper()));

        assertThatThrownBy(() -> service.list(new AuditQuery(20, null, null, null, null, null),
                new AuthenticatedActor(UUID.randomUUID(), Set.of("CUSTOMER"))))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private AuditEventView event(Instant at, UUID id) {
        return new AuditEventView(id, "TRANSFER_COMPLETED", UUID.randomUUID(), "TRANSFER", UUID.randomUUID(),
                "SUCCESS", at, UUID.randomUUID(), "Internal transfer completed.");
    }
}
