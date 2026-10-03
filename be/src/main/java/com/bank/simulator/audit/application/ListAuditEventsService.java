package com.bank.simulator.audit.application;

import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.shared.error.ApiException;
import com.bank.simulator.shared.pagination.CursorCodec;
import com.bank.simulator.shared.pagination.CursorPosition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ListAuditEventsService {
    private final AuditEventRepository repository;
    private final CursorCodec cursors;

    public ListAuditEventsService(AuditEventRepository repository, CursorCodec cursors) {
        this.repository = repository;
        this.cursors = cursors;
    }

    public AuditPage list(AuditQuery query, AuthenticatedActor actor) {
        if (!actor.hasRole("AUDITOR") && !actor.hasRole("ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Request is forbidden.");
        }
        CursorPosition cursor;
        try {
            cursor = query.cursor() == null ? null : cursors.decode(query.cursor());
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Audit event cursor is invalid.");
        }
        List<AuditEventView> rows = repository.find(query, cursor, query.limit() + 1);
        boolean hasNext = rows.size() > query.limit();
        List<AuditEventView> items = rows.stream().limit(query.limit()).toList();
        String next = hasNext ? cursors.encode(items.getLast().cursorPosition()) : null;
        return new AuditPage(items, next);
    }
}
