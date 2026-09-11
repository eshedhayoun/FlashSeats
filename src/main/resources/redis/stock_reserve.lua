-- Atomically takes seats from a tier's counter.
--
-- KEYS[1] catalog:stock:{eventId}:{tierId}
-- ARGV[1] quantity
--
--   1  reserved
--  -1  genuinely insufficient  -> 409 INSUFFICIENT_STOCK
--  -2  no counter at all       -> 503 INVENTORY_UNAVAILABLE, alarm, locked rebuild
--
-- The -1 / -2 split is the whole reason this is a script and not a DECRBY. Collapsing "missing"
-- into "sold out" is what lets a Redis eviction quietly tell every buyer the sale ended (ADR-004).

local stock = redis.call('GET', KEYS[1])
if not stock then
    return -2
end

local quantity = tonumber(ARGV[1])
if tonumber(stock) < quantity then
    return -1
end

redis.call('DECRBY', KEYS[1], quantity)
return 1
