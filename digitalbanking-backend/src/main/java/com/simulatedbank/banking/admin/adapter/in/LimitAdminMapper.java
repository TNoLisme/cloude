package com.simulatedbank.banking.admin.adapter.in;

import com.simulatedbank.banking.admin.adapter.in.LimitAdminDtos.ActorResponse;
import com.simulatedbank.banking.admin.adapter.in.LimitAdminDtos.ApprovalResponse;
import com.simulatedbank.banking.admin.adapter.in.LimitAdminDtos.ChangeRequest;
import com.simulatedbank.banking.admin.application.LimitApprovalBinding;
import com.simulatedbank.banking.admin.application.LimitApprovalService;
import com.simulatedbank.banking.identity.api.StaffDirectory;
import java.util.Map;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
interface LimitAdminMapper {
    @Mapping(target = "customerId", expression = "java(LimitApprovalBinding.id(request.customerId()))")
    @Mapping(target = "accountId", expression = "java(request.accountId() == null ? null : LimitApprovalBinding.id(request.accountId()))")
    @Mapping(target = "perTransaction", expression = "java(LimitApprovalBinding.money(request.perTransactionLimit()))")
    @Mapping(target = "daily", expression = "java(LimitApprovalBinding.money(request.dailyLimit()))")
    @Mapping(target = "currency", expression = "java(request.currency() == null ? \"VND\" : request.currency())")
    LimitApprovalBinding.Change toChange(ChangeRequest request);

    @Mapping(target = "approvalId", source = "approval.approvalId")
    @Mapping(target = "requestType", source = "approval.requestType")
    @Mapping(target = "targetType", source = "approval.targetType")
    @Mapping(target = "targetId", source = "approval.targetId")
    @Mapping(target = "status", source = "approval.status")
    @Mapping(target = "createdAt", source = "approval.createdAt")
    @Mapping(target = "decidedAt", source = "approval.decidedAt")
    @Mapping(target = "appliedAt", source = "approval.appliedAt")
    @Mapping(target = "decisionNote", source = "approval.decisionNote")
    @Mapping(target = "correlationId", source = "approval.correlationId")
    @Mapping(target = "payload", expression = "java(detail ? facts.approval().payload() : null)")
    @Mapping(target = "result", expression = "java(result(facts))")
    ApprovalResponse toResponse(LimitApprovalService.Result facts, @Context boolean detail);

    ActorResponse toActor(StaffDirectory.ActorSummary actor);

    default Map<String, Object> result(LimitApprovalService.Result facts) {
        var config = facts.configuration();
        return config == null || !"APPLIED".equals(facts.approval().status()) ? null
                : Map.of("limitId", config.limitId().toString(), "version", config.version(),
                        "effectiveFrom", config.effectiveFrom().toString());
    }
}
