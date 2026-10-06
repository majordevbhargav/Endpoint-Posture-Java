package com.endpointposture.endpoint.dto;

import java.util.List;

/** One page of results. {@code page} is zero-based. */
public record PageResponse<T>(List<T> items, long total, int page, int size) {}