# C02 — Identity and Metadata Contract

## Canonical layers

1. **Raw observation:** provider/local/download/Last.fm data as received, with provenance and capture time.
2. **Canonical projection:** Shippy's best stable metadata for the Recording.
3. **User override:** explicit owner edit; highest presentation authority until removed.

Raw observations are never silently destroyed merely because canonical metadata changes.

## Candidate evidence order

1. exact source key / managed-asset ownership;
2. conflict-free trusted strong IDs;
3. strong audio fingerprint;
4. compatible metadata evidence with version/duration vetoes;
5. user confirmation.

Metadata-only ambiguity must not auto-merge. Live/remix/acoustic/remaster/clean/explicit/version conflicts veto or require review.

## Candidate retrieval versus decision

Candidate retrieval may be broad but bounded: normalized FTS/title tokens, artist tokens, duration range, strong IDs, and fingerprints. The matching policy remains conservative. Do not make retrieval exact-string-only, and do not compensate by making merge thresholds reckless.

## External ID trust

Store provenance and trust separately from ID kind. A value read from an arbitrary local tag is not automatically as trustworthy as a conflict-free provider/authority result. Conflicting strong IDs block automatic linking and enter audit/review.

## Manual Identify

Confirmation attaches the exact local/source asset to the chosen canonical Recording, records the decision, recomputes metadata/provenance, updates every surface through RecordingId, and supports Undo/Unlink. It does not replace or rename the physical file unless a separate explicit tag-edit action succeeds.

## Retention

One-off streamed recordings may be transient. Durable triggers include Library relationships, playlist membership, permanent assets/downloads, manual identity/metadata decisions, active checkpoint/queue, pending work, and explicit retention policy. History should retain a display-safe snapshot without forcing every transient Recording row to live forever.
