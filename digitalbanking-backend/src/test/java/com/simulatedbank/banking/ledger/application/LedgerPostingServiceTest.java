package com.simulatedbank.banking.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simulatedbank.banking.ledger.api.dto.PostingCommand;
import com.simulatedbank.banking.ledger.api.dto.PostingRef;
import com.simulatedbank.banking.ledger.application.port.LedgerEntryStore;
import com.simulatedbank.banking.ledger.domain.EntryDraft;
import com.simulatedbank.banking.ledger.domain.EntrySide;
import com.simulatedbank.banking.ledger.domain.InvalidPostingCommandException;
import com.simulatedbank.banking.ledger.domain.LedgerInvariantException;
import com.simulatedbank.banking.ledger.domain.PostedEntry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** LED-POST-02/08/10/11 coordination: validate before write, append/flush/read/verify order, propagation. */
class LedgerPostingServiceTest {

    private static final long TRANSFER = 15;
    private static final long SOURCE = 20;
    private static final long DESTINATION = 23;
    private static final BigDecimal AMOUNT = new BigDecimal("125.50");
    private static final BigDecimal SOURCE_AFTER = BigDecimal.ZERO;
    private static final BigDecimal DEST_AFTER = new BigDecimal("325.50");
    private static final String CORRELATION = "transfer:15";
    private static final PostingCommand COMMAND = new PostingCommand(TRANSFER, SOURCE, DESTINATION, AMOUNT, "VND",
            SOURCE_AFTER, DEST_AFTER, CORRELATION);

    private final LedgerEntryStore store = mock(LedgerEntryStore.class);
    private final LedgerPostingService service = new LedgerPostingService(store);

    private static PostedEntry entry(long id, long account, int seq, EntrySide side, BigDecimal balance) {
        return new PostedEntry(id, TRANSFER, account, seq, side, AMOUNT, "VND", balance, CORRELATION);
    }

    private void stubHappyPath() {
        when(store.append(any(EntryDraft.class))).thenReturn(31L, 34L);
        when(store.findAllByTransferIdOrdered(TRANSFER)).thenReturn(List.of(
                entry(31, SOURCE, 1, EntrySide.DEBIT, SOURCE_AFTER),
                entry(34, DESTINATION, 2, EntrySide.CREDIT, DEST_AFTER)));
    }

    @Test
    void rejectsNullCommandWithoutTouchingStore() {
        assertThatThrownBy(() -> service.postDoubleEntry(null)).isInstanceOf(InvalidPostingCommandException.class);
        verifyNoInteractions(store);
    }

    @Test
    void rejectsInvalidCommandBeforeAnyAppend() {
        PostingCommand invalid = new PostingCommand(TRANSFER, SOURCE, SOURCE, AMOUNT, "VND",
                SOURCE_AFTER, DEST_AFTER, CORRELATION);
        assertThatThrownBy(() -> service.postDoubleEntry(invalid)).isInstanceOf(InvalidPostingCommandException.class);
        verify(store, never()).append(any());
        verify(store, never()).flush();
        verify(store, never()).findAllByTransferIdOrdered(anyLong());
    }

    @Test
    void appendsDebitThenCreditFlushesReadsAndReturnsGeneratedIds() {
        stubHappyPath();
        PostingRef ref = service.postDoubleEntry(COMMAND);
        assertThat(ref.debitEntryId()).isEqualTo(31);
        assertThat(ref.creditEntryId()).isEqualTo(34);

        InOrder order = inOrder(store);
        order.verify(store).append(new EntryDraft(TRANSFER, SOURCE, 1, EntrySide.DEBIT,
                new BigDecimal("125.50"), "VND", new BigDecimal("0.00"), CORRELATION));
        order.verify(store).append(new EntryDraft(TRANSFER, DESTINATION, 2, EntrySide.CREDIT,
                new BigDecimal("125.50"), "VND", new BigDecimal("325.50"), CORRELATION));
        order.verify(store).flush();
        order.verify(store).findAllByTransferIdOrdered(TRANSFER);
    }

    @Test
    void propagatesFirstAppendFailure() {
        when(store.append(any(EntryDraft.class))).thenThrow(new IllegalStateException("debit insert failed"));
        assertThatThrownBy(() -> service.postDoubleEntry(COMMAND)).isInstanceOf(IllegalStateException.class);
        verify(store, never()).flush();
    }

    @Test
    void propagatesSecondAppendFailure() {
        when(store.append(any(EntryDraft.class))).thenReturn(31L).thenThrow(new IllegalStateException("credit insert failed"));
        assertThatThrownBy(() -> service.postDoubleEntry(COMMAND)).isInstanceOf(IllegalStateException.class);
        verify(store, never()).findAllByTransferIdOrdered(anyLong());
    }

    @Test
    void propagatesFlushFailure() {
        when(store.append(any(EntryDraft.class))).thenReturn(31L, 34L);
        org.mockito.Mockito.doThrow(new IllegalStateException("flush failed")).when(store).flush();
        assertThatThrownBy(() -> service.postDoubleEntry(COMMAND)).isInstanceOf(IllegalStateException.class);
        verify(store, never()).findAllByTransferIdOrdered(anyLong());
    }

    @Test
    void propagatesReadBackFailure() {
        when(store.append(any(EntryDraft.class))).thenReturn(31L, 34L);
        when(store.findAllByTransferIdOrdered(TRANSFER)).thenThrow(new IllegalStateException("read failed"));
        assertThatThrownBy(() -> service.postDoubleEntry(COMMAND)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsCorruptedReadBackPair() {
        when(store.append(any(EntryDraft.class))).thenReturn(31L, 34L);
        when(store.findAllByTransferIdOrdered(TRANSFER)).thenReturn(List.of(
                entry(31, SOURCE, 1, EntrySide.DEBIT, SOURCE_AFTER),
                entry(34, DESTINATION, 2, EntrySide.CREDIT, BigDecimal.ZERO)));
        assertThatThrownBy(() -> service.postDoubleEntry(COMMAND)).isInstanceOf(LedgerInvariantException.class);
    }
}