import test from "node:test";
import assert from "node:assert/strict";
import { WebSocket } from "ws";
import { createRelayServer, loadConfig } from "../src/server.js";
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
