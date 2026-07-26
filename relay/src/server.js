import http from "node:http";
import { WebSocketServer, WebSocket } from "ws";
import {
  decode,
  encodeError,
  encodeHeartbeat,
  ProtocolError,
  TYPE,
} from "./protocol.js";
import { RelayRegistry } from "./registry.js";

const intEnv = (env, name, fallback) => {
  const value = Number.parseInt(env[name] ?? "", 10);
  return Number.isSafeInteger(value) && value > 0 ? value : fallback;
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
    maxInflight: intEnv(env, "CREW_RELAY_MAX_INFLIGHT_BYTES", 1024 * 1024),
    registrationMs: intEnv(env, "CREW_RELAY_REGISTRATION_MS", 10_000),
    idleMs: intEnv(env, "CREW_RELAY_IDLE_MS", 90_000),
    heartbeatMs: intEnv(env, "CREW_RELAY_HEARTBEAT_MS", 30_000),
    shutdownMs: intEnv(env, "CREW_RELAY_SHUTDOWN_MS", 1_000),
    rate: intEnv(env, "CREW_RELAY_MESSAGES_PER_MINUTE", 240),
    allowedOrigins,
    allowAnyOrigin: env.CREW_RELAY_ALLOW_ANY_ORIGIN === "true",
  });
}

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
          if (message.type !== TYPE.REGISTER)
            return close(client, "REGISTER_REQUIRED");
          const result = registry.register(client, message);
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
