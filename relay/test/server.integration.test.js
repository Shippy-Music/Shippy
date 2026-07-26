import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { WebSocket } from "ws";
import {
  createRelayServer,
  issueTurnCredential,
  loadConfig,
} from "../src/server.js";
import {
  decode,
  encodeData,
  encodeRegister,
  encodeRoute,
  ROLE,
  TYPE,
} from "../src/protocol.js";

const waitForEvent = (target, event) =>
  new Promise((resolve, reject) => {
    target.once(event, resolve);
    target.once("error", reject);
  });

const nextMessage = (ws) =>
  new Promise((resolve, reject) => {
    ws.once("message", (data, isBinary) =>
      resolve({ data: Buffer.from(data), isBinary }),
    );
    ws.once("error", reject);
  });

async function startRelay(overrides = {}) {
  const app = createRelayServer({
    ...loadConfig({ CREW_RELAY_ALLOW_ANY_ORIGIN: "true" }),
    ...overrides,
  });
  await new Promise((resolve) => app.server.listen(0, "127.0.0.1", resolve));
  const { port } = app.server.address();
  return {
    app,
    url: `ws://127.0.0.1:${port}/v1/crew`,
    healthUrl: `http://127.0.0.1:${port}/healthz`,
    iceUrl: `http://127.0.0.1:${port}/v1/ice`,
  };
}

async function open(url) {
  const ws = new WebSocket(url);
  await waitForEvent(ws, "open");
  return ws;
}

test("health, registration, route opening, and opaque DATA forwarding work over WebSocket", async (t) => {
  const { app, url, healthUrl } = await startRelay();
  const sockets = [];
  t.after(async () => {
    for (const ws of sockets) ws.terminate();
    await app.close();
  });

  const initialHealth = await fetch(healthUrl).then((response) =>
    response.json(),
  );
  assert.deepEqual(initialHealth, {
    ok: true,
    sessions: 0,
    routes: 0,
    connections: 0,
  });

  const host = await open(url);
  sockets.push(host);
  const hostRegistered = nextMessage(host);
  host.send(
    encodeRegister({
      role: ROLE.HOST,
      sessionLocator: Buffer.from("opaque locator"),
      inviteId: Buffer.from("opaque invite"),
    }),
  );
  const hostAck = await hostRegistered;
  assert.equal(hostAck.isBinary, true);
  assert.equal(hostAck.data[1], TYPE.REGISTERED);

  const join = await open(url);
  sockets.push(join);
  const joinRegistered = nextMessage(join);
  const routeOpened = nextMessage(host);
  join.send(
    encodeRegister({
      role: ROLE.JOIN,
      sessionLocator: Buffer.from("opaque locator"),
      inviteId: Buffer.from("opaque invite"),
    }),
  );
  const joinAck = await joinRegistered;
  const hostRoute = await routeOpened;
  assert.equal(joinAck.data[1], TYPE.REGISTERED);
  assert.equal(hostRoute.data[1], TYPE.ROUTE_OPEN);
  const routeId = joinAck.data.subarray(4, 20);
  assert.deepEqual(hostRoute.data.subarray(2, 18), routeId);

  const forwarded = nextMessage(host);
  join.send(encodeData(routeId, Buffer.from("opaque encrypted signaling")));
  const data = await forwarded;
  assert.equal(data.isBinary, true);
  assert.deepEqual(decode(data.data), {
    type: TYPE.DATA,
    routeId,
    payload: Buffer.from("opaque encrypted signaling"),
  });

  const activeHealth = await fetch(healthUrl).then((response) =>
    response.json(),
  );
  assert.deepEqual(activeHealth, {
    ok: true,
    sessions: 1,
    routes: 1,
    connections: 2,
  });

  const hostClosed = nextMessage(host);
  const joinClosed = nextMessage(join);
  host.send(encodeRoute(TYPE.ROUTE_CLOSE, routeId));
  const [hostClose, joinClose] = await Promise.all([hostClosed, joinClosed]);
  assert.equal(hostClose.data[1], TYPE.ROUTE_CLOSE);
  assert.equal(joinClose.data[1], TYPE.ROUTE_CLOSE);
  assert.equal(hostClose.data[18], Buffer.byteLength("ROUTE_CLOSED"));
});

test("shutdown terminates a WebSocket that ignores the close handshake within its grace period", async () => {
  const { app, url } = await startRelay({ shutdownMs: 25 });
  const ws = await open(url);
  const socketClosed = waitForEvent(ws, "close");
  const started = Date.now();
  await app.close();
  assert.ok(Date.now() - started < 500);
  await socketClosed;
});

test("TURN config accepts only TURN URLs and clamps credential TTL", () => {
  const configured = loadConfig({
    CREW_RELAY_TURN_URLS: "turn:turn.example:3478, turns:secure.example:5349?transport=tcp",
    CREW_RELAY_TURN_SECRET: "secret-at-least-16",
    CREW_RELAY_TURN_TTL_SECONDS: "999999",
  });
  assert.deepEqual(configured.turnUrls, [
    "turn:turn.example:3478",
    "turns:secure.example:5349?transport=tcp",
  ]);
  assert.equal(configured.turnTtlSeconds, 3600);
  assert.deepEqual(
    loadConfig({
      CREW_RELAY_TURN_URLS: "https://not-turn.example",
      CREW_RELAY_TURN_TTL_SECONDS: "1",
    }).turnUrls,
    [],
  );
  assert.deepEqual(
    loadConfig({
      CREW_RELAY_TURN_URLS: "turn:turn.example:70000",
    }).turnUrls,
    [],
  );
  assert.equal(
    loadConfig({ CREW_RELAY_TURN_TTL_SECONDS: "1" }).turnTtlSeconds,
    60,
  );
});

test("TURN REST credentials use deterministic expiry and HMAC-SHA1", () => {
  const config = loadConfig({
    CREW_RELAY_TURN_URLS: "turn:turn.example:3478",
    CREW_RELAY_TURN_SECRET: "turn-secret-12345",
  });
  const credential = issueTurnCredential(config, {
    now: () => 1_700_000_123_456,
    random: () => Buffer.alloc(18, 7),
  });
  assert.equal(credential.expiresAtEpochMs, 1_700_000_723_000);
  assert.equal(credential.username, "1700000723:BwcHBwcHBwcHBwcHBwcHBwcH");
  assert.equal(
    credential.credential,
    createHmac("sha1", "turn-secret-12345").update(credential.username).digest("base64"),
  );
});

test("ICE endpoint rejects disabled, malformed, oversized, and inactive requests without issuing credentials", async (t) => {
  const disabled = await startRelay();
  t.after(() => disabled.app.close());
  assert.equal(
    (await fetch(disabled.iceUrl, { method: "POST" })).status,
    503,
  );

  const { app, iceUrl } = await startRelay({
    ...loadConfig({
      CREW_RELAY_ALLOW_ANY_ORIGIN: "true",
      CREW_RELAY_TURN_URLS: "turn:turn.example:3478",
      CREW_RELAY_TURN_SECRET: "turn-secret-12345",
    }),
  });
  t.after(() => app.close());
  const inactive = {
    protocolVersion: 1,
    sessionLocator: "locator",
    inviteId: "invite",
  };
  assert.equal(
    (await fetch(iceUrl, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(inactive),
    })).status,
    404,
  );
  assert.equal(
    (await fetch(iceUrl, {
      method: "POST",
      headers: { "content-type": "text/plain" },
      body: JSON.stringify(inactive),
    })).status,
    400,
  );
  assert.equal(
    (await fetch(iceUrl, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "x".repeat(2049),
    })).status,
    400,
  );
});

test("ICE endpoint issues no-store TURN credentials for an active host registration", async (t) => {
  const config = loadConfig({
    CREW_RELAY_ALLOW_ANY_ORIGIN: "true",
    CREW_RELAY_TURN_URLS: "turn:turn.example:3478,turns:turn.example:5349?transport=tcp",
    CREW_RELAY_TURN_SECRET: "turn-secret-12345",
    CREW_RELAY_TURN_TTL_SECONDS: "600",
  });
  const { app, url, iceUrl } = await startRelay(config);
  const sockets = [];
  t.after(async () => {
    for (const ws of sockets) ws.terminate();
    await app.close();
  });
  const host = await open(url);
  sockets.push(host);
  const registered = nextMessage(host);
  host.send(
    encodeRegister({
      role: ROLE.HOST,
      sessionLocator: Buffer.from("locator"),
      inviteId: Buffer.from("invite"),
    }),
  );
  await registered;
  const response = await fetch(iceUrl, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      protocolVersion: 1,
      sessionLocator: "locator",
      inviteId: "invite",
    }),
  });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const body = await response.json();
  assert.deepEqual(body.iceServers[0].urls, config.turnUrls);
  assert.match(body.iceServers[0].username, /^\d+:[A-Za-z0-9_-]+$/);
  assert.equal(
    body.iceServers[0].credential,
    createHmac("sha1", "turn-secret-12345")
      .update(body.iceServers[0].username)
      .digest("base64"),
  );
  assert.equal(body.expiresAtEpochMs, Number(body.iceServers[0].username.split(":")[0]) * 1000);
});
