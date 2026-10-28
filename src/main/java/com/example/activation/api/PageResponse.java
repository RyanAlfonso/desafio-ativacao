package com.example.activation.api;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Página com formato estável (o JSON de {@code PageImpl} não é contrato da API). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
