package com.endpointposture.session;

import com.endpointposture.ise.config.IseProperties;
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
        if (!props.isVerifyTls()) {
            builder = builder.requestFactory(new JdkClientHttpRequestFactory(insecureHttpClient()));
        }
        this.restClient = builder.build();
    }

    private HttpClient insecureHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
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

    private List<IseActiveSession> parseActiveList(String xml) throws Exception {
        if (xml == null || xml.isBlank()) return List.of();

        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

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