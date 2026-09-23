package com.endpointposture.ise;

import com.endpointposture.audit.IseActionAudit;
import com.endpointposture.audit.IseActionAuditRepository;
import com.endpointposture.endpoint.Endpoint;
import com.endpointposture.endpoint.EndpointNotFoundException;
import com.endpointposture.endpoint.EndpointRepository;
import com.endpointposture.posture.AssessmentNotFoundException;
import com.endpointposture.posture.AssessmentService;
import com.endpointposture.posture.AssessmentStatus;
import com.endpointposture.posture.dto.AssessmentResponse;
import com.endpointposture.posture.dto.CheckResultResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The three explicit, operator-triggered ISE actions: share posture,
 * restrict, clear restriction.
 *
 * <p>Structurally separate from posture ingestion (project plan Section
 * 2.2 / 8.1): nothing in
 * {@link com.endpointposture.posture.PostureIngestService} calls this
 * class, and nothing here runs as a side effect of a posture report
 * arriving - only {@link IseActionController} calls it, one method per
 * button in the dashboard. Every call writes exactly one
 * {@link IseActionAudit} row - success or failure - before returning,
 * so a failed CoA attempt is always visible, never silently dropped.</p>
 */
@Service
public class IseActionService {

    private final EndpointRepository endpoints;
    private final AssessmentService assessmentService;
    private final IseTransport transport;
    private final IseActionAuditRepository auditRepository;

    public IseActionService(EndpointRepository endpoints, AssessmentService assessmentService,
                             IseTransport transport, IseActionAuditRepository auditRepository) {
        this.endpoints = endpoints;
        this.assessmentService = assessmentService;
        this.transport = transport;
        this.auditRepository = auditRepository;
    }

    /**
     * Writes the endpoint's latest stored assessment into ISE as a custom
     * attribute. Does not restrict anything and does not require a prior
     * restriction - sharing and restricting are fully independent, per
     * the platform's enforcement model.
     *
     * @param endpointId the endpoint to share
     * @param operator   the authenticated caller, recorded on the audit row
     * @return the ISE result
     * @throws EndpointNotFoundException if the endpoint does not exist
     */
    @Transactional
    public IseResult sharePosture(UUID endpointId, String operator) {
        Endpoint endpoint = endpoints.findById(endpointId)
                .orElseThrow(() -> new EndpointNotFoundException(endpointId.toString()));

        IseResult result;
        try {
            AssessmentResponse latest = assessmentService.getLatestForEndpoint(endpointId);
            result = transport.publishPosture(endpoint.getMacAddress(), latest.status().name(), summarizeFailedChecks(latest));
        } catch (AssessmentNotFoundException e) {
            result = new IseResult(false, "No stored assessment for this endpoint yet - run a posture check first");
        }

        writeAudit(endpoint.getId(), "SHARE_POSTURE", operator, result);
        return result;
    }

    /**
     * Requests an immediate restriction (CoA re-auth or ANC quarantine,
     * per {@code app.ise.enforcement-mode}). Does not require posture to
     * have been shared first - a restriction can be requested purely from
     * a security-indicator finding, independent of the posture pipeline.
     *
     * @param endpointId the endpoint to restrict
     * @param policy     optional ANC policy override
     * @param operator   the authenticated caller
     * @return the ISE result
     */
    @Transactional
    public IseResult restrict(UUID endpointId, String policy, String operator) {
        return enforce(endpointId, EnforcementAction.RESTRICT, policy, "RESTRICT", operator);
    }

    /**
     * Clears a previously-applied restriction.
     *
     * @param endpointId the endpoint to clear
     * @param operator   the authenticated caller
     * @return the ISE result
     */
    @Transactional
    public IseResult clearRestriction(UUID endpointId, String operator) {
        return enforce(endpointId, EnforcementAction.CLEAR, null, "CLEAR_RESTRICTION", operator);
    }

    private IseResult enforce(UUID endpointId, EnforcementAction action, String policy,
                               String auditActionType, String operator) {
        Endpoint endpoint = endpoints.findById(endpointId)
                .orElseThrow(() -> new EndpointNotFoundException(endpointId.toString()));

        IseResult result = transport.publishEnforcement(endpoint.getMacAddress(), action, policy);
        writeAudit(endpoint.getId(), auditActionType, operator, result);
        return result;
    }

    /** One line naming which checks were not compliant on the latest assessment, or {@code "none"}. */
    private String summarizeFailedChecks(AssessmentResponse assessment) {
        List<CheckResultResponse> failed = assessment.checks().stream()
                .filter(c -> c.status() != AssessmentStatus.COMPLIANT)
                .toList();
        if (failed.isEmpty()) return "none";
        return failed.stream().map(CheckResultResponse::checkType).collect(Collectors.joining(", "));
    }

    private void writeAudit(UUID endpointId, String actionType, String operator, IseResult result) {
        auditRepository.save(IseActionAudit.builder()
                .endpointId(endpointId)
                .actionType(actionType)
                .operator(operator)
                .succeeded(result.success())
                .detail(result.detail())
                .build());
    }
}