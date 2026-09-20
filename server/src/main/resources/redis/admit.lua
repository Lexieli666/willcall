-- Token-bucket admission, atomic across replicas.
--
-- Every replica runs an admitter on a timer. Without coordination they would each admit a full
-- batch and the reservation core would receive N times the rate it was measured to survive. The
-- usual fix is a leader election, which adds a dependency and a failure mode where the leader is
-- dead but not yet noticed. This script replaces that with atomicity: the bucket lives in Redis,
-- the refill is computed from elapsed time inside the script, and ZPOPMIN removes the admitted
-- entries in the same atomic step. Two replicas calling it concurrently share one bucket, so the
-- combined admission rate is the configured rate no matter how many replicas exist.
--
-- KEYS[1] queue sorted set, scored by arrival time
-- KEYS[2] bucket hash: tokens, ts
-- KEYS[3] admitted hash: userRef -> admitted-at millis
--
-- ARGV[1] now, millis
-- ARGV[2] refill rate, admissions per second
-- ARGV[3] burst, the most the bucket can hold
-- ARGV[4] admitted-hash ttl, millis
-- ARGV[5] maximum to admit in this call
--
-- Returns the flat ZPOPMIN reply: member, score, member, score, ...

local now      = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local burst    = tonumber(ARGV[3])
local ttl      = tonumber(ARGV[4])
local maxBatch = tonumber(ARGV[5])

local bucket = redis.call('HMGET', KEYS[2], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts     = tonumber(bucket[2])

-- A cold bucket starts full. The alternative - starting empty - would make the first admissions
-- after a restart wait for a refill, which is exactly when the queue is longest.
if tokens == nil then tokens = burst end
if ts == nil then ts = now end

local elapsed = now - ts
if elapsed < 0 then elapsed = 0 end

tokens = math.min(burst, tokens + (elapsed * rate / 1000.0))

local available = math.floor(math.min(tokens, maxBatch))
local admitted = {}

if available > 0 then
  admitted = redis.call('ZPOPMIN', KEYS[1], available)
  local count = #admitted / 2
  tokens = tokens - count
  for i = 1, #admitted, 2 do
    redis.call('HSET', KEYS[3], admitted[i], now)
  end
  if count > 0 then
    redis.call('PEXPIRE', KEYS[3], ttl)
  end
end

redis.call('HSET', KEYS[2], 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', KEYS[2], ttl)

return admitted
