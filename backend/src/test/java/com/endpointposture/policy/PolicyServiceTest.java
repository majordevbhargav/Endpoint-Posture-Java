package com.endpointposture.policy;

import com.endpointposture.policy.PolicyService.PolicySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PolicyServiceTest {

    @Mock AppPolicyRepository policies;
    @Mock AppPolicyRuleRepository rules;
    PolicyService service;

    AppPolicy current;

    @BeforeEach
    void setUp() {
        service = new PolicyService(policies, rules);
        current = AppPolicy.builder().id(UUID.randomUUID()).name("Default application policy")
                .version(3).active(true).createdBy("system").build();

        when(policies.findByActiveTrue()).thenReturn(Optional.of(current));
        when(policies.findFirstByOrderByVersionDesc()).thenReturn(Optional.of(current));
        when(policies.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(policies.save(any())).thenAnswer(i -> {
            AppPolicy p = i.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(rules.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // --- clean() ---

    @Test
    void cleanTrimsDropsBlanksAndDedupesCaseInsensitively() {
        assertEquals(List.of("uTorrent", "TeamViewer"),
                PolicyService.clean(List.of("  uTorrent ", "", "  ", "UTORRENT", "TeamViewer")));
    }

    @Test
    void cleanTreatsNullAsEmpty() {
        assertTrue(PolicyService.clean(null).isEmpty());
    }

    @Test
    void cleanRejectsTheAgentDelimiter() {
        assertThrows(IllegalArgumentException.class, () -> PolicyService.clean(List.of("a|b")));
    }

    @Test
    void cleanRejectsQuotes() {
        assertThrows(IllegalArgumentException.class, () -> PolicyService.clean(List.of("bad\"name")));
    }

    @Test
    void cleanRejectsOverlongPatterns() {
        assertThrows(IllegalArgumentException.class, () -> PolicyService.clean(List.of("x".repeat(201))));
    }

    // --- getActive() ---

    @Test
    void getActiveSplitsRulesByType() {
        when(rules.findByPolicyId(current.getId())).thenReturn(List.of(
                AppPolicyRule.builder().appPattern("Cisco Secure Client").ruleType(AppPolicyRule.RuleType.REQUIRED).build(),
                AppPolicyRule.builder().appPattern("uTorrent").ruleType(AppPolicyRule.RuleType.BLOCKED).build()));

        PolicySnapshot s = service.getActive();

        assertEquals(3, s.version());
        assertEquals(List.of("Cisco Secure Client"), s.requiredApps());
        assertEquals(List.of("uTorrent"), s.blockedApps());
    }

    @Test
    void getActiveFailsLoudlyWhenNoPolicyIsActive() {
        when(policies.findByActiveTrue()).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.getActive());
    }

    // --- replaceActive() ---

    @Test
    void replaceCreatesNextVersionAndDeactivatesTheOldOneFirst() {
        PolicySnapshot s = service.replaceActive(List.of("Cisco Secure Client"), List.of("uTorrent", "Steam"), "admin");

        assertEquals(4, s.version());
        assertEquals("admin", s.createdBy());
        assertEquals(List.of("Steam", "uTorrent"), s.blockedApps());

        // The old row must be flushed inactive BEFORE the new active row is inserted,
        // or the single-active unique index would reject the insert.
        InOrder order = inOrder(policies);
        order.verify(policies).saveAndFlush(current);
        order.verify(policies).save(any(AppPolicy.class));
        assertFalse(current.isActive());
    }

    @Test
    void replaceWritesOneRuleRowPerPattern() {
        service.replaceActive(List.of("A"), List.of("B", "C"), "admin");

        ArgumentCaptor<AppPolicyRule> captor = ArgumentCaptor.forClass(AppPolicyRule.class);
        verify(rules, times(3)).save(captor.capture());
        assertEquals(1, captor.getAllValues().stream()
                .filter(r -> r.getRuleType() == AppPolicyRule.RuleType.REQUIRED).count());
        assertEquals(2, captor.getAllValues().stream()
                .filter(r -> r.getRuleType() == AppPolicyRule.RuleType.BLOCKED).count());
    }

    @Test
    void replaceRejectsAnAppThatIsBothRequiredAndBlocked() {
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceActive(List.of("TeamViewer"), List.of("teamviewer"), "admin"));
        verify(policies, never()).save(any());
    }

    @Test
    void replaceAllowsEmptyLists() {
        PolicySnapshot s = service.replaceActive(List.of(), List.of(), "admin");
        assertTrue(s.requiredApps().isEmpty());
        assertTrue(s.blockedApps().isEmpty());
    }
}