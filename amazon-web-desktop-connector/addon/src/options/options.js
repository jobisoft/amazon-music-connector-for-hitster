/**
 * Options page: setup instructions while the helper app is missing, and
 * while it is connected the Amazon Music tab, the Android app download and
 * the information the helper app reports.
 */

import qrcode from '../vendor/qrcode.mjs';

const $ = id => document.getElementById(id);
const msg = (key, subs) => browser.i18n.getMessage(key, subs) || key;

const HELPER_FILES = [
  'amazon_music_connector_host.py',
  'amazon_music_connector_host.json',
  'install.sh',
  'install.bat',
  'uninstall.sh',
  'uninstall.bat',
];

const AMAZON_DOMAINS = ['com', 'de', 'co.uk', 'fr', 'it', 'es', 'co.jp', 'ca', 'com.au',
  'com.br', 'in', 'com.mx'];

const LANGUAGE_TO_DOMAIN = {
  'de': 'de', 'fr': 'fr', 'it': 'it', 'es': 'es', 'ja': 'co.jp', 'en-GB': 'co.uk',
  'en-CA': 'ca', 'en-AU': 'com.au', 'pt-BR': 'com.br', 'en-IN': 'in', 'es-MX': 'com.mx',
};

let lastInfo = null;
let lastAddonVersion = '';
let tokenVisible = false;

// ── Localization and OS specific parts ─────────────────────────────────────────

for (const el of document.querySelectorAll('[data-i18n]')) {
  el.textContent = msg(el.dataset.i18n);
}
document.title = msg('optionsPageTitle');

const { os } = await browser.runtime.getPlatformInfo();
for (const el of document.querySelectorAll('[data-os]')) {
  const wanted = el.dataset.os;
  const show = wanted === os || (wanted === 'unix' && os !== 'win');
  el.hidden = !show;
}

// ── Banner ─────────────────────────────────────────────────────────────────────

function setBanner(kind, title, detail = '') {
  $('banner').className = `banner banner-${kind}`;
  $('banner-spinner').hidden = kind !== 'checking';
  $('banner-title').textContent = title;
  $('banner-detail').textContent = detail;
  $('retry-btn').hidden = kind !== 'error';
}

// ── Helpers for building the page ──────────────────────────────────────────────

function formatUptime(seconds) {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return [d && `${d}d`, (d || h) && `${h}h`, (d || h || m) && `${m}m`, `${s}s`]
    .filter(Boolean).join(' ');
}

function row(label, value) {
  const tr = document.createElement('tr');
  const th = document.createElement('th');
  th.textContent = label;
  const td = document.createElement('td');
  td.textContent = value;
  tr.append(th, td);
  return tr;
}

function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.textContent = text;
  return e;
}

// ── QR code ────────────────────────────────────────────────────────────────────

/** Draw a QR code as SVG. Always black on white, scanners need the contrast. */
function drawQr(text) {
  const qr = qrcode(0, 'M');
  qr.addData(text);
  qr.make();
  const n = qr.getModuleCount();
  const margin = 4;
  const svgNs = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(svgNs, 'svg');
  svg.setAttribute('viewBox', `0 0 ${n + 2 * margin} ${n + 2 * margin}`);
  svg.setAttribute('shape-rendering', 'crispEdges');
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', text);
  const background = document.createElementNS(svgNs, 'rect');
  background.setAttribute('width', '100%');
  background.setAttribute('height', '100%');
  background.setAttribute('fill', '#fff');
  svg.append(background);
  let d = '';
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (qr.isDark(r, c)) d += `M${c + margin} ${r + margin}h1v1h-1z`;
    }
  }
  const path = document.createElementNS(svgNs, 'path');
  path.setAttribute('d', d);
  path.setAttribute('fill', '#000');
  svg.append(path);
  return svg;
}

let qrShown = '';

function renderQr(info, apkSync) {
  const apk = info.apk;
  const transferring = apkSync && (apkSync.state === 'uploading' || apkSync.state === 'checking');
  $('qr-ready').hidden = !apk.present;
  $('qr-missing').hidden = apk.present || transferring || apkSync?.state === 'error';
  const transfer = $('qr-transfer');
  transfer.hidden = !(transferring || apkSync?.state === 'error');
  if (apkSync?.state === 'uploading') {
    transfer.textContent = msg('qrTransferring', [String(Math.round(100 * apkSync.sent / apkSync.total))]);
  } else if (apkSync?.state === 'error') {
    transfer.textContent = msg('qrTransferFailed', [apkSync.error || '?']);
  } else if (transferring) {
    transfer.textContent = msg('qrChecking');
  }
  if (!apk.present) {
    qrShown = '';
    return;
  }

  const addresses = info.addresses.length ? info.addresses : ['127.0.0.1'];
  const select = $('qr-address');
  if (select.options.length !== addresses.length ||
      [...select.options].some((o, i) => o.value !== addresses[i])) {
    select.replaceChildren(...addresses.map(ip => new Option(ip, ip)));
  }
  $('qr-address-row').hidden = addresses.length < 2;
  const url = `http://${select.value || addresses[0]}:${info.port}/app`;
  $('qr-link').textContent = url;
  $('qr-apk-info').textContent = msg('qrApkInfo', [apk.version || '?', (apk.size / 1048576).toFixed(1)]);
  if (url !== qrShown) {
    $('qr').replaceChildren(drawQr(url));
    qrShown = url;
  }
}

$('qr-address').addEventListener('change', () => lastInfo && renderQr(lastInfo, null));

// ── Rendering of the helper information ────────────────────────────────────────

function renderInfo(info, addonVersion, apkSync) {
  lastInfo = info;
  lastAddonVersion = addonVersion;

  $('addresses').replaceChildren(...(info.addresses.length ? info.addresses : ['127.0.0.1'])
    .map(ip => el('code', 'addr', `http://${ip}:${info.port}`)));
  if (document.activeElement !== $('port-input')) $('port-input').value = info.port;
  $('token').textContent = tokenVisible ? info.token : '\u2022'.repeat(info.token.length);
  $('token-toggle').textContent = msg(tokenVisible ? 'btnHide' : 'btnShow');

  $('helper-info').replaceChildren(
    row(msg('infoAddonVersion'), addonVersion),
    row(msg('infoHostVersion'), info.hostVersion),
    row(msg('infoPython'), info.pythonVersion),
    row(msg('infoSystem'), `${info.platform} (${info.hostname})`),
    row(msg('infoPid'), String(info.pid)),
    row(msg('infoUptime'), formatUptime(info.uptime)),
    row(msg('infoConfigPath'), info.configPath),
    row(msg('infoServer'), info.serverRunning
      ? msg('infoServerRunning', [String(info.port)])
      : msg('infoServerStopped', [info.serverError || '?'])),
    row(msg('infoDebugApi'), msg('infoDebugApiValue')),
    row(msg('infoTotalRequests'), String(info.totalRequests)),
  );

  const rows = [...info.requests].reverse().map(r => {
    const tr = document.createElement('tr');
    const result = el('td');
    result.append(el('span', `badge badge-${r.status}`, msg(`status_${r.status}`)));
    if (r.detail) result.append(el('div', 'detail', r.detail));
    const source = el('td');
    source.append(el('span', 'badge badge-source', msg(`source_${r.source}`)));
    source.append(el('div', 'detail', r.remote));
    tr.append(
      el('td', '', new Date(r.time * 1000).toLocaleTimeString()),
      source,
      el('td', '', r.title ? `${r.title} - ${r.artist}` : ''),
      result,
    );
    return tr;
  });
  $('requests-body').replaceChildren(...rows);
  $('requests-table').hidden = rows.length === 0;
  $('requests-empty').hidden = rows.length !== 0;

  if (document.activeElement !== $('open-access')) $('open-access').checked = !!info.openAccess;
  renderPairing(info.pairing || []);
  renderQr(info, apkSync);
}

function renderPairing(pending) {
  $('pairing-section').hidden = pending.length === 0;
  $('pairing-list').replaceChildren(...pending.map(request => {
    const box = el('div', 'tab');
    const text = el('div', 'tab-title');
    text.append(el('div', '', request.name), el('div', 'tab-url', msg('pairFrom', [request.remote])));
    const allow = el('button', '', msg('btnAllow'));
    const deny = el('button', 'secondary danger', msg('btnDeny'));
    const decide = async value => {
      allow.disabled = deny.disabled = true;
      await browser.runtime.sendMessage({ cmd: 'pairDecide', id: request.id, allow: value });
      await refresh();
    };
    allow.addEventListener('click', () => decide(true));
    deny.addEventListener('click', () => decide(false));
    box.append(text, allow, deny);
    return box;
  }));
}

// ── Amazon Music ───────────────────────────────────────────────────────────────

async function initAmazonSelect() {
  const stored = (await browser.storage.local.get({ amazonDomain: '' })).amazonDomain;
  const language = navigator.language;
  const guess = LANGUAGE_TO_DOMAIN[language] || LANGUAGE_TO_DOMAIN[language.split('-')[0]] || 'com';
  const select = $('amazon-domain');
  select.replaceChildren(...AMAZON_DOMAINS.map(d => new Option(`music.amazon.${d}`, d)));
  select.value = AMAZON_DOMAINS.includes(stored) ? stored : guess;
  select.addEventListener('change', () => browser.storage.local.set({ amazonDomain: select.value }));
}

$('amazon-open').addEventListener('click', async () => {
  await browser.runtime.sendMessage({ cmd: 'openAmazon', domain: $('amazon-domain').value });
  await refreshAmazon();
});

async function refreshAmazon() {
  const answer = await browser.runtime.sendMessage({ cmd: 'amazonStatus' });
  const list = $('amazon-tabs');
  const tabs = answer?.tabs ?? [];
  if (tabs.length === 0) {
    list.replaceChildren(el('div', 'small', msg('amazonNoTabs')));
    return;
  }
  list.replaceChildren(...tabs.map(tab => {
    const box = el('div', 'tab');
    const text = el('div', 'tab-title');
    text.append(el('div', '', tab.title || tab.url), el('div', 'tab-url', tab.url));
    box.append(text, el('span', `badge ${tab.contentScript ? 'badge-ok' : 'badge-pending'}`,
      msg(tab.contentScript ? 'amazonScriptActive' : 'amazonScriptInactive')));
    return box;
  }));
}

// ── Refresh ────────────────────────────────────────────────────────────────────

async function refresh(reconnect = false) {
  const answer = await browser.runtime.sendMessage({ cmd: reconnect ? 'reconnect' : 'getInfo' });
  if (answer?.connected) {
    setBanner('ok', msg('bannerConnectedTitle'),
      msg('bannerConnectedDetail', [answer.info.hostVersion, String(answer.info.port)]));
    $('setup-section').hidden = true;
    $('info-section').hidden = false;
    renderInfo(answer.info, answer.addonVersion, answer.apkSync);
    await refreshAmazon();
  } else {
    setBanner('error', msg('bannerErrorTitle'), msg('bannerErrorDetail', [answer?.error || '?']));
    $('setup-section').hidden = false;
    $('info-section').hidden = true;
  }
}

// ── Actions ────────────────────────────────────────────────────────────────────

$('retry-btn').addEventListener('click', () => {
  setBanner('checking', msg('bannerChecking'));
  refresh(true);
});

$('token-toggle').addEventListener('click', () => {
  tokenVisible = !tokenVisible;
  if (lastInfo) renderInfo(lastInfo, lastAddonVersion, null);
});

$('token-copy').addEventListener('click', async () => {
  if (!lastInfo) return;
  await navigator.clipboard.writeText(lastInfo.token);
  $('token-copy').textContent = msg('btnCopied');
  setTimeout(() => { $('token-copy').textContent = msg('btnCopy'); }, 1500);
});

async function saveConfig(args) {
  $('config-error').hidden = true;
  const answer = await browser.runtime.sendMessage({ cmd: 'setConfig', ...args });
  if (!answer?.connected) {
    $('config-error').textContent = answer?.error || '?';
    $('config-error').hidden = false;
    return;
  }
  await refresh();
}

$('open-access').addEventListener('change', () => saveConfig({ openAccess: $('open-access').checked }));

$('port-save').addEventListener('click', () => {
  const port = Number($('port-input').value);
  saveConfig({ port });
});

$('token-regenerate').addEventListener('click', () => {
  if (window.confirm(msg('confirmRegenerate'))) saveConfig({ regenerateToken: true });
});

$('test-btn').addEventListener('click', async () => {
  if (!lastInfo) return;
  const box = $('test-result');
  box.hidden = false;
  box.textContent = msg('testRunning');
  try {
    const response = await fetch(`http://127.0.0.1:${lastInfo.port}/play`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${lastInfo.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: 'Start Me Up', artist: 'The Rolling Stones', year: 1981 }),
    });
    const body = await response.json();
    box.textContent = msg('testAnswer', [String(response.status), body.message || body.error || '']);
  } catch (ex) {
    box.textContent = msg('testFailed', [ex.message]);
  }
  await refresh();
});

// ── Download of the helper app ─────────────────────────────────────────────────

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let j = 0; j < 8; j++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[i] = c;
  }
  return t;
})();

function crc32(data) {
  let crc = 0xFFFFFFFF;
  for (const b of data) crc = CRC_TABLE[(crc ^ b) & 0xFF] ^ (crc >>> 8);
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

/** Date and time of a moment as the two 16-bit fields of a zip entry (1980 to 2107). */
function dosDateTime(d = new Date()) {
  const year = Math.min(Math.max(d.getFullYear(), 1980), 2107);
  return {
    date: ((year - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate(),
    time: (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1),
  };
}

/** Build an uncompressed zip. Entries are { name, data: Uint8Array, mode }. */
function buildZip(entries) {
  const enc = new TextEncoder();
  const stamp = dosDateTime();
  const parts = [];
  const central = [];
  let offset = 0;

  for (const { name, data, mode } of entries) {
    const nameBytes = enc.encode(name);
    const crc = crc32(data);

    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true);
    local.setUint16(4, 20, true);
    local.setUint16(10, stamp.time, true);
    local.setUint16(12, stamp.date, true);
    local.setUint32(14, crc, true);
    local.setUint32(18, data.length, true);
    local.setUint32(22, data.length, true);
    local.setUint16(26, nameBytes.length, true);

    const cd = new DataView(new ArrayBuffer(46));
    cd.setUint32(0, 0x02014b50, true);
    cd.setUint16(4, (3 << 8) | 20, true); // made by Unix, so the mode is honored
    cd.setUint16(6, 20, true);
    cd.setUint16(12, stamp.time, true);
    cd.setUint16(14, stamp.date, true);
    cd.setUint32(16, crc, true);
    cd.setUint32(20, data.length, true);
    cd.setUint32(24, data.length, true);
    cd.setUint16(28, nameBytes.length, true);
    cd.setUint32(38, ((0o100000 | mode) << 16) >>> 0, true);
    cd.setUint32(42, offset, true);

    parts.push(new Uint8Array(local.buffer), nameBytes, data);
    central.push(new Uint8Array(cd.buffer), nameBytes);
    offset += 30 + nameBytes.length + data.length;
  }

  const centralSize = central.reduce((n, p) => n + p.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, entries.length, true);
  end.setUint16(10, entries.length, true);
  end.setUint32(12, centralSize, true);
  end.setUint32(16, offset, true);

  return new Blob([...parts, ...central, new Uint8Array(end.buffer)], { type: 'application/zip' });
}

$('download-btn').addEventListener('click', async () => {
  const entries = [];
  for (const name of HELPER_FILES) {
    const response = await fetch(browser.runtime.getURL(`native-messaging-app/${name}`));
    const data = new Uint8Array(await response.arrayBuffer());
    const executable = name.endsWith('.sh') || name.endsWith('.py');
    entries.push({ name: `native-messaging-app/${name}`, data, mode: executable ? 0o755 : 0o644 });
  }
  const url = URL.createObjectURL(buildZip(entries));
  const a = document.createElement('a');
  a.href = url;
  a.download = 'amazon-music-connector-host.zip';
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
});

// ── Start ──────────────────────────────────────────────────────────────────────

await initAmazonSelect();
await refresh();
setInterval(() => {
  if (!document.hidden && !$('info-section').hidden && document.activeElement !== $('port-input')) {
    refresh();
  }
}, 3000);
