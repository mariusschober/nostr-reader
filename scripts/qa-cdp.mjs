import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../chrome/package.json', import.meta.url));
const { WebSocket } = require('ws');

export class QaBrowser {
  sequence = 0;
  pending = new Map();
  constructor(socket, state) {
    this.socket = socket; this.state = state;
    socket.on('message', raw => {
      const message = JSON.parse(raw.toString());
      const waiter = this.pending.get(message.id);
      if (!waiter) return;
      clearTimeout(waiter.timeout); this.pending.delete(message.id);
      if (message.error) waiter.reject(new Error(message.error.message)); else waiter.resolve(message.result);
    });
    socket.on('close', () => { for (const waiter of this.pending.values()) { clearTimeout(waiter.timeout); waiter.reject(new Error('QA browser connection closed')); } this.pending.clear(); });
  }
  static async connect(statePath) {
    const state = JSON.parse(readFileSync(statePath));
    const [port, path] = readFileSync(join(state.profile, 'DevToolsActivePort'), 'utf8').trim().split('\n');
    const socket = new WebSocket(`ws://127.0.0.1:${port}${path}`);
    await new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
    return new QaBrowser(socket, state);
  }
  send(method, params = {}, sessionId) {
    const id = ++this.sequence;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { this.pending.delete(id); reject(new Error(`QA browser command timed out: ${method}`)); }, 120000);
      this.pending.set(id, { resolve, reject, timeout });
      this.socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    });
  }
  async targets() { return (await this.send('Target.getTargets')).targetInfos; }
  async attach(targetId) { return (await this.send('Target.attachToTarget', { targetId, flatten: true })).sessionId; }
  async detach(sessionId) { await this.send('Target.detachFromTarget', { sessionId }); }
  async page(url) {
    const { targetId } = await this.send('Target.createTarget', { url });
    const sessionId = await this.attach(targetId);
    await this.send('Page.enable', {}, sessionId);
    await this.wait(async () => (await this.evaluate(sessionId, 'document.readyState')) === 'complete');
    return { targetId, sessionId };
  }
  async evaluate(sessionId, expression) {
    const result = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }, sessionId);
    if (result.exceptionDetails) throw new Error('QA page evaluation failed: ' + (result.exceptionDetails.exception?.description ?? result.exceptionDetails.text));
    return result.result.value;
  }
  async wait(predicate, timeout = 60000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) { const value = await predicate(); if (value) return value; await new Promise(resolve => setTimeout(resolve, 200)); }
    throw new Error('QA condition timed out');
  }
  async click(sessionId, selector) {
    const bounds = await this.evaluate(sessionId, `(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el) return null; el.scrollIntoView({block:'center'}); const r = el.getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2}; })()`);
    if (!bounds) throw new Error(`QA control unavailable: ${selector}`);
    await this.send('Input.dispatchMouseEvent', { type: 'mousePressed', ...bounds, button: 'left', clickCount: 1 }, sessionId);
    await this.send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...bounds, button: 'left', clickCount: 1 }, sessionId);
  }
  async screenshot(sessionId, path) {
    const image = await this.send('Page.captureScreenshot', { format: 'png' }, sessionId);
    writeFileSync(path, Buffer.from(image.data, 'base64'));
  }
  async control(path, body = {}) {
    const response = await fetch(`http://127.0.0.1:${this.state.controlPort}${path}`, {
      method: 'POST', headers: { 'x-reader-qa': this.state.token, 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    if (!response.ok) throw new Error(`QA controller HTTP ${response.status}`);
    return response.json();
  }
  async android(type, args = {}, timeout = 90000) {
    const id = crypto.randomUUID();
    await this.control('/android/enqueue', { id, type, ...args });
    const result = await this.wait(() => this.control('/android/results', { id }), timeout);
    if (result.errorType) throw new Error(`Android ${type}: ${result.errorType} ${result.error}`);
    return result;
  }
  close() { this.socket.close(); }
}
