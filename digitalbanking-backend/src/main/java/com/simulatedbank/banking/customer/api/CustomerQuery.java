package com.simulatedbank.banking.customer.api;

import java.util.Optional;

/**
 * Hợp đồng public nội bộ của Customer cho Identity/Account/Admin (BE-CUS-IMP-04).
 *
 * <p>Nguồn: {@code docs-common/design/05. Modules contract.md} §6 (`customer.api.CustomerQuery`,
 * Đề xuất được owner Customer chốt bằng task này); {@code docs-backend/modules/customer/design.md}
 * §5 (BE-CUS-DOC-01); {@code docs-common/04. Database design & analysis.md} §4.2.2; {@code docs-common/03. SRS.md}
 * FR-008–011; [DEC-CUS-003](../../../../../DECISIONS.md).</p>
 *
 * <p>Customer là write-owner duy nhất của ánh xạ {@code user_id -> customer_id} (`banking.customer.user_id`
 * UNIQUE, 04 §4.2.2). Consumer gọi qua interface này, KHÔNG import entity/repository/application/domain
 * của Customer (design/05 §3.1, BE-CON-12/13/14) và KHÔNG tự suy ra customer_id từ bảng khác.</p>
 *
 * <p>Transaction semantics (design/05 §3.2/§6): đọc thuần ({@code R}) — không side effect, không
 * tạo/đổi Customer row; không có write use case nào nằm ở đây.</p>
 *
 * <p>PII: snapshot tối thiểu chỉ gồm identifier và status. Không có full name, phone, email,
 * national ID, CIF, KYC, địa chỉ, DOB, credential hay audit metadata — các field đó không thuộc
 * hợp đồng này (04 §4.2.2 không có cột KYC/CIF; CUS-OI-01 chưa được owner chốt).</p>
 */
public interface CustomerQuery {

    /**
     * Hai trạng thái đã duyệt của {@code banking.customer.status} (04 §4.2.2, CHECK constraint
     * {@code ck_customer_status}). Không tự thêm giá trị (ví dụ FROZEN/CLOSED/PENDING thuộc
     * CUS-OI-01, chưa được duyệt). Tên {@code Status} thay vì {@code CustomerStatus} để tránh
     * xung đột tên với {@code customer.domain.CustomerStatus} trong implementor.
     */
    enum Status {
        ACTIVE,
        INACTIVE
    }

    /**
     * Snapshot tối thiểu đã duyệt: identifier ánh xạ + status. Kiểu enum/status riêng của
     * {@code api} để consumer không phải import {@code customer.domain} (design/05 §3.1).
     * Record bất biến; {@code toString()} chỉ chứa ba field này, không chứa PII.
     *
     * @param customerId customer ID nội bộ (decimal string khi ra ngoài HTTP, 07 §8.2.1)
     * @param userId     Identity user identifier mà snapshot ánh xạ tới
     * @param status     trạng thái hồ sơ thực tế trong DB; eligibility nghiệp vụ do consumer quyết
     */
    record CustomerIdentity(long customerId, long userId, Status status) {
    }

    /**
     * Ánh xạ duy nhất {@code userId -> customerId} cho {@code app_user} loại CUSTOMER.
     *
     * <p>Behavior:</p>
     * <ul>
     *   <li>{@code userId <= 0} → {@code BankingBusinessException(VALIDATION_ERROR)}; không gọi persistence.</li>
     *   <li>User không tồn tại, hoặc user tồn tại nhưng chưa có Customer row → {@code Optional.empty()}
     *       (design/05 §6: "Không thấy" không coi là lỗi DB; không tự tạo Customer row khi query).</li>
     *   <li>Row {@code INACTIVE} vẫn trả snapshot đầy đủ kèm status — CustomerQuery KHÔNG hợp nhất
     *       "không tồn tại" với "không active"; chính sách eligibility của Account/Transfer là
     *       CUS-OI-06, chưa được duyệt nên provider không tự áp điều kiện thêm.</li>
     * </ul>
     *
     * @param userId định danh tin cậy của Identity (server-side, không nhận từ client)
     * @return snapshot tối thiểu hoặc empty khi không có ánh xạ
     */
    Optional<CustomerIdentity> findByUserId(long userId);

    /**
     * Ánh xạ {@code customerId -> userId/status} phục vụ tra cứu vận hành theo exact ID của staff
     * (`API-ADM-02`, 07 §8.2.5 — `404 CUSTOMER_NOT_FOUND` phải phân biệt được với customer chưa có
     * account). Bổ sung tối thiểu, cùng snapshot không PII như {@link #findByUserId(long)}.
     *
     * <p>Behavior:</p>
     * <ul>
     *   <li>{@code customerId <= 0} → {@code BankingBusinessException(VALIDATION_ERROR)}; không gọi persistence.</li>
     *   <li>Không tồn tại → {@code Optional.empty()}; {@code INACTIVE} vẫn trả snapshot kèm status thật
     *       (CUS-OI-06 chưa chốt eligibility, provider không tự áp điều kiện thêm).</li>
     *   <li>Read-only {@code R}: không tạo/đổi row, không decrypt PII, không audit nghiệp vụ.</li>
     * </ul>
     *
     * @param customerId ID nội bộ đã validate định dạng ở adapter, không phải danh tính actor
     * @return snapshot tối thiểu hoặc empty khi không tồn tại
     */
    Optional<CustomerIdentity> findByCustomerId(long customerId);
}
