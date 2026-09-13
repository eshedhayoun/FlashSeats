package com.flashseats.bot.model;

/** What an operator decided about an address. */
public enum IpRuleAction {
    /**
     * Exempt from the <strong>IP</strong> bucket only.
     *
     * <p>Never from the session bucket, and never from anything else. An allow-list entry is for a
     * known shared egress — an office, a partner's proxy — where hundreds of legitimate buyers share
     * one address and the coarse flood backstop would throttle all of them. It is not a statement
     * that the traffic is trusted, so the per-session control still applies to every one of them.
     */
    ALLOW,

    /** Refused at the filter, before any business logic runs. */
    DENY
}
