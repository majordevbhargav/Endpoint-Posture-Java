package com.endpointposture.indicator.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;

/** Body the agent POSTs to /api/v1/security-indicators. Single-element arrays collapsed by PowerShell are accepted. */
public record SecurityReportRequest(
        String jobId,
        EndpointDto endpoint,
        String status,
        String detail,
        Integer windowSeconds,
        Integer intervalSeconds,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<Sample> samples
) {
    public record EndpointDto(String mac, String hostname, String ip) {}

    public record Sample(Integer index, Double offsetSec,
                         @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<Connection> connections) {}

    public record Connection(String remoteAddress, Integer remotePort, Integer localPort, Integer pid, String process) {}
}