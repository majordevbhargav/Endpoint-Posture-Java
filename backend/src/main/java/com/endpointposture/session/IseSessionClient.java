package com.endpointposture.session;

import com.endpointposture.ise.config.IseProperties;
import com.endpointposture.ise.config.IseHttpClientFactory;
import com.endpointposture.security.SecureXml;
import com.endpointposture.session.dto.IseActiveSession;
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

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Polls ISE's MNT ActiveList API for currently-active sessions.
 *
 * <p>Never throws. A failed poll is reported as {@link SessionPoll#failed}
 * (distinct from a successful poll that found zero sessions), so
 * {@link IseSessionWatcher} can tell "ISE is down" from "nobody is connected".</p>
 */
@Component
public class IseSessionClient {

    private static final Logger log = LoggerFactory.getLogger(IseSessionClient.class);

    /**
     * Result of one poll.
     *
     * @param ok       {@code true} if ISE answered and the response parsed
     * @param sessions active sessions; empty on failure (and legitimately empty on success)
     * @param error    failure reason when {@code ok} is false
     */
    public record SessionPoll(boolean ok, List<IseActiveSession> sessions, String error) {
        static SessionPoll ok(List<IseActiveSession> s) { return new SessionPoll(true, s, null); }
        static SessionPoll failed(String e)             { return new SessionPoll(false, List.of(), e); }
    }

    private final IseProperties props;
    private final RestClient restClient;

    public IseSessionClient(IseProperties props) {
        this.props = props;

        if (!props.isConfigured()) {
            this.restClient = null;
            return;
        }

        RestClient.Builder builder = RestClient.builder().baseUrl(props.getBaseUrl());
        HttpClient httpClient = IseHttpClientFactory.create(props);
        if (httpClient != null) {
            builder = builder.requestFactory(new JdkClientHttpRequestFactory(httpClient));
        }
        this.restClient = builder.build();
    }

    /** @return the poll outcome; never throws */
    public SessionPoll fetchActiveSessions() {
        if (!props.isConfigured()) {
            log.debug("ISE not configured (app.ise.base-url is blank) - skipping session poll");
            return SessionPoll.failed("ISE not configured");
        }

        try {
            String basicAuth = Base64.getEncoder().encodeToString(
                    (props.getUsername() + ":" + props.getPassword()).getBytes(StandardCharsets.UTF_8));

            String xml = restClient.get()
                    .uri("/admin/API/mnt/Session/ActiveList")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth)
                    .retrieve()
                    .body(String.class);

            return SessionPoll.ok(parseActiveList(xml));
        } catch (RestClientException e) {
            log.warn("ISE session poll failed (will retry next tick): {}", e.getMessage());
            return SessionPoll.failed(e.getMessage());
        } catch (Exception e) {
            log.warn("Could not parse ISE ActiveList response (will retry next tick)", e);
            return SessionPoll.failed("Unparseable response: " + e.getMessage());
        }
    }

    List<IseActiveSession> parseActiveList(String xml) throws Exception {
        if (xml == null || xml.isBlank()) return List.of();

        Document doc = SecureXml.parse(xml);

        List<IseActiveSession> sessions = new ArrayList<>();
        NodeList entries = doc.getElementsByTagName("activeSession");

        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            String mac = textOf(entry, "calling_station_id");
            String ip = textOf(entry, "framed_ip_address");
            if (mac != null && !mac.isBlank()) {
                sessions.add(new IseActiveSession(mac, ip));
            }
        }
        return sessions;
    }

    private String textOf(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() > 0 ? nl.item(0).getTextContent() : null;
    }
}
