import { randomBytes } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import { once } from "node:events";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import { test } from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { createStore } from "./workspace.mjs";
import { workspaceServer } from "./server.mjs";

async function fixture(t) {
    const dir = mkdtempSync(join(tmpdir(), "secondbrain-test-"));
    const database = join(dir, "workspace.sqlite");
    const first = randomBytes(32).toString("base64url");
    const second = randomBytes(32).toString("base64url");
    const store = createStore(database);
    store.addCredential("alpha", first);
    store.addCredential("beta", second);
    const server = workspaceServer(store).listen(0, "127.0.0.1");
    await once(server, "listening");
    const base = "http://127.0.0.1:" + server.address().port;
    const connections = [];
    t.after(async () => {
        await Promise.all(connections.map(client => client.close()));
        await new Promise(resolve => server.close(resolve));
        store.close();
        rmSync(dir, { recursive: true });
    });
    async function client(token) {
        const value = new Client({ name: "second-brain-test", version: "1.0.0" });
        const transport = new StreamableHTTPClientTransport(new URL(base + "/mcp"), {
            requestInit: { headers: { Authorization: "Bearer " + token } }
        });
        await value.connect(transport);
        connections.push(value);
        return value;
    }
    async function sync(path = "", body, token = first) {
        return fetch(base + "/v1/sync" + path, {
            method: body ? "POST" : "GET",
            headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
            body: body ? JSON.stringify(body) : undefined
        });
    }
    return { database, store, base, first, second, client, sync };
}

async function call(client, name, args, fails = false) {
    const result = await client.callTool({ name, arguments: args });
    assert.equal(result.isError === true, fails, JSON.stringify(result.content));
    return fails ? result.content[0].text : result.structuredContent;
}

test("official MCP client discovers and executes authenticated workspace tools", async t => {
    const fixtureData = await fixture(t);
    const owner = await fixtureData.client(fixtureData.first);
    const guest = await fixtureData.client(fixtureData.second);
    const { tools } = await owner.listTools();
    for (const name of [
        "create_task", "get_task", "list_tasks", "search_tasks", "update_task", "complete_task", "reopen_task", "reschedule_task", "delete_task",
        "create_note", "get_note", "list_notes", "search_notes", "update_note", "delete_note",
        "create_event", "get_event", "list_events", "update_event", "reschedule_event", "delete_event",
        "list_collections", "create_collection", "rename_collection", "move_item", "delete_collection",
        "get_today_schedule", "get_day_schedule", "get_upcoming_schedule", "get_date_range_schedule",
        "search_workspace", "get_workspace_structure", "get_item_details"
    ]) assert.ok(tools.some(tool => tool.name === name), name);

    const folder = await call(owner, "create_collection", { name: "Career" });
    const child = await call(owner, "create_collection", { name: "Interviews", parentId: folder.id });
    await call(owner, "create_collection", { name: "Too deep", parentId: child.id }, true);
    await call(guest, "create_collection", { name: "Cross account", parentId: folder.id }, true);
    const data = { title: "Prepare for interview", collectionId: child.id, scheduledDate: "2090-05-09",
        scheduledTime: "11:30", deadline: "2090-05-10", reminderMinutes: 60, requestId: "once" };
    const task = await call(owner, "create_task", data);
    assert.deepEqual(await call(owner, "create_task", data), task);
    await call(owner, "create_task", { ...data, title: "Different" }, true);
    assert.equal(task.scheduledTime, "11:30");
    assert.equal(task.reminderMinutes, 60);
    assert.equal(task.deadline, "2090-05-10");
    assert.deepEqual(await call(owner, "get_task", { id: task.id }), task);
    assert.equal((await call(owner, "get_day_schedule", { date: task.scheduledDate })).days[0].items[0].id, task.id);
    assert.equal((await call(owner, "search_tasks", { query: "interview" })).total, 1);
    assert.equal((await call(guest, "list_tasks", {})).total, 0);
    await call(guest, "get_task", { id: task.id }, true);
    await call(owner, "update_task", { id: task.id, expectedRevision: task.revision + 1, title: "Stale" }, true);
    const completed = await call(owner, "complete_task", { id: task.id, expectedRevision: task.revision });
    assert.equal(completed.completed, true);
    const reopened = await call(owner, "reopen_task", { id: task.id, expectedRevision: completed.revision });
    const moved = await call(owner, "reschedule_task", { id: task.id, expectedRevision: reopened.revision,
        scheduledDate: "2090-05-10", scheduledTime: "12:30" });
    assert.equal(moved.scheduledTime, "12:30");
    assert.equal(moved.reminderMinutes, 60);
    const removed = await call(owner, "delete_task", { id: task.id, expectedRevision: moved.revision });
    assert.equal(removed.deleted, true);
    await call(owner, "get_task", { id: task.id }, true);

    const inferred = await call(owner, "create_task", { title: "Call Dad", timeZone: "Europe/Berlin" });
    assert.ok(inferred.scheduledDate && inferred.scheduledTime && Number.isInteger(inferred.reminderMinutes));
    assert.deepEqual(inferred.inferredSchedule, ["date", "time", "reminder"]);
    const note = await call(owner, "create_note", { title: "Interview notes", description: "Bring a portfolio" });
    const updated = await call(owner, "update_note", { id: note.id, expectedRevision: note.revision, description: "Updated" });
    assert.equal((await call(owner, "get_note", { id: note.id })).description, updated.description);
    const event = await call(owner, "create_event", {
        title: "Interview", scheduledDate: "2090-05-08", scheduledTime: "09:00", endTime: "10:00"
    });
    const rescheduled = await call(owner, "reschedule_event", { id: event.id, expectedRevision: event.revision, scheduledDate: "2090-05-10" });
    assert.equal(rescheduled.endDate, "2090-05-10");
    assert.deepEqual(await call(owner, "get_event", { id: event.id }), rescheduled);
    const renamed = await call(owner, "rename_collection", { id: child.id, expectedRevision: child.revision, name: "Archive" });
    assert.equal(renamed.parentId, folder.id);
    assert.equal((await call(owner, "get_workspace_structure", {})).total, 2);
    assert.equal((await call(owner, "search_workspace", { query: "portfolio" })).total, 0);
    assert.equal((await call(owner, "search_notes", { query: "updated" })).total, 1);
    assert.equal((await call(owner, "get_date_range_schedule", { from: "2090-05-09", to: "2090-05-11" }))
        .days[1].items[0].id, event.id);
    assert.equal((await call(owner, "get_today_schedule", { timeZone: "Europe/Berlin" })).days.length, 1);
    assert.equal((await call(owner, "get_upcoming_schedule", { timeZone: "Europe/Berlin", days: 3 })).days.length, 3);
    const shiftedNote = await call(owner, "move_item", { id: note.id, expectedRevision: updated.revision, collectionId: child.id });
    assert.equal((await call(owner, "get_item_details", { id: note.id })).collectionId, child.id);
    await call(owner, "delete_collection", { id: child.id, expectedRevision: renamed.revision }, true);
    await call(owner, "delete_note", { id: note.id, expectedRevision: shiftedNote.revision });
    assert.equal((await call(owner, "list_notes", {})).total, 0);
    const updatedEvent = await call(owner, "update_event", { id: event.id, expectedRevision: rescheduled.revision, location: "Room 2" });
    assert.equal((await call(owner, "list_events", {})).items[0].location, "Room 2");
    await call(owner, "delete_event", { id: event.id, expectedRevision: updatedEvent.revision });
    assert.equal((await call(owner, "list_events", {})).total, 0);
    const updatedTask = await call(owner, "update_task", { id: inferred.id, expectedRevision: inferred.revision, priority: "HIGH" });
    assert.equal((await call(owner, "get_task", { id: inferred.id })).priority, updatedTask.priority);
    await call(owner, "delete_collection", { id: child.id, expectedRevision: renamed.revision });
    assert.equal((await call(owner, "list_collections", {})).total, 1);
    assert.equal((await fetch(fixtureData.base + "/mcp")).status, 401);
    assert.equal((await fetch(fixtureData.base + "/mcp", { headers: { Origin: "https://untrusted.example" } })).status, 403);
});

test("synchronization is account scoped, atomic, revision checked, durable and idempotent", async t => {
    const data = await fixture(t);
    const batch = { schemaVersion: 1, requestId: "offline-one", changes: [
        { kind: "collection", id: "stable-folder", revision: 0, fields: { name: "Music" } },
        { kind: "note", id: "stable-note", revision: 0, fields: { title: "Composition", collectionId: "stable-folder" } }
    ] };
    const response = await data.sync("", batch);
    assert.equal(response.status, 200);
    const first = await response.json();
    assert.deepEqual(await (await data.sync("", batch)).json(), first);
    const rejected = { schemaVersion: 1, requestId: "offline-two", changes: [
        { kind: "note", id: "extra-note", revision: 0, fields: { title: "Must be rolled back" } },
        { kind: "note", id: "stable-note", revision: 5, fields: { title: "Stale" } }
    ] };
    assert.equal((await data.sync("", rejected)).status, 409);
    const sync = await (await data.sync()).json();
    assert.equal(sync.changes.length, 2);
    assert.equal((await (await data.sync("?cursor=" + sync.cursor)).json()).changes.length, 0);
    assert.equal((await (await data.sync("", null, data.second)).json()).changes.length, 0);
    const reopened = createStore(data.database);
    assert.equal(reopened.authenticate(data.first), "alpha");
    assert.equal(reopened.invoke("alpha", "get_note", { id: "stable-note" }).title, "Composition");
    reopened.revokeCredential(data.first);
    reopened.close();
    assert.equal((await data.sync()).status, 401);
});
