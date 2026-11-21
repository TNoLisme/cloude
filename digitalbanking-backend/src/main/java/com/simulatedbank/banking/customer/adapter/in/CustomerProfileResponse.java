package com.simulatedbank.banking.customer.adapter.in;

import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.time.Instant;

/**
 * Wire DTO of `CustomerProfile` (`07` §7.2.9, openapi `#/components/schemas/CustomerProfile`) dùng
 * chung bởi `API-CUS-01` `GET /api/v1/customers/me` (owner: fullName đầy đủ, 07 §8.2.1) và
 * `API-ADM-01` `GET /api/v1/admin/customers/{customerId}` (staff: fullName masked theo
 * DEC-CUS-005, 07 §8.2.4). Không tự thêm/bớt field:
 * `customerId`/`userId` là decimal string (`07` §8.2.1), `phoneMasked`/`emailMasked` nullable
 * JSON null khi vắng, `status` chỉ `ACTIVE`/`INACTIVE`, `createdAt`/`updatedAt` Instant UTC.
 * Không có correlationId/KYC/CIF/envelope — những field đó không thuộc hợp đồng này.
 * Chỉ chứa dữ liệu display-safe: liên hệ luôn mask, không password/secret (`FR-039`).
 */
record CustomerProfileResponse(
        String customerId,
        String userId,
        String fullName,
        String phoneMasked,
        String emailMasked,
        CustomerStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
