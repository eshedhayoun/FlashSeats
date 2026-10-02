package com.flashseats.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Notification tunables. {@code enabled} itself is read by the conditional beans, not here. */
@ConfigurationProperties(prefix = "flashseats.notification")
@Getter
@Setter
public class NotificationProperties {

    /**
     * How long a claim may stay {@code PENDING} before it is presumed stranded by a process that died
     * between claiming and sending (ADR-069). Well above a render plus an SMTP round trip, so a send
     * still in progress is never mistaken for one that died.
     */
    private int strandedAfterSeconds = 600;
}
