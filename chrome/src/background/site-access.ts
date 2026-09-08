/** Inline buttons require a deliberate per-site optional permission grant. */
export const PROVIDER_ORIGINS = [
  "https://chatgpt.com/*", "https://chat.openai.com/*", "https://claude.ai/*",
  "https://notebook.google.com/*", "https://grok.com/*", "https://substack.com/*", "https://x.com/*",
  "https://gemini.google.com/*", "https://www.perplexity.ai/*", "https://perplexity.ai/*",
];
const SCRIPT_ID = "reader-provider-buttons";
let pending = Promise.resolve();
export function refreshSiteScripts(): Promise<void> {
  pending = pending.catch(() => {}).then(async () => {
    const allowed = await Promise.all(PROVIDER_ORIGINS.map(async origin =>
      await chrome.permissions.contains({ origins: [origin] }) ? origin : null));
    const matches = allowed.filter((origin): origin is string => origin !== null);
    const existing = await chrome.scripting.getRegisteredContentScripts({ ids: [SCRIPT_ID] });
    if (!matches.length) {
      if (existing.length) await chrome.scripting.unregisterContentScripts({ ids: [SCRIPT_ID] });
      return;
    }
    const script: chrome.scripting.RegisteredContentScript = {
      id: SCRIPT_ID, matches, js: ["content.js"], runAt: "document_idle", persistAcrossSessions: true,
    };
    if (existing.length) await chrome.scripting.updateContentScripts([script]);
    else await chrome.scripting.registerContentScripts([script]);
  });
  return pending;
}
chrome.permissions.onAdded.addListener(() => { void refreshSiteScripts().catch(() => {}); });
chrome.permissions.onRemoved.addListener(() => { void refreshSiteScripts().catch(() => {}); });
void refreshSiteScripts().catch(() => {});
