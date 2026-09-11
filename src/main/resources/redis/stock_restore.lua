-- Returns seats to a tier's counter.
--
-- KEYS[1] catalog:stock:{eventId}:{tierId}
-- ARGV[1] quantity
--
--  >= 0  the counter's new value
--    -2  no counter at all -- nothing was restored
--
-- The EXISTS guard is load-bearing. INCRBY treats a missing key as zero and creates it, so a bare
-- INCRBY would rebuild a lost counter from whatever hold happened to expire next -- a counter
-- conjured from nothing, which is exactly what ADR-004 forbids. The seats are instead accounted for
-- by the drift metric and returned by an explicit rebuild.
--
-- A live counter can never reach -2: stock_reserve.lua refuses to decrement below the requested
-- quantity, so the sentinel is unambiguous.

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -2
end

return redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
