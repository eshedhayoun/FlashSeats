-- One cluster-wide admission allowance per promotion interval (ADR-049).
--
--   KEYS[1]  queue:budget
--   ARGV[1]  admissions allowed in a window
--   ARGV[2]  admissions this caller wants
--   ARGV[3]  window length, milliseconds
--
-- Returns how many the caller may promote: never more than it asked for, and never more
-- than the window has left. One round trip, and atomic, so two replicas promoting two
-- different sales in the same second cannot both read the allowance as untouched.
--
-- The window is anchored by the key's own TTL rather than by a clock-derived bucket: the
-- replicas never have to agree about the time, and a skewed clock cannot open a second
-- window that hands out a second full budget.

local budget = tonumber(ARGV[1])
local want = tonumber(ARGV[2])

local spent = redis.call('GET', KEYS[1])

if not spent then
    local grant = math.min(want, budget)
    if grant <= 0 then
        return 0
    end
    redis.call('SET', KEYS[1], grant, 'PX', tonumber(ARGV[3]))
    return grant
end

local grant = math.min(want, budget - tonumber(spent))
if grant <= 0 then
    return 0
end

-- Deliberately no PEXPIRE here: the window ends a fixed time after it opened. Extending it
-- on every claim would make a busy cluster hold one window open indefinitely.
redis.call('INCRBY', KEYS[1], grant)
return grant
