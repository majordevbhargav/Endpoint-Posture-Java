package com.endpointposture.diagnostic;

import com.endpointposture.diagnostic.dto.DiagnosticReportRequest;
import com.endpointposture.diagnostic.dto.DiagnosticResponse;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The single write path for diagnostics. Every run, even one that could not
 * measure anything, leaves a row ("a failed attempt is still evidence").
 * Nothing here calls Cisco ISE.
 */
@Service
public class DiagnosticService {

    private final EndpointDiagnosticRepository repository;
    private final EndpointService endpointService;
    private final DiagnosticScorer scorer;

    public DiagnosticService(EndpointDiagnosticRepository repository,
                             EndpointService endpointService,
                             DiagnosticScorer scorer) {
        this.repository = repository;
        this.endpointService = endpointService;
        this.scorer = scorer;
    }

    /**
     * Stores one agent report.
     *
     * @throws IllegalArgumentException if the MAC is missing, the status is unknown,
     *                                  or an {@code OK} report carries no measurements
     */
    @Transactional
    public DiagnosticResponse ingest(DiagnosticReportRequest req) {
        if (req.endpoint() == null || req.endpoint().mac() == null || req.endpoint().mac().isBlank()) {
            throw new IllegalArgumentException("endpoint.mac is required");
        }

        EndpointDiagnostic.Status status = parseStatus(req.status());

        Endpoint endpoint = endpointService.upsertByMac(
                req.endpoint().mac(), req.endpoint().ip(), req.endpoint().hostname(), null, null);

        Integer score = null;
        EndpointDiagnostic.Band band = null;
        List<Map<String, Object>> deductions = null;
        String error = null;

        if (status == EndpointDiagnostic.Status.OK) {
            if (req.gateway() == null && req.dns() == null && req.internet() == null && req.tcp443() == null) {
                throw new IllegalArgumentException("An OK report must include at least one probe result");
            }
            DiagnosticScorer.Result r = scorer.score(req.gateway(), req.dns(), req.internet(), req.tcp443());
            score = r.score();
            band = r.band();
            deductions = r.deductions();
        } else {
            error = req.detail() == null || req.detail().isBlank() ? "Endpoint could not be probed" : req.detail();
        }

        Map<String, Object> raw = new HashMap<>();
        raw.put("jobId", req.jobId());
        raw.put("status", status.name());
        raw.put("detail", req.detail());
        raw.put("gateway", req.gateway());
        raw.put("dns", req.dns());
        raw.put("internet", req.internet());
        raw.put("tcp443", req.tcp443());
        raw.put("traceroute", req.traceroute());

        EndpointDiagnostic saved = repository.save(EndpointDiagnostic.builder()
                .endpointId(endpoint.getId())
                .jobId(parseUuidOrNull(req.jobId()))
                .status(status)
                .score(score)
                .band(band)
                .deductions(deductions)
                .rawReport(raw)
                .errorMessage(error)
                .build());

        return toResponse(saved);
    }

    /** Records a run that never produced a report: status FAILED, score null (never zero). */
    @Transactional
    public DiagnosticResponse recordFailure(UUID endpointId, UUID jobId, String reason) {
        String detail = reason == null ? "Unknown failure" : reason;
        EndpointDiagnostic saved = repository.save(EndpointDiagnostic.builder()
                .endpointId(endpointId)
                .jobId(jobId)
                .status(EndpointDiagnostic.Status.FAILED)
                .rawReport(Map.of("error", detail))
                .errorMessage(detail)
                .build());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<DiagnosticResponse> history(UUID endpointId) {
        return repository.findByEndpointIdOrderByCollectedAtDesc(endpointId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Optional<DiagnosticResponse> latest(UUID endpointId) {
        return repository.findFirstByEndpointIdOrderByCollectedAtDesc(endpointId).map(this::toResponse);
    }

    private EndpointDiagnostic.Status parseStatus(String raw) {
        if (raw == null) throw new IllegalArgumentException("status is required");
        try {
            EndpointDiagnostic.Status s = EndpointDiagnostic.Status.valueOf(raw.trim().toUpperCase());
            if (s == EndpointDiagnostic.Status.FAILED) {
                throw new IllegalArgumentException("status must be OK or WINRM_UNAVAILABLE");
            }
            return s;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status must be OK or WINRM_UNAVAILABLE");
        }
    }

    private UUID parseUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private DiagnosticResponse toResponse(EndpointDiagnostic d) {
        return new DiagnosticResponse(
                d.getId(), d.getEndpointId(), d.getJobId(), d.getStatus(), d.getScore(), d.getBand(),
                d.getDeductions(), d.getRawReport(), d.getErrorMessage(), d.getCollectedAt());
    }
}