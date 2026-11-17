package com.simulatedbank.banking.identity.application;

import jakarta.validation.constraints.AssertTrue;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** BE-ID-IMP-10: explicit RP/origin configuration; UV is always required, never configurable. */
@ConfigurationProperties(prefix = "app.identity.fido")
@Validated
public record FidoProperties(
        @DefaultValue("false") boolean enabled,
        String rpId,
        Set<String> allowedOrigins,
        @DefaultValue("30s") Duration challengeTtl) {

    private static final Pattern DNS_NAME = Pattern.compile(
            "(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");

    public FidoProperties {
        allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
    }

    @AssertTrue(message = "enabled FIDO requires a DNS RP ID, explicit secure origins and a positive TTL")
    public boolean isConfigurationValid() {
        if (!enabled) {
            return true;
        }
        return rpId != null && DNS_NAME.matcher(rpId).matches()
                && !rpId.matches("[0-9.]+")
                && challengeTtl != null && !challengeTtl.isNegative() && !challengeTtl.isZero()
                && !allowedOrigins.isEmpty() && allowedOrigins.stream().allMatch(this::isValidOrigin);
    }

    private boolean isValidOrigin(String origin) {
        try {
            URI uri = new URI(origin);
            String host = uri.getHost();
            return host != null && (host.equals(rpId) || host.endsWith("." + rpId))
                    && ("https".equals(uri.getScheme())
                        || ("http".equals(uri.getScheme()) && "localhost".equals(host)))
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && uri.getRawPath().isEmpty()
                    && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535))
                    && origin.equals(uri.getScheme() + "://" + host
                        + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
