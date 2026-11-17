package com.simulatedbank.banking.identity.application;

import java.util.Set;

/** Port đọc authority snapshot (roles + permissions + privilege) cho `Session` (design/05 §3.1). */
public interface AuthorityReader {

    Set<String> loadAuthorities(long userId);
}
