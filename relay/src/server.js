import http from "node:http";
import { createHmac, randomBytes } from "node:crypto";
import { WebSocketServer, WebSocket } from "ws";
import {
  decode,
  encodeError,
  encodeHeartbeat,
  ProtocolError,
  TYPE,
} from "./protocol.js";
import { RelayRegistry } from "./registry.js";
import { LIMITS } from "./protocol.js";

const ICE_BODY_BYTES = 2 * 1024;
const TURN_TTL_DEFAULT = 600;
const TURN_TTL_MIN = 60;
const TURN_TTL_MAX = 3600;
const TURN_URLS_MAX = 4;
const TURN_URL_BYTES_MAX = 512;
const TURN_SECRET_BYTES_MIN = 16;
const TURN_SECRET_BYTES_MAX = 256;
const TURN_URL_PATTERN =
  /^(turn|turns):[A-Za-z0-9.-]+(?::([0-9]{1,5}))?(?:\?transport=(udp|tcp))?$/;

const intEnv = (env, name, fallback) => {
  const value = Number.parseInt(env[name] ?? "", 10);
  return Number.isSafeInteger(value) && value > 0 ? value : fallback;
};

const boundedIntEnv = (env, name, fallback, min, max) => {
  const value = intEnv(env, name, fallback);
  return Math.min(max, Math.max(min, value));
};

const turnUrls = (value) => {
  const urls = (value ?? "")
    .split(",")
    .map((url) => url.trim())
    .filter(Boolean);
  if (!urls.length || urls.length > TURN_URLS_MAX) return [];
  return urls.every((url) => {
    if (Buffer.byteLength(url) > TURN_URL_BYTES_MAX) return false;
    const match = TURN_URL_PATTERN.exec(url);
    if (!match) return false;
    const port = match[2];
    return !port || (Number(port) >= 1 && Number(port) <= 65535);
  })
    ? urls
    : [];
};

const turnSecret = (value) => {
  const secret = value ?? "";
  const size = Buffer.byteLength(secret);
  return size >= TURN_SECRET_BYTES_MIN && size <= TURN_SECRET_BYTES_MAX
    ? secret
    : "";
};

export function loadConfig(env = process.env) {
  const allowedOrigins = (env.CREW_RELAY_ALLOWED_ORIGINS ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  return Object.freeze({
    port: intEnv(env, "PORT", 8080),
    maxSessions: intEnv(env, "CREW_RELAY_MAX_SESSIONS", 256),
    maxJoins: intEnv(env, "CREW_RELAY_MAX_JOINS", 16),
    hostPresenceMs: boundedIntEnv(
      env,
      "CREW_RELAY_HOST_PRESENCE_MS",
      30_000,
      5_000,
      120_000,
    ),
    maxInflight: intEnv(env, "CREW_RELAY_MAX_INFLIGHT_BYTES", 1024 * 1024),
    registrationMs: intEnv(env, "CREW_RELAY_REGISTRATION_MS", 10_000),
    idleMs: intEnv(env, "CREW_RELAY_IDLE_MS", 90_000),
    heartbeatMs: intEnv(env, "CREW_RELAY_HEARTBEAT_MS", 30_000),
    shutdownMs: intEnv(env, "CREW_RELAY_SHUTDOWN_MS", 1_000),
    rate: intEnv(env, "CREW_RELAY_MESSAGES_PER_MINUTE", 240),
    turnUrls: turnUrls(env.CREW_RELAY_TURN_URLS),
    turnSecret: turnSecret(env.CREW_RELAY_TURN_SECRET),
    turnTtlSeconds: boundedIntEnv(
      env,
      "CREW_RELAY_TURN_TTL_SECONDS",
      TURN_TTL_DEFAULT,
      TURN_TTL_MIN,
      TURN_TTL_MAX,
    ),
    allowedOrigins,
    allowAnyOrigin: env.CREW_RELAY_ALLOW_ANY_ORIGIN === "true",
  });
}

export function issueTurnCredential(
  config,
  { now = Date.now, random = randomBytes } = {},
) {
  const expiresAtEpochMs =
    (Math.floor(now() / 1000) + config.turnTtlSeconds) * 1000;
  const username = `${expiresAtEpochMs / 1000}:${random(18).toString("base64url")}`;
  const credential = createHmac("sha1", config.turnSecret)
    .update(username)
    .digest("base64");
  return { username, credential, expiresAtEpochMs };
}

const validIceRegistration = (body) => {
  if (!body || typeof body !== "object" || Array.isArray(body)) return null;
  if (body.protocolVersion !== 1) return null;
  for (const [name, limit] of [
    ["sessionLocator", LIMITS.locator],
    ["inviteId", LIMITS.invite],
  ]) {
    if (typeof body[name] !== "string") return null;
    const size = Buffer.byteLength(body[name]);
    if (size < 1 || size > limit) return null;
  }
  return {
    sessionLocator: body.sessionLocator,
    inviteId: body.inviteId,
  };
};

const readJsonBody = (req) =>
  new Promise((resolve) => {
    const declaredLength = req.headers["content-length"];
    const contentLength = Number(declaredLength);
    if (
      declaredLength !== undefined &&
      (!/^\d+$/.test(declaredLength) || contentLength > ICE_BODY_BYTES)
    ) {
      req.resume();
      return resolve(null);
    }
    let size = 0;
    const chunks = [];
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size <= ICE_BODY_BYTES) chunks.push(chunk);
    });
    req.on("end", () => {
      if (size > ICE_BODY_BYTES) return resolve(null);
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch {
        resolve(null);
      }
    });
    req.on("error", () => resolve(null));
  });

const log = (event, detail = {}) =>
  process.stdout.write(
    `${JSON.stringify({ ts: new Date().toISOString(), event, ...detail })}\n`,
  );

export function createRelayServer(config = loadConfig()) {
  let inflight = 0;
  const clients = new Set();
  const send = (client, frame) => {
    if (
      client.ws.readyState !== WebSocket.OPEN ||
      client.ws.bufferedAmount + frame.length > config.maxInflight ||
      inflight + frame.length > config.maxInflight
    )
      return false;
    inflight += frame.length;
    try {
      client.ws.send(frame, { binary: true }, () => {
        inflight = Math.max(0, inflight - frame.length);
      });
      return true;
    } catch {
      inflight = Math.max(0, inflight - frame.length);
      return false;
    }
  };

  const registry = new RelayRegistry({
    maxSessions: config.maxSessions,
    maxJoinsPerSession: config.maxJoins,
    hostPresenceMs: config.hostPresenceMs,
    deliver: send,
  });
  const server = http.createServer((req, res) => {
    if (req.method === "GET" && req.url === "/healthz") {
      const counts = registry.counts();
      res.writeHead(200, {
        "content-type": "application/json",
        "cache-control": "no-store",
      });
      res.end(JSON.stringify({ ok: true, ...counts }));
      return;
    }
    if (req.method === "POST" && new URL(req.url, "http://relay").pathname === "/v1/ice") {
      if (!config.turnUrls.length || !config.turnSecret) {
        req.resume();
        res.writeHead(503, { "cache-control": "no-store" }).end();
        return;
      }
      if (!/^application\/json(?:\s*;|$)/i.test(req.headers["content-type"] ?? "")) {
        req.resume();
        res.writeHead(400, { "cache-control": "no-store" }).end();
        return;
      }
      readJsonBody(req).then((body) => {
        const registration = validIceRegistration(body);
        if (!registration) {
          res.writeHead(400, { "cache-control": "no-store" }).end();
          return;
        }
        if (!registry.hasSession(registration)) {
          res.writeHead(404, { "cache-control": "no-store" }).end();
          return;
        }
        const { username, credential, expiresAtEpochMs } = issueTurnCredential(config);
        res.writeHead(200, {
          "content-type": "application/json",
          "cache-control": "no-store",
        });
        res.end(
          JSON.stringify({
            iceServers: [{ urls: config.turnUrls, username, credential }],
            expiresAtEpochMs,
          }),
        );
      });
      return;
    }
    res.writeHead(404).end();
  });
  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: 33 * 1024,
    perMessageDeflate: false,
  });

  server.on("upgrade", (req, socket, head) => {
    if (new URL(req.url, "http://relay").pathname !== "/v1/crew")
      return socket.destroy();
    const origin = req.headers.origin;
    if (
      origin &&
      !config.allowAnyOrigin &&
      !config.allowedOrigins.includes(origin)
    ) {
      socket.write("HTTP/1.1 403 Forbidden\r\n\r\n");
      return socket.destroy();
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws));
  });

  wss.on("connection", (ws) => {
    const client = {
      ws,
      registered: false,
      lastSeen: Date.now(),
      windowAt: Date.now(),
      messages: 0,
    };
    clients.add(client);
    const close = (target, reason) => {
      if (target.ws.readyState === WebSocket.OPEN) {
        send(target, encodeError(reason));
        target.ws.close(1008, reason);
      }
    };
    const registrationTimer = setTimeout(() => {
      if (!client.registered) close(client, "REGISTRATION_TIMEOUT");
    }, config.registrationMs);

    ws.on("message", (raw, isBinary) => {
      client.lastSeen = Date.now();
      if (!isBinary) return close(client, "BINARY_REQUIRED");

      const now = Date.now();
      if (now - client.windowAt >= 60_000) {
        client.windowAt = now;
        client.messages = 0;
      }
      if (++client.messages > config.rate) return close(client, "RATE_LIMIT");

      try {
        const message = decode(raw);
        if (!client.registered) {
          if (message.type !== TYPE.REGISTER && message.type !== TYPE.HOST_RESUME)
            return close(client, "REGISTER_REQUIRED");
          const result =
            message.type === TYPE.HOST_RESUME
              ? registry.resume(client, message)
              : registry.register(client, message);
          if (!result.ok) return close(client, result.reason);
          client.registered = true;
          clearTimeout(registrationTimer);
          log("registered", registry.counts());
          return;
        }
        if (message.type === TYPE.PING)
          return send(client, encodeHeartbeat(TYPE.PONG));
        if (message.type === TYPE.ROUTE_CLOSE) {
          const result = registry.closeRequested(client, message.routeId);
          if (!result.ok) close(client, result.reason);
          return;
        }
        if (message.type !== TYPE.DATA)
          return close(client, "UNEXPECTED_FRAME");
        const result = registry.forward(client, message);
        if (!result.ok) close(client, result.reason);
      } catch (error) {
        close(
          client,
          error instanceof ProtocolError ? error.reason : "BAD_FRAME",
        );
      }
    });
    ws.on("pong", () => {
      client.lastSeen = Date.now();
    });
    ws.on("close", () => {
      clearTimeout(registrationTimer);
      clients.delete(client);
      registry.close(client);
      log("closed", registry.counts());
    });
    ws.on("error", () => {});
  });

  const heartbeat = setInterval(() => {
    const now = Date.now();
    registry.sweep(now);
    for (const client of clients) {
      if (now - client.lastSeen > config.idleMs) client.ws.terminate();
      else if (client.ws.readyState === WebSocket.OPEN) client.ws.ping();
    }
  }, config.heartbeatMs);

  const close = async () => {
    clearInterval(heartbeat);
    for (const client of clients) client.ws.close(1001, "SERVER_SHUTDOWN");
    server.closeIdleConnections?.();
    const closed = new Promise((resolve) => server.close(resolve));
    let forceShutdown;
    const timeout = new Promise((resolve) => {
      forceShutdown = setTimeout(resolve, config.shutdownMs);
    });
    await Promise.race([closed, timeout]);
    clearTimeout(forceShutdown);
    for (const client of clients) client.ws.terminate();
    server.closeAllConnections?.();
  };
  return { server, wss, registry, close };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const app = createRelayServer();
  app.server.listen(loadConfig().port, () =>
    log("listening", { port: loadConfig().port }),
  );
  const shutdown = () => app.close().finally(() => process.exit(0));
  process.once("SIGTERM", shutdown);
  process.once("SIGINT", shutdown);
}
