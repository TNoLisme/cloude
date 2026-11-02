package com.bank.simulator.shared.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public final class CursorCodec {
    private static final int VERSION = 1;
    private final ObjectMapper mapper;

    public CursorCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(CursorPosition position) {
        try {
            String json = mapper.createObjectNode()
                    .put("version", VERSION)
                    .put("createdAt", position.createdAt().toString())
                    .put("id", position.id().toString())
                    .toString();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Cursor cannot be encoded", error);
        }
    }

    public CursorPosition decode(String encoded) {
        if (encoded == null || encoded.length() > 256) throw invalid();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()
                    || root.size() != 3
                    || !root.has("version") || !root.get("version").canConvertToInt()
                    || root.get("version").intValue() != VERSION
                    || !root.has("createdAt") || !root.get("createdAt").isTextual()
                    || !root.has("id") || !root.get("id").isTextual()) throw invalid();
            return new CursorPosition(VERSION, Instant.parse(root.get("createdAt").textValue()),
                    UUID.fromString(root.get("id").textValue()));
        } catch (Exception error) {
            throw invalid();
        }
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Cursor is invalid");
    }
}
