package com.simulatedbank.banking.limit.application.port;

import com.simulatedbank.banking.limit.api.TransferLimits.Reservation;
import com.simulatedbank.banking.limit.api.TransferLimits.ReservationState;
import com.simulatedbank.banking.limit.api.TransferLimits.ScopeEffect;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LimitTransferStore {
    record StoredReservation(Reservation reservation, Instant gateAt, ReservationState state,
                             Instant terminalAt, LocalDate terminalDate, Integer effectCount) { }

    BigDecimal pendingAmount(long customerId);

    void insertReservation(Reservation reservation, Instant gateAt);

    Optional<StoredReservation> findReservation(long transferId);

    boolean finish(long transferId, ReservationState state, Instant terminalAt, LocalDate date, int effectCount);

    void insertEffect(long transferId, ScopeEffect effect);

    List<ScopeEffect> findEffects(long transferId);
}
