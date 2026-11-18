package com.simulatedbank.banking.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import com.simulatedbank.banking.customer.domain.CustomerStatus;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit contract of the public {@link CustomerQuery} implementation (BE-CUS-IMP-04). */
class CustomerQueryServiceTest {

    private InMemoryIdentityReader reader;
    private CustomerQueryService service;

    @BeforeEach
    void setUp() {
        reader = new InMemoryIdentityReader();
        service = new CustomerQueryService(reader);
    }

    @Test
    void resolvesValidUserIdToApprovedMinimalSnapshot() {
        CustomerIdentityReader.Identity seeded = reader.seed(101, CustomerStatus.ACTIVE);

        Optional<CustomerQuery.CustomerIdentity> resolved = service.findByUserId(101);

        assertThat(resolved).get().satisfies(snapshot -> {
            assertThat(snapshot.customerId()).isEqualTo(seeded.customerId());
            assertThat(snapshot.userId()).isEqualTo(101);
            assertThat(snapshot.status()).isEqualTo(CustomerQuery.Status.ACTIVE);
        });
        assertThat(reader.lookups).isEqualTo(1);
    }

    @Test
    void unknownUserReturnsEmptyAfterSingleAuthoritativeLookup() {
        assertThat(service.findByUserId(404)).isEmpty();
        assertThat(reader.lookups).isEqualTo(1);
    }

    @Test
    void invalidUserIdIsRejectedWithoutTouchingPersistence() {
        assertValidation(() -> service.findByUserId(0));
        assertValidation(() -> service.findByUserId(-7));
        assertThat(reader.lookups).isZero();
    }

    @Test
    void inactiveCustomerStaysPresentAndDistinctFromMissing() {
        CustomerIdentityReader.Identity seeded = reader.seed(202, CustomerStatus.INACTIVE);

        Optional<CustomerQuery.CustomerIdentity> resolved = service.findByUserId(202);

        assertThat(resolved).get().satisfies(snapshot -> {
            assertThat(snapshot.customerId()).isEqualTo(seeded.customerId());
            assertThat(snapshot.userId()).isEqualTo(202);
            assertThat(snapshot.status()).isEqualTo(CustomerQuery.Status.INACTIVE);
        });
        assertThat(service.findByUserId(203)).isEmpty();
    }

    @Test
    void resolvesCustomerIdToTheSameMinimalSnapshot() {
        CustomerIdentityReader.Identity seeded = reader.seed(404, CustomerStatus.ACTIVE);

        Optional<CustomerQuery.CustomerIdentity> resolved = service.findByCustomerId(seeded.customerId());

        assertThat(resolved).contains(new CustomerQuery.CustomerIdentity(
                seeded.customerId(), 404, CustomerQuery.Status.ACTIVE));
        assertThat(reader.lookups).isEqualTo(1);
    }

    @Test
    void unknownOrInvalidCustomerIdNeverProducesASnapshot() {
        assertThat(service.findByCustomerId(999)).isEmpty();
        assertThat(reader.lookups).isEqualTo(1);
        assertValidation(() -> service.findByCustomerId(0));
        assertValidation(() -> service.findByCustomerId(-3));
        assertThat(reader.lookups).isEqualTo(1);
    }

    @Test
    void exposesExactlyTheApprovedFieldsAndStaysImmutable() {
        Class<CustomerQuery.CustomerIdentity> dto = CustomerQuery.CustomerIdentity.class;

        assertThat(dto.isRecord()).isTrue();
        assertThat(Modifier.isFinal(dto.getModifiers())).isTrue();
        assertThat(Arrays.stream(dto.getRecordComponents())
                .map(component -> component.getName() + ":" + component.getType().getSimpleName())
                .toList())
                .containsExactly("customerId:long", "userId:long", "status:Status");
        assertThat(dto.getDeclaredFields())
                .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers())).isTrue());
    }

    @Test
    void mappingNeverCarriesPiiFields() {
        CustomerIdentityReader.Identity seeded = reader.seed(303, CustomerStatus.ACTIVE);
        CustomerQuery.CustomerIdentity snapshot = service.findByUserId(303).orElseThrow();

        assertThat(Arrays.stream(CustomerQuery.CustomerIdentity.class.getRecordComponents())
                .map(component -> component.getName() + ":" + component.getType().getSimpleName())
                .toList())
                .doesNotContain("fullName:String", "phone:String", "email:String",
                        "nationalId:String", "kycStatus:String", "cifNumber:String");
        assertThat(snapshot.toString())
                .isEqualTo("CustomerIdentity[customerId=%d, userId=%d, status=ACTIVE]"
                        .formatted(seeded.customerId(), seeded.userId()))
                .doesNotContain("fullName", "phone", "email", "ciphertext");
    }

    @Test
    void persistenceFailurePropagatesPerConventions() {
        RuntimeException technical = new RuntimeException("synthetic persistence outage");
        reader.failure = technical;

        assertThatThrownBy(() -> service.findByUserId(101)).isSameAs(technical);
        assertThatThrownBy(() -> service.findByCustomerId(1)).isSameAs(technical);
    }

    private static void assertValidation(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BankingBusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private static final class InMemoryIdentityReader implements CustomerIdentityReader {
        private final Map<Long, Identity> rowsByUserId = new LinkedHashMap<>();
        private final Map<Long, Identity> rowsByCustomerId = new LinkedHashMap<>();
        private long nextCustomerId = 1;
        private int lookups;
        private RuntimeException failure;

        @Override
        public Optional<Identity> findByUserId(long userId) {
            lookups++;
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(rowsByUserId.get(userId));
        }

        @Override
        public Optional<Identity> findById(long customerId) {
            lookups++;
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(rowsByCustomerId.get(customerId));
        }

        Identity seed(long userId, CustomerStatus status) {
            Identity identity = new Identity(nextCustomerId++, userId, status);
            rowsByUserId.put(userId, identity);
            rowsByCustomerId.put(identity.customerId(), identity);
            return identity;
        }
    }
}
