-- velocity.lua — atomic velocity record+count for RedisStateStore.
--
-- Replaces the previous three-call sequence (ZREMRANGEBYSCORE + ZADD +
-- ZCARD + EXPIRE) which, under millisecond-concurrent writes to the same
-- account, could interleave such that an evicting call deleted entries
-- another caller had just added — leading to a double-count miss.
--
-- KEYS[1]  ZSET key (e.g. "velocity:ACC-123")
-- ARGV[1]  current timestamp in ms (the new entry's score)
-- ARGV[2]  window floor in ms (entries strictly older than this are evicted)
-- ARGV[3]  the new entry's member (a tx id)
-- ARGV[4]  key TTL in seconds (so dormant accounts age out)
--
-- Returns the post-operation cardinality (== number of velocity events in
-- the rolling window).
--
-- Atomic from Redis' perspective: the script runs to completion before any
-- other command on the same shard executes, eliminating the interleave.

local key      = KEYS[1]
local now_ms   = tonumber(ARGV[1])
local floor_ms = tonumber(ARGV[2])
local member   = ARGV[3]
local ttl_sec  = tonumber(ARGV[4])

-- Evict entries strictly older than the window floor.
redis.call('ZREMRANGEBYSCORE', key, '-inf', floor_ms - 1)

-- Add the new entry. Score = now_ms, member = txId.
redis.call('ZADD', key, now_ms, member)

-- Refresh the per-account TTL so the key dies if the account goes silent.
redis.call('EXPIRE', key, ttl_sec)

return tonumber(redis.call('ZCARD', key))
