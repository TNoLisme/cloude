package com.simulatedbank.banking.identity.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * State machine của `auth_challenge.status` (04 §4.2.5, CHECK `ck_auth_challenge_status`):
 * `CREATED → SENT → VERIFIED`; sai vượt ngưỡng → `LOCKED`; hết hạn → `EXPIRED`.
 *
 * <p>Không có state `CONSUMED` (OD-ID-06, BE-ID-IMP-01 Gate A): consume được thể hiện qua
 * cột `consumed_at` (status giữ `VERIFIED`) — copy SQL `status='CONSUMED'` từ design/04 sẽ
 * vi phạm CHECK constraint. Các nguồn có thẩm quyền: `docs-common/04` > `design/*` > docs-backend.</p>
 *
 * <p>Transition hợp lệ (nguồn `04` §4.2.5 business rules + `design/02` §5.1):</p>
 * <ul>
 *   <li>CREATED → SENT (dispatch delivery, BE-ID-IMP-06) | VERIFIED | LOCKED | EXPIRED | CANCELLED</li>
 *   <li>SENT → VERIFIED | LOCKED | EXPIRED | CANCELLED</li>
 *   <li>VERIFIED → EXPIRED (chỉ khi chưa consume — `consumed_at IS NULL`); consume là cột
 *       `consumed_at`, không đổi status</li>
 *   <li>LOCKED, EXPIRED, CANCELLED: terminal — không transition nào khác</li>
 * </ul>
 *
 * <p>CANCELLED được giữ trong enum theo CHECK constraint nhưng CHƯA có luồng định nghĩa
 * (OD-ID-06: invalidate policy MOD-OI-07 còn mở, thuộc BE-ID-IMP-06); không code path nào set.</p>
 */
public enum ChallengeState {
    CREATED,
    SENT,
    VERIFIED,
    LOCKED,
    EXPIRED,
    CANCELLED;

    /** Transition hợp lệ; `null` key hoặc giá trị vắng mặt = transition bị cấm. */
    private static final Map<ChallengeState, Set<ChallengeState>> ALLOWED = Map.of(
            CREATED, Set.of(SENT, VERIFIED, LOCKED, EXPIRED, CANCELLED),
            SENT, Set.of(VERIFIED, LOCKED, EXPIRED, CANCELLED),
            VERIFIED, Set.of(EXPIRED),
            LOCKED, Set.of(),
            EXPIRED, Set.of(),
            CANCELLED, Set.of());

    /** Parse an toàn giá trị DB; giá trị lạ → empty (caller fail-closed, không tự suy diễn). */
    public static Optional<ChallengeState> parse(String dbValue) {
        if (dbValue == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ChallengeState.valueOf(dbValue.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Transition validation tường minh (không phụ thuộc check ở controller); SQL conditional là backstop. */
    public boolean canTransitionTo(ChallengeState target) {
        return ALLOWED.get(this).contains(target);
    }

    /** Challenge đã issue và còn active (cho phép verify); VERIFIED/terminal loại trừ. */
    public boolean isIssuedActive() {
        return this == CREATED || this == SENT;
    }
}
