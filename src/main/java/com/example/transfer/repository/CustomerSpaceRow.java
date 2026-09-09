package com.example.transfer.repository;

import java.time.OffsetDateTime;

/** customer_space 表一行。 */
public record CustomerSpaceRow(
        Long id,
        String code,
        String name,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
