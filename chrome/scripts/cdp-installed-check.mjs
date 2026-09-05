import WebSocket from "ws";

const port = Number(process.argv[2] ?? 9223);
const action = process.argv[3] ?? "status";
const endpoint = `http://127.0.0.1:${port}`;

async function targets() {
  const response = await fetch(`${endpoint}/json/list`);
  if (!response.ok) throw new Error(`CDP target listing failed: ${response.status}`);
  return response.json();
}

async function connect(target) {
  const socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
  let id = 0;
  const pending = new Map();
  const events = [];
  socket.on("message", (data) => {
    const message = JSON.parse(String(data));
    if (!message.id) {
      if (message.method) events.push(message);
      return;
    }
    const waiter = pending.get(message.id);
    if (!waiter) return;
    pending.delete(message.id);
    if (message.error) waiter.reject(new Error(message.error.message));
    else waiter.resolve(message.result);
  });
  return {
    call(method, params = {}) {
      const requestId = ++id;
      socket.send(JSON.stringify({ id: requestId, method, params }));
      return new Promise((resolve, reject) => pending.set(requestId, { resolve, reject }));
    },
    events,
    close() { socket.close(); },
  };
}

async function evaluate(target, expression) {
  const client = await connect(target);
  try {
    const response = await client.call("Runtime.evaluate", {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    if (response.exceptionDetails) {
      throw new Error(response.exceptionDetails.exception?.description ?? response.exceptionDetails.text);
    }
    return response.result.value;
  } finally {
    client.close();
  }
}

function extensionPage(all) {
  return all.find((target) => target.type === "page" && target.url.includes("/src/ui/options.html"));
}

function worker(all) {
  return all.find((target) => target.type === "service_worker" && target.url.endsWith("/background.js"));
}

const all = await targets();
const page = extensionPage(all);
if (!page && action !== "content-storage-static") {
  throw new Error("Reader settings target not found");
}

if (action === "status") {
  const status = await evaluate(page, `chrome.runtime.sendMessage({kind:"reader-status"}).then(s => ({
    ok: s.ok,
    paired: s.paired,
    relayCount: Array.isArray(s.relays) ? s.relays.length : 0,
    relays: s.relays,
    customRelays: s.customRelays,
    relayChangePending: s.relayChangePending,
    pending: s.pending,
    delivery: s.delivery
  }))`);
  console.log(JSON.stringify(status, null, 2));
} else if (action === "send") {
  const doc = {
    title: "Reader seven-relay ACK verification - 2026-09-05",
    markdown: "Synthetic non-sensitive payload sent from Chrome for Testing after authenticated seven-relay pairing.",
    sourceType: "web",
    sourceUrl: "https://example.com/reader-seven-relay-verification",
  };
  const result = await evaluate(page, `chrome.runtime.sendMessage({kind:"reader-capture",doc:${JSON.stringify(doc)}})`);
  console.log(JSON.stringify({
    ok: result?.ok === true,
    queued: result?.queued === true,
    transferIdPrefix: typeof result?.transferId === "string" ? result.transferId.slice(0, 8) : null,
  }, null, 2));
} else if (action === "retry") {
  const result = await evaluate(page, `chrome.runtime.sendMessage({kind:"reader-retry"})`);
  console.log(JSON.stringify({ok: result?.ok === true, retried: result?.retried ?? null}, null, 2));
} else if (action === "check-acks") {
  const result = await evaluate(page, `chrome.runtime.sendMessage({kind:"reader-check-acks"})`);
  console.log(JSON.stringify({ok: result?.ok === true}, null, 2));
} else if (action === "ui-state") {
  const result = await evaluate(page, `({
    pendingText: document.getElementById("pend")?.textContent ?? null,
    deliveryText: document.getElementById("deliveryStates")?.textContent ?? null,
    messageText: document.getElementById("relayMessage")?.textContent ?? null,
    checkDisabled: document.getElementById("checkDelivery")?.disabled ?? null
  })`);
  console.log(JSON.stringify(result, null, 2));
} else if (action === "permission-state") {
  const result = await evaluate(page, `chrome.permissions.getAll()`);
  console.log(JSON.stringify(result, null, 2));
} else if (action === "paired-integrity") {
  const result = await evaluate(page, `(async () => {
    const state = await chrome.storage.local.get([
      "deviceSeckey",
      "channelPubkey",
      "relays",
      "channelRelaySetDigest",
      "protocolVersion"
    ]);
    const digestBytes = Array.isArray(state.relays)
      ? await crypto.subtle.digest("SHA-256", new TextEncoder().encode(JSON.stringify(state.relays)))
      : null;
    const computedDigest = digestBytes
      ? [...new Uint8Array(digestBytes)].map((byte) => byte.toString(16).padStart(2, "0")).join("")
      : null;
    return {
      protocolV2: state.protocolVersion === 2,
      deviceKeyPresentAndCanonical: typeof state.deviceSeckey === "string" && /^[0-9a-f]{64}$/.test(state.deviceSeckey),
      channelKeyPresentAndCanonical: typeof state.channelPubkey === "string" && /^[0-9a-f]{64}$/.test(state.channelPubkey),
      relayCount: Array.isArray(state.relays) ? state.relays.length : 0,
      relayDigestPresentAndCanonical: typeof state.channelRelaySetDigest === "string" && /^[0-9a-f]{64}$/.test(state.channelRelaySetDigest),
      relayDigestMatches: computedDigest !== null && computedDigest === state.channelRelaySetDigest
    };
  })()`);
  console.log(JSON.stringify(result, null, 2));
} else if (action === "reload") {
  const scheduled = await evaluate(page, `setTimeout(() => chrome.runtime.reload(), 100); true`);
  console.log(JSON.stringify({reloadScheduled: scheduled === true}, null, 2));
} else if (action === "page-reload") {
  const client = await connect(page);
  try {
    await client.call("Page.navigate", {url: page.url});
    console.log(JSON.stringify({pageReloadScheduled:true}, null, 2));
  } finally {
    client.close();
  }
} else if (action === "content-storage") {
  await evaluate(page, `chrome.runtime.sendMessage({kind:"reader-status"})`);
  const serviceWorker = worker(await targets());
  if (!serviceWorker) throw new Error("Reader service worker target not found");
  const result = await evaluate(serviceWorker, `(async () => {
    const permission = await chrome.permissions.contains({origins:["https://chatgpt.com/*"]});
    const tabs = await chrome.tabs.query({active:true,currentWindow:true});
    if (!tabs[0]?.id) return {permission,tested:false,reason:"no_matching_tab"};
    try {
      const injected = await chrome.scripting.executeScript({
        target:{tabId:tabs[0].id},
        func:async () => {
          try {
            const value = await chrome.storage.local.get(["deviceSeckey","pairingSessionsV2"]);
            return {
              storageCallSucceeded:true,
              deviceSecretVisible:Object.hasOwn(value,"deviceSeckey"),
              pairingStateVisible:Object.hasOwn(value,"pairingSessionsV2")
            };
          } catch {
            return {storageCallSucceeded:false,deviceSecretVisible:false,pairingStateVisible:false};
          }
        }
      });
      return {permission,tested:true,...injected[0].result};
    } catch (error) {
      return {
        permission,
        tested:false,
        reason:"script_injection_denied",
        activeUrl:tabs[0]?.url ?? null,
        detail:String(error)
      };
    }
  })()`);
  console.log(JSON.stringify(result, null, 2));
} else if (action === "content-storage-static") {
  const target = all.find((item) => item.type === "page" && !item.url.startsWith("chrome-extension://"));
  if (!target) throw new Error("Disposable web-page target not found");
  const client = await connect(target);
  try {
    await client.call("Runtime.enable");
    await client.call("Page.enable");
    await client.call("Page.navigate", { url: "https://chatgpt.com/" });
    await new Promise((resolve) => setTimeout(resolve, 5000));

    const activeContexts = new Map();
    for (const event of client.events) {
      if (event.method === "Runtime.executionContextsCleared") activeContexts.clear();
      if (event.method === "Runtime.executionContextDestroyed") {
        activeContexts.delete(event.params.executionContextId);
      }
      if (event.method === "Runtime.executionContextCreated") {
        activeContexts.set(event.params.context.id, event.params.context);
      }
    }
    const allContexts = [...activeContexts.values()];
    const contexts = allContexts.filter((context) => context.auxData?.type === "isolated");
    const attempts = [];
    for (const context of contexts) {
      const response = await client.call("Runtime.evaluate", {
        contextId: context.id,
        expression: `(async () => {
          if (!globalThis.chrome?.storage?.local) return {hasStorageApi:false};
          try {
            const value = await chrome.storage.local.get(["deviceSeckey","pairingSessionsV2"]);
            return {
              hasStorageApi:true,
              storageCallSucceeded:true,
              deviceSecretVisible:Object.hasOwn(value,"deviceSeckey"),
              pairingStateVisible:Object.hasOwn(value,"pairingSessionsV2")
            };
          } catch (error) {
            return {
              hasStorageApi:true,
              storageCallSucceeded:false,
              deviceSecretVisible:false,
              pairingStateVisible:false,
              errorName:error?.name ?? "Error"
            };
          }
        })()`,
        awaitPromise: true,
        returnByValue: true,
      });
      const value = response.result?.value;
      if (value && typeof value === "object") attempts.push(value);
    }
    console.log(JSON.stringify({
      tested: attempts.length > 0,
      contextsWithStorageApi: attempts.filter((item) => item.hasStorageApi).length,
      storageCallSucceeded: attempts.some((item) => item.storageCallSucceeded),
      deviceSecretVisible: attempts.some((item) => item.deviceSecretVisible),
      pairingStateVisible: attempts.some((item) => item.pairingStateVisible),
      allContexts: allContexts.map((context) => ({
        name: context.name,
        origin: context.origin,
        type: context.auxData?.type,
      })),
      isolatedContexts: contexts.map((context) => ({
        name: context.name,
        origin: context.origin,
        type: context.auxData?.type,
      })),
      attempts,
    }, null, 2));
  } finally {
    client.close();
  }
} else {
  throw new Error(`Unknown action: ${action}`);
}
