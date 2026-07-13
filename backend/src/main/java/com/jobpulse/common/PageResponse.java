package com.jobpulse.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A stable, hand-shaped page response -- never Spring Data's own Page/PageImpl
 * directly. Same "DTOs at the boundary" rule as every entity in this codebase:
 * framework types don't leave the backend, because their JSON shape isn't a
 * contract this API controls.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
