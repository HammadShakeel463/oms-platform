package com.oms.order.api.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A stable pagination envelope.
 *
 * <p>Spring Data can serialise a {@code Page} directly, and Boot now warns when you do:
 * the JSON shape of {@code PageImpl} is an implementation detail that has changed between
 * versions and would drag every client along with it. Declaring the envelope makes the
 * contract ours.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
