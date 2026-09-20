-- Join the queue, or report the position already held.
--
-- Atomic because the alternative - check membership, then add - lets a double-tap on a phone
-- create two entries, or worse, move somebody backwards. A buyer's arrival score is written once
-- and never changed: refreshing the page, reconnecting, or opening a second tab must not cost
-- them their place, and must not gain them one either.
--
-- KEYS[1] queue sorted set
-- KEYS[2] admitted hash
--
-- ARGV[1] user reference
-- ARGV[2] arrival time, millis
-- ARGV[3] queue ttl, millis
--
-- Returns { state, score, rank, queueLength } where state is one of:
--   "admitted" - already through; the caller should issue a token
--   "joined"   - added just now
--   "queued"   - already waiting, position unchanged

local userRef = ARGV[1]
local now     = tonumber(ARGV[2])
local ttl     = tonumber(ARGV[3])

if redis.call('HEXISTS', KEYS[2], userRef) == 1 then
  return { 'admitted', 0, 0, redis.call('ZCARD', KEYS[1]) }
end

local existing = redis.call('ZSCORE', KEYS[1], userRef)
local state

if existing then
  state = 'queued'
else
  redis.call('ZADD', KEYS[1], now, userRef)
  redis.call('PEXPIRE', KEYS[1], ttl)
  existing = now
  state = 'joined'
end

local rank = redis.call('ZRANK', KEYS[1], userRef)
return { state, tostring(existing), rank, redis.call('ZCARD', KEYS[1]) }
