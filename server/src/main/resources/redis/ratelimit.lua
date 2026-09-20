-- Token-bucket rate limiting, atomic.
--
-- Atomic because the obvious implementation - read the count, decide, write it back - lets a
-- burst of concurrent requests all read the same value and all decide they are allowed. That is
-- precisely the case a rate limiter exists for, so a check-then-act version fails exactly when it
-- matters.
--
-- KEYS[1] bucket hash: tokens, ts
--
-- ARGV[1] now, millis
-- ARGV[2] refill rate, tokens per second
-- ARGV[3] burst, the most the bucket can hold
-- ARGV[4] key ttl, millis
--
-- Returns { allowed, tokensRemaining, retryAfterMillis }

local now   = tonumber(ARGV[1])
local rate  = tonumber(ARGV[2])
local burst = tonumber(ARGV[3])
local ttl   = tonumber(ARGV[4])

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts     = tonumber(bucket[2])

-- A first-time caller starts with a full bucket, so an ordinary user's first request is never
-- refused. Starting empty would rate-limit everybody's very first action.
if tokens == nil then tokens = burst end
if ts == nil then ts = now end

local elapsed = now - ts
if elapsed < 0 then elapsed = 0 end

tokens = math.min(burst, tokens + (elapsed * rate / 1000.0))

local allowed = 0
local retryAfter = 0

if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  -- How long until one token exists. Returned so the caller can put a real number in
  -- Retry-After instead of a guess; a client told to retry in one second when it will be refused
  -- for four just produces three more refusals.
  retryAfter = math.ceil((1 - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', KEYS[1], ttl)

return { allowed, tostring(tokens), retryAfter }
