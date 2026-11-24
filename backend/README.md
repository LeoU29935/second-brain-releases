# Second Brain workspace server

The server runs independently of Android. It uses Node.js 24, SQLite, and the built-in HTTP server. SQLite keeps setup local and avoids a required cloud account. Run a single instance per database file on local persistent storage. Back up the SQLite database with the SQLite backup API or after stopping the server.

## Local setup

Run these commands from the backend directory. Generate a unique credential for each client and keep it in a password manager. Use cryptographically random, 32-byte URL-safe tokens.

```powershell
$env:SB_DATABASE = "secondbrain.sqlite"
$env:SB_WORKSPACE_TOKEN = node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('base64url'))"
node server.mjs add-token my-workspace
```

Copy the generated credential into your MCP client's protected authorization settings, then clear the environment variable. Multiple credentials can belong to one account and can be revoked independently.

```powershell
Remove-Item Env:SB_WORKSPACE_TOKEN
node server.mjs
```

Configure a Streamable HTTP client with `http://127.0.0.1:8787/mcp` and `Authorization: Bearer <token>`. Each credential maps to a server-side account. Tool IDs, document revisions, and request IDs are scoped to that account. To revoke a credential, put it in `SB_WORKSPACE_TOKEN`, run `node server.mjs revoke-token`, and clear the variable again.

## Sync API

`GET /v1/sync?cursor=0` reads up to 100 changes, including tombstones. Store the returned cursor only after applying the page locally. Repeat the request with the new cursor until caught up.

`POST /v1/sync` accepts an atomic batch of up to 50 changes. Each change has a stable `id`, a `kind`, the last acknowledged `revision` (zero for a new ID), and changed `fields` or `deleted: true`. Send schema version 1 and a unique `requestId` per batch. Retry an interrupted request with the same ID and contents. A conflict rejects the full batch. Pull the latest state and reconcile before retrying with a new ID.

Authentication and revision checks apply to MCP and sync. MCP update and delete tools require the item's current revision, and mutations accept a request ID for replay protection. Collection deletes require the collection to be empty. New nested folders require a main folder parent.

## Deployment and current integration

Keep the application bound to loopback and place an HTTPS reverse proxy in front of it for remote clients. Preserve the authorization header and set appropriate client and proxy timeouts. The service uses revocable bearer tokens supplied by the administrator. It has no hosted OAuth authorization server.

This repository includes the local server and sync protocol. Android still uses Room and the on-device planner. It does not connect to this server or register reminders for server-side tasks. The service has not been deployed remotely.

## Tests

```text
npm ci
npm test
```

Tests use the official MCP TypeScript SDK as a client over Streamable HTTP with a real temporary SQLite database. They exercise authentication, discovery, account isolation, revision conflicts, retries, atomic sync, and persistence across reopen.
