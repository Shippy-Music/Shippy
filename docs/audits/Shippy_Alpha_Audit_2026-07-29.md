# Shippy Alpha Code, Reliability, Security, and Product Audit

**Repository:** `Shippy-Music/Shippy`  
**Branch reviewed:** `main`  
**Commit reviewed:** `1a4b04b7ad7d3f02ae71ca885859ea6e3a432f62`  
**Audit date:** 29 July 2026  
**Product stage:** Personal-use alpha

## Executive assessment

Shippy is not a superficial reskin. It has a serious canonical track model, local/provider/download/Crew abstractions, durable download jobs, playback checkpoints, lyrics caching, Last.fm integration, a relay protocol with bounded messages, and unusually candid engineering documentation. The architecture is substantially better than the pace and breadth of recent changes would normally suggest.

The main risk is **state consistency at integration boundaries**, not catastrophic security. Several independently reasonable subsystems exchange queue positions, delayed UI commands, partially resolved tracks, filesystem artifacts, or mutable Crew media state in ways that can diverge under fast interaction, process death, provider failure, or concurrent peers. These defects are precisely the ones that make an otherwise attractive app feel unreliable: the wrong queue row moves, a tap waits too long, a stale song remains visible, a timer silently disappears, or the player skips without explaining why.

For Shippy to become a dependable daily driver, the next milestone should not be another large feature wave. It should be a **behavioral hardening pass** focused on immediate playback, stable identity-based queue operations, atomic state publication, process-death recovery, bounded concurrency, and device-level performance verification.

### Overall readiness

| Area | Assessment |
|---|---|
| Product intent and architecture | Strong for an alpha |
| Local playback foundation | Mature upstream base, but new integration races remain |
| Provider playback | Functional architecture; start latency and redirect handling need work |
| Downloads | Good durable model; filesystem publication and scanning need redesign |
| Crew | Ambitious and carefully specified; concurrency and reconciliation defects remain |
| Security | Generally thoughtful; exported Android components and backup policy need release hardening |
| UI/UX | Visually coherent, but player, queue, search, and lyrics have measurable or likely jank paths |
| Automated tests | Broad JVM coverage; physical-device, instrumentation, performance, and accessibility coverage are the largest gaps |
| Public-release readiness | Not yet; suitable for controlled personal alpha after the P1 defects below are addressed |

## Scope and confidence

This was a static review through the connected GitHub repository, covering product documentation, architecture, recent Shippy changes, Android application code, the relay service, persistence, playback, queue, downloads, search, lyrics, Crew media transfer, settings, and release configuration.

The repository was **not cloned or built locally in this audit**, and no APK, physical device, emulator, real provider account, SAF document provider, two-device Crew session, or hostile network was exercised. Findings are classified as:

- **Confirmed:** The problematic behavior follows directly from the reviewed code path.
- **High-risk:** The code contains a clear race, lifecycle hazard, or unsafe assumption, but device execution is still required to observe frequency and exact symptoms.
- **Product recommendation:** A design or interaction change based on Shippy’s stated goal of being a fast, calm, daily-use music player.

## Priority definitions

- **P0:** Data loss, serious security exposure, or broadly reproducible crash. None were confirmed in this static pass.
- **P1:** Daily-driver or release blocker. Can produce wrong state, stale playback, severe latency, corrupt user expectations, or unsafe concurrency.
- **P2:** Important reliability, performance, privacy, accessibility, or maintainability issue.
- **P3:** Polish, information architecture, cleanup, or future-scale improvement.

---

# Highest-priority findings

## SH-001 · Crew authoritative empty queue is ignored

**Priority:** P1  
**Confidence:** Confirmed  
**Area:** Crew playback reconciliation

`CrewPlaybackBridge.applyCrew` contains a host-bootstrap special case and then returns whenever `crew.queue` is empty. That means an empty queue received as authoritative state is never applied locally. A joiner can retain stale playback, and removing the final Crew item can leave a song or queue visible that no longer exists in Crew state.

**Evidence**

- `app/src/main/java/org/oxycblt/auxio/shippy/crew/playback/CrewPlaybackBridge.kt`
- `applyCrew(...)`, especially the host-seed branch followed by the unconditional empty-queue return.

**Recommended correction**

Represent these as distinct states:

1. `HostNeedsInitialSeed`
2. `AuthoritativeQueue(items = emptyList())`

Only the first may preserve or publish the local queue. The second must clear the canonical local queue, stop or pause playback according to product policy, reset current index and progression, and publish the empty state to UI consumers.

**Acceptance tests**

- A joiner with an existing local queue joins a Crew whose queue is empty and ends with no current item.
- Host removes the final item; every participant reaches the same empty state.
- Host bootstrap still seeds an initial local queue exactly once.
- Reconnect to an empty checkpoint cannot resurrect an earlier queue.

---

## SH-002 · One unresolvable Crew item aborts the entire queue update

**Priority:** P1  
**Confidence:** Confirmed  
**Area:** Crew/provider resolution

Crew queue reconciliation resolves items in a loop and returns on the first `PlaybackPreparation.Failed`. A single unavailable provider track therefore prevents valid items from being installed, including potentially the current item. The result is stale local state rather than a partially useful, explicitly degraded Crew queue.

**Evidence**

- `CrewPlaybackBridge.kt`, queue-resolution loop in `applyCrew(...)`.

**Recommended correction**

- Resolve the current item first.
- Resolve immediate neighbors next.
- Represent unresolved entries as stable unavailable placeholders rather than aborting the queue.
- Retry individual entries when provider health changes.
- Surface one concise status such as “2 songs unavailable on this device,” without exposing provider plumbing unless the user asks.

**Acceptance tests**

- A 20-item Crew queue with one unavailable item still installs the other 19.
- If the unavailable item is not current, playback starts immediately.
- If the current item is unavailable, Shippy advances according to a documented rule and explains the action once.
- Provider recovery updates only the affected item without rebuilding the entire queue.

---

## SH-003 · Queue UI actions use stale adapter indices against current canonical state

**Priority:** P1  
**Confidence:** High-risk, strongly supported  
**Area:** Queue state and interaction

`QueueViewModel` maps the canonical queue to display rows asynchronously. Click, swipe, and drag actions then pass `bindingAdapterPosition` or saved positions into `PlaybackStateManager`. During the mapping interval, the display list can describe an older queue while the manager already owns a newer one. “Remove row 6” can consequently remove whichever item currently occupies canonical index 6, not the row the user touched.

The fragment also contains a comment acknowledging a fast-user race capable of consuming a bad update instruction and crashing initialization.

**Evidence**

- `app/src/main/java/org/oxycblt/auxio/playback/queue/QueueViewModel.kt`
  - `updateQueueAsync(...)`
  - `goto(...)`
  - `removeQueueDataItem(...)`
  - `moveQueueDataItems(...)`
- `QueueFragment.kt`, click handling through `bindingAdapterPosition`.
- `QueueDragCallback.kt`, swipe and drag operations by raw position.

**Recommended correction**

- Cross the UI boundary using `QueueItemId`, never position.
- Resolve the current canonical index from the ID immediately before mutation.
- Publish queue rows, active item, and update instructions as one immutable render state with a monotonically increasing generation.
- Reject a gesture whose source generation is stale instead of applying it to a different queue.

**Acceptance tests**

- Rapid skip while swiping a row never removes the wrong item.
- Provider resolution finishing during a drag cannot move another track.
- Shuffle, Crew update, or download-candidate remap during queue interaction remains identity-correct.
- Rotation and process recreation do not replay stale adapter instructions.

---

## SH-004 · Pager queue and pager command are published separately and can be mismatched

**Priority:** P1  
**Confidence:** High-risk  
**Area:** Now Playing pager

`PlaybackViewModel` asynchronously maps the queue, emits a `PagerCommand`, and separately publishes `PagerQueue`. `PlaybackPanelFragment` receives a queue snapshot, schedules work through frame callbacks, and later consumes the shared command event. Under rapid queue changes, the consumed command may belong to a newer update than the queue argument captured by the delayed callback.

This is a credible source of cover flicker, incorrect adapter update instructions, sudden jumps, or transient state desynchronization.

**Evidence**

- `PlaybackViewModel.kt`, `updatePagerQueueAsync(...)`.
- `PlaybackPanelFragment.kt`, `updatePager(...)` and `updatePagerImpl(...)`.

**Recommended correction**

Publish one immutable object:

```text
PagerRenderState(
  generation,
  queue,
  currentItemId,
  updateInstruction,
  animationIntent
)
```

The fragment should apply only the newest generation, and scrolling should resolve the current position from `currentItemId` after the list update.

**Acceptance tests**

- Ten rapid next/previous actions never show the wrong cover or jump backward.
- Shuffle during a page animation remains aligned with the canonical current item.
- Queue replacement followed immediately by removal cannot apply an instruction to the wrong list.

---

## SH-005 · Starting an album or playlist resolves the entire queue before playback begins

**Priority:** P1  
**Confidence:** Confirmed  
**Area:** Provider playback latency

`ShippyPlaybackController.playQueue` resolves every track sequentially before handing the queue to the player. A user can tap the first song and wait while Shippy negotiates URLs for an entire album or playlist. A failure in a later item can also prevent the selected track from playing.

**Evidence**

- `app/src/main/java/org/oxycblt/auxio/shippy/playback/ShippyPlaybackController.kt`
- Queue resolution and playback construction in `playQueue(...)`.

**Recommended correction**

1. Resolve and start the selected track immediately.
2. Install canonical queue metadata with unresolved candidates.
3. Prefetch the next one or two items.
4. Resolve the rest lazily as the cursor approaches.
5. Refresh expired provider URLs at use time, not at initial queue creation.

**Acceptance targets**

- Warm local/provider tap-to-audio p50 below 150 ms where the source permits it.
- Cold provider tap-to-audio p50 below 600 ms, with immediate visible pressed/loading state.
- A later unavailable track never blocks the selected track.

---

## SH-006 · Player silently skips every playback error

**Priority:** P1  
**Confidence:** Confirmed  
**Area:** Playback recovery

`ExoPlaybackStateHolder` advances after playback errors. This treats transient network failure, expired provider URL, unsupported codec, corrupt local file, and permanent unavailability as the same event. The user experiences a mysterious skip, while Shippy loses the chance to refresh a URL or retry a recoverable failure.

**Evidence**

- `app/src/main/java/org/oxycblt/auxio/playback/service/ExoPlaybackStateHolder.kt`
- Error callback that advances to the next item.

**Recommended correction**

Introduce typed failure policy:

- **Expired or rejected provider locator:** refresh candidate and retry once.
- **Transient network error:** bounded retry with short backoff while preserving current item.
- **Decoder failure:** try an alternate candidate if available.
- **Missing local/download artifact:** mark unavailable and reconcile storage.
- **Permanent failure:** show a compact error and skip only after policy is exhausted.

Record a redacted diagnostic event with track realm, candidate kind, recovery attempted, and final outcome.

---

## SH-007 · Playback position polling continues at 10 Hz while paused

**Priority:** P1  
**Confidence:** Confirmed behavior; suspected link to backward-position bug  
**Area:** UI state, battery, timeline accuracy

`PlaybackViewModel.onProgressionChanged` starts an infinite 100 ms polling loop regardless of whether playback is active. The class already contains a TODO for subtle backward movement when paused. The loop captures a `Progression` instance and repeatedly recalculates from it, creating a plausible race with newer progression snapshots while also doing unnecessary work during pause and when the player UI is not visible.

**Evidence**

- `PlaybackViewModel.kt`
- Class TODO concerning backward position on pause.
- `onProgressionChanged(...)`, including the unconditional `while (true)` and 100 ms delay.

**Recommended correction**

- Poll only while playing and while at least one visible consumer needs smooth progress.
- On pause, publish one final authoritative position and stop the ticker.
- Use a generation token so an old ticker cannot write after a newer progression arrives.
- Clamp ordinary playback ticks monotonically; allow backward movement only for a confirmed seek or track change.
- Drive the decorative waveform/ship from frame time while visible, not from a global 10 Hz state flow.

---

## SH-008 · Shuffle state mutation is delayed and can be silently discarded

**Priority:** P1  
**Confidence:** Confirmed  
**Area:** Interaction reliability

The shuffle button updates its appearance optimistically, stores a pending target, and delays the real state mutation by 120 ms. `onDestroyBinding` removes the callback and clears the pending target. Closing the player, rotating, or navigating away immediately after tapping can therefore make the control visibly respond while the command never reaches playback state.

**Evidence**

- `PlaybackPanelFragment.kt`, shuffle click handler and `SHUFFLE_COMMIT_DELAY_MS`.
- `onDestroyBinding(...)`, which removes and discards the pending callback.

**Recommended correction**

Commit the state immediately. Delay only expensive rendering or queue animation. The visual control should render from the canonical state afterward, not become a temporary second authority.

---

## SH-009 · Ambiguous genre playback opens the artist chooser

**Priority:** P1  
**Confidence:** Confirmed  
**Area:** Playback decision UI

`playFromGenreImpl` creates `PlaybackDecision.PlayFromArtist(song)` when a genre choice is required. The wrong dialog is therefore shown for ambiguous genre playback.

**Evidence**

- `PlaybackViewModel.kt`, `playFromGenreImpl(...)`.

**Correction**

Use `PlaybackDecision.PlayFromGenre(song)` and add a direct unit/UI test for both ambiguous artist and ambiguous genre flows.

---

## SH-010 · Full-player architecture already produces about 140 ms of skipped frames

**Priority:** P1  
**Confidence:** Confirmed by in-code measurement/comment  
**Area:** Performance and player architecture

`PlaybackPanelFragment.updatePager` documents roughly 140 ms of frame skipping on next/previous and uses frame-commit callbacks or double `postOnAnimation` calls to defer updates until nested bottom-sheet layout work settles. This is not a small cosmetic defect. It is a structural conflict between the desired animated player and the current nested sheet/layout architecture.

**Evidence**

- `PlaybackPanelFragment.kt`, detailed comment and workaround in `updatePager(...)`.

**Recommended correction**

Treat the expanded player as a stable, dedicated surface rather than repeatedly forcing a complex nested bottom sheet to remeasure. Preserve the mini-player transition, but move the full player into a layout whose artwork, metadata, controls, and lyrics have predictable constraints.

Add Macrobenchmark coverage for:

- next/previous
- cover swipe
- mini-player expansion/collapse
- queue open/close
- lyrics open and active-line update

Target zero frozen frames and p95 frame duration below the device refresh budget on a mid-range phone.

---

## SH-011 · Local search performs full-library filtering and normalization on the main thread

**Priority:** P1  
**Confidence:** Confirmed  
**Area:** Search performance

`SearchViewModel.search` launches in `viewModelScope` and directly calls `searchImpl`. `SearchEngineImpl.search` synchronously filters songs, albums, artists, genres, and playlists and performs Unicode normalization. No background dispatcher is used for the local scan.

This makes the “local-first” result path increasingly likely to hitch typing as the library grows.

**Evidence**

- `app/src/main/java/org/oxycblt/auxio/search/SearchViewModel.kt`, `search(...)` and `searchImpl(...)`.
- `SearchEngine.kt`, synchronous filtering and normalization.

**Recommended correction**

- Move local search to `Dispatchers.Default`.
- Debounce text input by roughly 100–150 ms while preserving immediate history display.
- Precompute normalized searchable tokens when the library index changes.
- Publish local and provider results with query generations; discard stale completions.

**Acceptance tests**

- Continuous typing in a 25,000-track library causes no main-thread frame over 32 ms on a mid-range device.
- A slower provider response for query A cannot overwrite query B.

---

## SH-012 · A partially written download can become visible to the music indexer

**Priority:** P1  
**Confidence:** High-risk, architecture-level  
**Area:** Downloads and filesystem publication

`SafDownloadStorage.createPendingDocument` creates the final display name with an audio MIME type inside the selected destination. The worker verifies and marks the artifact available later. Because the destination is also a library source, a filesystem/document-provider scan can discover the audio document before Shippy’s Room “publication gate” says it is ready.

The database gate protects Shippy’s download projection. It cannot prevent another indexer path from seeing the half-written document.

**Evidence**

- `app/src/main/java/org/oxycblt/auxio/shippy/download/SafDownloadStorage.kt`, `createPendingDocument(...)`.
- `ShippyDownloadWorker.kt`, transfer, verification, and final availability transition.

**Recommended correction**

- Write to app-private storage or a non-audio temporary document outside the indexed subtree.
- Verify size, integrity, and decodability there.
- Atomically move where supported; otherwise copy to the final document and publish only after successful close.
- Keep a durable temporary-artifact record and reconcile orphans after process death.
- Trigger one targeted library refresh only after final publication.

---

## SH-013 · Creating or inspecting downloads recursively scans the full destination

**Priority:** P1  
**Confidence:** Confirmed  
**Area:** SAF performance

`createPendingDocument` calls `inspectDestination`, which recursively enumerates the selected tree. Settings also reconcile the destination on resume. This makes a simple download request or settings visit proportional to every document in the destination, including potentially slow cloud-backed SAF providers.

**Evidence**

- `SafDownloadStorage.kt`, `createPendingDocument(...)`, `inspectDestination(...)`, and recursive scan implementation.
- `RootPreferenceFragment.kt`, destination refresh on resume.
- `DownloadDestinationReconciler.kt`, reconciliation beginning with destination inspection.

**Recommended correction**

Separate operations:

- **Cheap health check:** retained URI permission, root existence, and a bounded write probe.
- **Incremental managed-artifact lookup:** use persisted document URIs.
- **Full reconciliation/import:** explicit maintenance action or low-priority periodic work, with progress and cancellation.

Never perform an unbounded recursive scan on a tap-to-download path.

---

## SH-014 · Download removal deletes the file before recording a durable removal state

**Priority:** P1  
**Confidence:** Confirmed ordering hazard  
**Area:** Download durability

`DownloadWorkCoordinator.remove` deletes storage first and applies the reducer/database event afterward. A process crash, provider exception, or database failure between those operations can leave a persisted `AVAILABLE` job pointing to a missing artifact.

**Recommended correction**

Use a two-phase durable removal:

1. Persist `REMOVING` or a tombstone.
2. Cancel work and delete the artifact.
3. Persist `REMOVED`.
4. Reconcile unfinished removals at startup.

This same state machine should handle permission revocation and externally deleted files.

---

## SH-015 · Provider and download HTTP clients reject all redirects

**Priority:** P1  
**Confidence:** Confirmed  
**Area:** Network compatibility

Both the provider transport and download connection disable redirects. Many legitimate media/CDN endpoints redirect to a signed regional host or refreshed object URL. Shippy will classify those responses as unavailable even when the source is valid.

**Evidence**

- `ProviderHttpTransport.kt`, `instanceFollowRedirects = false`.
- `DownloadTransferEngine.kt`, redirect-disabled connection and status handling.

**Recommended correction**

Implement bounded manual redirects:

- maximum three to five hops
- HTTPS only, with no downgrade
- revalidate host and URL at each hop
- strip authorization or sensitive headers when origin changes
- preserve range semantics only when safe
- log redirect outcome without URLs containing tokens

---

## SH-016 · Crew media receiver and transfer controller mutate shared state without a single owner

**Priority:** P1  
**Confidence:** High-risk  
**Area:** Crew concurrency

Crew peer traffic is collected by separate coroutines, while `CrewMediaReceiver` and `CrewMediaTransferController` maintain mutable maps, counters, and transfer state without an actor, mutex, or documented single-thread confinement. Concurrent peers can race transfer creation, cancellation, byte accounting, chunk acceptance, and cleanup.

**Evidence**

- `CrewMediaReceiver.kt`, mutable transfer map and counters.
- `CrewMediaTransferController.kt`, mutable outbound/inbound transfer maps.
- `CrewMediaSessionRouter.kt` and `CrewSessionEngine.kt`, independent peer/inbound collection paths.

**Recommended correction**

Use a single-owner coroutine actor for all media-transfer state, or one mutex that covers each complete state transition. Do not lock only individual map operations while counters and lifecycle actions remain outside the critical section.

**Acceptance tests**

- Two peers start, cancel, resume, and complete transfers concurrently.
- Duplicate and out-of-order chunks never produce negative counters or leaked state.
- Peer disconnect during completion produces exactly one terminal event.
- Fuzzed frame ordering preserves the configured memory bound.

---

## SH-017 · Crew media transfer retains and copies too much payload data in memory

**Priority:** P1  
**Confidence:** Confirmed design cost  
**Area:** Memory and GC

The transfer path uses bounded object sizes, which is good, but still retains full payload bytes, creates chunk copies, stores chunk lists, and allocates a final assembled object. With concurrent transfers and fanout, an 8 MB media object can consume several times that amount and cause GC pauses or process pressure.

**Recommended correction**

- Stream chunks through pooled buffers.
- Hash incrementally.
- Store inbound content in an app-private temporary file instead of a list of byte arrays.
- Apply explicit per-peer and global byte budgets.
- Resume by offset/index without repeatedly allocating `drop(...)` lists.
- Expose backpressure rather than accepting every offered object into memory.

---

## SH-018 · Relay uses one global in-flight byte budget

**Priority:** P2  
**Confidence:** Confirmed  
**Area:** Relay fairness and availability

The relay server increments one `inflight` counter for all clients and refuses sends when that global budget is exhausted. A slow or abusive route can therefore cause unrelated sessions to fail delivery.

**Evidence**

- `relay/src/server.js`, global `inflight` variable and `send(...)`.

**Recommended correction**

Use layered budgets:

- per WebSocket client
- per route
- per session
- global emergency ceiling

Close or throttle the offending route instead of returning delivery failure to unrelated users. Emit counters for drops, buffered bytes, and disconnect reason.

---

## SH-019 · TURN credential endpoint has no request rate limit

**Priority:** P2  
**Confidence:** Confirmed  
**Area:** Relay abuse resistance

WebSocket messages have a per-connection rate limit, but `POST /v1/ice` can issue TURN credentials whenever valid session identifiers are supplied and has no local token bucket. Anyone who obtains or guesses an active invite tuple can repeatedly request credentials and consume TURN resources.

**Evidence**

- `relay/src/server.js`, `/v1/ice` handler and separate WebSocket rate-limit logic.

**Recommended correction**

Add rate limits by IP, session, and invite; apply a low burst and short refill period; place a reverse-proxy limit in front of the Node service; and record credential-issuance metrics without logging secrets.

---

## SH-020 · Composite filesystem uses an unbounded channel

**Priority:** P2  
**Confidence:** Confirmed  
**Area:** Indexing and memory

`CompositeFS` uses `Channel.UNLIMITED`. A producer that outpaces its consumer can accumulate arbitrary pending items during a large or slow filesystem exploration.

**Recommended correction**

Use a bounded channel, propagate cancellation promptly, and make producer backpressure explicit. Consider safe concurrent exploration of primary and secondary sources with deterministic merge order if startup latency warrants it.

---

# Additional reliability and correctness findings

## SH-021 · Crew playback exceptions are swallowed without diagnostics

**Priority:** P2  
**Confidence:** Confirmed

A broad exception handler in Crew playback reconciliation performs no visible recovery and no diagnostic logging. This turns an internal defect into stale state with no useful evidence.

**Correction:** Catch expected failure types, rethrow cancellation, log a redacted structured event, transition to an explicit recoverable Crew state, and provide retry/end-session actions where appropriate.

---

## SH-022 · Optional crossfade keeps two ExoPlayer instances alive

**Priority:** P2  
**Confidence:** Confirmed design choice; performance impact requires measurement

The playback holder creates primary and standby players for crossfade. This increases decoder, native-memory, and initialization pressure even when crossfade is disabled or unavailable.

**Correction:** Instantiate the standby player lazily when crossfade is enabled and a next item is eligible; release it after inactivity or under memory pressure. Measure startup time, resident memory, and decoder contention on low- and mid-range devices.

---

## SH-023 · Search history records provider selections before playback succeeds

**Priority:** P2  
**Confidence:** Confirmed

`SearchViewModel.playProviderTrack` records the selection before provider resolution/playback returns. A dead or unplayable result is therefore promoted into history as though it worked.

**Correction:** Record successful playback after `PlaybackStartResult.Started`. If preserving user intent is useful, store failed attempts separately and suppress repeated broken results until provider health changes.

---

## SH-024 · Sleep timer is entirely in memory

**Priority:** P2  
**Confidence:** Confirmed

The timer mode, deadline, and finish-current item are held in `SleepTimerController`. Releasing the service resets the policy and cancels its handler callback. Process or service death silently cancels the user’s intention.

**Correction:** Persist timer mode and wall-clock deadline, restore it at playback-service startup, and validate against elapsed/wall-clock changes. For finish-current mode, persist the stable queue-item/track identity and clearly define behavior if the queue is replaced.

---

## SH-025 · Last.fm backlog flush processes only one batch per trigger

**Priority:** P3  
**Confidence:** Confirmed behavior

The scrobble outbox reads at most 50 entries and flushes one batch. A large offline backlog drains only across later attach/playback triggers.

**Correction:** Drain a bounded number of batches per run with cancellation and exponential backoff. Keep the existing serialized delivery mutex.

---

## SH-026 · Player action state observes every download job for one current track

**Priority:** P2  
**Confidence:** Confirmed

`PlayerActionsViewModel` combines `downloads.observeAll()` and scans for the latest job matching the current track. Every download update rebuilds action state from the full job list.

**Correction:** Add a DAO/repository query such as `observeLatestForTrack(trackId)` and index it appropriately.

---

## SH-027 · Saving liked and playlist destinations is not one atomic operation

**Priority:** P2  
**Confidence:** Confirmed ordering

The player action writes liked state and playlist memberships in two repository calls. A failure or cancellation between them can leave only half of the user’s confirmed dialog state applied.

**Correction:** Add one transactional DAO/repository operation that upserts metadata, liked state, and playlist memberships atomically.

---

## SH-028 · Download reconciliation applies job and relationship repairs separately

**Priority:** P2  
**Confidence:** Confirmed ordering hazard

Reconciliation iterates missing jobs and relationship repairs as separate writes. Process death can leave the Downloads projection inconsistent with job state.

**Correction:** Execute the reconciliation plan inside a Room transaction, or persist a durable reconciliation generation and resume it idempotently.

---

## SH-029 · MainActivity marks an incoming intent consumed before successful classification

**Priority:** P3  
**Confidence:** Confirmed low-impact lifecycle issue

Incoming intents are tagged as consumed before all validation/classification succeeds. A malformed or incomplete VIEW intent can therefore be suppressed on recreation without an explicit invalid-link result.

**Correction:** Mark handled only after successful dispatch, or persist a terminal `HandledInvalid(reason)` state and show one concise error.

---

# Security and privacy hardening

## SH-030 · Exported media browser accepts every client

**Priority:** P2  
**Confidence:** Confirmed  
**Area:** Local privacy and component exposure

`AuxioService` is exported as a media browser. `onGetRoot` ignores `clientPackageName` and `clientUid` and always returns the music root. Any installed application can attempt to browse local library metadata and interact with the media session.

**Evidence**

- `app/src/main/AndroidManifest.xml`, exported `AuxioService`.
- `AuxioService.kt`, unconditional `onGetRoot(...)`.

**Recommended correction**

Validate clients using `MediaSessionManager`/`RemoteUserInfo`, trusted system status, signature relationship, and an explicit compatibility allowlist for Android Auto or known system clients. Return no root for untrusted callers. Test automotive and assistant integrations before restricting production behavior.

---

## SH-031 · Exported cover provider has no caller gate

**Priority:** P2  
**Confidence:** Confirmed

`CoverProvider` is exported and exposes artwork by URI without a permission check. The IDs may be opaque, but an app that obtains or enumerates valid URIs can read local or stored artwork.

**Correction:** Prefer a non-exported provider with per-URI grants, or require a signature-level permission where system integration permits it. Verify notification, widget, media-browser, and Android Auto artwork paths after the change.

---

## SH-032 · Backup policy is broader than the app’s durable-state design

**Priority:** P2  
**Confidence:** Confirmed

The app allows backup, while backup and extraction rules exclude only the upstream music cache. This can include transient Crew/session state, work metadata, encrypted credential envelopes, provider settings, and internal databases that may not be meaningful or restorable on another device.

Last.fm secrets are encrypted with Android Keystore, which prevents a straightforward raw-secret leak, but restoring the encrypted envelope without its device key can force a confusing sign-out.

**Correction:** Define explicit includes for intended user-owned durable data and explicit excludes for:

- encrypted credential envelopes
- Crew rejoin/session secrets and checkpoints where unsafe
- caches and transient provider state
- WorkManager internals
- temporary downloads
- diagnostics

Document what restoration preserves.

---

## SH-033 · Exported media-button receiver trusts broad delivery

**Priority:** P2  
**Confidence:** Confirmed hardening gap

The exported `MediaButtonReceiver` starts the foreground playback service whenever a queue exists. The code itself contains TODOs and warns about foreground-service timing crashes. It does not visibly enforce the expected action and key-event shape before forwarding.

**Correction:** Validate `ACTION_MEDIA_BUTTON`, parse and validate the key event, ignore malformed/replayed input, and prefer normal MediaSession handling where possible. Restrict export or permissions if compatibility allows.

---

## SH-034 · Custom-scheme track links are not verified App Links

**Priority:** P3  
**Confidence:** Confirmed product/security property

The bounded payload and checksum are useful for corruption detection, and the resolver still treats fields as untrusted. However, `shippy://track` can be claimed by another app and may not be linkified consistently by messaging clients. Its embedded payload also produces long URLs.

**Correction:** Use a verified HTTPS App Link as the primary format, with a compact opaque share token or compact signed payload. Retain the custom scheme only as a fallback for personal builds. A checksum should not be described as authentication.

---

## SH-035 · Application identity remains `org.oxycblt.auxio`

**Priority:** P3 before public release  
**Confidence:** Confirmed

The namespace and application ID still use the upstream Auxio identity. This is acceptable during private development, but public distribution should decide identity before users accumulate backups, links, widgets, preferences, and installed builds.

**Correction:** Choose the final package/authority identity before public alpha. Treat migration as a deliberate release event because changing the application ID produces a new Android app installation.

---

# Persistence, build, and validation

## SH-036 · Room schema export is disabled despite frequent migrations

**Priority:** P2  
**Confidence:** Confirmed

`ShippyDatabase` is at version 10 with handwritten migrations, but `exportSchema = false`. This removes a useful audit trail and makes robust migration testing harder.

**Correction:** Enable schema export, commit versioned schemas, and add `MigrationTestHelper` coverage for every adjacent migration plus representative long paths such as 1→10 and 5→10. Validate preserved playlists, download artifacts, Crew checkpoints, playback checkpoints, lyrics, and saved provider entities.

---

## SH-037 · CI does not exercise the riskier device and release paths

**Priority:** P1 release gate  
**Confidence:** Confirmed from workflow and project status

The Android workflow performs formatting, JVM tests, lint, and debug assembly. The project documentation reports extensive JVM tests but no physical-device verification for the current alpha. That leaves the most failure-prone paths outside automation: SAF, real MediaSession behavior, WorkManager foreground execution, process death, WebRTC/Crew, R8, frame timing, accessibility, and OEM layout behavior.

**Recommended gates**

- Debug and release assembly with R8 smoke install.
- Emulator connected tests for navigation, queue identity operations, process recreation, and Room migration.
- Macrobenchmarks and Baseline Profile generation.
- Two-device scripted Crew smoke test on LAN and relay.
- SAF matrix using at least local DocumentsUI plus one slow/cloud-like provider.
- Network fault tests: redirect, timeout, offline, expired URL, partial response, rate limit.
- TalkBack, large text, RTL, reduced-motion, and keyboard/focus tests.
- One mid-range physical Android device as the daily performance baseline.

---

## SH-038 · UI dependency stack combines old pinned components with an alpha Material release

**Priority:** P2  
**Confidence:** Confirmed configuration; exact regressions require matrix testing

The build pins an older Fragment, RecyclerView, and ViewPager2 due known behavior while using an alpha Material release. The player also depends on patched/backported Material components. This increases the probability that fixes in one layer destabilize another, especially around predictive back, nested scrolling, bottom sheets, and pager layout.

**Correction:** Build a small compatibility matrix, isolate custom patches behind narrow adapters, prefer stable Material for public builds, and remove dependency pins only with dedicated regression tests. The long-term fix should reduce reliance on fragile nested-sheet behavior rather than permanently freezing the entire UI stack.

---

## SH-039 · Known user-visible TODOs live only as comments

**Priority:** P2 process  
**Confidence:** Confirmed

The code contains acknowledged problems involving pause-position movement, play-button flicker, playback accessibility, RecyclerView behavior, Material ripple behavior, and media-button service handling. The repository currently does not expose a corresponding triaged issue backlog.

**Correction:** Convert each user-visible TODO/FIXME into a tracked issue with reproduction steps, expected behavior, affected devices, owner, and an acceptance test. Comments are useful context; they are not prioritization.

---

# UI, UX, and “Apple-like” product direction

“Apple-like” should describe **behavioral quality**, not a collection of glossy cards and spring animations. For Shippy, it should mean that taps are immediate, state never lies, transitions preserve spatial context, failures are quiet but understandable, and advanced freedom remains available without leaking implementation details into everyday use.

## SH-040 · Lyrics re-render the entire document on every active-line change

**Priority:** P2  
**Confidence:** Confirmed

The full lyrics surface rebuilds a complete `SpannableStringBuilder`, allocates a clickable span for every line, relays out the entire text, and smooth-scrolls whenever the active line changes. Long lyrics can create allocation/layout pressure. More importantly, the forced smooth-scroll will fight a user who manually scrolls ahead.

**Correction**

- Use a stable line list or RecyclerView with item IDs and payload updates for active/inactive state.
- Cache parsed/static line data.
- Enter a temporary manual-scroll mode when the user scrolls; pause auto-follow and show a small “Return to current lyric” control.
- Resume following only after the user explicitly recenters or after a conservative idle rule.
- Announce only the current line to accessibility services, not the whole lyrics body on each change.
- Respect reduced motion and avoid compulsory smooth scrolling.

**Visual direction**

- Increase inactive-line contrast enough to remain readable.
- Use artwork-derived color subtly, without muddying text.
- Keep the active line prominent through scale/weight and spacing rather than excessive glow.
- Make tap-to-seek discoverable with a brief first-use hint, not permanent instructional clutter.

---

## SH-041 · Home is a scroll view containing several non-scrolling RecyclerViews

**Priority:** P2 as content grows  
**Confidence:** Confirmed

This is acceptable while each section is tiny, but it eagerly measures/binds multiple lists and will scale poorly as recommendations, pinned collections, downloads, Last.fm rows, and Crew state expand.

**Correction:** Use one top-level RecyclerView/ConcatAdapter with section models, stable IDs, explicit item limits, and “See all” destinations. This also makes skeleton loading, diffing, scroll restoration, and accessibility order more predictable.

---

## SH-042 · Settings mix ordinary preferences with service plumbing

**Priority:** P2 product clarity  
**Confidence:** Product recommendation grounded in current hierarchy

The root settings screen mixes Appearance, Personalization, Content, Audio, Last.fm, streaming provider configuration, Crew relay details, library folders, download destination, reindex, and rescan. Technical relay configuration appears alongside ordinary daily settings, while Last.fm and providers are structured differently.

**Recommended hierarchy**

1. **Appearance**
2. **Playback & Audio**
3. **Library & Downloads**
4. **Services**
   - Streaming providers
   - Last.fm
   - Lyrics source status
5. **Crew**
   - Profile and permissions
   - Media sharing
6. **Advanced & Diagnostics**
   - Relay endpoint
   - Reindex/rescan
   - Logs and provider health

Use plain-language distinctions between “Refresh library metadata,” “Scan folders again,” and “Repair downloads.” Keep provider/relay freedom, but place it behind progressive disclosure.

---

## SH-043 · Player chrome competes with the music

**Priority:** P3 polish  
**Confidence:** Product recommendation

The full player includes a centered “Playback” toolbar title, album/context subtitle, primary metadata, several icon actions, waveform, and queue controls. The literal title adds little while consuming visual hierarchy.

**Direction**

- Remove or de-emphasize the generic “Playback” title.
- Let artwork, title, and artist define the surface.
- Show context only when useful: album, playlist, Crew, or search source.
- Keep save/download/queue actions visually stable and render their state from one authoritative model.
- Avoid changing icon meaning without a clear state transition or label.

---

## SH-044 · Crew should communicate people and shared intent, not transport mechanics

**Priority:** P2 product recommendation

Crew’s internals are sophisticated, but the primary experience should remain human:

- Persistent compact status: connected, reconnecting, or local-only.
- Member avatars/names and a clear coordinator when relevant.
- Microcopy such as “Kashish added this” or “Queue updated by Rudra,” rather than relay/session terminology.
- One clear media-sharing permission explanation.
- Graceful unavailable-item markers per device.
- Reactions capped and pooled, with reduced-motion support.
- Technical relay and TURN status only under diagnostics.

---

## SH-045 · Tiny ship on the waveform is viable if it remains decoration, not control complexity

**Priority:** P3 signature polish  
**Confidence:** Product recommendation tailored to Shippy

A small ship riding the progress wave can become a distinctive Shippy motif without turning the player into a theme-park dashboard.

**Implementation constraints**

- Keep the normal seekbar as the actual accessible control and hit target.
- Render the ship as the visual thumb/marker, with no separate accessibility node.
- Interpolate position continuously from the player clock while the surface is visible; do not move it in coarse 100 ms jumps.
- Freeze naturally on pause and settle cleanly after seek.
- Reduce or remove bobbing when system animation scale is zero.
- Scale it conservatively so it does not obscure the waveform or timestamp.
- Use a simplified vector silhouette that remains legible at small sizes.

The signature detail should reward attention, not demand it.

---

# Positive engineering findings

The audit found several areas worth preserving:

1. **Canonical domain modeling:** Local, provider, download, and Crew tracks are being unified deliberately instead of duplicated into unrelated player paths.
2. **Bounded input handling:** Provider responses, relay frames, share links, and media objects generally use explicit size limits.
3. **Crew authority design:** The reducer and snapshot work show serious thought about publisher identity, coordinator authority, sequence gaps, replay bounds, and election state.
4. **Relay registry fundamentals:** Resume tokens are verified through SHA-256 and timing-safe comparison; sessions, joins, routes, and payloads are bounded.
5. **Last.fm credential handling:** API credentials are encrypted with Android Keystore rather than stored as plaintext.
6. **Last.fm scrobble policy:** The outbox, serialized delivery, and seek-resistant listened-time policy are sensible foundations.
7. **Download state machine:** The durable job model, candidate records, verification metadata, WorkManager integration, and reconciliation concept are substantially better than a simple “download URL to file” implementation.
8. **Cancellation awareness:** Several network and coroutine paths explicitly preserve cancellation instead of converting it into generic failure.
9. **Honest project documentation:** The status and device-handoff files accurately distinguish authored tests from compiled/device-verified behavior.
10. **UI intent:** Stable navigation, local-first search, synced lyrics, persistent playback, unified actions, and Crew are coherent parts of one product rather than random feature accumulation.

---

# Recommended execution order

## Phase 1 · Make state impossible to lie about

1. Replace queue positions with stable IDs across click, swipe, drag, pager, and Crew boundaries.
2. Publish pager/list state atomically with generations.
3. Fix Crew empty queue and partial-resolution behavior.
4. Commit shuffle immediately and stop stale tickers.
5. Fix the genre chooser bug and add focused regression tests.
6. Add typed playback failure recovery instead of unconditional skip.

**Exit condition:** Fast interaction cannot mutate the wrong item, and UI state always converges to canonical playback state.

## Phase 2 · Make taps immediate and persistence durable

1. Start selected provider track before resolving the rest of its queue.
2. Move local search off the main thread and pre-index normalized terms.
3. Redesign download temporary publication and durable removal.
4. Separate cheap destination health checks from full SAF reconciliation.
5. Persist sleep timer intent.
6. Add bounded redirect support.

**Exit condition:** Everyday playback, search, and download interactions remain responsive under large libraries, slow storage, and ordinary network failure.

## Phase 3 · Bound concurrency and prove behavior on devices

1. Convert Crew media state to actors or fully serialized state machines.
2. Stream media transfers through disk-backed bounded buffers.
3. Add relay fairness and ICE endpoint limits.
4. Add exported-component and backup hardening.
5. Enable Room schema export and migration tests.
6. Add emulator, physical-device, two-device Crew, SAF, R8, performance, and accessibility gates.

**Exit condition:** The alpha survives process death, concurrent peers, OEM layout behavior, provider changes, and real storage/network conditions with diagnosable outcomes.

## Phase 4 · Premium polish

1. Replace the full-player layout workaround rather than adding more delayed callbacks.
2. Rebuild lyrics around stable line rows and manual-follow behavior.
3. Convert Home to one sectioned list.
4. Simplify settings hierarchy.
5. Add the ship-on-wave signature with restrained motion.
6. Tune typography, contrast, haptics, empty states, and error wording from physical-device sessions.

**Exit condition:** Shippy feels calm because its behavior is stable, not because animations conceal delay.

---

# Suggested release gates for the next alpha

A build should not graduate from personal alpha until all of the following are true:

- [ ] No queue operation crosses the UI boundary by raw position.
- [ ] Selected provider tracks start without resolving the full queue.
- [ ] Empty and partially unavailable Crew queues converge correctly on two devices.
- [ ] Download partial files are never visible as final library tracks.
- [ ] Process death restores playback checkpoint, download work, and sleep timer predictably.
- [ ] Search typing remains frame-stable on a large synthetic library.
- [ ] Next/previous and cover swipe meet a documented frame-time budget.
- [ ] Release/R8 APK installs and completes local/provider/download/Crew smoke tests.
- [ ] Room migrations preserve real data from every shipped alpha schema.
- [ ] TalkBack, 200% font scale, RTL, reduced motion, and gesture navigation are exercised.
- [ ] Exported media components and backup contents have explicit threat/compatibility decisions.
- [ ] Known TODOs are represented by tracked issues with acceptance tests.

## Final verdict

Shippy has enough architectural substance to become the daily driver you want. Its current weakness is the seam between systems: canonical queue versus asynchronously rendered queue, Room publication versus filesystem visibility, Crew authority versus local playback, attractive motion versus nested layout cost, and durable intent versus in-memory controllers.

That is fixable without rewriting the app. The correct move is a concentrated hardening cycle that removes secondary authorities, makes identity stable, starts audio before background preparation, and proves the result on actual devices. Once those foundations are reliable, the visual polish, synced lyrics, Crew presence, and tiny ship can feel premium rather than decorative compensation for uncertainty.
