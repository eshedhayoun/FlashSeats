-- Moves a batch of buyers out of the line and onto promotion passes, in one round trip (ADR-079).
--
--   KEYS[1]    queue:passes:{e}
--   KEYS[2]    queue:waiting:{e}
--   KEYS[3..]  queue:pass:{e}:{sid}, one per buyer, in the order of ARGV
--   ARGV[1]    the pass TTL, seconds
--   ARGV[2]    when the passes expire, epoch milliseconds: their score in queue:passes
--   ARGV[3..]  session id, pass token -- a pair per buyer
--
-- Returns how many buyers were promoted.
--
-- Per buyer, the same three writes in the same order as the pipeline this replaces (ADR-065):
-- the pass first, so a buyer who is out of the line always holds one. A pipeline needed a
-- connection of its own, and with no pool Spring opened a fresh one for every tick of every sale.

local ttl = ARGV[1]
local expiresAt = ARGV[2]

for i = 3, #KEYS do
    local sessionId = ARGV[2 * i - 3]
    local passToken = ARGV[2 * i - 2]
    redis.call('SET', KEYS[i], passToken, 'EX', ttl)
    redis.call('ZADD', KEYS[1], expiresAt, sessionId)
    redis.call('ZREM', KEYS[2], sessionId)
end

return #KEYS - 2
