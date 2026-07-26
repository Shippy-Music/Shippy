import {
  ROLE,
  TYPE,
  encodeData,
  encodeRegistered,
  encodeRoute,
  newRouteId,
  routeKey,
} from "./protocol.js";

const sessionKey = ({ sessionLocator, inviteId }) => {
  const locator = Buffer.from(sessionLocator);
  const invite = Buffer.from(inviteId);
  const key = Buffer.allocUnsafe(4 + locator.length + invite.length);
  key.writeUInt16BE(locator.length, 0);
  locator.copy(key, 2);
  key.writeUInt16BE(invite.length, 2 + locator.length);
  invite.copy(key, 4 + locator.length);
  return key.toString("base64url");
};

export class RelayRegistry {
  constructor({
    maxSessions = 256,
    maxJoinsPerSession = 16,
    randomRoute = newRouteId,
    deliver,
  }) {
    this.maxSessions = maxSessions;
    this.maxJoinsPerSession = maxJoinsPerSession;
    this.randomRoute = randomRoute;
    this.deliver = deliver;
    this.sessions = new Map();
    this.members = new Map();
  }

  register(connection, registration) {
    const key = sessionKey(registration);
    if (this.members.has(connection))
      return { ok: false, reason: "ALREADY_REGISTERED" };

    let session = this.sessions.get(key);
    if (registration.role === ROLE.HOST) {
      if (session) return { ok: false, reason: "DUPLICATE_HOST" };
      if (this.sessions.size >= this.maxSessions)
        return { ok: false, reason: "SESSION_LIMIT" };

      session = { key, host: connection, routes: new Map() };
      this.sessions.set(key, session);
      this.members.set(connection, { session, role: ROLE.HOST });
      if (!this.deliver(connection, encodeRegistered({ role: ROLE.HOST }))) {
        this.members.delete(connection);
        this.sessions.delete(key);
        return { ok: false, reason: "REGISTRATION_DELIVERY_FAILED" };
      }
      return { ok: true };
    }

    if (!session) return { ok: false, reason: "HOST_UNAVAILABLE" };
    if (session.routes.size >= this.maxJoinsPerSession)
      return { ok: false, reason: "JOIN_LIMIT" };

    const routeId = this.randomRoute();
    const keyRoute = routeKey(routeId);
    if (session.routes.has(keyRoute))
      return { ok: false, reason: "ROUTE_COLLISION" };

    const route = { id: routeId, join: connection };
    session.routes.set(keyRoute, route);
    this.members.set(connection, { session, role: ROLE.JOIN, route });
    const registered = this.deliver(
      connection,
      encodeRegistered({ role: ROLE.JOIN, routeId }),
    );
    const opened =
      registered &&
      this.deliver(session.host, encodeRoute(TYPE.ROUTE_OPEN, routeId));
    if (!opened) {
      this.members.delete(connection);
      session.routes.delete(keyRoute);
      return { ok: false, reason: "REGISTRATION_DELIVERY_FAILED" };
    }
    return { ok: true, routeId };
  }

  forward(connection, { routeId, payload }) {
    const member = this.members.get(connection);
    if (!member) return { ok: false, reason: "NOT_REGISTERED" };

    const route = member.session.routes.get(routeKey(routeId));
    if (!route || (member.role === ROLE.JOIN && route.join !== connection))
      return { ok: false, reason: "ROUTE_FORBIDDEN" };

    const recipient =
      member.role === ROLE.HOST ? route.join : member.session.host;
    if (!this.deliver(recipient, encodeData(route.id, payload))) {
      this.closeRoute(member.session, route, "RECIPIENT_UNAVAILABLE");
      return { ok: false, reason: "RECIPIENT_UNAVAILABLE" };
    }
    return { ok: true };
  }

  closeRequested(connection, routeId) {
    const member = this.members.get(connection);
    if (!member) return { ok: false, reason: "NOT_REGISTERED" };
    const route = member.session.routes.get(routeKey(routeId));
    if (!route || (member.role === ROLE.JOIN && route.join !== connection)) {
      return { ok: false, reason: "ROUTE_FORBIDDEN" };
    }
    this.closeRoute(member.session, route, "ROUTE_CLOSED");
    return { ok: true };
  }

  close(connection, reason = "DISCONNECTED") {
    const member = this.members.get(connection);
    if (!member) return;

    if (member.role === ROLE.HOST) {
      for (const route of [...member.session.routes.values()])
        this.closeRoute(member.session, route, "HOST_CLOSED");
      this.sessions.delete(member.session.key);
    } else {
      this.closeRoute(member.session, member.route, reason);
    }
    this.members.delete(connection);
  }

  closeRoute(session, route, reason) {
    if (!session.routes.delete(routeKey(route.id))) return;
    this.members.delete(route.join);
    this.deliver(session.host, encodeRoute(TYPE.ROUTE_CLOSE, route.id, reason));
    this.deliver(route.join, encodeRoute(TYPE.ROUTE_CLOSE, route.id, reason));
  }

  counts() {
    let routes = 0;
    for (const session of this.sessions.values()) routes += session.routes.size;
    return {
      sessions: this.sessions.size,
      routes,
      connections: this.members.size,
    };
  }
}
