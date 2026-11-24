import { createServer } from "node:http";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { createStore, toolList, WorkspaceError } from "./workspace.mjs";

const versions = ["2025-11-25", "2025-06-18", "2025-03-26"];
const maxBody = 512 * 1024;

function reply(response, status, body) {
    if (body !== undefined) response.setHeader("Content-Type", "application/json; charset=utf-8");
    response.setHeader("Cache-Control", "no-store");
    response.writeHead(status).end(body === undefined ? undefined : JSON.stringify(body));
}

async function readBody(request) {
    const chunks = [];
    let size = 0;
    for await (const chunk of request) {
        size += chunk.length;
        if (size > maxBody) throw new WorkspaceError("Request body is too large.");
        chunks.push(chunk);
    }
    try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
    catch { throw new WorkspaceError("Invalid JSON."); }
}

const success = (id, result) => ({ jsonrpc: "2.0", id, result });
const failure = (id, code, message) => ({ jsonrpc: "2.0", id, error: { code, message } });

function handleMcp(response, store, account, message) {
    const { id, method, params } = message ?? {};
    if (!message || Array.isArray(message) || message.jsonrpc !== "2.0" || typeof method !== "string" ||
        (Object.hasOwn(message, "id") && !(typeof id === "string" || Number.isSafeInteger(id)))) {
        reply(response, 400, failure(null, -32600, "Invalid request."));
        return;
    }
    if (id === undefined) {
        reply(response, 202);
        return;
    }
    let result;
    switch (method) {
        case "initialize":
            if (typeof params?.protocolVersion !== "string" || typeof params?.clientInfo?.name !== "string") {
                reply(response, 200, failure(id, -32602, "Invalid initialize parameters."));
                return;
            }
            result = {
                protocolVersion: versions.includes(params.protocolVersion) ? params.protocolVersion : versions[0],
                capabilities: { tools: {} },
                serverInfo: { name: "second-brain", version: "0.4.8" }
            };
            break;
        case "ping":
            result = {};
            break;
        case "tools/list":
            result = { tools: toolList };
            break;
        case "tools/call": {
            if (typeof params?.name !== "string") {
                reply(response, 200, failure(id, -32602, "A tool name is required."));
                return;
            }
            try {
                const value = store.invoke(account, params.name, params.arguments ?? {});
                result = { content: [{ type: "text", text: JSON.stringify(value) }], structuredContent: value };
            } catch (error) {
                if (!(error instanceof WorkspaceError)) console.error("Workspace operation failed.");
                result = { content: [{ type: "text", text: error instanceof WorkspaceError ?
                    error.message : "Workspace operation failed." }], isError: true };
            }
            break;
        }
        default:
            reply(response, 200, failure(id, -32601, "Method not found."));
            return;
    }
    reply(response, 200, success(id, result));
}

export function workspaceServer(store) {
    const limits = new Map();
    let minute = 0;
    function limited(account) {
        const current = Math.floor(Date.now() / 60000);
        if (current !== minute) {
            minute = current;
            limits.clear();
        }
        const count = (limits.get(account) ?? 0) + 1;
        limits.set(account, count);
        return count > (account.startsWith("anonymous:") ? 40 : 180);
    }

    return createServer({ requestTimeout: 15000, headersTimeout: 10000 }, async (request, response) => {
        let path;
        try { path = new URL(request.url, "http://localhost"); }
        catch { return reply(response, 400, { error: "Invalid request path." }); }
        if (!["/mcp", "/v1/sync"].includes(path.pathname)) return reply(response, 404, { error: "Not found." });
        if (request.headers.origin !== undefined) return reply(response, 403, { error: "Origin is not allowed." });
        const token = request.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]{43,128})$/i)?.[1];
        const account = token && store.authenticate(token);
        if (limited(account ? "account:" + account : "anonymous:" + request.socket.remoteAddress)) {
            response.setHeader("Retry-After", "60");
            return reply(response, 429, { error: "Too many requests." });
        }
        if (!account) {
            response.setHeader("WWW-Authenticate", "Bearer");
            return reply(response, 401, { error: "Authentication required." });
        }
        const mcp = path.pathname === "/mcp";
        if (!mcp && request.method === "GET") {
            try { return reply(response, 200, store.pull(account, Number(path.searchParams.get("cursor") ?? 0))); }
            catch { return reply(response, 400, { error: "Invalid synchronization cursor." }); }
        }
        if (request.method !== "POST") {
            response.setHeader("Allow", mcp ? "POST" : "GET, POST");
            return reply(response, 405);
        }
        if (mcp && request.headers["mcp-protocol-version"] !== undefined &&
            !versions.includes(request.headers["mcp-protocol-version"])) {
            return reply(response, 400, failure(null, -32600, "Unsupported protocol version."));
        }
        if (request.headers["content-type"]?.split(";")[0].trim().toLowerCase() !== "application/json") {
            return reply(response, 415, { error: "Use application/json." });
        }
        if (mcp && !request.headers.accept?.includes("application/json")) {
            return reply(response, 406, failure(null, -32600, "Accept application/json."));
        }
        if (Number(request.headers["content-length"]) > maxBody) return reply(response, 413, { error: "Request is too large." });
        let content;
        try { content = await readBody(request); }
        catch (error) {
            return reply(response, 400, mcp ? failure(null, -32700, "Invalid request body.") : { error: error.message });
        }
        if (mcp) return handleMcp(response, store, account, content);
        try { reply(response, 200, store.push(account, content)); }
        catch (error) {
            if (!(error instanceof WorkspaceError)) console.error("Synchronization failed.");
            reply(response, error instanceof WorkspaceError ? 409 : 500,
                { error: error instanceof WorkspaceError ? error.message : "Synchronization failed." });
        }
    });
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
    const store = createStore(process.env.SB_DATABASE ?? "secondbrain.sqlite");
    const [operation, account] = process.argv.slice(2);
    if (operation === "add-token" || operation === "revoke-token") {
        const token = process.env.SB_WORKSPACE_TOKEN;
        if (!token) throw new Error("Set SB_WORKSPACE_TOKEN.");
        if (operation === "add-token") store.addCredential(account, token);
        else store.revokeCredential(token);
        store.close();
    } else if (operation === undefined) {
        const server = workspaceServer(store);
        const port = Number(process.env.SB_PORT ?? 8787);
        server.listen(port, "127.0.0.1", () => console.info("Second Brain is listening on 127.0.0.1:" + port));
        const stop = () => server.close(() => store.close());
        process.on("SIGINT", stop);
        process.on("SIGTERM", stop);
    } else throw new Error("Use add-token, revoke-token, or start the server without arguments.");
}
