# Shippy Product Specification

> **Canonical source of truth:** `X:\piko\shippy\docs\PRODUCT_SPEC.md`
>
> This 2026-07-16 document is preserved as historical context. It predates the
> locked Auxio/Kotlin foundation and complete Crew requirements and must not
> drive implementation.

**Status:** Superseded historical draft  
**Owner:** Shippy team  
**Last updated:** 2026-07-16  
**Implementation status:** Pre-foundation; no Shippy codebase exists yet.

## 1. Mission

Build **Shippy**, a premium, open-source Android music app that makes music feel like one coherent collection regardless of whether a track is local, downloaded, streamed, or available from an approved peer.

> **Music is music. The source is an implementation detail.**

Shippy should be fast, calm, accessible, offline-capable, and native-feeling. It is not a clone of Spotify, nor a catalogue service. It is a player and music workspace that gives people control over their library, providers, and devices.

## 2. Current Context And Evidence

### Inspected

- `X:\piko` is currently empty. This document is the initial project artifact.
- Bloomee's public repository describes an ad-free, multi-source Flutter music player and is licensed under GPL-2.0. It is the candidate foundation for Shippy, not yet an adopted dependency or fork.

### Inferred

- The fastest credible path is to fork Bloomee, retain GPL-2.0, and modernize the product in vertical slices.
- A Kotlin/Compose rewrite may be the eventual best Android implementation, but is not justified until the Bloomee audit proves that its architecture blocks Shippy's first release.

### Open

- Whether Shippy starts as a direct GPL fork or a new native implementation.
- Which sources still work reliably in the currently maintained Bloomee branch.
- Whether `Shippy` is publishable as the final brand. It is acceptable as a project codename, but name, package, domain, and trademark clearance are required before release.

## 3. Product Principles

1. **One library, not source silos.** Local, downloaded, and streamed tracks remain one collection; availability is shown as metadata.
2. **One-tap intent.** A user searches, taps, and listens. Provider choice and retries are hidden unless the user asks.
3. **Offline-first.** Queueing, local playback, downloaded music, and cached metadata remain useful without internet.
4. **Native Android respect.** Media controls, widgets, notifications, system colours where appropriate, predictive back, and accessibility settings must feel first-class.
5. **Calm over spectacle.** Premium means hierarchy, speed, typography, and feedbackâ€”not constant blur, gradients, or animation.
6. **Consent over magic.** P2P, device discovery, sharing, and any future content pull require visible user control.
7. **No AI theatre.** AI is not a V1 feature. It may only be added later when it removes real user friction.
8. **Open core.** The player must remain useful without an account, ads, or a hosted service.

## 4. Target Users

### Primary: the independent Android music listener

Uses local files, downloaded tracks, and multiple music sources. Wants a polished player without ads, a fragmented library, or a subscription-shaped product.

### Secondary: the technical music user

Sideloads apps, values privacy and local ownership, may use Last.fm or self-hosted media later, and wants the app to work when connectivity is poor.

### Later: the listening group

Wants a small, low-friction shared listening session with friends, not a social network.

## 5. Product Boundary

### Shippy Alpha: the first strong release

Shippy Alpha proves one complete loop:

```text
Open Shippy -> find a track -> play -> add to queue -> download -> continue offline
```

It must include:

- A new Shippy visual system and app shell.
- Home, Search, Library, and Now Playing surfaces.
- Unified display of existing local/downloaded/online results where the selected foundation supports them.
- A canonical `Track` presentation model with availability and source badges.
- Playback queue and now-playing state.
- Download status integrated into the normal library, plus a Downloads filter.
- Background playback, notification controls, and a correct MediaSession surface.
- Accessibility baseline: semantic labels, scalable text, 48 dp minimum touch targets, contrast-safe state cues, and reduced motion support.
- A basic Android home-screen widget if the selected foundation can support it without blocking Alpha.

### Explicit Alpha non-goals

- A new streaming catalogue, music licensing business, or server-operated music hosting.
- Spotify or YouTube Music account migration.
- Friends, feeds, chat, accounts, cloud sync, blends, or recommendations.
- Audio transport from host device to peer.
- LAN `Pull` transfer.
- Full Jam/Sync networking.
- Native Kotlin rewrite.
- AI chat, AI DJ, or an AI marketing layer.

These ideas remain valid; they are simply not allowed to delay the first Shippy release.

## 6. Information Architecture

### Primary navigation

| Surface | User job | Must not become |
|---|---|---|
| Home | Resume listening, see useful shortcuts and recent activity | A dumping ground for every feature |
| Search | Find a song, album, artist, or playlist across available sources | Separate provider search apps |
| Library | Browse owned, saved, and downloaded music as one collection | A file browser by default |
| Crew Mode | Shared listening session (Jam); host-led state sync later | A social network or chat room |
| Now Playing | Control and understand the current listening context | A crowded settings panel |

### Library structure

Library has filters/sections, not isolated product worlds:

- Songs
- Albums
- Artists
- Playlists
- Downloads
- Liked
- Local tracks

`Downloads` and `Local tracks` are filtered views. A downloaded song still appears in its album, artist, playlist, search result, and queue.

### Track availability language

Each track can have multiple availability states:

| State | Meaning | Default action |
|---|---|---|
| Local | File exists in user storage | Play immediately |
| Downloaded | Shippy-managed offline copy exists | Play immediately |
| Streamable | An enabled provider can play it | Stream |
| Cached | Temporary data is ready for fast replay | Use when needed |
| Peer available | An approved session peer reports possession | Offer later; never transfer silently |
| Unavailable | No legal/functional source can play it now | Explain and offer alternatives |

Source is visible as a small provenance detail, not a navigation decision.

## 7. Core User Flows

### 7.1 Golden playback flow

1. User opens Shippy.
2. Home restores the current or recent listening context.
3. User searches once or opens a library item.
4. Shippy chooses the best available copy according to the user's preference policy.
5. Playback starts; Now Playing and system controls reflect the same state.
6. User may add to queue or download without leaving the context.
7. If connectivity disappears, downloaded/local tracks continue normally.

### 7.2 Source resolution policy

Default preference is a safe, explainable policy:

```text
Local -> Downloaded -> Existing cached source -> Preferred enabled provider -> Another enabled provider -> Unavailable
```

The user can later customise provider priority. Shippy must never silently substitute a different recording while claiming it is the same track.

### 7.3 Download flow

1. User taps download on a track, album, or playlist.
2. Shippy shows clear progress and storage impact.
3. The item remains in normal views with a downloaded indicator.
4. Library > Downloads is a filterable overview, not a separate music universe.
5. Removing a download changes availability; it does not delete a local user-owned file.

### 7.4 Future Sync flow (not Alpha)

1. Host starts a **Sync Session** from Now Playing.
2. Guest joins through QR code or link.
3. Host is authoritative for current item, play/pause, seeking, and queue order.
4. Guests sync their own lawful playback source; Shippy sends state, not music audio.
5. Host can transfer authority or end the session.

This model is intentionally state-sync first. Audio redistribution is a separate legal, product, and networking decision.

## 8. Experience And Design Direction

### Visual character

- Spacious, typographic, artwork-led, and quietly confident.
- Dark mode must be intentional; dynamic colour is supportive, not a substitute for hierarchy.
- Large artwork and direct queue access make Now Playing the emotional centre.
- Motion confirms causality: play starts, artwork moves, sheets attach to their trigger, and navigation maintains context.

### Interaction rules

- Use one obvious primary action per screen.
- Prefer labels for unfamiliar concepts; icon-only actions need semantic labels and tooltips where appropriate.
- Do not use colour as the only source or download-status signal.
- Controls remain reachable with one hand on typical Android devices.
- Reduced motion must replace, not merely slow, non-essential animation.

### Accessibility requirements

- TalkBack labels and logical focus order for every interactive element.
- Dynamic type without clipped titles or inaccessible controls.
- 48 dp minimum tap targets.
- High-contrast-safe iconography and textual state labels.
- Accessible seek and volume controls with meaningful increments.
- Respect system reduced-motion and colour preferences where available.

## 9. Technical Direction

### Foundation decision gate

Shippy does **not** commit to Flutter or Kotlin yet. The first engineering milestone audits the current Bloomee branch and makes the decision using evidence.

| Option | Choose when | Trade-off |
|---|---|---|
| Fork Bloomee / Flutter | It builds reliably, core playback/provider flows are understandable, and the UI/state layers can be replaced incrementally | Distributed Shippy remains GPL-2.0 and inherits Flutter/plugin constraints |
| Kotlin / Compose rewrite | The audit shows unrecoverable architecture, performance, or Android-integration constraints | Much slower; playback, downloads, provider integration, and edge cases must be reimplemented |

**Default decision:** fork first. A rewrite requires a written audit finding and an explicit decision.

### Assumed Shippy layering (fork path)

```text
Screens and reusable UI
        |
Presentation state / view models
        |
Shippy domain model and use cases
        |
Repository adapters
        |
Existing provider, local-library, download, and playback implementations
        |
Android platform integration
```

The first architectural objective is to put a Shippy domain boundary between the UI and source-specific code. Avoid a massive backend rewrite.

### Canonical domain contracts

The implementation must converge on these concepts, independent of framework:

- `Track`: canonical identity, title, artists, artwork, duration, versions, provenance, availability.
- `TrackCandidate`: provider/local representation that can map to a canonical `Track`.
- `PlaybackItem`: a concrete playable representation selected for the current device.
- `LibraryEntry`: user relationship to a track: liked, saved, downloaded, local, recently played.
- `QueueItem`: ordered playback context, including who added it in future sync sessions.
- `DownloadJob`: requested, queued, downloading, complete, failed, removed.
- `PlayerState`: idle, loading, buffering, playing, paused, seeking, ended, error.

### State ownership

- Player state has one authority inside the application and bridges to Android media controls.
- UI surfaces subscribe to state; they do not each implement playback logic.
- Source resolution is deterministic and logged for diagnosis.
- Download and playback are independent state machines coordinated through availability.

## 10. Android Integration

### Alpha requirements

- MediaSession-backed notification and lock-screen controls.
- Correct foreground/background playback behaviour.
- Notification actions that update the same player state as the UI.
- One compact, actionable home-screen widget if feasible after the base player is stable.

### Deferred integration

- Quick Settings tile.
- Android Auto.
- Wear OS.
- Expanded/large widgets and live progress animation.

Native Android code is acceptable for widgets, MediaSession, notification actions, and platform services even if the main app remains Flutter.

## 11. Networking, Sharing, And Privacy

### Sync sessions: future product contract

- Use a host-authoritative session model.
- Sync only playback state first: item identity, position, play/pause, queue, and authority transfer.
- QR/link joining may use a small signalling service; media state should travel peer-to-peer where practical.
- Network failure must degrade gracefully to local independent playback with a visible sync status.

### `Pull`: deliberately deferred

`Pull` is a future consent-based request to obtain a track from a session peer. If ever implemented, it requires:

- A clear host approval prompt naming track, size, target device, and retention choice.
- Per-session and trusted-device approval policy.
- Temporary cache deletion policy.
- Encryption and device identity design.
- A legal review of redistribution risk and source-specific terms.

It must never silently transfer media files. It must not be represented as a current Shippy feature until it exists and has passed these gates.

### Privacy baseline

- No account required for Alpha.
- Store library, queue, and playback state locally by default.
- No analytics or tracking by default.
- Any later network feature documents what data leaves the device and why.

## 12. Integrations

### Alpha

- Reuse only the provider and playback capabilities actually verified in the chosen foundation.

### Next candidates

- Last.fm: scrobbling, loved-track import, artist similarity, tags, and listening statistics.
- Playlist migration: import playlists only where API and terms permit it.
- Jellyfin/Navidrome: later provider integrations if Shippy's repository boundary proves durable.

### Not planned as a dependency

- AI recommendation engine.
- In-app chat.
- A Shippy-operated music catalogue.

## 13. Licensing And Compliance Boundary

- A direct Bloomee fork is a GPL-2.0 derivative and must remain distributed under GPL-2.0 with required notices and source availability.
- A clean Kotlin implementation can use a different license only when it contains no copyrighted Bloomee code or other incompatible copied code. Ideas and interaction patterns are not code permission.
- Provider integrations, playback extraction, downloading, and peer content transfer each have their own terms and copyright risks. Their current legal/technical viability must be checked before Shippy represents them as supported.
- Shippy must not promise or implement evasion of provider restrictions.

This is an engineering boundary, not legal advice. Obtain qualified legal advice before commercial distribution or introducing peer media transfer.

## 14. Roadmap

### Milestone 0 â€” Foundation audit (2 days)

**Outcome:** an evidence-based fork vs rewrite decision.

- Clone the maintained Bloomee repository without modifying it.
- Build and install its Android app on a real device.
- Record exact toolchain versions, build steps, warnings, and runtime failures.
- Trace source search, playback, queue, downloads, local scan, background controls, and current state-management locations.
- Capture baseline video/screenshots and measure the visible pain points.
- Write `docs/FOUNDATION_AUDIT.md` with a decision and constraints.

**Exit criterion:** Shippy has a reproducible baseline build and a signed-off foundation decision.

### Milestone 1 â€” Shippy shell and design system (1â€“2 weeks)

**Outcome:** Shippy looks intentional while retaining a working playback baseline.

- Add Shippy branding behind a temporary package/app-id strategy.
- Establish tokens for typography, spacing, elevation, shape, and motion.
- Implement app shell, bottom navigation, mini-player, reusable track row, artwork treatment, and primary controls.
- Rebuild Now Playing as the first flagship screen.
- Add screen-reader semantics as components are created.

**Exit criterion:** a user can play an existing track and experience Shippy's shell, mini-player, and Now Playing without a broken control path.

### Milestone 2 â€” Unified library and golden flow (2â€“3 weeks)

**Outcome:** Shippy proves its main thesis.

- Introduce canonical track/candidate mapping at an adapter boundary.
- Present unified results with compact source and availability badges.
- Implement clear source-resolution policy and fallback messages.
- Integrate download states into track, album, playlist, and Downloads views.
- Verify the full offline golden flow on a physical device.

**Exit criterion:** all available copies of a track are presented as one listening choice; downloaded tracks remain normal library items.

### Milestone 3 â€” Android polish and accessibility (1â€“2 weeks)

**Outcome:** the app feels native outside its own UI.

- Harden MediaSession, notification, background playback, and interruption handling.
- Build one useful widget.
- Add large-text, reduced-motion, and contrast regression checks.
- Profile startup, search scrolling, artwork loading, and Now Playing transitions.

**Exit criterion:** system controls and Shippy UI never disagree about the current track or play state.

### Milestone 4 â€” Sync-session proof (separate prototype)

**Outcome:** two devices can join and stay in sync using their own valid sources.

- Define session messages and host authority.
- Build QR/link join and visible connection status.
- Sync play/pause, seek, current item, and queue changes.
- Test on same LAN first, then remote conditions.

**Exit criterion:** a two-device session survives basic seek/pause/queue interaction with bounded, recoverable drift.

### Later milestones

- Last.fm integration.
- Playlist migration.
- Social sharing without chat.
- Optional hosted sync/signalling service.
- `Pull` feasibility and legal review.
- Native Kotlin/Compose reconsideration only if audit data demands it.

## 15. Task Tree And Verification

### Workstream A: Foundation

- [ ] Clone the candidate Bloomee repository into a separate working directory.
  - Validation: record commit SHA and clean `git status`.
- [ ] Build an Android debug APK using the documented toolchain.
  - Validation: APK installs and opens on a real device.
- [ ] Map source, playback, download, local-library, state, and Android integration modules.
  - Validation: `docs/FOUNDATION_AUDIT.md` names each entry point and dependency.
- [ ] Decide fork or rewrite.
  - Validation: decision has concrete reasons, cost, and rejected alternative.

### Workstream B: Shippy foundation

- [ ] Create the Shippy repo structure, license decision record, and contributor guidance.
  - Validation: fresh clone has documented setup and a reproducible build command.
- [ ] Add automated formatting, static analysis, and a device smoke checklist.
  - Validation: all checks pass locally and in CI.
- [ ] Establish design tokens and core accessible components.
  - Validation: component previews/screenshots cover normal, large-text, and dark-mode states.

### Workstream C: Golden flow

- [ ] Implement unified `Track` and availability presentation boundary.
  - Validation: tests map at least local, downloaded, and provider candidates to visible states.
- [ ] Implement source resolution.
  - Validation: deterministic tests cover local, downloaded, streamable, and unavailable cases.
- [ ] Rebuild Now Playing, queue, and download affordances.
  - Validation: manual device flow completes without leaving the primary context.
- [ ] Verify offline playback after download.
  - Validation: enable airplane mode; selected downloaded track plays and queue behaviour is clear.

### Workstream D: Android quality

- [ ] Synchronize player state with notification and lock-screen controls.
  - Validation: changing state from either surface updates the other within one second.
- [ ] Implement first widget.
  - Validation: widget displays current item and performs play/pause correctly.
- [ ] Run accessibility and performance pass.
  - Validation: TalkBack flow, large-text views, and a physical-device scroll/startup profile are recorded.

## 16. Acceptance Criteria For Shippy Alpha

- [ ] A first-time user can find and play music without choosing a source tab.
- [ ] One track can show local/downloaded/streamable availability clearly and accessibly.
- [ ] Downloaded tracks remain discoverable from normal library and playlist views.
- [ ] Offline playback works for downloaded and local items.
- [ ] Queue, Now Playing, notification controls, and lock-screen controls share one correct state.
- [ ] Search and library scrolling are subjectively smooth on the target physical Android device; no known sustained jank is accepted without a tracked issue.
- [ ] Core controls work with TalkBack and large text.
- [ ] The app contains no account wall, ads, chat, peer media transfer, or AI feature.
- [ ] License notices and source-distribution obligations match the selected foundation.

## 17. Risks And Chosen Decisions

| Risk | Decision |
|---|---|
| The feature list grows into an unfinishable platform | Alpha is limited to the golden playback loop. New ideas go in `FUTURE.md`. |
| Bloomee's code prevents clean iteration | Audit before redesign; rewrite only with written evidence. |
| GPL conflicts with desired distribution model | Treat fork path as GPL; do not attempt to relicense derivative code. |
| Streaming/provider behaviour changes or breaks | Build adapters and verify each provider on the actual release branch. |
| Audio sharing creates legal, privacy, or bandwidth problems | Sync playback state first; defer audio transfer and `Pull`. |
| UI polish creates jank | Profile on device early; avoid expensive blur and unnecessary rebuilds. |
| `Shippy` conflicts with existing names | Keep it as codename until name clearance is complete. |

## 18. Documentation Rules

- This file is the product boundary and decision record.
- Update it when scope, foundation choice, architecture, or milestone exit criteria change.
- Do not turn it into a changelog. Put implementation history in `CHANGELOG.md` or issue/PR records later.
- Capture postponed ideas in `FUTURE.md` with one sentence and no implementation commitment.
- Any claim that a feature works must state whether it is **Implemented**, **Smoke-tested**, or **Verified on device**.

## 19. Immediate `$ship` Handoff

1. Create a clean Shippy Git repository and add this document unchanged as the initial source of truth.
2. Clone Bloomee alongside it and perform Milestone 0; do not redesign or refactor before the audit concludes.
3. Choose the foundation, then create the first Shippy design-system task: app shell, mini-player, and Now Playing.

**Definition of done for the next checkpoint:** Shippy has a reproducible Android baseline, a written foundation decision, and a small, physically tested Shippy UI slice that plays one real track.

## 20. External References

- Bloomee source and GPL-2.0 license: <https://github.com/mslaksh/Music-app>
- Bloomee project site / current release surface: <https://bloomeex.org/bloomeetunes/>
