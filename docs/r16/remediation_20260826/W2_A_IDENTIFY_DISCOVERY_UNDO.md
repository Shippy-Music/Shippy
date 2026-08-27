# Wave 2A - Identify Track External Discovery and Durable Undo

## Prerequisite

Wave 1D is reviewed and its exact unmerge semantics are stable.

## Outcome

Complete the real Identify Track product path for an ugly local file absent from the local
catalogue.

## Required behavior

1. Add the smallest app-layer coordinator that combines immediate local candidates with
   progressively arriving enabled-provider candidates through the existing
   `SourceDiscoveryRepository` / provider-observation boundary.
2. Preserve provenance, typed source identity, cancellation, generation guards, bounded
   concurrency/results, and partial provider failures.
3. Do not route identity discovery through playback resolution and do not create another provider
   registry.
4. Confirmation remains one atomic identity decision and retains the existing queue occurrence.
5. After confirmation, expose a Snackbar Undo action that invokes the existing durable reversal.
6. Add a persistent Unlink/Undo Identification action from the relevant metadata/track detail
   surface so reversal survives beyond the Snackbar window.

## Owned files

- `app/.../r16/identity/**`
- existing source-discovery composition/binding files directly required
- focused identity UI/coordinator tests

Shared data mutation changes require supervisor review; do not reopen merge/unmerge implementation.

## Acceptance

- A local track with no local canonical candidate receives external candidates with provenance.
- A stale query generation cannot overwrite a newer query.
- One provider failure does not erase local/other-provider candidates.
- Confirm -> Undo and confirm -> later Unlink restore the prior identity relationship.
