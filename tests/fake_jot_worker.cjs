#!/usr/bin/env node
// Test double for the Hermes Jot worker (runtime/worker.cjs).
// Same wire protocol: stdin {"kind":"tool","name":...,"args":{...}},
// stdout {"data": ...} or {"error": {"code","message"}}. State: <dir>/jot.json.
// If <dir>/agent.disabled exists, every tool returns AGENT_DISABLED.
const fs = require("fs");
const path = require("path");

const dir = process.argv[2];
const statePath = path.join(dir, "jot.json");

function load() {
  try {
    return JSON.parse(fs.readFileSync(statePath, "utf8"));
  } catch {
    return { version: 1, notes: [], folders: [], agentEnabled: true };
  }
}
function save(s) {
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(statePath, JSON.stringify(s));
}
function tasksOf(text) {
  const out = [];
  for (const line of String(text).split("\n")) {
    const m = line.match(/^- \[([ x])\] (.*)$/);
    if (m) out.push({ index: out.length + 1, text: m[2], checked: m[1] === "x" });
  }
  return out;
}
function setTaskLine(text, index, checked) {
  let n = 0;
  return String(text)
    .split("\n")
    .map((line) => {
      if (/^- \[[ x]\] /.test(line)) {
        n += 1;
        if (n === index) return `- [${checked ? "x" : " "}] ${line.slice(6)}`;
      }
      return line;
    })
    .join("\n");
}

let input = "";
process.stdin.on("data", (d) => (input += d));
process.stdin.on("end", () => {
  const req = JSON.parse(input);
  const args = req.args || {};
  const reply = (obj) => process.stdout.write(JSON.stringify(obj));
  if (fs.existsSync(path.join(dir, "agent.disabled"))) {
    return reply({ error: { code: "AGENT_DISABLED", message: "AI collaboration is off." } });
  }
  const state = load();
  const find = (id) => state.notes.find((n) => n.id === id && !n.deletedAt);
  switch (req.name) {
    case "jot_list": {
      const q = (args.query || "").toLowerCase();
      const notes = state.notes
        .filter((n) => !n.deletedAt)
        .filter((n) => !q || (n.title + "\n" + n.text).toLowerCase().includes(q))
        .map((n) => ({ id: n.id, title: n.title, revision: n.revision }));
      return reply({ data: { notes, total: notes.length, nextOffset: null, folders: [] } });
    }
    case "jot_create": {
      const note = {
        id: "note-" + (state.notes.length + 1) + "-" + Math.floor(Math.random() * 1e6),
        title: args.title || "",
        text: args.text || "",
        revision: 1,
        deletedAt: null,
      };
      state.notes.push(note);
      save(state);
      return reply({ data: { id: note.id, revision: 1 } });
    }
    case "jot_read": {
      const n = find(args.id);
      if (!n) return reply({ error: { code: "NOT_FOUND", message: "no such note" } });
      return reply({
        data: { id: n.id, title: n.title, markdown: n.text, revision: n.revision, tasks: tasksOf(n.text) },
      });
    }
    case "jot_update": {
      const n = find(args.id);
      if (!n) return reply({ error: { code: "NOT_FOUND", message: "no such note" } });
      if (args.revision !== n.revision)
        return reply({ error: { code: "REVISION_CONFLICT", message: `expected ${n.revision}` } });
      if (typeof args.appendText === "string") n.text += args.appendText;
      n.revision += 1;
      save(state);
      return reply({ data: { id: n.id, revision: n.revision } });
    }
    case "jot_set_task": {
      const n = find(args.id);
      if (!n) return reply({ error: { code: "NOT_FOUND", message: "no such note" } });
      if (args.revision !== n.revision)
        return reply({ error: { code: "REVISION_CONFLICT", message: `expected ${n.revision}` } });
      n.text = setTaskLine(n.text, args.index, !!args.checked);
      n.revision += 1;
      save(state);
      return reply({ data: { id: n.id, revision: n.revision } });
    }
    default:
      return reply({ error: { code: "UNKNOWN_TOOL", message: req.name } });
  }
});
