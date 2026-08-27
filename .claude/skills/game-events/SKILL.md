---
name: game-events
description: The GameEvent contract for the Faultory shop-floor simulation — which interface to implement, where and when to publish, and the counterName/counterKeys shape that authored encounters and statistics read. Use whenever adding or changing behaviour on the factory floor (production, QA verdicts, conveyor transitions, worker objectives, interactions, security, economy), whenever touching core.encounters or any class in core.shop.systems, and when asked why something is missing from statistics or never fires an encounter.
---

# Adding a game event

Everything that happens on the factory floor publishes a `GameEvent`, whether or not anything is
listening. The feed is the single source achievements, storytelling beats, in-game encounters and
statistics all read from, so an event missing today is a feature that cannot be built tomorrow
without reopening the simulation.

## The publishing channel

Systems publish through `ShopFloorEvents`, which stamps the current level on the event and hands it
to the `EventBus`. It is never null and never optional:

```kotlin
events.publish { SomethingHappenedEvent(…, levelId = it) }
```

Its default instance publishes into a bus nobody listens to, which is what tests and headless
simulation get for free. Never reintroduce a nullable `EventBus` or a `levelIdProvider` into a
system — take `ShopFloorEvents`.

## The four steps

1. **Declare it.** Add the event to `core.encounters.GameEvent`, implementing `GameEvent` plus
   whichever of `ProductEvent` / `ActorEvent` / `MachineEvent` / `EconomyEvent` describe it, with a
   `counterName` and a `levelId`.
2. **Publish from the owner, at the moment of change.** Publish from the system that owns the
   behaviour, at the moment the state actually changes — not where the change was requested. A
   handover is reported when the payload moves, not when the interaction is asked for; a verdict is
   reported on the frame it is reached, not in a disposition step that retries.
3. **Publish exactly once per occurrence.** Where two actors tick the same event (interactions),
   only the initiator reports. Where a step is retried until it succeeds, publish on the transition.
4. **Generalise instead of twinning.** Prefer one event carrying a discriminator over an
   actor-specific twin — `ProductPickedUpEvent` carries the picker's `workerRole` rather than there
   being one event per role.

## Counters come for free

`EncounterEngine` knows nothing about individual event types. Each event names its own counters via
`counterName` and `counterKeys(scope)`. The default gives an all-levels and a per-level total; an
override adds breakdown keys in the `<counterName>.<scope>.<suffix>` shape that
`Condition.CounterAtLeast` reads. A new event therefore starts accumulating statistics and becomes
usable by authored content the moment it is published.

`counterName` values and the legacy `shipped.<quality>.<scope>` keys are persisted in
`encounters.json`. Treat them like JSON field names and do not rename them casually.

## What is deliberately not published

Continuous motion. A product advancing one belt tile or a worker crossing one tile emits nothing.
State transitions do — entering and leaving a belt, being blocked, falling, standing up, changing
hands, being shipped.
