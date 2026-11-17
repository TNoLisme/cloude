package com.simulatedbank.banking.audit.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Internal event representation; snapshots are copied before validation. */
public record AuditEvent(String action, String actorType, Long actorUserId, String actorRole,
                         String entityType, String entityId,
                         Map<String, Object> beforeState, Map<String, Object> afterState,
                         BigDecimal amount, String currency, String correlationId,
                         String requestId, String outcome, Map<String, Object> clientInfoMasked) {
    public AuditEvent {
        beforeState = copyMap(beforeState);
        afterState = copyMap(afterState);
        clientInfoMasked = copyMap(clientInfoMasked);
    }

    private static Map<String, Object> copyMap(Map<String, ?> source) {
        if (source == null) return null;
        return copyObjectMap(source, new IdentityHashMap<>(), 0, new int[]{0});
    }

    private static Map<String, Object> copyObjectMap(Map<?, ?> map, IdentityHashMap<Object, Boolean> path, int depth, int[] count) {
        if (depth > 16 || ++count[0] > 4096) {
            throw new IllegalArgumentException("Snapshot nesting or element count exceeds the audit limit");
        }
        if (path.put(map, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic snapshot is not allowed");
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException("Snapshot keys must be strings");
            }
            copy.put(stringKey, copyValue(AuditSnapshotValues.normalize(stringKey, item), path, depth + 1, count));
        });
        path.remove(map);
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value, IdentityHashMap<Object, Boolean> path, int depth, int[] count) {
        if (depth > 16 || ++count[0] > 4096) {
            throw new IllegalArgumentException("Snapshot nesting or element count exceeds the audit limit");
        }
        if (value instanceof String string && string.length() > 32 * 1024) {
            throw new IllegalArgumentException("Snapshot scalar exceeds 32 KiB");
        }
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return copyObjectMap(map, path, depth, count);
        }
        if (value instanceof List<?> list) {
            if (path.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic snapshot is not allowed");
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(copyValue(item, path, depth + 1, count)));
            path.remove(value);
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Unsupported snapshot value type");
    }
}
