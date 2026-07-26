import test from "node:test";
import assert from "node:assert/strict";
import { decode, encodeRegister, ROLE, TYPE } from "../src/protocol.js";
import { RelayRegistry } from "../src/registry.js";

const registration = (role, locator = "a", invite = "b") => ({
  role,
  protocolVersion: 1,
  sessionLocator: Buffer.from(locator),
  inviteId: Buffer.from(invite),
});
function fixture(options = {}) {
  const sent = new Map();
  const deliver = (to, frame) => {
    (sent.get(to) ?? sent.set(to, []).get(to)).push(frame);
    return !to.blocked;
  };
  const registry = new RelayRegistry({
    deliver,
    randomRoute: () => Buffer.alloc(16, 7),
    ...options,
  });
  return { registry, sent };
}

test("host and join form one isolated opaque route and forward only exact route data", () => {
  const { registry, sent } = fixture();
  const host = {},
    join = {},
    other = {};
  assert.equal(registry.register(host, registration(ROLE.HOST, "x")).ok, true);
  const opened = registry.register(join, registration(ROLE.JOIN, "x"));
  assert.equal(opened.ok, true);
  assert.equal(
    registry.forward(join, {
      routeId: opened.routeId,
      payload: Buffer.from("ciphertext"),
    }).ok,
    true,
  );
  assert.equal(sent.get(host).at(-1)[1], TYPE.DATA);
  assert.deepEqual(
    registry.forward(other, {
      routeId: opened.routeId,
      payload: Buffer.from("x"),
    }),
    { ok: false, reason: "NOT_REGISTERED" },
  );
  assert.equal(
    registry.register({}, registration(ROLE.JOIN, "different")).reason,
    "HOST_UNAVAILABLE",
  );
});
test("limits and duplicate hosts are rejected without replacing a live host", () => {
  const { registry } = fixture({ maxSessions: 1, maxJoinsPerSession: 1 });
  const host = {};
  assert.equal(registry.register(host, registration(ROLE.HOST)).ok, true);
  assert.equal(
    registry.register({}, registration(ROLE.HOST)).reason,
    "DUPLICATE_HOST",
  );
  assert.equal(
    registry.register({}, registration(ROLE.HOST, "second")).reason,
    "SESSION_LIMIT",
  );
  assert.equal(registry.register({}, registration(ROLE.JOIN)).ok, true);
  assert.equal(
    registry.register({}, registration(ROLE.JOIN)).reason,
    "JOIN_LIMIT",
  );
});
test("length-prefixed opaque session keys do not collide when identifiers contain delimiters", () => {
  const { registry } = fixture();
  assert.equal(
    registry.register({}, registration(ROLE.HOST, "a\0b", "c")).ok,
    true,
  );
  assert.equal(
    registry.register({}, registration(ROLE.HOST, "a", "b\0c")).ok,
    true,
  );
  assert.deepEqual(registry.counts(), {
    sessions: 2,
    routes: 0,
    connections: 2,
  });
});
test("hasSession confirms only an active exact host registration", () => {
  const { registry } = fixture();
  const host = {};
  registry.register(host, registration(ROLE.HOST, "locator", "invite"));
  assert.equal(
    registry.hasSession({ sessionLocator: "locator", inviteId: "invite" }),
    true,
  );
  assert.equal(
    registry.hasSession({ sessionLocator: "locator", inviteId: "other" }),
    false,
  );
  registry.close(host);
  assert.equal(
    registry.hasSession({ sessionLocator: "locator", inviteId: "invite" }),
    false,
  );
});
test("registration rolls back when delivery of REGISTERED or ROUTE_OPEN fails", () => {
  const host = {};
  const failedHostDelivery = new RelayRegistry({ deliver: () => false });
  assert.deepEqual(failedHostDelivery.register(host, registration(ROLE.HOST)), {
    ok: false,
    reason: "REGISTRATION_DELIVERY_FAILED",
  });
  assert.deepEqual(failedHostDelivery.counts(), {
    sessions: 0,
    routes: 0,
    connections: 0,
  });

  let failRouteOpen = false;
  const registry = new RelayRegistry({
    deliver: (to, frame) =>
      !(failRouteOpen && to === host && frame[1] === TYPE.ROUTE_OPEN),
  });
  assert.equal(registry.register(host, registration(ROLE.HOST)).ok, true);
  failRouteOpen = true;
  assert.deepEqual(registry.register({}, registration(ROLE.JOIN)), {
    ok: false,
    reason: "REGISTRATION_DELIVERY_FAILED",
  });
  assert.deepEqual(registry.counts(), {
    sessions: 1,
    routes: 0,
    connections: 1,
  });
});
test("join closure affects only its route, while host closure cleans every route", () => {
  let next = 1;
  const { registry } = fixture({ randomRoute: () => Buffer.alloc(16, next++) });
  const host = {},
    one = {},
    two = {};
  registry.register(host, registration(ROLE.HOST));
  registry.register(one, registration(ROLE.JOIN));
  registry.register(two, registration(ROLE.JOIN));
  registry.close(one);
  assert.deepEqual(registry.counts(), {
    sessions: 1,
    routes: 1,
    connections: 2,
  });
  registry.close(host);
  assert.deepEqual(registry.counts(), {
    sessions: 1,
    routes: 0,
    connections: 0,
  });
});
test("backpressured recipient closes the exact route instead of buffering or replaying", () => {
  const { registry } = fixture();
  const host = {},
    join = {};
  registry.register(host, registration(ROLE.HOST));
  const opened = registry.register(join, registration(ROLE.JOIN));
  join.blocked = true;
  assert.deepEqual(
    registry.forward(host, {
      routeId: opened.routeId,
      payload: Buffer.from("opaque"),
    }),
    { ok: false, reason: "RECIPIENT_UNAVAILABLE" },
  );
  assert.deepEqual(registry.counts(), {
    sessions: 1,
    routes: 0,
    connections: 1,
  });
});
test("an authenticated host or route joiner can close only its live route", () => {
  let next = 1;
  const { registry } = fixture({ randomRoute: () => Buffer.alloc(16, next++) });
  const host = {},
    one = {},
    two = {};
  registry.register(host, registration(ROLE.HOST));
  const routeOne = registry.register(one, registration(ROLE.JOIN)).routeId;
  const routeTwo = registry.register(two, registration(ROLE.JOIN)).routeId;
  assert.deepEqual(registry.closeRequested(one, routeTwo), {
    ok: false,
    reason: "ROUTE_FORBIDDEN",
  });
  assert.deepEqual(registry.closeRequested(one, routeOne), { ok: true });
  assert.deepEqual(registry.closeRequested(host, routeTwo), { ok: true });
  assert.deepEqual(registry.counts(), {
    sessions: 1,
    routes: 0,
    connections: 1,
  });
});

test("host disconnect holds bounded presence and only its rotated opaque token can resume", () => {
  let now = 1_000;
  let nextToken = 1;
  const { registry, sent } = fixture({
    now: () => now,
    hostPresenceMs: 30_000,
    randomToken: () => Buffer.alloc(32, nextToken++),
  });
  const host = {};
  assert.equal(registry.register(host, registration(ROLE.HOST, "resume")).ok, true);
  const initialToken = decode(sent.get(host).at(-1)).resumeToken;
  assert.equal(
    registry.resume({}, { ...registration(ROLE.HOST, "resume"), resumeToken: initialToken }).reason,
    "HOST_UNAVAILABLE",
  );
  registry.close(host);
  assert.deepEqual(registry.counts(), { sessions: 1, routes: 0, connections: 0 });
  assert.equal(registry.register({}, registration(ROLE.JOIN, "resume")).reason, "HOST_UNAVAILABLE");
  assert.equal(
    registry.resume({}, { ...registration(ROLE.HOST, "resume"), resumeToken: Buffer.alloc(32, 9) }).reason,
    "INVALID_RESUME",
  );
  const resumedHost = {};
  assert.equal(
    registry.resume(resumedHost, { ...registration(ROLE.HOST, "resume"), resumeToken: initialToken }).ok,
    true,
  );
  const rotatedToken = decode(sent.get(resumedHost).at(-1)).resumeToken;
  assert.notDeepEqual(rotatedToken, initialToken);
  registry.close(resumedHost);
  assert.equal(
    registry.resume({}, { ...registration(ROLE.HOST, "resume"), resumeToken: initialToken }).reason,
    "INVALID_RESUME",
  );
  now += 30_000;
  registry.sweep();
  assert.equal(registry.counts().sessions, 0);
  assert.equal(
    registry.resume({}, { ...registration(ROLE.HOST, "resume"), resumeToken: rotatedToken }).reason,
    "HOST_UNAVAILABLE",
  );
});
