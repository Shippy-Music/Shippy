# Shippy Crew signaling relay (foundation)

This is the smallest self-hostable hosted **signaling-only** foundation for Crew. It is not TURN, a media relay, a music library, or a complete Crew server. It holds only live in-memory rendezvous/routes; restarting it drops every session.

The binary WebSocket endpoint is `GET /v1/crew` (upgrade) and liveness is `GET /healthz`. The server accepts only a versioned bounded binary envelope: a host registers opaque `sessionLocator` and `inviteId`; a joiner registers the same opaque values; the server generates a 128-bit route ID and forwards bounded opaque `DATA` only along that exact live route. Shippy endpoints authenticate and encrypt/sign their own signaling payloads. The QR invitation bearer secret and reusable device credentials must never be sent to this service.

## Optional TURN credentials

This relay can mint short-lived coturn REST credentials, but it never relays media itself. Enable it only with both `CREW_RELAY_TURN_URLS` (up to four comma-separated `turn:`/`turns:` URLs) and a random 16–256-byte `CREW_RELAY_TURN_SECRET`. `CREW_RELAY_TURN_TTL_SECONDS` defaults to 600 and is clamped to 60–3600 seconds.

`POST /v1/ice` accepts bounded JSON containing `protocolVersion: 1`, `sessionLocator`, and `inviteId`. It returns no-store ICE credentials only while that exact host registration is live; otherwise it returns 404. Invalid requests return 400 and unconfigured TURN returns 503. Do not include the QR invitation bearer secret: `inviteId` is only the existing opaque, bounded registration identifier.

## Run locally

Requires Node 22+:

```sh
npm install
npm test
npm start
curl http://127.0.0.1:8080/healthz
```

Copy `.env.example` into deployment configuration as needed. Browser `Origin` values are rejected by default. List trusted origins with `CREW_RELAY_ALLOWED_ORIGINS`, or set `CREW_RELAY_ALLOW_ANY_ORIGIN=true` only when deliberately disabling that protection. Terminate TLS at a reverse proxy; use `wss://` from clients.

## Run with Docker

```sh
docker build -t shippy-crew-relay .
docker run --rm --read-only --tmpfs /tmp:rw,noexec,nosuid,size=16m -p 8080:8080 --env-file .env shippy-crew-relay
curl http://127.0.0.1:8080/healthz
```

The image runs as the built-in non-root `node` user and needs no writable application storage.

## Privacy and retention

No music bytes, library metadata, invitation secret, device credential, route ID, locator, invite ID, or opaque payload is logged. Structured logs contain only event/reason and aggregate live counts. The relay does not persist registrations, routes, frames, or media; it performs no offline buffering or replay. A missing or backpressured recipient closes the route instead of retaining data.

## Limits and lifecycle

The service enforces registration and idle timeouts, WebSocket heartbeats, per-connection message rate, maximum sessions/joins, frame/payload bounds, and a process-wide in-flight-byte ceiling. Duplicate hosts are refused. Closing a host closes all of its routes; closing a joiner closes only that route. SIGTERM/SIGINT stop accepting work, close active sockets, and terminate sockets that have not closed within the configured shutdown grace period.
