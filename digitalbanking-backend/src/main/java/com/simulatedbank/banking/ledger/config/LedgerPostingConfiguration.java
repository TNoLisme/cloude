package com.simulatedbank.banking.ledger.config;

import com.simulatedbank.banking.ledger.adapter.out.persistence.LedgerEntryJpaRepository;
import com.simulatedbank.banking.ledger.adapter.out.persistence.LedgerEntryPersistenceAdapter;
import com.simulatedbank.banking.ledger.api.LedgerPosting;
import com.simulatedbank.banking.ledger.application.LedgerPostingService;
import org.springframework.context.annotation.Bean;

/** Import explicitly from the application composition root when enabling the posting path. */
public class LedgerPostingConfiguration {
    @Bean
    LedgerPosting ledgerPosting(LedgerEntryJpaRepository repository) {
        return new LedgerPostingService(new LedgerEntryPersistenceAdapter(repository));
    }
}
