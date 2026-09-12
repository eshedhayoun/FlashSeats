local budget_key = KEYS[1]
local member = ARGV[1]
local expires_at = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

redis.call('ZREMRANGEBYSCORE', budget_key, '-inf', now)

if redis.call('ZSCORE', budget_key, member) == false then
    return 0
end

redis.call('ZADD', budget_key, expires_at, member)
return 1
