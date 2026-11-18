package com.simulatedbank.banking.account.adapter.out;

import com.simulatedbank.banking.account.domain.AccountStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access nội bộ cho Account (BE-ACC-03). Chỉ các derived query đã duyệt — không
 * {@code @Query} tự do, không truy vấn toàn bảng, không xoá/sửa hàng loạt.
 *
 * <p>Access path khớp index canonical của {@code V8}: {@code account_pkey},
 * {@code uq_account_no_hash}, {@code ix_account_customer}. Không expose repository ra ngoài module
 * (ArchUnit + package-private).</p>
 */
@ConditionalOnProperty(name = "app.account.enabled", havingValue = "true", matchIfMissing = true)
interface AccountJpaRepository extends JpaRepository<AccountEntity, Long> {

    Optional<AccountEntity> findByAccountNumberHash(String accountNumberHash);

    List<AccountEntity> findByCustomerIdOrderByAccountIdAsc(long customerId);

    List<AccountEntity> findByCustomerIdAndStatusOrderByAccountIdAsc(long customerId, AccountStatus status);
}
