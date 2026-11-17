package com.simulatedbank.banking.notification.adapter.out.persistence;

import com.simulatedbank.banking.notification.domain.NotificationItem;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface NotificationMapper {

    NotificationItem toDomain(NotificationEntity entity);

    NotificationEntity toEntity(NotificationItem domain);
}
