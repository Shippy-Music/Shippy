# Shippy UX Contract

This document translates `PRODUCT_SPEC.md` into screen and interaction rules.
It is a behavioral contract, not a pixel-perfect design file.

## 1. Experience Character

Shippy starts with Auxio's clarity and Android-native behavior, then adds the
information architecture and directness expected from a modern streaming app.

The interface should feel:

- Quiet, fast, and artwork-led
- Dense enough to be useful but never crowded
- Familiar without being a visual clone
- Consistent across local, downloaded, provider, and Crew contexts
- Understandable without exposing provider or network implementation details

Avoid glossy cards everywhere, excessive blur, neon gradients, nested action
drawers, duplicated commands, unexplained icons, and destructive controls on
ordinary song rows.

## 2. Global Shell

### Primary navigation

Bottom navigation has exactly four destinations:

1. Home
2. Search
3. Library
4. Crew

The active destination is clear through icon and label. The shell preserves each
destination's navigation and scroll state when switching tabs.

### Mini-player

When a current item exists, the mini-player sits immediately above bottom
navigation. It shows:

- Artwork
- Title
- Primary artist
- Play/pause
- A subtle progress indication

Tap or upward gesture expands Now Playing. Horizontal gestures may skip tracks
only if they remain discoverable, accessible, and conflict-free with system
navigation. The mini-player never contains delete or developer actions.

### Back and dismissal

- Back closes the most local transient surface first.
- Back from full Now Playing collapses to the mini-player.
- Back never exits directly merely because music is playing.
- Predictive Back visually previews the destination/collapse.

## 3. Home

Home prioritizes continuation:

1. Active Crew or resumable playback context
2. Continue listening / recently played
3. Pinned library shortcuts
4. Useful provider recommendations or charts
5. Recently added/downloaded items

Each section has a reason to exist and can disappear when empty. Home is not a
settings launcher or a catalogue of every integration.

## 4. Search

### Search behavior

- Focus enters one global query field.
- Results update quickly with stable layout.
- Typo tolerance and recent searches are supported.
- With no query, recent searches render as useful result-like rows with retained
  title, subtitle, and artwork where available.
- The query field owns a direct clear affordance.
- Provider requests may arrive independently without reshuffling already
  interacted-with rows unpredictably.
- Loading and partial-provider failure are scoped to the affected result group.

### Result groups

- Songs
- Albums
- Artists
- Playlists
- On this device

`On this device` is a labelled Local group. It offers convenience without
merging exact files into provider identity automatically.

Search opened from Library is a local-only mode. It never becomes global
provider Search merely because the same result components are reused.

### Result actions

Tapping a song plays it in its result context. The row overflow includes only
non-primary actions such as play next, add to queue, add to playlist, album,
artist, share, and information. Save/download indicators are direct when
relevant.

## 5. Library

### Header

Library provides:

- Search within library
- Sort/filter
- Create/add
- A clear current filter

### Content

The first-class collection list includes:

- Liked
- Downloads
- Local
- User playlists
- Saved albums
- Saved artists
- Saved individual songs

Pinned items appear first. Type filters can narrow the visible library without
moving the user into separate product worlds.

### Permanent collections

Liked, Downloads, and Local use distinct default artwork and labels. Their
overflow never offers Rename or Delete.

User playlists expose Rename and Delete only in appropriate management surfaces,
not as a permanently visible trash icon beside every row. User playlists may
choose custom artwork through the native Android image picker; permanent
collection artwork is intentionally not user-editable.

All collections can be pinned. Long-press drag reorders the combined system and
user list; dragging across the pinned boundary changes pin state instead of
requiring a second disconnected settings flow.

### Local

Local includes Songs, Albums, Artists, Genres, Folders, and search using the
foundation's proven local-library behavior where possible. Folder management
lives in Settings and explains indexing/access status.

### Downloads

Downloads shows Shippy-managed permanent copies with sorting, size/storage
summary, and failed/in-progress state where useful. Downloaded items remain in
their original albums and playlists.

## 6. Collection Detail

Album and playlist detail surfaces contain:

- Artwork and identity
- Creator/artist and useful metadata
- Save/download controls
- Primary Play and Shuffle actions
- Track list
- Restrained overflow for management/share/info

The download action works on the collection and reflects partial/in-progress/
complete state. It is hidden when nothing eligible is missing and never attempts
to redownload Local or already available tracks. Mixed downloaded and
streamable tracks are expected. Each collection persists its own sort mode.

## 7. Now Playing

### Layout order

1. Collapse affordance and restrained overflow
2. Artwork
3. Track identity, optionally reinforced by a compact artwork thumbnail
4. Save
5. Seek/progress with elapsed/remaining time
6. Shuffle, previous, play/pause, next, repeat
7. Sleep timer at the left; conditional Download and Queue at the right
8. Lyrics below the main player content

The exact responsive arrangement may change by screen size, but the hierarchy
must remain.

### Expansion and collapse

- Tapping or dragging upward on the persistent mini-player expands Now Playing.
- Every expansion resets the player's content scroll to the artwork/top state.
- Dragging downward on the expanded player collapses it back to the mini-player.
- The collapse gesture uses the same native bottom-sheet motion as expansion,
  not a second screen transition.
- Back also collapses Now Playing before leaving the current root destination.
- When Queue is expanded, Queue owns the drag gesture until it closes.

### Save behavior

- Unsaved: outlined `+`
- One tap: add to Liked with immediate confirmation
- Saved: completed state
- Subsequent tap or an adjacent contextual action: edit collection destinations

No separate “Favorite” action appears in the overflow.

### Download behavior

- One visible action changes between download, progress, downloaded, and retry.
- Removing a Shippy download is a deliberate secondary action.
- “Available Offline” does not appear as a duplicate menu item.
- Local files do not show a misleading Shippy-download action.

### Queue

Queue is directly reachable and supports drag reorder, remove, play next, and
clear-context actions with undo where appropriate. During Crew it shows who
added each item and reflects remote edits in place.

### Lyrics

Lyrics live below or in a naturally attached player section. Synchronized lyrics
follow playback when available. The user can disable automatic following and
return to the active line. Cached lyrics remain available offline.

The full lyrics surface keeps track identity centered, gives the active line
clear scale/weight/contrast, makes surrounding lines smaller and matte, and uses
an edge fade rather than hard clipping. Its visible actions are conditional
Download and the same consolidated track overflow; Local media does not show
Download. Tapping a timestamped synchronized line seeks playback to that line
and immediately updates the progress presentation; plain lyrics are not
misrepresented as seekable.

### Overflow

Allowed:

- Add to playlist / edit saved destinations
- Play next
- Add to queue
- Go to queue
- Go to album
- Go to artist
- Start/open Crew
- Share
- Song information
- Sleep timer

Not allowed:

- Duplicate favorite/save
- Duplicate download/offline
- Raw provider debugging
- Unexplained “smart” maintenance actions
- Destructive delete without context

### Song information

Information is a separate, scannable surface containing:

- Full title, artists, album, artwork, duration, year, genre when known
- Current playable source and availability
- File details for exact Local or Downloaded copies
- Original provider link
- Technical/provider metadata under an Advanced section
- Refresh/re-resolve maintenance actions only when genuinely necessary

## 8. Crew

### Empty state

Crew offers:

- Start a Crew
- Scan QR
- Join from link/code
- Configure hosted relay
- A short explanation: listen together, locally or remotely

### Active state

The active Crew surface contains:

- Current item and playback state
- Member avatars/names with concise connection indicators
- Invite/QR
- Shared queue
- Reaction control
- Push & Pull status
- Leave Crew

Network diagnostics remain hidden unless degraded behavior requires explanation.
A joined Crew remains on this active surface during bounded recovery and shows
only a calm Reconnecting state. If the secure reconnect lease expires, say so
plainly instead of spinning or exposing an address.

### Collaborative actions

Any participant can browse Home, Search, Library, or Local while remaining in
Crew. Playing a song or playlist updates the Crew playback context for everyone.
The UI should make the shared effect clear without inserting a confirmation
dialog into every play action.

### Push & Pull prompts

Normal path: no prompt.

When a required peer-only track is blocked because the toggle is off:

> This track is available from your Crew. Turn on Push & Pull to keep listening?

The prompt explains temporary streaming and provides Enable / Not now. It does
not expose transfer protocols.

### Reactions

The reaction picker is compact. Selected emoji animate upward over the player
and disappear. Reduced-motion mode uses a quiet static appearance/fade.

## 9. Settings

Settings groups are task-oriented:

- Playback and audio
- Downloads and storage
- Local music folders
- Providers
- Crew and Push & Pull
- Last.fm and integrations
- Appearance
- Accessibility
- About and diagnostics

Important choices:

- Preferred provider and fallback order
- Download destination and quality
- Local source folders
- Push & Pull one-toggle state
- Hosted relay address/configuration with bounded Checking/Reachable/Unreachable
  feedback
- Equalizer, normalization, gapless/crossfade
- Sleep timer defaults if any
- Dynamic color, theme, reduced motion

Developer/provider diagnostics live under About and diagnostics, not ordinary
song menus.

## 10. Sharing

The share sheet begins with two Shippy choices:

1. Share original link
2. Share with Shippy

The second produces a deep link/compact payload that opens the exact intended
track or resolves it honestly. Copying a link and Android's normal share targets
remain straightforward.

## 11. Motion

Motion communicates continuity:

- Mini-player expands from its current position.
- Artwork and track identity maintain context.
- Queue edits animate locally without rebuilding the entire screen.
- Reactions are playful but short.
- State changes use subtle spring/fade behavior.
- Default Shippy transitions run at 1.25x the previous Material duration:
  smooth enough to preserve continuity, short enough to feel immediate.
- Android's animator-duration setting remains authoritative.

No continuous background animation, unnecessary blur recomputation, or motion
that delays input. Reduced motion removes shared-element travel and floating
reactions in favor of direct fades/state changes.

## 12. Release Accessibility Checklist

This checklist is intentionally executed after the functional application and
product polish are complete. It remains a release requirement, not a current
first-pass implementation blocker.

Every new component must verify:

- TalkBack name, role, state, and action
- Logical focus order
- 48 dp target independent of visible icon size
- Large text at Android accessibility scales
- State conveyed by more than color
- Contrast in light/dark/dynamic themes
- Switches and sliders expose values
- Drag actions have accessible alternatives
- Queue and Crew changes are announced without excessive interruption
- Decorative artwork/reactions do not pollute the accessibility tree

## 13. UX Regression Rules

A change is rejected when it:

- Adds a fifth primary destination
- Turns Now Playing into a bottom-navigation tab
- Duplicates save or download actions
- Requires provider selection before every play
- Makes Local silently replace provider media
- Exposes Push & Pull outside the active Crew trust boundary
- Adds chat to Crew
- Places a delete icon permanently beside normal tracks
- Causes Back from Now Playing to leave the app
- Sacrifices TalkBack, large text, or reduced motion for visual polish

## 14. Current Lyrics Target

Now Playing uses one continuous vertical surface:

- artwork and canonical controls first
- top-right overflow owns the consolidated current-track action sheet
- the title row owns the save/playlist plus-to-tick state
- Download and Queue sit in the utility row below playback controls
- a large synced-lyrics preview card immediately below
- nearby lines remain visible while the active line is emphasized
- tapping the card opens the full synced-lyrics sheet
- preview and full view share the same playback position and seek authority

Lyrics must never become a disconnected static page or a duplicate player.
