# Load testing

Everything here was measured on **local Docker Compose, not AWS** — see
[ADR 0004](adr/0004-run-on-local-docker-compose-until-aws-credentials-exist.md) for why, and
`load/results/*/run-context.md` for the exact conditions of each run.

## The rule

A number is publishable only if there is a file under `load/results/` that produced it, and a
`run-context.md` beside it saying what the machine was doing at the time. The runner writes that
context from the live environment — container limits read out of Docker, the target read from the
base URL — so it cannot drift from what actually ran.

## Running a scenario

```bash
make up                              # three replicas behind the edge proxy
./scripts/run-load.sh smoke          # k6 scenarios
./scripts/run-load.sh flash
./scripts/run-sse-load.sh 5000       # the Server-Sent Events scenario
```

## Why the SSE scenario is not k6

k6 drives every HTTP scenario here. It cannot drive the SSE one: k6 has no streaming response
body, so it cannot hold an event stream open and read frames from it. Polling in a loop would
measure a different system.

So that one scenario is a small Node program (`load/sse/`), written in TypeScript and typechecked
in CI alongside the k6 scripts, run through `scripts/run-sse-load.sh` so it produces the same
result directory shape as everything else. This is a limitation of the tool, recorded rather than
papered over.

## Host tuning

Without this, a load run measures the generator's limits rather than the service's, and the
failures look like the service refusing connections.

### File descriptors

Each connection costs one descriptor at the generator and **two at the proxy** — client socket and
upstream socket. Five thousand streams is therefore ten thousand descriptors in nginx alone.

```bash
ulimit -n                       # per-shell soft limit; 1,048,576 on the machine used here
ulimit -n 200000                # raise it for this shell if it is lower
```

Permanent, in `/etc/security/limits.conf`:

```
*  soft  nofile  200000
*  hard  nofile  200000
```

Containers have their own limits, set in `docker-compose.yml`: the edge proxy is given
`nofile: 65535`, and `nginx-main.conf` sets `worker_rlimit_nofile 65535` and
`worker_connections 16384`. The image defaults are 1024 and 512, which fail at roughly 500
streams.

**Symptom when this is wrong:** `EMFILE: too many open files` at the generator, or connections
refused at around 500 or 1,000 with nothing in the application log. The application is fine; the
proxy ran out of descriptors.

### Ephemeral ports

Every outbound connection consumes a source port. The generator opens N to the proxy and the proxy
opens up to N upstream, from the same host.

```bash
cat /proc/sys/net/ipv4/ip_local_port_range     # 32768 60999 by default: about 28,000 ports
sudo sysctl -w net.ipv4.ip_local_port_range="10240 65535"   # about 55,000
```

28,000 is enough for the 5,000-connection scenario and is not enough for 20,000. Widening the
range is the first thing to do before going higher.

**Symptom when this is wrong:** `EADDRNOTAVAIL`, or connections that stall during the ramp and
then succeed in bursts as ports are recycled.

### TIME_WAIT

A closed connection holds its port in `TIME_WAIT` for 60 seconds. Repeated runs exhaust the range
even when a single run does not.

```bash
sudo sysctl -w net.ipv4.tcp_tw_reuse=1          # reuse TIME_WAIT sockets for outbound connections
ss -s                                            # count them: look at the timewait figure
```

`tcp_tw_reuse` is safe for outbound connections and is the right setting on a load generator.
`tcp_tw_recycle` no longer exists in modern kernels and should not be looked for; it broke clients
behind NAT and was removed.

### Accept backlog

Five thousand connections arriving over twenty seconds is 250 per second of connection setup.

```bash
cat /proc/sys/net/core/somaxconn                 # 4096 on the machine used here
sudo sysctl -w net.core.somaxconn=8192
sudo sysctl -w net.ipv4.tcp_max_syn_backlog=8192
```

The application's own backlog is `server.tomcat.accept-count`, set to 500.

**Symptom when this is wrong:** connection timeouts during the ramp while the service is idle, and
a rising `ListenOverflows` in `netstat -s`.

### conntrack

Relevant when the host runs a firewall or Docker's iptables rules track every connection.

```bash
sudo sysctl net.netfilter.nf_conntrack_max       # often 262144
sudo dmesg | grep -i conntrack                   # "table full, dropping packet" is the symptom
```

Loopback traffic on this host is not conntracked, so the 5,000-connection run does not touch it.
It is listed because the same run against a cloud deployment would, and the failure — packets
silently dropped, connections hanging rather than failing — is unpleasant to diagnose from the
application side.

### Under WSL2

The measurements here were taken under WSL2, which has its own kernel and its own limits. `sysctl`
changes inside the distribution do apply, but do not persist across a WSL restart unless written
to `/etc/sysctl.conf` **and** `wsl.conf` is configured to apply it. The relevant values at the time
of each run are recorded in that run's `run-context.md`, read from `/proc` rather than assumed.

## What gets measured, and what is deliberately included

| Measurement | From | To | Includes |
|---|---|---|---|
| Hold latency | request sent | response received | queueing, lock wait, commit |
| Checkout latency | request sent | response received | the fake gateway's configured latency, stated with the result |
| **Propagation** | **the PostgreSQL commit** | **the frame arriving at the client** | **the 50 ms coalescing window and the outbox relay tick** |
| Memory per connection | resident set before | resident set at peak | everything else that grew during the window; an upper bound |

Propagation deliberately starts at the commit rather than at the fan-out. Measuring from when the
relay picked the row up would quietly exclude the relay's own queueing, which is time a buyer
waits, and would make the published percentile smaller than the truth.

Memory per connection is reported as an **upper bound** for the same reason in reverse: it
attributes all growth during the window to the connections, including JIT compilation and heap
growth caused by the seat-change traffic. Being precise about it would need a heap dump per
connection; being honest about it needs one sentence.

## Reading a result directory

```
load/results/2026-09-20/sse-5000-203914/
├── run-context.md        commit, host, container limits, kernel tuning, caveats
├── sse-result.json       the measurement
├── memory-samples.txt    resident set sampled every five seconds
└── stdout.log            everything the generator printed
```

`load/RESULTS_SUMMARY.md` maps every number published in `README.md` back to one of these, and
states which targets were missed.
