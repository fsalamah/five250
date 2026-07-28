/** Minimal MCP stdio client for testing: spawns server.mjs and speaks
 * newline-delimited JSON-RPC (initialize / tools/list / tools/call). */
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

export class McpClient {
  constructor(env = {}) {
    const serverPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "server.mjs");
    this.child = spawn(process.execPath, [serverPath], {
      stdio: ["pipe", "pipe", "pipe"],
      env: { ...process.env, ...env },
    });
    this.child.stderr.on("data", (d) => process.stderr.write(`[server] ${d}`));
    this.buf = "";
    this.pending = new Map();
    this.nextId = 1;
    this.child.stdout.on("data", (d) => {
      this.buf += d.toString();
      let i;
      while ((i = this.buf.indexOf("\n")) >= 0) {
        const line = this.buf.slice(0, i).trim();
        this.buf = this.buf.slice(i + 1);
        if (!line) continue;
        const msg = JSON.parse(line);
        if (msg.id !== undefined && this.pending.has(msg.id)) {
          const { resolve, reject } = this.pending.get(msg.id);
          this.pending.delete(msg.id);
          msg.error ? reject(new Error(`RPC error ${msg.error.code}: ${msg.error.message}`)) : resolve(msg.result);
        }
      }
    });
  }

  send(msg) {
    this.child.stdin.write(JSON.stringify(msg) + "\n");
  }

  request(method, params, timeoutMs = 180000) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`timeout waiting for ${method}`));
      }, timeoutMs);
      this.pending.set(id, {
        resolve: (v) => { clearTimeout(t); resolve(v); },
        reject: (e) => { clearTimeout(t); reject(e); },
      });
      this.send({ jsonrpc: "2.0", id, method, params });
    });
  }

  async initialize() {
    const r = await this.request("initialize", {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: { name: "five250-test-client", version: "0.0.1" },
    });
    this.send({ jsonrpc: "2.0", method: "notifications/initialized" });
    return r;
  }

  listTools() {
    return this.request("tools/list", {});
  }

  async call(name, args = {}) {
    const r = await this.request("tools/call", { name, arguments: args });
    const text = (r.content || []).map((c) => c.text).join("\n");
    return { isError: r.isError === true, text };
  }

  close() {
    this.child.kill();
  }
}
