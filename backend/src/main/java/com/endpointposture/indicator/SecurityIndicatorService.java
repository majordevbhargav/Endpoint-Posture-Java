package com.endpointposture.indicator;

import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointService;
import com.endpointposture.indicator.dto.SecurityIndicatorResponse;
import com.endpointposture.indicator.dto.SecurityReportRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Single write path for security indicators. A run that measured nothing still leaves a row. Never calls ISE. */
@Service
public class SecurityIndicatorService {

    private final EndpointSecurityIndicatorRepository repository;
    private final EndpointService endpointService;
    private final SecurityIndicatorAnalyzer analyzer;
    private final ObjectMapper objectMapper;

    public SecurityIndicatorService(EndpointSecurityIndicatorRepository repository, EndpointService endpointService,
                                    SecurityIndicatorAnalyzer analyzer, ObjectMapper objectMapper) {
        this.repository = repository;
        this.endpointService = endpointService;
        this.analyzer = analyzer;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SecurityIndicatorResponse ingest(SecurityReportRequest req) {
        if (req.endpoint() == null || req.endpoint().mac() == null || req.endpoint().mac().isBlank()) {
            throw new IllegalArgumentException("endpoint.mac is required");
        }
        Endpoint endpoint = endpointService.upsertByMac(
                req.endpoint().mac(), req.endpoint().ip(), req.endpoint().hostname(), null, null);
        return ingestForTrustedEndpoint(endpoint.getId(), parseUuidOrNull(req.jobId()), req, List.of(), List.of());
    }

    /** Worker path binds evidence to the locked job endpoint and job UUID. */
    @Transactional
    public SecurityIndicatorResponse ingestForWorker(UUID endpointId, UUID jobId, SecurityReportRequest req) {
        return ingestForWorker(endpointId, jobId, req, List.of(), List.of());
    }

    @Transactional
    public SecurityIndicatorResponse ingestForWorker(UUID endpointId, UUID jobId, SecurityReportRequest req,
            List<String> errors, List<String> warnings) {
        if (endpointId == null || jobId == null || req == null) {
            throw new IllegalArgumentException("A valid security-indicator payload is required");
        }
        return ingestForTrustedEndpoint(endpointId, jobId, req, errors, warnings);
    }

    private SecurityIndicatorResponse ingestForTrustedEndpoint(UUID endpointId, UUID jobId, SecurityReportRequest req, List<String> errors, List<String> warnings) {
        EndpointSecurityIndicator.Status status = parseStatus(req.status());
        List<SecurityReportRequest.Sample> samples =
                req.samples() == null ? List.of() : req.samples().stream().filter(Objects::nonNull).toList();

        EndpointSecurityIndicator.RiskLevel risk = null;
        List<Map<String, Object>> findings = null;
        Map<String, Object> summary = null;
        String error = null;

        if (status == EndpointSecurityIndicator.Status.OK) {
            if (samples.isEmpty()) throw new IllegalArgumentException("An OK report must include at least one sample");
            SecurityIndicatorAnalyzer.Result r =
                    analyzer.analyze(samples, req.intervalSeconds() == null ? 5 : req.intervalSeconds());
            risk = r.risk();
            findings = r.findings();
            summary = r.summary();
        } else {
            error = req.detail() == null || req.detail().isBlank() ? "Endpoint could not be sampled" : req.detail();
        }

        Map<String, Object> raw = objectMapper.convertValue(req, new TypeReference<Map<String, Object>>() {});
        raw.put("jobId", jobId == null ? null : jobId.toString());
        if (errors != null && !errors.isEmpty()) raw.put("workerErrors", errors);
        if (warnings != null && !warnings.isEmpty()) raw.put("workerWarnings", warnings);

        EndpointSecurityIndicator saved = repository.save(EndpointSecurityIndicator.builder()
                .endpointId(endpointId)
                .jobId(jobId)
                .status(status).riskLevel(risk).findings(findings).summary(summary)
                .rawReport(raw).errorMessage(error).build());
        return toResponse(saved);
    }

    /** Records a run that never produced a report: status FAILED, risk null. */
    @Transactional
    public SecurityIndicatorResponse recordFailure(UUID endpointId, UUID jobId, String reason) {
        String detail = reason == null ? "Unknown failure" : reason;
        return toResponse(repository.save(EndpointSecurityIndicator.builder()
                .endpointId(endpointId).jobId(jobId)
                .status(EndpointSecurityIndicator.Status.FAILED)
                .rawReport(Map.of("error", detail)).errorMessage(detail).build()));
    }

    @Transactional(readOnly = true)
    public List<SecurityIndicatorResponse> history(UUID endpointId) {
        return repository.findByEndpointIdOrderByCollectedAtDesc(endpointId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Optional<SecurityIndicatorResponse> latest(UUID endpointId) {
        return repository.findFirstByEndpointIdOrderByCollectedAtDesc(endpointId).map(this::toResponse);
    }

    private EndpointSecurityIndicator.Status parseStatus(String raw) {
        if (raw == null) throw new IllegalArgumentException("status is required");
        try {
            EndpointSecurityIndicator.Status s = EndpointSecurityIndicator.Status.valueOf(raw.trim().toUpperCase());
            if (s == EndpointSecurityIndicator.Status.FAILED) throw new IllegalArgumentException();
            return s;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status must be OK or WINRM_UNAVAILABLE");
        }
    }

    private UUID parseUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private SecurityIndicatorResponse toResponse(EndpointSecurityIndicator e) {
        return new SecurityIndicatorResponse(e.getId(), e.getEndpointId(), e.getJobId(), e.getStatus(),
                e.getRiskLevel(), e.getFindings(), e.getSummary(), e.getErrorMessage(), e.getCollectedAt());
    }
}