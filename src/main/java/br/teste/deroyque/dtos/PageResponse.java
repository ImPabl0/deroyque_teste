package br.teste.deroyque.dtos;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Envelope de paginação estável, sem expor a serialização interna de {@link Page}. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
