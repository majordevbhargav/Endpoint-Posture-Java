package com.endpointposture.ise;

import com.endpointposture.audit.IseActionAuditRepository;
import com.endpointposture.ise.dto.IseActionStateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class IseActionStateService {

    private final IseActionAuditRepository auditRepository;

    public IseActionStateService(IseActionAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * @return the latest RESTRICT or CLEAR_RESTRICTION audit row per endpoint
     */
    @Transactional(readOnly = true)
    public List<IseActionStateResponse> getLatestEnforcementStates() {
        return auditRepository.findLatestEnforcementActionsPerEndpoint()
                .stream()
                .map(IseActionStateResponse::from)
                .toList();
    }
}
