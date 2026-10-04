package com.flashseats.shared.redis;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DigestUtils;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * A Lua script read from the classpath once, with its SHA1 computed once.
 *
 * <p>Not Spring's {@code DefaultRedisScript} over a {@code ClassPathResource}: that one asks the
 * resource whether it has changed on <em>every</em> call, under a lock, and inside a packaged jar the
 * question opens a URL connection into the nested jar. Every script call in the system paid it,
 * serialised through one lock per script; on {@code GET /queue/status}, the most-called path, it was
 * the largest cost in the request (ADR-079). A script inside a jar cannot change while the process
 * runs, so there is nothing to check.
 *
 * <p>The executor still falls back to a full {@code EVAL} on {@code NOSCRIPT}, so a Redis restart or a
 * failover re-registers the script without anyone noticing.
 */
public record LuaScript<T>(String script, String sha1, Class<T> resultType) implements RedisScript<T> {

    public static <T> LuaScript<T> load(String classpathLocation, Class<T> resultType) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new LuaScript<>(script, DigestUtils.sha1DigestAsHex(script), resultType);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Cannot read Lua script " + classpathLocation, unreadable);
        }
    }

    @Override
    public String getSha1() {
        return sha1;
    }

    @Override
    public Class<T> getResultType() {
        return resultType;
    }

    @Override
    public String getScriptAsString() {
        return script;
    }
}
