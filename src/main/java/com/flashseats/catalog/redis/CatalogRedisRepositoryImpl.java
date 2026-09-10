package com.flashseats.catalog.redis;
import java.util.OptionalInt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis implementation of the catalog inventory store.
 *
 * <p>String values are used deliberately because inventory is an integer
 * counter and the rest of the project already uses a simple Redis deployment.
 */
@Repository
public class CatalogRedisRepositoryImpl implements CatalogRedisRepository {
    private final StringRedisTemplate redisTemplate;
    public CatalogRedisRepositoryImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    @Override
    public OptionalInt getStock(long eventId, long tierId) {
        String stockKey = CatalogRedisKeys.stock(eventId, tierId);
        String stockValue = redisTemplate.opsForValue().get(stockKey);
        if (stockValue == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(stockValue));
    }
    @Override
    public boolean initializeIfAbsent(long eventId, long tierId, int totalCapacity) {
        if (totalCapacity < 0) {
            throw new IllegalArgumentException("totalCapacity must be non-negative");
        }
        String stockKey = CatalogRedisKeys.stock(eventId, tierId);
        Boolean wasSet = redisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(totalCapacity));
        return Boolean.TRUE.equals(wasSet);
    }
    @Override
    public void setStock(long eventId, long tierId, int remaining) {
        if (remaining < 0) {
            throw new IllegalArgumentException(
                    "remaining must be non-negative");
        }

        String key = CatalogRedisKeys.stock(eventId, tierId);

        redisTemplate.opsForValue().set(
                key,
                Integer.toString(remaining));
    }
}
