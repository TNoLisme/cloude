package com.simulatedbank.banking.admin.adapter.out;

import com.simulatedbank.banking.admin.domain.ApprovalRequest;
import org.mapstruct.Mapper;

@Mapper
interface ApprovalPersistenceMapper {
    ApprovalRequest toSnapshot(ApprovalEntity entity);
}
