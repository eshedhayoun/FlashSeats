-- One session's place in a sale, read in one round trip (ADR-079).
--
--   KEYS[1]  queue:admit:{e}:{sid}
--   KEYS[2]  queue:pass:{e}:{sid}
--   KEYS[3]  queue:waiting:{e}
--   KEYS[4]  queue:exhausted:{e}
--   ARGV[1]  the session id
--
-- Returns { admission token | nil, admission TTL in seconds, pass token | nil, rank | nil,
-- exhausted marker 0 | 1 }. Only reads, and nothing here decides anything: the order of the
-- checks is the state machine, and that lives in QueueService.decide.
--
-- A script and not a pipeline, because this is the most-called path in the system. A Lettuce
-- pipeline needs a connection of its own, and with no pool Spring opened one -- after asking
-- Sentinel where the primary is -- for every call, then closed it: two TCP handshakes per poll.
-- A script runs on the one shared connection everything else uses. It is also a snapshot, which
-- a pipeline only happens to be: a promotion can never land between the pass and the rank.

return {
    redis.call('GET', KEYS[1]),
    redis.call('TTL', KEYS[1]),
    redis.call('GET', KEYS[2]),
    redis.call('ZRANK', KEYS[3], ARGV[1]),
    redis.call('EXISTS', KEYS[4])
}
