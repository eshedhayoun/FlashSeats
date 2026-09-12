local budget_key = KEYS[1]
local member = ARGV[1]
local expires_at = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local maximum = tonumber(ARGV[4])

redis.call('ZREMRANGEBYSCORE', budget_key, '-inf', now)

if redis.call('ZCARD', budget_key) >= maximum then
    return 0
end

redis.call('ZADD', budget_key, expires_at, member)
return 1
