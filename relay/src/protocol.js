import { randomBytes } from "node:crypto";

export const ENVELOPE_VERSION = 1;
export const ROLE = Object.freeze({ HOST: 1, JOIN: 2 });
export const TYPE = Object.freeze({
  REGISTER: 1,
  REGISTERED: 2,
  ROUTE_OPEN: 3,
  ROUTE_CLOSE: 4,
  DATA: 5,
  ERROR: 6,
  PING: 7,
  PONG: 8,
  HOST_RESUME: 9,
});
export const ROUTE_ID_BYTES = 16;
export const RESUME_TOKEN_BYTES = 32;
export const LIMITS = Object.freeze({
  locator: 96,
  invite: 64,
  data: 32 * 1024,
  frame: 33 * 1024,
  reason: 64,
});

export class ProtocolError extends Error {
  constructor(reason) {
    super(reason);
    this.reason = reason;
  }
}
const fail = (reason) => {
  throw new ProtocolError(reason);
};
const bytes = (value) => (Buffer.isBuffer(value) ? value : Buffer.from(value));
const bounded = (value, max, reason) => {
  const out = bytes(value);
  if (out.length < 1 || out.length > max) fail(reason);
  return out;
};
const header = (type, size) => {
  const out = Buffer.allocUnsafe(2 + size);
  out[0] = ENVELOPE_VERSION;
  out[1] = type;
  return out;
};

export function encodeRegister({
  role,
  protocolVersion = 1,
  sessionLocator,
  inviteId,
}) {
  if (![ROLE.HOST, ROLE.JOIN].includes(role) || protocolVersion !== 1)
    fail("BAD_REGISTER");
  const locator = bounded(sessionLocator, LIMITS.locator, "BAD_LOCATOR");
  const invite = bounded(inviteId, LIMITS.invite, "BAD_INVITE");
  const out = header(TYPE.REGISTER, 4 + locator.length + invite.length);
  out[2] = role;
  out[3] = protocolVersion;
  out[4] = locator.length;
  locator.copy(out, 5);
  out[5 + locator.length] = invite.length;
  invite.copy(out, 6 + locator.length);
  return out;
}

export function encodeRegistered({ role, routeId = null, resumeToken = null }) {
  if (![ROLE.HOST, ROLE.JOIN].includes(role)) fail("BAD_REGISTERED");
  const route = routeId ? routeBytes(routeId) : null;
  const token = resumeToken ? resumeTokenBytes(resumeToken) : null;
  if ((role === ROLE.HOST && (route || !token)) || (role === ROLE.JOIN && (!route || token)))
    fail("BAD_REGISTERED");
  const payload = route ?? token;
  const out = header(TYPE.REGISTERED, 2 + payload.length);
  out[2] = role;
  out[3] = route ? 1 : 0;
  payload.copy(out, 4);
  return out;
}
export function encodeHostResume({ protocolVersion = 1, sessionLocator, inviteId, resumeToken }) {
  if (protocolVersion !== 1) fail("BAD_HOST_RESUME");
  const locator = bounded(sessionLocator, LIMITS.locator, "BAD_LOCATOR");
  const invite = bounded(inviteId, LIMITS.invite, "BAD_INVITE");
  const token = resumeTokenBytes(resumeToken);
  const out = header(TYPE.HOST_RESUME, 3 + locator.length + invite.length + token.length);
  out[2] = protocolVersion;
  out[3] = locator.length;
  locator.copy(out, 4);
  out[4 + locator.length] = invite.length;
  invite.copy(out, 5 + locator.length);
  token.copy(out, 5 + locator.length + invite.length);
  return out;
}
export function encodeRoute(type, routeId, reason = "") {
  const route = routeBytes(routeId);
  const why = reason
    ? bounded(reason, LIMITS.reason, "BAD_REASON")
    : Buffer.alloc(0);
  const out = header(type, ROUTE_ID_BYTES + 1 + why.length);
  route.copy(out, 2);
  out[18] = why.length;
  why.copy(out, 19);
  return out;
}
export const encodeData = (routeId, payload) => {
  const route = routeBytes(routeId);
  const body = bounded(payload, LIMITS.data, "DATA_TOO_LARGE");
  const out = header(TYPE.DATA, ROUTE_ID_BYTES + body.length);
  route.copy(out, 2);
  body.copy(out, 18);
  return out;
};
export const encodeError = (reason) => {
  const body = bounded(reason, LIMITS.reason, "BAD_REASON");
  const out = header(TYPE.ERROR, 1 + body.length);
  out[2] = body.length;
  body.copy(out, 3);
  return out;
};
export const encodeHeartbeat = (type) => header(type, 0);
export const newRouteId = (random = randomBytes) => random(ROUTE_ID_BYTES);
export const routeKey = (routeId) => routeBytes(routeId).toString("base64url");
export const routeBytes = (routeId) => {
  const out = bytes(routeId);
  if (out.length !== ROUTE_ID_BYTES) fail("BAD_ROUTE");
  return out;
};
export const resumeTokenBytes = (token) => {
  const out = bytes(token);
  if (out.length !== RESUME_TOKEN_BYTES) fail("BAD_RESUME_TOKEN");
  return out;
};

export function decode(frame) {
  const input = bytes(frame);
  if (input.length < 2 || input.length > LIMITS.frame) fail("BAD_FRAME");
  if (input[0] !== ENVELOPE_VERSION) fail("UNSUPPORTED_VERSION");
  const type = input[1];
  if (type === TYPE.REGISTER) {
    if (input.length < 7) fail("BAD_REGISTER");
    const role = input[2];
    const protocolVersion = input[3];
    const locatorLength = input[4];
    const inviteAt = 5 + locatorLength;
    if (
      ![ROLE.HOST, ROLE.JOIN].includes(role) ||
      protocolVersion !== 1 ||
      locatorLength < 1 ||
      locatorLength > LIMITS.locator ||
      inviteAt >= input.length
    )
      fail("BAD_REGISTER");
    const inviteLength = input[inviteAt];
    if (
      inviteLength < 1 ||
      inviteLength > LIMITS.invite ||
      inviteAt + 1 + inviteLength !== input.length
    )
      fail("BAD_REGISTER");
    return {
      type,
      role,
      protocolVersion,
      sessionLocator: input.subarray(5, inviteAt),
      inviteId: input.subarray(inviteAt + 1),
    };
  }
  if (type === TYPE.HOST_RESUME) {
    if (input.length < 2 + 3 + RESUME_TOKEN_BYTES) fail("BAD_HOST_RESUME");
    const protocolVersion = input[2];
    const locatorLength = input[3];
    const inviteAt = 4 + locatorLength;
    if (
      protocolVersion !== 1 ||
      locatorLength < 1 ||
      locatorLength > LIMITS.locator ||
      inviteAt >= input.length
    )
      fail("BAD_HOST_RESUME");
    const inviteLength = input[inviteAt];
    const tokenAt = inviteAt + 1 + inviteLength;
    if (
      inviteLength < 1 ||
      inviteLength > LIMITS.invite ||
      tokenAt + RESUME_TOKEN_BYTES !== input.length
    )
      fail("BAD_HOST_RESUME");
    return {
      type,
      protocolVersion,
      sessionLocator: input.subarray(4, inviteAt),
      inviteId: input.subarray(inviteAt + 1, tokenAt),
      resumeToken: input.subarray(tokenAt),
    };
  }
  if (type === TYPE.REGISTERED) {
    if (input.length < 4) fail("BAD_REGISTERED");
    const role = input[2];
    const hasRoute = input[3];
    if (![ROLE.HOST, ROLE.JOIN].includes(role) || ![0, 1].includes(hasRoute))
      fail("BAD_REGISTERED");
    if (role === ROLE.HOST && hasRoute === 0 && input.length === 4 + RESUME_TOKEN_BYTES)
      return { type, role, resumeToken: input.subarray(4) };
    if (role === ROLE.JOIN && hasRoute === 1 && input.length === 4 + ROUTE_ID_BYTES)
      return { type, role, routeId: input.subarray(4) };
    fail("BAD_REGISTERED");
  }
  if (type === TYPE.DATA) {
    if (input.length <= 18 || input.length - 18 > LIMITS.data) fail("BAD_DATA");
    return {
      type,
      routeId: input.subarray(2, 18),
      payload: input.subarray(18),
    };
  }
  if (type === TYPE.ROUTE_CLOSE) {
    if (input.length !== 19 || input[18] !== 0) fail("BAD_ROUTE_CLOSE");
    return { type, routeId: input.subarray(2, 18) };
  }
  if (type === TYPE.PING || type === TYPE.PONG) {
    if (input.length !== 2) fail("BAD_HEARTBEAT");
    return { type };
  }
  fail("UNEXPECTED_FRAME");
}
