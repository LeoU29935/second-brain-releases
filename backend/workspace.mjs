import { createHash, randomUUID } from "node:crypto";
import { DatabaseSync } from "node:sqlite";

export class WorkspaceError extends Error {}
const requireValue = (condition, message) => { if (!condition) throw new WorkspaceError(message); };
const hash = value => createHash("sha256").update(value).digest("hex");
const kinds = ["task", "note", "event", "collection"];
const repeats = ["NONE", "DAILY", "WEEKDAYS", "WEEKLY", "MONTHLY", "YEARLY"];
const dayFormat = /^\d{4}-\d{2}-\d{2}$/;
const clockFormat = /^([01]\d|2[0-3]):[0-5]\d$/;
const dayMs = 86400000;

function parseDay(day) {
    requireValue(typeof day === "string" && dayFormat.test(day), "Use an ISO calendar date.");
    const time = Date.parse(day + "T00:00:00Z");
    requireValue(Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === day, "Invalid calendar date.");
    return time;
}

function dayString(time) { return new Date(time).toISOString().slice(0, 10); }

function localNow(zone) {
    let format;
    try {
        format = new Intl.DateTimeFormat("en-CA", {
            timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit",
            hour: "2-digit", minute: "2-digit", hourCycle: "h23"
        });
    } catch {
        throw new WorkspaceError("Use a valid IANA time zone.");
    }
    const fields = Object.fromEntries(format.formatToParts().map(({ type, value }) => [type, value]));
    return { day: [fields.year, fields.month, fields.day].join("-"), time: fields.hour + ":" + fields.minute };
}

const str = (maxLength = 180) => ({ type: "string", minLength: 1, maxLength });
const text = { type: "string", maxLength: 48000 };
const integer = (minimum, maximum) => ({ type: "integer", minimum, maximum });
const date = { ...str(10), pattern: dayFormat.source };
const clock = { ...str(5), pattern: clockFormat.source };
const id = { ...str(100), pattern: "^[a-zA-Z0-9_-]+$" };
const nullableId = { ...id, type: ["string", "null"] };
const requestId = { requestId: id };
const weekdays = { type: "array", items: integer(1, 7), maxItems: 7 };
const patterns = { repeatInterval: integer(1, 365), repeatWeekdays: weekdays };
const address = { id };
const version = { ...address, expectedRevision: integer(1, Number.MAX_SAFE_INTEGER), ...requestId };
const zone = { timeZone: str(100) };
const repeat = { type: "string", enum: repeats };
const reminder = integer(0, 10080);
const item = {
    title: str(), description: text, collectionId: nullableId,
    tags: { type: "array", items: id, maxItems: 50 },
    related: { type: "array", items: id, maxItems: 50 }
};
const task = {
    ...item, scheduledDate: date, scheduledTime: clock, deadline: date, timeOfDay: {
        type: "string", enum: ["MORNING", "AFTERNOON", "EVENING", "NIGHT"]
    },
    durationMinutes: integer(1, 10080), reminderMinutes: reminder, repeat, ...patterns,
    priority: { type: "string", enum: ["NONE", "LOW", "MEDIUM", "HIGH"] }, ...zone
};
const event = {
    ...item, scheduledDate: date, scheduledTime: clock, endDate: date, endTime: clock,
    location: { ...text, maxLength: 500 }, allDay: { type: "boolean" }, reminderMinutes: reminder, repeat
};
const collection = { name: str(), description: text, parentId: nullableId };
const byType = { task, event, note: item, collection };
const pagination = { offset: integer(0, 1000000), limit: integer(1, 100) };
const search = { query: str(500), ...pagination };
const definitions = new Map();

function register(name, description, properties, required, handle, mutation = false) {
    definitions.set(name, {
        tool: { name, description, inputSchema: { type: "object", properties, required, additionalProperties: false },
            annotations: { readOnlyHint: !mutation } },
        handle, mutation
    });
}

function validate(data, schema) {
    requireValue(data !== null && typeof data === "object" && !Array.isArray(data), "Supply an argument object.");
    for (const name of schema.required) requireValue(Object.hasOwn(data, name), "Missing " + name + ".");
    for (const [name, value] of Object.entries(data)) {
        const property = Object.hasOwn(schema.properties, name) && schema.properties[name];
        requireValue(property, "Unknown argument.");
        validateValue(name, value, property);
    }
}

function validateValue(name, value, property) {
    const type = Array.isArray(value) ? "array" : value === null ? "null" :
        Number.isSafeInteger(value) ? "integer" : typeof value;
    requireValue([property.type].flat().includes(type), "Invalid " + name + ".");
    if (type === "string") requireValue(value.length >= (property.minLength ?? 0) &&
        value.length <= (property.maxLength ?? Infinity) &&
        (!property.pattern || new RegExp(property.pattern).test(value)) &&
        (!property.enum || property.enum.includes(value)), "Invalid " + name + ".");
    if (type === "integer") requireValue(value >= property.minimum && value <= property.maximum, "Invalid " + name + ".");
    if (type === "array") {
        requireValue(value.length <= property.maxItems, "Too many entries in " + name + ".");
        for (const element of value) validateValue(name, element, property.items);
    }
}

function scheduleTask(value, workspace) {
    const current = localNow(value.timeZone ?? "UTC");
    const starts = { MORNING: 8, AFTERNOON: 12, EVENING: 17, NIGHT: 21 };
    const ends = { MORNING: 12, AFTERNOON: 17, EVENING: 21, NIGHT: 24 };
    const start = starts[value.timeOfDay] ?? 9;
    const end = ends[value.timeOfDay] ?? 21;
    const first = parseDay(value.scheduledDate ?? current.day);
    const last = value.scheduledDate ? first : Math.min(first + 7 * dayMs,
        value.deadline ? parseDay(value.deadline) : Infinity);
    const duration = value.durationMinutes ?? (/^(call|send|reply)\b/i.test(value.title) ? 15 : 30);
    let selected;
    for (let day = first; day <= last && !selected; day += dayMs) {
        const weekday = new Date(day).getUTCDay() || 7;
        if (value.repeat === "WEEKDAYS" && weekday > 5 ||
            value.repeatWeekdays?.length && !value.repeatWeekdays.includes(weekday)) continue;
        const date = dayString(day);
        if (value.scheduledTime) {
            if (value.scheduledDate || date > current.day || value.scheduledTime > current.time) {
                selected = { scheduledDate: date, scheduledTime: value.scheduledTime };
            }
            continue;
        }
        for (let minute = start * 60; minute + duration <= end * 60; minute += 15) {
            const time = clockTime(minute);
            if (date < current.day || (date === current.day && time <= current.time)) continue;
            if (workspace.some(other => conflicts(other, date, minute, duration))) continue;
            selected = { scheduledDate: date, scheduledTime: time };
            break;
        }
    }
    requireValue(selected, "Choose a date and time with room for the task.");
    const reminderMinutes = value.reminderMinutes ?? (
        /interview|appointment|flight/i.test(value.title) ? 60 : value.priority === "HIGH" ? 20 :
        value.repeat && value.repeat !== "NONE" ? 5 : duration <= 15 ? 0 : 10);
    return { ...value, ...selected, reminderMinutes, durationMinutes: duration,
        inferredSchedule: [!value.scheduledDate && "date", !value.scheduledTime && "time",
            value.reminderMinutes === undefined && "reminder"].filter(Boolean) };
}

function clockTime(minutes) {
    return [Math.floor(minutes / 60), minutes % 60].map(n => String(n).padStart(2, "0")).join(":");
}

function minutes(time) {
    const [hour, minute] = time.split(":").map(Number);
    return hour * 60 + minute;
}

function conflicts(item, day, start, duration) {
    if (item.completed || !occursOn(item, day)) return false;
    if (item.allDay) return true;
    if (!item.scheduledTime) return false;
    const first = item.kind === "event" && item.scheduledDate < day && item.endDate >= day ? 0 : minutes(item.scheduledTime);
    const last = item.kind === "event" && item.endDate > day ? 1440 :
        item.endTime ? minutes(item.endTime) : first + (item.durationMinutes ?? 30);
    return start < last && start + duration > first;
}

function occursOn(item, day) {
    if (!item.scheduledDate || day < item.scheduledDate) return false;
    const target = parseDay(day);
    const first = parseDay(item.scheduledDate);
    const elapsed = Math.round((target - first) / dayMs);
    const span = item.kind === "event" && item.endDate ? (parseDay(item.endDate) - first) / dayMs : 0;
    if (elapsed <= span) return true;
    if (item.completed) return false;
    switch (item.repeat) {
        case "DAILY": return elapsed % (item.repeatInterval ?? 1) === 0;
        case "WEEKDAYS": return ![0, 6].includes(new Date(target).getUTCDay());
        case "WEEKLY": {
            const anchorWeekday = new Date(first).getUTCDay() || 7;
            const week = Math.floor((elapsed + anchorWeekday - 1) / 7);
            return week % (item.repeatInterval ?? 1) === 0 && (item.repeatWeekdays?.length
                ? item.repeatWeekdays.includes(new Date(target).getUTCDay() || 7) : elapsed % 7 <= span);
        }
        case "MONTHLY":
        case "YEARLY": {
            const anchor = new Date(first);
            const candidate = new Date(target);
            if (item.repeat === "YEARLY" && candidate.getUTCMonth() !== anchor.getUTCMonth()) return false;
            const last = new Date(Date.UTC(candidate.getUTCFullYear(), candidate.getUTCMonth() + 1, 0)).getUTCDate();
            return candidate.getUTCDate() === Math.min(anchor.getUTCDate(), last);
        }
        default: return false;
    }
}

function canonical(value) {
    if (Array.isArray(value)) return value.map(canonical);
    if (value === null || typeof value !== "object") return value;
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
}

function fields(args) {
    const { id, expectedRevision, requestId: replay, ...data } = args;
    return data;
}

function page(source, args) {
    return { items: source.slice(args.offset ?? 0, (args.offset ?? 0) + (args.limit ?? 50)), total: source.length };
}

function matches(value, query) {
    const searchText = [value.title, value.name, value.description].filter(Boolean).join(" ").toLowerCase();
    return query.toLowerCase().split(/\s+/).filter(Boolean).every(term => searchText.includes(term));
}

for (const kind of kinds) {
    const createFields = { ...byType[kind], ...requestId };
    const required = kind === "collection" ? ["name"] : kind === "event" ? ["title", "scheduledDate"] : ["title"];
    register("create_" + kind, "Create a " + kind, createFields, required,
        (view, args) => view.save(kind, randomUUID(), 0, fields(args)), true);
    const plural = kind + "s";
    register("list_" + plural, "List " + plural, pagination, [], (view, args) => page(view.list(kind), args));
    if (kind !== "collection") {
        register("get_" + kind, "Get a " + kind, address, ["id"], (view, args) => view.get(args.id, kind));
        register("update_" + kind, "Update a " + kind + " at its current revision", { ...byType[kind], ...version },
            ["id", "expectedRevision"], (view, args) => view.save(kind, args.id, args.expectedRevision, fields(args)), true);
    }
    register("delete_" + kind, "Delete a " + kind + " at its current revision", version, ["id", "expectedRevision"],
        (view, args) => view.remove(kind, args.id, args.expectedRevision), true);
    if (kind === "task" || kind === "note") register("search_" + plural, "Search " + plural, search, ["query"],
        (view, args) => page(view.list(kind).filter(value => matches(value, args.query)), args));
}

register("rename_collection", "Rename a collection", { ...version, name: str() }, ["id", "expectedRevision", "name"],
    (view, args) => view.save("collection", args.id, args.expectedRevision, { name: args.name }), true);
register("move_item", "Move an item or subfolder", { ...version, collectionId: nullableId },
    ["id", "expectedRevision", "collectionId"], (view, args) => {
        const item = view.get(args.id);
        return view.save(item.kind, item.id, args.expectedRevision, {
            [item.kind === "collection" ? "parentId" : "collectionId"]: args.collectionId
        });
    }, true);
for (const kind of ["task", "event"]) {
    register("reschedule_" + kind, "Reschedule a " + kind, {
        ...version, scheduledDate: date, scheduledTime: clock,
        ...(kind === "event" ? { endDate: date, endTime: clock } : {})
    }, ["id", "expectedRevision", "scheduledDate"], (view, args) => {
        const current = view.get(args.id, kind);
        const changes = fields(args);
        if (kind === "event" && changes.endDate === undefined) {
            changes.endDate = dayString(parseDay(changes.scheduledDate) + parseDay(current.endDate) - parseDay(current.scheduledDate));
        }
        return view.save(kind, args.id, args.expectedRevision, changes);
    }, true);
}

for (const [verb, complete] of [["complete", true], ["reopen", false]]) {
    register(verb + "_task", verb + " a task", version, ["id", "expectedRevision"],
        (view, args) => view.save("task", args.id, args.expectedRevision, { completed: complete }), true);
}

register("search_workspace", "Search the workspace", search, ["query"],
    (view, args) => page(view.list().filter(value => matches(value, args.query)), args));
register("get_workspace_structure", "Get workspace collection hierarchy", pagination, [],
    (view, args) => page(view.list("collection"), args));
register("get_item_details", "Get an item by ID", address, ["id"], (view, args) => view.get(args.id));

function calendar(view, from, to) {
    const first = parseDay(from);
    const last = parseDay(to);
    requireValue(last >= first && last - first < 93 * dayMs, "Choose a date range of up to 93 days.");
    const entries = view.list().filter(item => item.kind === "task" || item.kind === "event");
    return { days: Array.from({ length: 1 + (last - first) / dayMs }, (_, offset) => {
        const date = dayString(first + offset * dayMs);
        return { date, items: entries.filter(item => occursOn(item, date)).sort((a, b) =>
            (a.scheduledTime ?? "").localeCompare(b.scheduledTime ?? "") || a.id.localeCompare(b.id)) };
    }) };
}

register("get_today_schedule", "Get today's schedule", zone, [], (view, args) => {
    const { day } = localNow(args.timeZone ?? "UTC");
    return calendar(view, day, day);
});
register("get_day_schedule", "Get a day's schedule", { date }, ["date"], (view, args) => calendar(view, args.date, args.date));
register("get_upcoming_schedule", "Get upcoming schedule", { ...zone, days: integer(1, 93) }, [], (view, args) => {
    const { day } = localNow(args.timeZone ?? "UTC");
    return calendar(view, day, dayString(parseDay(day) + ((args.days ?? 30) - 1) * dayMs));
});
register("get_date_range_schedule", "Get a range of scheduled items", { from: date, to: date }, ["from", "to"],
    (view, args) => calendar(view, args.from, args.to));

export const toolList = Array.from(definitions.values(), definition => definition.tool);

export function createStore(file) {
    const db = new DatabaseSync(file);
    db.exec("PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON;");
    db.exec(`
        CREATE TABLE IF NOT EXISTS credentials (digest TEXT PRIMARY KEY, account TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS items (
            account TEXT NOT NULL, id TEXT NOT NULL, kind TEXT NOT NULL, revision INTEGER NOT NULL,
            document TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (account, id)
        );
        CREATE TABLE IF NOT EXISTS changes (
            cursor INTEGER PRIMARY KEY, account TEXT NOT NULL, document TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS changes_account ON changes(account, cursor);
        CREATE TABLE IF NOT EXISTS replay (
            account TEXT NOT NULL, id TEXT NOT NULL, fingerprint TEXT NOT NULL, result TEXT NOT NULL,
            PRIMARY KEY (account, id)
        );
    `);

    function transaction(operation) {
        db.exec("BEGIN IMMEDIATE");
        try {
            const result = operation();
            db.exec("COMMIT");
            return result;
        } catch (error) {
            db.exec("ROLLBACK");
            throw error;
        }
    }

    function scoped(account) {
        const rowFor = id => db.prepare("SELECT * FROM items WHERE account=? AND id=?").get(account, id);
        function get(id, kind) {
            const row = rowFor(id);
            requireValue(row && !row.deleted && (!kind || row.kind === kind), "Item not found.");
            return JSON.parse(row.document);
        }
        function list(kind) {
            const rows = kind ? db.prepare("SELECT document FROM items WHERE account=? AND deleted=0 AND kind=? ORDER BY id")
                .all(account, kind) : db.prepare("SELECT document FROM items WHERE account=? AND deleted=0 ORDER BY id").all(account);
            return rows.map(row => JSON.parse(row.document));
        }
        function write(value, deleted = false) {
            const document = JSON.stringify(value);
            db.prepare(`INSERT INTO items(account,id,kind,revision,document,deleted) VALUES(?,?,?,?,?,?)
                ON CONFLICT(account,id) DO UPDATE SET revision=excluded.revision,document=excluded.document,deleted=excluded.deleted`)
                .run(account, value.id, value.kind, value.revision, document, Number(deleted));
            db.prepare("INSERT INTO changes(account,document) VALUES(?,?)").run(account, document);
            return value;
        }
        function save(kind, id, revision, patch) {
            const current = revision === 0 ? undefined : get(id, kind);
            requireValue(revision === 0 ? !rowFor(id) : current.revision === revision,
                "Revision conflict. Fetch the current item before retrying.");
            let value = { ...current, ...patch, id, kind, revision: revision + 1 };
            if (kind === "collection") {
                requireValue(value.name?.trim(), "A collection needs a name.");
                if (value.parentId && value.parentId !== current?.parentId) {
                    const parent = get(value.parentId, "collection");
                    requireValue(parent.id !== id && !parent.parentId && !list("collection").some(folder => folder.parentId === id),
                        "A folder can be nested only under a main folder.");
                }
            } else {
                requireValue(value.title?.trim(), "An item needs a title.");
                if (value.collectionId) get(value.collectionId, "collection");
                if (kind === "task" && (!current || !value.scheduledDate || !value.scheduledTime || value.reminderMinutes === undefined)) {
                    value = scheduleTask(value, list().filter(item => item.id !== id));
                }
                if (value.repeatWeekdays?.length) requireValue(value.repeat === "WEEKLY" &&
                    new Set(value.repeatWeekdays).size === value.repeatWeekdays.length,
                    "Choose distinct weekdays for a weekly pattern.");
                if (value.repeatInterval > 1) requireValue(["DAILY", "WEEKLY"].includes(value.repeat),
                    "Intervals require daily or weekly recurrence.");
                if (value.scheduledDate) parseDay(value.scheduledDate);
                if (value.deadline) parseDay(value.deadline);
                if (kind === "event") {
                    value.endDate ??= value.scheduledDate;
                    requireValue(parseDay(value.endDate) >= parseDay(value.scheduledDate) &&
                        (value.endDate !== value.scheduledDate || !value.scheduledTime ||
                            !value.endTime || value.endTime > value.scheduledTime), "An event must end after it starts.");
                }
            }
            return write(value);
        }
        function remove(kind, id, revision) {
            const current = get(id, kind);
            requireValue(current.revision === revision, "Revision conflict. Fetch the current item before retrying.");
            if (kind === "collection") requireValue(
                !list().some(value => value.parentId === id || value.collectionId === id), "Move the collection contents before deleting it.");
            return write({ id, kind, revision: revision + 1, deleted: true }, true);
        }
        return { list, get, save, remove };
    }

    function once(account, key, input, operation) {
        const fingerprint = hash(JSON.stringify(canonical(input)));
        const previous = key && db.prepare("SELECT fingerprint,result FROM replay WHERE account=? AND id=?").get(account, key);
        if (previous) {
            requireValue(previous.fingerprint === fingerprint, "The request ID already belongs to a different operation.");
            return JSON.parse(previous.result);
        }
        const result = operation();
        if (key) db.prepare("INSERT INTO replay VALUES(?,?,?,?)").run(account, key, fingerprint, JSON.stringify(result));
        return result;
    }

    return {
        close: () => db.close(),
        addCredential(account, token) {
            validateValue("account", account, id);
            requireValue(typeof token === "string" && /^[A-Za-z0-9_-]{43,128}$/.test(token), "Provide a generated URL-safe token.");
            db.prepare("INSERT INTO credentials VALUES(?,?)").run(hash(token), account);
        },
        revokeCredential(token) { db.prepare("DELETE FROM credentials WHERE digest=?").run(hash(token)); },
        authenticate(token) { return db.prepare("SELECT account FROM credentials WHERE digest=?").get(hash(token))?.account; },
        invoke(account, name, args) {
            const definition = definitions.get(name);
            requireValue(definition, "Unknown tool.");
            validate(args, definition.tool.inputSchema);
            const run = () => definition.handle(scoped(account), args);
            return definition.mutation ? transaction(() => once(account, args.requestId, [name, args], run)) : run();
        },
        pull(account, cursor) {
            validateValue("cursor", cursor, integer(0, Number.MAX_SAFE_INTEGER));
            const rows = db.prepare("SELECT cursor,document FROM changes WHERE account=? AND cursor>? ORDER BY cursor LIMIT 100")
                .all(account, cursor);
            return { schemaVersion: 1, cursor: rows.at(-1)?.cursor ?? cursor, changes: rows.map(row => JSON.parse(row.document)) };
        },
        push(account, input) {
            requireValue(input && input.schemaVersion === 1 && Array.isArray(input.changes) &&
                input.changes.length > 0 && input.changes.length <= 50, "Invalid synchronization batch.");
            validateValue("requestId", input.requestId, id);
            return transaction(() => once(account, input.requestId, ["sync", input], () => {
                const view = scoped(account);
                return { items: input.changes.map(change => {
                    requireValue(change && kinds.includes(change.kind), "Invalid item type.");
                    validateValue("id", change.id, id);
                    validateValue("revision", change.revision, integer(0, Number.MAX_SAFE_INTEGER));
                    if (change.deleted === true) return view.remove(change.kind, change.id, change.revision);
                    validate(change.fields, { properties: byType[change.kind], required: [], additionalProperties: false });
                    return view.save(change.kind, change.id, change.revision, change.fields);
                }) };
            }));
        }
    };
}
