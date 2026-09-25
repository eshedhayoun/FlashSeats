package com.flashseats.bot.service;

import com.flashseats.bot.config.BotProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Token buckets for sessions and for source addresses.
 *
 * <p><strong>The session bucket is the primary control; the IP bucket is a coarse flood
 * backstop.</strong> That asymmetry is deliberate and it is the opposite of the obvious design: a
 * flash sale means thousands of legitimate humans arriving at once, many of them sharing one
 * carrier-grade NAT or corporate gateway. A tight per-IP limit would block all of them during
 * precisely the traffic spike this system exists to serve (ADR-011).
 */
@Service
public class RateLimitService {

    private static final String SESSION_PREFIX = "bot:rate:session:";
    private static final String IP_PREFIX = "bot:rate:ip:";

    private final ProxyManager<byte[]> buckets;
    private final Supplier<BucketConfiguration> sessionConfig;
    private final Supplier<BucketConfiguration> ipConfig;
    private final String sessionKeyPrefix;
    private final String ipKeyPrefix;
    private final Set<String> trustedProxies;

    public RateLimitService(ProxyManager<byte[]> buckets, BotProperties properties) {
        this.buckets = buckets;
        BotProperties.Bucket sessionBucket = properties.getSessionBucket();
        BotProperties.Bucket ipBucket = properties.getIpBucket();
        this.sessionConfig = configFor(sessionBucket);
        this.ipConfig = configFor(ipBucket);
        this.sessionKeyPrefix = versionedPrefix(SESSION_PREFIX, sessionBucket);
        this.ipKeyPrefix = versionedPrefix(IP_PREFIX, ipBucket);
        this.trustedProxies = Set.copyOf(properties.getTrustedProxies());
    }

    /**
     * Whether a peer address may speak for someone else via {@code X-Forwarded-For} (ADR-039).
     *
     * <p>Exact addresses, not ranges: the set is the handful of load balancers in front of this
     * app, and a CIDR parser is a place for a subtle bug to hide in the one check that decides
     * whether the rate limiter can be bypassed. Empty by default — trust nobody until told.
     */
    public boolean isTrustedProxy(String peerAddress) {
        return peerAddress != null && trustedProxies.contains(peerAddress);
    }

    public boolean allowSession(String sessionId) {
        return tryConsume(sessionKeyPrefix + sessionId, sessionConfig);
    }

    public boolean allowIp(String ip) {
        return tryConsume(ipKeyPrefix + ip, ipConfig);
    }

    private String versionedPrefix(String prefix, BotProperties.Bucket bucket) {
        return prefix + "v" + bucket.getCapacity() + "-" + bucket.getRefillPerSecond() + ":";
    }

    private boolean tryConsume(String key, Supplier<BucketConfiguration> configuration) {
        return buckets.builder()
                .build(key.getBytes(StandardCharsets.UTF_8), configuration)
                .tryConsume(1);
    }

    /**
     * A burst capacity with a steady refill: brief bursts are normal browsing, sustained volume is
     * not.
     */
    private Supplier<BucketConfiguration> configFor(BotProperties.Bucket bucket) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(bucket.getCapacity())
                        .refillGreedy(bucket.getRefillPerSecond(), Duration.ofSeconds(1))
                        .build())
                .build();
        return () -> configuration;
    }
}
