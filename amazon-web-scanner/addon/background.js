/**
 * background.js - Amazon Web Scanner
 *
 * Keeps one native messaging connection to the helper app. The helper app
 * listens on 127.0.0.1 and forwards the requests it gets as
 *   { requestId, type: "dom" | "tabs" | "navigate" | "back" | "act" | "reload", ... }
 * The add-on answers each one with
 *   { requestId, cmd: "result", result: { ok, ... } }
 *
 * Commands to the helper app are answered with
 *   { requestId, ok: true, result } or { requestId, ok: false, error }.
 *
 * The options page talks to this script via runtime messages:
 *   { cmd: "getInfo" | "reconnect" | "newToken" | "amazonStatus" | "openAmazon" }
 */

const NATIVE_APP = 'amazon_web_scanner_host';
const COMMAND_TIMEOUT = 8000;

const AMAZON_DOMAINS = ['com', 'de', 'co.uk', 'fr', 'it', 'es', 'co.jp', 'ca', 'com.au',
  'com.br', 'in', 'com.mx'];
const AMAZON_PATTERNS = AMAZON_DOMAINS.map(d => `https://music.amazon.${d}/*`);

let buildInfo = null;

async function loadBuildInfo() {
  try {
    const response = await fetch(browser.runtime.getURL('build.json'));
    buildInfo = response.ok ? await response.json() : null;
  } catch {
    buildInfo = null;
  }
}

/** "1.0.0 (build 2026-11-08 04:12:05)", or just the version for an unpacked add-on. */
function versionText() {
  const version = browser.runtime.getManifest().version;
  return buildInfo ? `${version} (build ${buildInfo.builtAt})` : `${version} (not built, loaded from the source folder)`;
}

let port = null;
let connectError = null;
const pending = new Map();

// ── Native connection ──────────────────────────────────────────────────────────

function failPending(reason) {
  for (const { reject, timer } of pending.values()) {
    clearTimeout(timer);
    reject(new Error(reason));
  }
  pending.clear();
}

function connect() {
  if (port) return true;
  try {
    const p = browser.runtime.connectNative(NATIVE_APP);
    p.onMessage.addListener(onNativeMessage);
    p.onDisconnect.addListener(() => {
      const err = p.error?.message ?? browser.runtime.lastError?.message;
      connectError = err || 'The helper app has stopped.';
      port = null;
      failPending(connectError);
    });
    port = p;
    connectError = null;
    console.info(`Add-on ${versionText()} connected.`);
    return true;
  } catch (ex) {
    connectError = ex.message;
    port = null;
    return false;
  }
}

function onNativeMessage(msg) {
  if (msg?.type) {
    handleForwarded(msg).then(result => {
      port?.postMessage({ requestId: msg.requestId, cmd: 'result', result });
    });
    return;
  }
  const entry = pending.get(msg?.requestId);
  if (!entry) return;
  pending.delete(msg.requestId);
  clearTimeout(entry.timer);
  if (msg.ok) entry.resolve(msg.result);
  else entry.reject(new Error(msg.error || 'Unknown error'));
}

/** Send a command to the helper app and wait for its reply. */
function command(cmd, args = {}) {
  if (!connect()) return Promise.reject(new Error(connectError || 'Cannot start the helper app.'));
  return new Promise((resolve, reject) => {
    const requestId = crypto.randomUUID();
    const timer = setTimeout(() => {
      pending.delete(requestId);
      reject(new Error('The helper app did not answer in time.'));
    }, COMMAND_TIMEOUT);
    pending.set(requestId, { resolve, reject, timer });
    port.postMessage({ requestId, cmd, ...args });
  });
}

// ── Amazon Music tabs ──────────────────────────────────────────────────────────

async function amazonTabs() {
  const tabs = await browser.tabs.query({ url: AMAZON_PATTERNS });
  // the tab the user looks at first, then the one used last
  return tabs.sort((a, b) => (b.active - a.active) || ((b.lastAccessed || 0) - (a.lastAccessed || 0)));
}

/** Send a message to the content script, inject it first if the tab has none. */
async function talkToTab(tabId, message) {
  try {
    return await browser.tabs.sendMessage(tabId, message);
  } catch {
    await browser.tabs.executeScript(tabId, { file: '/content/amazon-music.js' });
    console.info(`Content script injected into tab ${tabId}.`);
    return browser.tabs.sendMessage(tabId, message);
  }
}

/** The open Amazon Music tab with this id, or undefined. */
async function pickTab(tabId) {
  const tabs = await amazonTabs();
  return tabs.find(t => t.id === tabId);
}

async function describeTabs() {
  const tabs = await amazonTabs();
  return Promise.all(tabs.map(async tab => {
    let contentScript = false;
    try {
      contentScript = !!(await browser.tabs.sendMessage(tab.id, { cmd: 'ping' }))?.ok;
    } catch {
      // no content script in this tab (yet)
    }
    return { tabId: tab.id, url: tab.url, title: tab.title, active: tab.active, windowId: tab.windowId,
      contentScript };
  }));
}

// ── Requests forwarded by the helper app ───────────────────────────────────────

/**
 * The answer when the tab is gone or left music.amazon.*, so the client has to
 * pick a tab from /tabs again.
 */
function noTab(tabId) {
  return { ok: false, status: 409, code: 'INVALID_TAB',
    error: `Tab ${tabId} is not an open Amazon Music tab. Pick a tab from /tabs.` };
}

async function handleForwarded(request) {
  try {
    switch (request.type) {
      case 'dom': return await handleDom(request);
      case 'navigate': return await handleNavigate(request);
      case 'act': return await handleAct(request);
      case 'back': return await handleBack(request);
      case 'reload':
        if (!(await pickTab(request.tabId))) return noTab(request.tabId);
        // answer first, the reload ends this page and with it the connection
        setTimeout(() => browser.runtime.reload(), 300);
        return { ok: true, message: 'Reloading the add-on.' };
      case 'tabs': return { ok: true, tabs: await describeTabs() };
      default: return { ok: false, error: `Unknown request type: ${request.type}` };
    }
  } catch (ex) {
    console.error(`Request ${request.type} failed: ${ex.message}`);
    return { ok: false, error: ex.message };
  }
}

async function handleAct(request) {
  const tab = await pickTab(request.tabId);
  if (!tab) return noTab(request.tabId);
  const { tabId, type, requestId, ...options } = request;
  console.info(`Act ${options.action} in tab ${tab.id}`);
  const result = await talkToTab(tab.id, { cmd: 'act', ...options });
  return { ...result, tabId: tab.id };
}

/** Load a page in a tab and wait until it has finished loading. */
function navigateTab(tabId, url) {
  return new Promise((resolve, reject) => {
    let seenLoading = false;
    const done = error => {
      clearTimeout(timer);
      browser.tabs.onUpdated.removeListener(listener);
      if (error) reject(error);
      else resolve();
    };
    const listener = (id, change) => {
      if (id !== tabId) return;
      if (change.status === 'loading') seenLoading = true;
      if (change.status === 'complete' && seenLoading) done();
    };
    const timer = setTimeout(() => done(new Error('The page did not finish loading in time.')), 20000);
    browser.tabs.onUpdated.addListener(listener);
    browser.tabs.update(tabId, { url }).catch(done);
  });
}

async function handleNavigate(request) {
  const url = String(request.url || '');
  if (!AMAZON_PATTERNS.some(p => url.startsWith(p.slice(0, -1)))) {
    return { ok: false, error: 'Only Amazon Music pages can be opened.' };
  }
  const tab = await pickTab(request.tabId);
  if (!tab) return noTab(request.tabId);
  console.info(`Navigating tab ${tab.id}`);
  await navigateTab(tab.id, url);
  const after = await browser.tabs.get(tab.id);
  return { ok: true, tabId: tab.id, url: after.url, title: after.title };
}

/**
 * Go one step back in the history of a tab and wait until it has settled.
 * A page restored from the cache or a single page app step may not report
 * "loading", so without any sign of a change the current address is returned after a short wait.
 */
function backInTab(tabId) {
  return new Promise((resolve, reject) => {
    let loading = false;
    let changed = false;
    const done = error => {
      clearTimeout(timer);
      clearTimeout(quiet);
      browser.tabs.onUpdated.removeListener(listener);
      if (error) reject(error);
      else resolve();
    };
    const listener = (id, change) => {
      if (id !== tabId) return;
      if (change.status === 'loading') loading = true;
      if (change.url) changed = true;
      if (change.status === 'complete' && (loading || changed)) done();
    };
    const timer = setTimeout(() => done(new Error('The page did not finish loading in time.')), 20000);
    const quiet = setTimeout(() => { if (!loading && !changed) done(); }, 2000);
    browser.tabs.onUpdated.addListener(listener);
    browser.tabs.goBack(tabId).catch(() => done(new Error('The tab has no earlier page in its history.')));
  });
}

async function handleBack(request) {
  const tab = await pickTab(request.tabId);
  if (!tab) return noTab(request.tabId);
  console.info(`Going back in tab ${tab.id}`);
  await backInTab(tab.id);
  const after = await browser.tabs.get(tab.id);
  return { ok: true, tabId: tab.id, url: after.url, title: after.title };
}

async function handleDom(request) {
  const tab = await pickTab(request.tabId);
  if (!tab) return noTab(request.tabId);
  const { tabId, type, requestId, ...options } = request;
  const result = await talkToTab(tab.id, { cmd: 'dom', ...options });
  return { ...result, tabId: tab.id };
}

// ── The options page ───────────────────────────────────────────────────────────

browser.runtime.onMessage.addListener(async message => {
  try {
    switch (message?.cmd) {
      case 'reconnect':
        if (port) {
          port.disconnect();
          port = null;
        }
        // fall through to read the info right away
      case 'getInfo':
        return { connected: true, info: await command('getInfo'), addonVersion: versionText() };
      case 'newToken':
        return { connected: true, info: await command('setConfig', { regenerateAdminToken: true }) };
      case 'amazonStatus':
        return { ok: true, tabs: await describeTabs() };
      case 'openAmazon': {
        const domain = AMAZON_DOMAINS.includes(message.domain) ? message.domain : 'com';
        const existing = (await amazonTabs()).find(t => t.url.startsWith(`https://music.amazon.${domain}/`));
        if (existing) {
          await browser.tabs.update(existing.id, { active: true });
          await browser.windows.update(existing.windowId, { focused: true });
          return { ok: true, tabId: existing.id, reused: true };
        }
        const tab = await browser.tabs.create({ url: `https://music.amazon.${domain}/` });
        return { ok: true, tabId: tab.id, reused: false };
      }
    }
  } catch (ex) {
    return { connected: false, ok: false, error: connectError || ex.message };
  }
  return undefined;
});

loadBuildInfo().then(connect);
