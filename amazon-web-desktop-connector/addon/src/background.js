/**
 * background.js - Amazon Music Connector for Hitster
 *
 * Keeps one native messaging connection to the helper app. The helper app
 * listens on the local network and forwards the requests it gets as
 *   { requestId, type: "play" | "control" | "autoplay" | "dom" | "tabs" | "navigate" | "act" | "reload", ... }
 * The add-on answers each one with
 *   { requestId, cmd: "result", result: { ok, ... } }
 *
 * Commands to the helper app are answered with
 *   { requestId, ok: true, result } or { requestId, ok: false, error }.
 *
 * The options page talks to this script via runtime messages:
 *   { cmd: "getInfo" | "reconnect" | "setConfig" | "pairDecide" | "amazonStatus" | "openAmazon" }
 */

const NATIVE_APP = 'amazon_music_connector_host';
const COMMAND_TIMEOUT = 8000;
const APK_CHUNK_SIZE = 512 * 1024;

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

/** "1.0.1 (build 2026-11-08 04:12:05)", or just the version for an unpacked add-on. */
function versionText() {
  const version = browser.runtime.getManifest().version;
  return buildInfo ? `${version} (build ${buildInfo.builtAt})` : `${version} (not built, loaded from the source folder)`;
}

let port = null;
let connectError = null;
const pending = new Map();
const apkSync = { state: 'idle', sent: 0, total: 0, error: null };

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
    hostEvent('info', `Add-on ${versionText()} connected.`);
    syncApk().catch(ex => {
      apkSync.state = 'error';
      apkSync.error = ex.message;
    });
    return true;
  } catch (ex) {
    connectError = ex.message;
    port = null;
    return false;
  }
}

function onNativeMessage(msg) {
  if (msg?.event === 'pairRequest') {
    // a phone asks to be allowed: bring the options page to the front, it has the buttons
    hostEvent('info', `${msg.name} asks to connect, opening the options page.`);
    browser.runtime.openOptionsPage();
    return;
  }
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

/** Write a line into the log of the helper app (visible to the developer). */
function hostEvent(level, text) {
  console[level === 'error' ? 'error' : 'info'](text);
  port?.postMessage({ cmd: 'event', level, text });
}

// ── The Android app, bundled in the add-on ─────────────────────────────────────

function toBase64(bytes) {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 8192) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
  }
  return btoa(binary);
}

/** Hand the bundled APK to the helper app, unless it has this version already. */
async function syncApk() {
  apkSync.state = 'checking';
  apkSync.error = null;
  let meta;
  try {
    const response = await fetch(browser.runtime.getURL('app/apk.json'));
    if (!response.ok) throw new Error('none');
    meta = await response.json();
  } catch {
    apkSync.state = 'none';
    return;
  }
  const info = await command('getInfo');
  if (info.apk?.present && info.apk.sha256 === meta.sha256) {
    apkSync.state = 'done';
    return;
  }
  const file = await fetch(browser.runtime.getURL(`app/${meta.file}`));
  const bytes = new Uint8Array(await file.arrayBuffer());
  apkSync.state = 'uploading';
  apkSync.total = bytes.length;
  apkSync.sent = 0;
  for (let offset = 0; offset < bytes.length; offset += APK_CHUNK_SIZE) {
    const chunk = bytes.subarray(offset, offset + APK_CHUNK_SIZE);
    await command('apkChunk', {
      offset,
      total: bytes.length,
      sha256: meta.sha256,
      version: meta.version,
      last: offset + chunk.length >= bytes.length,
      data: toBase64(chunk),
    });
    apkSync.sent = offset + chunk.length;
  }
  apkSync.state = 'done';
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
    hostEvent('info', `Content script injected into tab ${tabId}.`);
    return browser.tabs.sendMessage(tabId, message);
  }
}

async function pickTab(tabId) {
  const tabs = await amazonTabs();
  if (tabId !== undefined) return tabs.find(t => t.id === tabId);
  return tabs[0];
}

/**
 * The page only runs reliably while its tab is the selected one in a window
 * that has the focus. In the background Firefox slows its timers down and the
 * player does not start playing. So a request brings the tab to the front.
 */
async function focusTab(tab) {
  try {
    if (!tab.active) await browser.tabs.update(tab.id, { active: true });
    const win = await browser.windows.get(tab.windowId);
    if (!win.focused || win.state === 'minimized') {
      await browser.windows.update(tab.windowId, win.state === 'minimized'
        ? { focused: true, state: 'normal' } : { focused: true });
    }
    if (!tab.active || !win.focused) await new Promise(resolve => setTimeout(resolve, 400));
  } catch (ex) {
    hostEvent('error', `Could not bring the Amazon Music tab to the front: ${ex.message}`);
  }
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
    return { tabId: tab.id, url: tab.url, title: tab.title, active: tab.active, contentScript };
  }));
}

// ── Requests forwarded by the helper app ───────────────────────────────────────

const NO_TAB = 'No Amazon Music tab is open. Open one with the button on the options page of the add-on.';

async function handleForwarded(request) {
  try {
    switch (request.type) {
      case 'play': return await handlePlay(request);
      case 'dom': return await handleDom(request);
      case 'navigate': return await handleNavigate(request);
      case 'act': return await handleAct(request);
      case 'control': return await handleControl(request);
      case 'autoplay': return await handleAutoplay(request);
      case 'reload':
        // answer first, the reload ends this page and with it the connection
        setTimeout(() => browser.runtime.reload(), 300);
        return { ok: true, message: 'Reloading the add-on.' };
      case 'tabs': return { ok: true, tabs: await describeTabs() };
      default: return { ok: false, error: `Unknown request type: ${request.type}` };
    }
  } catch (ex) {
    hostEvent('error', `Request ${request.type} failed: ${ex.message}`);
    return { ok: false, error: ex.message };
  }
}

/**
 * A play request of the Android app. Searches the song on the song search page
 * of the open Amazon Music tab and lets the content script click the best hit.
 * The phone must not show title or artist to the players (the Android app hides them).
 */
let playQueue = Promise.resolve();

/**
 * Play requests run one after the other. The second one would type into the
 * search box while the first one still waits for its results.
 */
function handlePlay(request) {
  const run = playQueue.then(() => playOne(request), () => playOne(request));
  playQueue = run.catch(() => {});
  return run;
}

async function playOne(request) {
  const tab = await pickTab();
  if (!tab) {
    hostEvent('info', `Play request "${request.title}": ${NO_TAB}`);
    return { ok: false, error: NO_TAB };
  }
  hostEvent('info', `Play request "${request.title}" by "${request.artist}" in tab ${tab.id}`);
  await focusTab(tab);
  const result = await talkToTab(tab.id, { cmd: 'play', title: request.title, artist: request.artist, start: request.start });
  if (result?.ok) {
    if (result.seekError) hostEvent('error', `Could not jump ahead: ${result.seekError}`);
    hostEvent('info', `Playing "${result.played.title}" by ${result.played.artists.join(', ')} (score ${result.played.score}), started at ${result.startedAt ?? 0} s`);
    return { ok: true, message: 'Playing on your computer.', played: result.played };
  }
  hostEvent('error', `Play failed: ${result?.error}${result?.candidates ? ' ' + JSON.stringify(result.candidates) : ''}`);
  return { ok: false, error: result?.error || 'Playing failed.', candidates: result?.candidates };
}

/**
 * Back 10 s, forward 10 s, replay and stop of the phone. They queue behind a
 * running play request, which would otherwise search while the player jumps.
 */
function handleControl(request) {
  const run = playQueue.then(() => controlOne(request), () => controlOne(request));
  playQueue = run.catch(() => {});
  return run;
}

async function controlOne(request) {
  const tab = await pickTab();
  if (!tab) return { ok: false, error: NO_TAB };
  if (request.action !== 'state') {
    hostEvent('info', `Control ${request.action} in tab ${tab.id}`);
    await focusTab(tab); // the phone asks for the state every second, that must not steal the focus
  }
  const result = await talkToTab(tab.id, { cmd: 'control', action: request.action, position: request.position });
  return result?.ok ? { ok: true, message: 'Done.', position: result.position, duration: result.duration, playing: result.playing }
    : { ok: false, error: result?.error || 'The player did not react.' };
}

async function handleAutoplay(request) {
  const tab = await pickTab(request.tabId);
  if (!tab) return { ok: false, error: NO_TAB };
  hostEvent('info', `Switching autoplay off in tab ${tab.id}`);
  return { ...(await talkToTab(tab.id, { cmd: 'autoplay' })), tabId: tab.id };
}

async function handleAct(request) {
  const tab = await pickTab(request.tabId);
  if (!tab) return { ok: false, error: NO_TAB };
  const { tabId, type, requestId, ...options } = request;
  hostEvent('info', `Act ${options.action} on "${options.selector}" in tab ${tab.id}`);
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
  if (!tab) return { ok: false, error: NO_TAB };
  hostEvent('info', `Navigating tab ${tab.id} to ${url}`);
  await navigateTab(tab.id, url);
  const after = await browser.tabs.get(tab.id);
  return { ok: true, tabId: tab.id, url: after.url, title: after.title };
}

async function handleDom(request) {
  const tab = await pickTab(request.tabId);
  if (!tab) return { ok: false, error: request.tabId === undefined ? NO_TAB : `Tab ${request.tabId} is not an Amazon Music tab.` };
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
      case 'getInfo': {
        const info = await command('getInfo');
        return {
          connected: true,
          info,
          addonVersion: versionText(),
          apkSync: { ...apkSync },
        };
      }
      case 'setConfig': {
        const info = await command('setConfig', {
          port: message.port,
          regenerateToken: !!message.regenerateToken,
          openAccess: typeof message.openAccess === 'boolean' ? message.openAccess : undefined,
        });
        return { connected: true, info };
      }
      case 'pairDecide': {
        const info = await command('pairDecide', { id: message.id, allow: !!message.allow });
        return { connected: true, info };
      }
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

browser.runtime.onInstalled.addListener(({ reason }) => {
  if (reason === 'install') browser.runtime.openOptionsPage();
});

loadBuildInfo().then(connect);
