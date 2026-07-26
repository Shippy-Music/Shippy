import test from "node:test";
import assert from "node:assert/strict";
import {
  decode,
  encodeData,
  encodeRegister,
  encodeRoute,
  LIMITS,
  ProtocolError,
  ROLE,
  TYPE,
} from "../src/protocol.js";

test("register envelope round-trips bounded opaque identifiers", () => {
  const frame = encodeRegister({
    role: ROLE.HOST,
    sessionLocator: Buffer.from("locator"),
    inviteId: Buffer.from("invite"),
  });
  assert.deepEqual(decode(frame), {
    type: TYPE.REGISTER,
    role: ROLE.HOST,
    protocolVersion: 1,
    sessionLocator: Buffer.from("locator"),
    inviteId: Buffer.from("invite"),
  });
});
test("codec rejects corrupt, text-like, and oversized data frames", () => {
  assert.throws(() => decode(Buffer.from([1, TYPE.DATA])), ProtocolError);
  assert.throws(
    () => decode(Buffer.from([9, TYPE.PING])),
    /UNSUPPORTED_VERSION/,
  );
  assert.throws(
    () => encodeData(Buffer.alloc(16), Buffer.alloc(32 * 1024 + 1)),
    /DATA_TOO_LARGE/,
  );
  const oversizedInbound = Buffer.alloc(18 + LIMITS.data + 1);
  oversizedInbound[0] = 1;
  oversizedInbound[1] = TYPE.DATA;
  assert.throws(() => decode(oversizedInbound), /BAD_DATA/);
});
test("inbound route close accepts only an empty client reason", () => {
  const routeId = Buffer.alloc(16, 9);
  assert.deepEqual(decode(encodeRoute(TYPE.ROUTE_CLOSE, routeId)), {
    type: TYPE.ROUTE_CLOSE,
    routeId,
  });
  assert.throws(
    () => decode(encodeRoute(TYPE.ROUTE_CLOSE, routeId, "client reason")),
    /BAD_ROUTE_CLOSE/,
  );
});
