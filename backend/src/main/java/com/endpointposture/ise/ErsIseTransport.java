package com.endpointposture.ise;

import com.endpointposture.ise.config.IseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ISE ERS REST (Context-In API) implementation of {@link IseTransport}.
 *
 * <p>
 * Wraps the same two operations the Python prototype's
 * {@code ers_transport.py} implements: {@link #publishPosture} writes a
 * posture fact as a custom endpoint attribute for ISE's own Authorization
 * Policy to act on, and {@link #publishEnforcement} requests either an
 * ANC quarantine/clear or an attribute-write-plus-CoA-reauth, depending
 * on {@code app.ise.enforcement-mode}. Neither is ever called except from
 * {@link IseActionService}, itself reachable only through an explicit
 * operator action — this class makes no enforcement decision on its own.
 * </p>
 */

@Component
public class ErsIseTransport implements IseTransport {

    private static final Logger log = LoggerFactory.getLogger(ErsIseTransport.class);

    private final IseProperties props;
    private final RestClient restClient;
    private final String basicAuthHeader;

    public ErsIseTransport(IseProperties props) {
        this.props = props;
        this.basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString(
                (props.getUsername() + ":" + props.getPassword()).getBytes(StandardCharsets.UTF_8));

        if (!props.isConfigured()) {
            // No base URL yet - every method below checks isConfigured()
            // again before using this, so a null client here is safe.
            this.restClient = null;
            return;
        }

        RestClient.Builder builder = RestClient.builder().baseUrl(props.getBaseUrl());
        if (!props.isVerifyTls()) {
            // Lab/self-signed ISE deployments commonly present a
            // certificate the JVM's default trust store won't recognize.
            // Matches the Python side's urllib3.disable_warnings(...) +
            // verify=False behavior - a deliberate, configured choice
            // (app.ise.verify-tls), not an accident.
            builder = builder.requestFactory(new JdkClientHttpRequestFactory(insecureHttpClient()));
        }
        this.restClient = builder.build();
    }

    private HttpClient insecureHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[] { new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            } };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder()
                    .sslContext(ctx)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build an insecure HTTP client for app.ise.verify-tls=false", e);
        }
    }

    @Override
    public boolean reachable() {
        if (restClient == null)
            return false;
        try {
            restClient.get()
                    .uri("/ers/config/endpoint?filter=mac.EQ.00:00:00:00:00:00&size=1")
                    .header(HttpHeaders.AUTHORIZATION, basicAuthHeader)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    /**
     * @return the ERS internal endpoint id for this MAC, or {@code null} if ISE has
     *         no record of it yet
     */

    @SuppressWarnings("unchecked")
    private String endpointIdFor(String mac) {
        Map<String, Object> body = restClient.get()
                .uri("/ers/config/endpoint?filter=mac.EQ.{mac}", mac)
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader)
                .header(HttpHeaders.ACCEPT, "application/json")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .retrieve()
                .body(Map.class);
        if (body == null)
            return null;

        Object searchResult = body.get("SearchResult");
        if (!(searchResult instanceof Map<?, ?> sr))
            return null;
        Object resources = sr.get("resources");
        if (!(resources instanceof List<?> list) || list.isEmpty())
            return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> resource))
            return null;
        Object id = resource.get("id");
        return id == null ? null : id.toString();
    }

    @Override
    public IseResult publishPosture(String mac, String status, String details) {
        if (!props.isConfigured()) {
            return new IseResult(false, "ISE is not configured (app.ise.base-url is blank)");
        }
        try {
            Map<String, Object> attrs = Map.of(
                    "ExternalComplianceStatus", status,
                    "PostureLastChecked", Instant.now().toString(),
                    "PostureFailedChecks", (details == null || details.isBlank()) ? "none" : details);

            Map<String, Object> endpointBody = new HashMap<>();
            endpointBody.put("mac", mac);
            endpointBody.put("customAttributes", Map.of("customAttributes", attrs));

            String existingId = endpointIdFor(mac);
            if (existingId != null) {
                endpointBody.put("id", existingId);
                restClient.put()
                        .uri("/ers/config/endpoint/{id}", existingId)
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader)
                        .header(HttpHeaders.ACCEPT, "application/json")
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(Map.of("ERSEndPoint", endpointBody))
                        .retrieve()
                        .toBodilessEntity();
            } else {
                restClient.post()
                        .uri("/ers/config/endpoint")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader)
                        .header(HttpHeaders.ACCEPT, "application/json")
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(Map.of("ERSEndPoint", endpointBody))
                        .retrieve()
                        .toBodilessEntity();
            }
            return new IseResult(true, "Posture shared with ISE as " + status);
        } catch (Exception e) {
            log.warn("publishPosture failed for {}: {}", mac, e.getMessage());
            return new IseResult(false, e.getMessage());
        }
    }

    @Override
    public IseResult publishEnforcement(String mac, EnforcementAction action, String policy) {
        if (!props.isConfigured()) {
            return new IseResult(false, "ISE is not configured (app.ise.base-url is blank)");
        }
        try {
            return props.getEnforcementMode() == IseProperties.Mode.ANC
                    ? applyAnc(mac, action, policy)
                    : applyAttributeAndReauth(mac, action);
        } catch (Exception e) {
            log.warn("publishEnforcement({}) failed for {}: {}", action, mac, e.getMessage());
            return new IseResult(false, e.getMessage());
        }
    }

    private IseResult applyAnc(String mac, EnforcementAction action, String policy) {
        String path = action == EnforcementAction.RESTRICT
                ? "/ers/config/ancendpoint/apply"
                : "/ers/config/ancendpoint/clear";

        List<Map<String, String>> data = new ArrayList<>();
        data.add(Map.of("name", "macAddress", "value", mac));
        if (action == EnforcementAction.RESTRICT) {
            String policyName = (policy == null || policy.isBlank()) ? props.getAncPolicyName() : policy;
            data.add(Map.of("name", "policyName", "value", policyName));
        }

        restClient.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader)
                .header(HttpHeaders.ACCEPT, "application/json")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(Map.of("OperationAdditionalData", Map.of("additionalData", data)))
                .retrieve()
                .toBodilessEntity();

        return new IseResult(true, action == EnforcementAction.RESTRICT ? "ANC_APPLIED" : "ANC_CLEARED");
    }

    private IseResult applyAttributeAndReauth(String mac, EnforcementAction action) {
        if (action == EnforcementAction.CLEAR) {
            // Attribute mode has no standing ANC restriction to remove -
            // clearing means "share compliant posture again", which is
            // its own explicit Share Posture action. Matches
            // ers_transport.py's ENFORCEMENT_MODE == "attribute" path,
            // which never implements a CLEAR beyond that.
            return new IseResult(true,
                    "CLEAR_RESTRICTION requested under attribute mode - there is no ANC policy to remove here. "
                            + "Share this endpoint's current posture to have ISE re-evaluate access on next reauth.");
        }

        Map<String, String> session = sessionDetailFor(mac);
        if (session.isEmpty()) {
            return new IseResult(false, "NO_ACTIVE_SESSION - facts stored, will apply on next connect");
        }

        String psn = session.getOrDefault("acs_server", session.get("server"));
        if (psn == null || psn.isBlank()) {
            return new IseResult(false, "SESSION_FOUND_BUT_NO_PSN - cannot trigger CoA, check manually");
        }

        String response = restClient.get()
                .uri("/admin/API/mnt/CoA/Reauth/{psn}/{mac}/1", psn, mac)
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader)
                .header(HttpHeaders.ACCEPT, "application/xml")
                .retrieve()
                .body(String.class);

        boolean fired = response != null && response.contains("<results>true</results>");
        return new IseResult(fired, fired ? "REAUTH_APPLIED" : "CoA call returned no confirmation");
    }

    /**
     * Best-effort flat map of every leaf field in ISE's MNT session-detail XML for
     * one MAC.
     */
    private Map<String, String> sessionDetailFor(String mac) {
        try {
            String xml = restClient.get()
                    .uri("/admin/API/mnt/Session/MACAddress/{mac}", mac)
                    .header(HttpHeaders.AUTHORIZATION, basicAuthHeader)
                    .header(HttpHeaders.ACCEPT, "application/xml")
                    .retrieve()
                    .body(String.class);
            if (xml == null || xml.isBlank())
                return Map.of();

            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            Map<String, String> fields = new HashMap<>();
            NodeList all = doc.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Element el = (Element) all.item(i);
                if (el.getChildNodes().getLength() == 1
                        && el.getFirstChild() != null
                        && el.getFirstChild().getNodeValue() != null
                        && !el.getFirstChild().getNodeValue().isBlank()) {
                    fields.put(el.getTagName(), el.getFirstChild().getNodeValue().trim());
                }
            }
            return fields;
        } catch (Exception e) {
            log.warn("Could not fetch ISE session detail for {}: {}", mac, e.getMessage());
            return Map.of();
        }
    }
}