# <date> — <short name>

- **Type:** game day | real incident
- **Severity:** <what a buyer experienced>
- **Duration:** <first symptom to resolution>
- **Author:** Lexie Li

## What happened

Two or three sentences. What a buyer experienced, not what the system did internally.

## Prediction made beforehand

For a game day, what was written down before the scenario ran. Copied verbatim, including the
parts that turned out wrong — an exercise whose prediction is edited afterwards has proved nothing.

## Timeline

All times UTC. Every row is either something that happened or something somebody did.

| Time | Event |
|---|---|
| | |

## Detection

What made it visible, and how long that took. If it was noticed by a person looking at the right
graph rather than by an alert, say so: that is a finding about the alerts.

## Root cause

The mechanism, not the trigger. "Redis was restarted" is a trigger; "the admission bucket refills
from a cold start to a full burst, so a restart admits a flood" is a cause.

## What made it worse, and what made it better

Often more useful than the cause. Anything that delayed detection, obscured the signal, or made
the first fix the wrong one.

## Impact

Measured, not estimated. Requests affected, buyers affected, seats affected, money involved.
Where a number is unavailable, say it is unavailable rather than guessing.

## What was wrong in our understanding

The part most postmortems skip. Which document, dashboard or assumption said something that turned
out not to be true, and what it says now.

## Actions

| # | Action | Kind | Owner | Status |
|---|---|---|---|---|
| 1 | | prevent / detect / mitigate | | |

Every action names which of the three it is. A list of only "prevent" actions usually means nobody
asked how the next unknown failure will be noticed.

## Evidence

Dashboard screenshots, the raw result directory, log excerpts, the commit that fixed it.
