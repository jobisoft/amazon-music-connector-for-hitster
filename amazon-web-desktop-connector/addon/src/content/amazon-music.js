/**
 * Content script for the Amazon Music web player.
 *
 * It answers messages of the background script:
 *
 *   { cmd: "ping" }
 *       -> { ok, url, title, readyState }
 *
 *   { cmd: "dom", selector?, mode?, maxBytes?, limit?, strip?, waitFor?, timeout? }
 *       -> { ok, url, title, mode, selector, matched, truncated, data }
 *
 * The player is built from web components with shadow roots, which a plain
 * document.querySelector() or outerHTML cannot see into. The functions below
 * walk through open and closed shadow roots (Firefox hands content scripts
 * the closed ones as element.openOrClosedShadowRoot).
 *
 * Selectors may cross shadow boundaries with ">>>":
 *   "music-app >>> music-shuffle-button"
 * means: find music-app in the document, then music-shuffle-button inside the
 * shadow root of each match.
 *
 *   { cmd: "act", action: "click" | "type" | "press", selector, index?, text?, key?, timeout? }
 *       -> { ok, action, matched, tag, text, url }
 *       Acts on the element the selector finds (the first one, or the one at
 *       "index"), waiting for it to appear like "waitFor" does (default 5000 ms).
 *         click  pointer and mouse events, then click
 *         type   focus, set the value (text may be empty to clear), input and change events
 *         press  keydown, keypress and keyup of "key" (Enter, Escape, ArrowDown, ...)
 *       The events are synthetic (isTrusted is false), the page may treat them
 *       differently from real input. That is what this interface is there to find out.
 *
 *   { cmd: "play", title, artist, start?, timeout? }
 *       -> { ok, played: { title, artist, score }, error? }
 *       Works on a song search page (/search/<query>?type=song). Rates the
 *       results, clicks the play button of the best one and waits until the
 *       mini player shows the song, then jumps to "start" (default 20 s).
 *
 *   { cmd: "control", action: "back10" | "forward10" | "restart" | "stop" | "seek" | "state", position? }
 *       -> { ok, position, duration, playing }
 *       Moves the playhead of the media element of the player. "restart"
 *       jumps to the start offset (20 s) and plays, "stop" pauses. A song is
 *       always paused a second before its end, the player never goes on to a next one.
 *
 *   { cmd: "autoplay" }
 *       -> { ok, changed }
 *       Switches "Autoplay" (similar songs after the music ends) off, through
 *       the settings menu of the page. "play" does that by itself.
 *
 * "waitFor" is a selector (same syntax). The answer comes as soon as it matches,
 * or fails after "timeout" milliseconds (default 10000, at most 30000). The
 * player is a single page app, so results appear after the page has loaded.
 *
 * Modes of "dom":
 *   html     serialized markup with <#shadow-root> markers (default)
 *   text     the visible text, one line per text node
 *   summary  a list of interactive elements with tag, id, classes, role,
 *            label, text and link, to find what to click
 */

(() => {
  if (window.__amazonMusicConnector) return;
  window.__amazonMusicConnector = true;

  const DEFAULT_MAX_BYTES = 400000;
  const DEFAULT_LIMIT = 400;
  const VOID_TAGS = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
    'link', 'meta', 'source', 'track', 'wbr']);
  const DROP_TAGS = new Set(['script', 'style', 'noscript', 'link', 'meta']);
  const INTERACTIVE_TAGS = new Set(['a', 'button', 'input', 'select', 'textarea', 'summary']);
  const INTERACTIVE_ROLES = new Set(['button', 'link', 'tab', 'menuitem', 'option', 'checkbox',
    'switch', 'slider', 'searchbox', 'textbox', 'combobox', 'listitem', 'row']);

  const shadowOf = el => el.openOrClosedShadowRoot || el.shadowRoot || null;

  // ── selection ────────────────────────────────────────────────────────────────

  function deepQueryAll(selector) {
    const parts = selector.split('>>>').map(s => s.trim()).filter(Boolean);
    let current = [document];
    parts.forEach((part, index) => {
      const next = [];
      for (const root of current) {
        const scope = index === 0 ? root : shadowOf(root);
        if (scope) next.push(...scope.querySelectorAll(part));
      }
      current = next;
    });
    return parts.length === 0 ? [] : current;
  }

  /** Visit every element below root, going through shadow roots as well. */
  function* walk(root, depth = 0, hosts = []) {
    for (const child of root.children || []) {
      yield { el: child, depth, hosts };
      const shadow = shadowOf(child);
      if (shadow) yield* walk(shadow, depth + 1, [...hosts, describe(child)]);
      yield* walk(child, depth + 1, hosts);
    }
  }

  function describe(el) {
    let s = el.localName;
    if (el.id) s += `#${el.id}`;
    const classes = (el.getAttribute('class') || '').split(/\s+/).filter(Boolean).slice(0, 2);
    if (classes.length) s += '.' + classes.join('.');
    return s;
  }

  // ── html ─────────────────────────────────────────────────────────────────────

  function attributes(el, strip) {
    let out = '';
    for (const attr of el.attributes) {
      if (strip && attr.name === 'style') continue;
      let value = attr.value;
      if (value.length > 200) value = value.slice(0, 200) + '...';
      out += ` ${attr.name}="${value.replace(/"/g, '&quot;')}"`;
    }
    return out;
  }

  function serialize(node, state, depth) {
    if (state.bytes > state.maxBytes) {
      state.truncated = true;
      return '';
    }
    const pad = ' '.repeat(Math.min(depth, 40));
    let out;
    if (node.nodeType === Node.TEXT_NODE) {
      const text = node.nodeValue.replace(/\s+/g, ' ').trim();
      out = text ? `${pad}${text}\n` : '';
    } else if (node.nodeType === Node.ELEMENT_NODE) {
      const tag = node.localName;
      if (state.strip && DROP_TAGS.has(tag)) return '';
      out = `${pad}<${tag}${attributes(node, state.strip)}>`;
      if (VOID_TAGS.has(tag)) {
        out += '\n';
      } else if (state.strip && tag === 'svg') {
        out += `</svg>\n`;
      } else {
        out += '\n';
        const shadow = shadowOf(node);
        if (shadow) {
          out += `${pad} <#shadow-root>\n`;
          for (const child of shadow.childNodes) out += serialize(child, state, depth + 2);
          out += `${pad} </#shadow-root>\n`;
        }
        for (const child of node.childNodes) out += serialize(child, state, depth + 1);
        out += `${pad}</${tag}>\n`;
      }
    } else {
      return '';
    }
    state.bytes += out.length;
    return out;
  }

  // ── text and summary ─────────────────────────────────────────────────────────

  function collectText(root, lines, state) {
    for (const node of root.childNodes) {
      if (state.bytes > state.maxBytes) {
        state.truncated = true;
        return;
      }
      if (node.nodeType === Node.TEXT_NODE) {
        const text = node.nodeValue.replace(/\s+/g, ' ').trim();
        if (text) {
          lines.push(text);
          state.bytes += text.length;
        }
      } else if (node.nodeType === Node.ELEMENT_NODE && !DROP_TAGS.has(node.localName)) {
        const shadow = shadowOf(node);
        if (shadow) collectText(shadow, lines, state);
        collectText(node, lines, state);
      }
    }
  }

  function summarize(roots, limit) {
    const items = [];
    for (const root of roots) {
      for (const { el, hosts } of walk(root)) {
        const role = el.getAttribute('role');
        const tag = el.localName;
        const testid = el.getAttribute('data-testid');
        const interactive = INTERACTIVE_TAGS.has(tag) || INTERACTIVE_ROLES.has(role);
        if (tag === 'svg' || testid === 'Icon') continue;
        const textLength = (el.textContent || '').trim().length;
        const label = el.getAttribute('aria-label') || el.getAttribute('title');
        // Containers with a lot of text only repeat what their children say.
        const interesting = interactive || (textLength <= 60 && (testid || label));
        if (!interesting) continue;
        const text = (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 80);
        items.push({
          selector: describe(el),
          inside: hosts.length ? hosts.join(' >>> ') : undefined,
          role: role || undefined,
          label: el.getAttribute('aria-label') || el.getAttribute('title') || undefined,
          testid: el.getAttribute('data-testid') || undefined,
          href: el.getAttribute('href') || undefined,
          type: el.getAttribute('type') || undefined,
          placeholder: el.getAttribute('placeholder') || undefined,
          text: text || undefined,
        });
        if (items.length >= limit) return items;
      }
    }
    return items;
  }

  // ── messages ─────────────────────────────────────────────────────────────────

  function renderDom(message) {
    const mode = message.mode || 'html';
    const selector = message.selector || '';
    const maxBytes = message.maxBytes || DEFAULT_MAX_BYTES;
    const strip = message.strip !== false;
    const roots = selector ? deepQueryAll(selector) : [document.documentElement];
    const result = {
      ok: true,
      url: location.href,
      title: document.title,
      mode,
      selector,
      matched: roots.length,
      truncated: false,
    };
    if (selector && roots.length === 0) {
      return { ...result, ok: false, error: `Nothing matches "${selector}".` };
    }
    const state = { bytes: 0, maxBytes, truncated: false, strip };
    const shown = roots.slice(0, 20);
    if (mode === 'summary') {
      result.data = summarize(shown, message.limit || DEFAULT_LIMIT);
    } else if (mode === 'text') {
      const lines = [];
      for (const root of shown) collectText(root, lines, state);
      result.data = lines.join('\n');
    } else {
      result.data = shown.map(root => serialize(root, state, 0)).join('\n');
    }
    result.truncated = state.truncated;
    return result;
  }

  async function waitForMatches(selector, timeout) {
    const end = Date.now() + Math.min(timeout, 30000);
    let found = deepQueryAll(selector);
    while (found.length === 0 && Date.now() < end) {
      await new Promise(resolve => setTimeout(resolve, 200));
      found = deepQueryAll(selector);
    }
    return found;
  }

  const KEY_CODES = { Enter: 13, Escape: 27, ArrowDown: 40, ArrowUp: 38, ArrowLeft: 37,
    ArrowRight: 39, Tab: 9, ' ': 32 };

  function fire(el, Type, name, init) {
    el.dispatchEvent(new Type(name, { bubbles: true, cancelable: true, composed: true, ...init }));
  }

  function clickElement(el) {
    el.scrollIntoView({ block: 'center' });
    const rect = el.getBoundingClientRect();
    const at = { clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2, button: 0 };
    for (const name of ['pointerdown', 'mousedown']) {
      fire(el, name.startsWith('pointer') ? PointerEvent : MouseEvent, name, { ...at, buttons: 1 });
    }
    el.focus?.();
    for (const name of ['pointerup', 'mouseup']) {
      fire(el, name.startsWith('pointer') ? PointerEvent : MouseEvent, name, at);
    }
    el.click();
  }

  function setInputValue(el, text) {
    el.focus();
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    // React tracks the value itself, so go through the native setter
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, text);
    fire(el, InputEvent, 'input', { inputType: 'insertText', data: text });
    fire(el, Event, 'change', {});
  }

  function pressKey(el, key) {
    const init = { key, code: key.length === 1 ? `Key${key.toUpperCase()}` : key,
      keyCode: KEY_CODES[key] || 0, which: KEY_CODES[key] || 0 };
    el.focus?.();
    for (const name of ['keydown', 'keypress', 'keyup']) fire(el, KeyboardEvent, name, init);
  }

  async function handleAct(message) {
    const matches = await waitForMatches(message.selector, message.timeout ?? 5000);
    const el = matches[message.index || 0];
    if (!el) {
      return { ok: false, url: location.href, matched: matches.length,
        error: `Nothing matches "${message.selector}" at index ${message.index || 0}.` };
    }
    el.scrollIntoView({ block: 'center' });
    switch (message.action) {
      case 'click':
        clickElement(el);
        break;
      case 'type':
        setInputValue(el, message.text ?? '');
        break;
      case 'press':
        pressKey(el, message.key || 'Enter');
        break;
      default:
        return { ok: false, error: `Unknown action: ${message.action}` };
    }
    return {
      ok: true,
      action: message.action,
      matched: matches.length,
      tag: el.localName,
      text: (el.textContent || el.value || '').replace(/\s+/g, ' ').trim().slice(0, 80),
      url: location.href,
    };
  }

  // ── playing a song ───────────────────────────────────────────────────────────

  /** Lower case, no accents, no brackets, no punctuation. "(Live)" and the like are rated apart. */
  function normalize(text) {
    const plain = text.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/['\u2019`]/g, '');
    // "Title - Remastered 2009" and "Title (Live)" are the same title with an addition
    const withoutBrackets = plain.replace(/\(.*?\)|\[.*?\]/g, ' ').replace(/\s-\s.*$/, '');
    const clean = s => s.replace(/&/g, ' and ').replace(/[^a-z0-9]+/g, ' ').trim();
    return clean(withoutBrackets) || clean(plain);
  }

  const UNWANTED = ['live', 'karaoke', 'tribute', 'cover', 'instrumental', 'remix', 'acoustic',
    'demo', 'edit', 'version', 'mix', 'sped up', 'slowed'];

  function artistScore(wanted, found) {
    const targets = wanted.split(/\s*(?:feat\.?|ft\.?|featuring|&|,| und | and | with )\s*/i)
      .map(normalize).filter(Boolean);
    let best = 0;
    for (const name of found.map(normalize)) {
      for (const target of targets) {
        if (name === target) best = Math.max(best, 50);
        else if (name.includes(target) || target.includes(name)) best = Math.max(best, 25);
      }
    }
    return best;
  }

  function rate(candidate, wantedTitle, wantedArtist) {
    const rawTitle = candidate.title.toLowerCase();
    const wanted = normalize(wantedTitle);
    const found = normalize(candidate.title);
    let score = 0;
    if (found === wanted) score += 100;
    else if (found.startsWith(wanted) || wanted.startsWith(found)) score += 50;
    else if (found.includes(wanted) || wanted.includes(found)) score += 20;
    score += artistScore(wantedArtist, candidate.artists);
    // At equal rating the plain title wins over "(Remastered)", "- Mono" and the like.
    const decorated = /[(\[]|\s-\s/.test(rawTitle) && !/[(\[]|\s-\s/.test(wantedTitle);
    if (decorated) score -= 10;
    for (const word of UNWANTED) {
      if (new RegExp(`\\b${word}\\b`).test(rawTitle) && !wantedTitle.toLowerCase().includes(word)) score -= 60;
    }
    return score;
  }

  const text = el => (el?.textContent || '').replace(/\s+/g, ' ').trim();

  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

  async function waitUntil(condition, timeout) {
    const end = Date.now() + timeout;
    while (!condition()) {
      if (Date.now() > end) return false;
      await sleep(150);
    }
    return true;
  }

  /**
   * Search through the search box of the page and show only songs.
   * The page is not reloaded. A reload would stop the music, and Firefox
   * would block the sound of the next song, because a script click is no
   * user gesture and the new page has had no real click yet.
   */
  async function searchInPage(query) {
    const BOX = '[data-testid="SearchBox_TextInput"]';
    let box = deepQueryAll(BOX)[0];
    if (!box) {
      const link = deepQueryAll('a[href="/search"]')[0];
      if (!link) throw new Error('The page has no search.');
      clickElement(link);
      box = (await waitForMatches(BOX, 8000))[0];
      if (!box) throw new Error('The search box did not appear.');
    }
    // The page writes the query into the address its own way ("&" and ":" stay as they are),
    // so compare the decoded path.
    const target = `/search/${query}`;
    const decodedPath = () => {
      try { return decodeURIComponent(location.pathname); } catch { return location.pathname; }
    };
    const alreadyThere = decodedPath() === target;
    const before = location.pathname + location.search;
    setInputValue(box, query);
    pressKey(box, 'Enter');
    if (alreadyThere) {
      await sleep(1000);
    } else if (!await waitUntil(() => decodedPath() === target
        || (location.pathname + location.search !== before && location.pathname.startsWith('/search/')), 10000)) {
      throw new Error('The search did not start.');
    }
    const pill = (await waitForMatches('[data-testid$="SearchResultsTypeFilter_Pill_song"]', 8000))[0];
    if (!pill) throw new Error('The song filter did not appear.');
    if (!location.search.includes('type=song')) {
      clickElement(pill);
      if (!await waitUntil(() => location.search.includes('type=song'), 5000)) {
        throw new Error('The song filter did not apply.');
      }
    }
    await sleep(500); // let the rows of the new result replace the old ones
  }


  // ── player control ───────────────────────────────────────────────────────────

  /** Songs start here, the first seconds of an intro give too little to guess from. */
  const START_AT = 20;

  const mediaElements = () => [...document.querySelectorAll('video, audio')];

  /** The element that carries the music (the player streams into a <video> with a blob URL). */
  function mediaElement() {
    const list = mediaElements();
    return list.find(m => (m.currentSrc || m.src) && m.duration > 0) || list.find(m => m.currentSrc || m.src) || null;
  }

  function playerState() {
    const media = mediaElement();
    if (media && media.duration > 0) {
      return { position: Math.round(media.currentTime * 10) / 10, duration: Math.round(media.duration * 10) / 10,
        playing: !media.paused };
    }
    const slider = document.querySelector('[data-testid$="MiniPlayer_ProgressSlider"]')?.closest('[role="slider"]');
    return {
      position: Number(slider?.getAttribute('aria-valuenow')) || 0,
      duration: Number(slider?.getAttribute('aria-valuemax')) || 0,
      playing: !!document.querySelector('[data-testid$="MiniPlayer_Pause"]'),
    };
  }

  /** Move the playhead and wait until the player has arrived there. */
  async function seekTo(seconds) {
    const media = mediaElement();
    if (!media) throw new Error('The player has no media to seek in.');
    const max = Number.isFinite(media.duration) ? Math.max(0, media.duration - 2) : seconds;
    const target = Math.min(Math.max(0, seconds), max);
    media.currentTime = target;
    await waitUntil(() => !media.seeking && Math.abs(media.currentTime - target) < 2, 4000);
    return target;
  }

  async function setPlaying(wanted) {
    if (playerState().playing === wanted) return;
    const button = document.querySelector(wanted ? '[data-testid$="MiniPlayer_Play"]' : '[data-testid$="MiniPlayer_Pause"]');
    if (button) clickElement(button);
    else if (wanted) await mediaElement()?.play();
    else mediaElement()?.pause();
    await waitUntil(() => playerState().playing === wanted, 3000);
  }

  async function handleControl(message) {
    if (!mediaElement() && !playerState().duration) return { ok: false, error: 'Nothing is playing.' };
    const now = playerState();
    switch (message.action) {
      case 'back10': await seekTo(now.position - 10); break;
      case 'forward10': await seekTo(now.position + 10); break;
      case 'restart':
        await seekTo(startFor(now.duration, message.start));
        await setPlaying(true);
        break;
      case 'stop': await setPlaying(false); break;
      case 'seek':
        if (!Number.isFinite(message.position)) return { ok: false, error: 'position is required.' };
        await seekTo(message.position);
        break;
      case 'state': break;
      default: return { ok: false, error: `Unknown action: ${message.action}` };
    }
    return { ok: true, action: message.action, ...playerState() };
  }

  /** The start offset, lower for very short songs. */
  function startFor(duration, wanted) {
    const start = Number.isFinite(wanted) && wanted >= 0 ? wanted : START_AT;
    return duration && duration < start + 30 ? Math.floor(duration / 4) : start;
  }

  // ── stop at the end of the song ──────────────────────────────────────────────

  /**
   * After the last second the player would go on with the next song of its
   * queue (the search results). A game round has one song, so the music is
   * paused shortly before the end.
   */
  let endHandled = false;
  function stopAtEnd() {
    const media = mediaElement();
    if (!media || !(media.duration > 0)) return;
    const left = media.duration - media.currentTime;
    if (left > 3) { endHandled = false; return; }
    if (endHandled || media.paused || left > 1) return;
    endHandled = true;
    setPlaying(false).catch(() => media.pause());
  }
  // "timeupdate" does not bubble, the capture phase gets it; the timer is for the moments it is late
  document.addEventListener('timeupdate', stopAtEnd, true);
  setInterval(stopAtEnd, 250);

  // ── autoplay ─────────────────────────────────────────────────────────────────

  let autoplayCheckedAt = 0;
  const AUTOPLAY_RECHECK = 10 * 60 * 1000;

  /**
   * "Autoplay" keeps playing similar songs after the music ends. For the game
   * a song has to end. The switch sits in the settings menu: its item opens a
   * dialog with either a "disable" or an "enable" button (the test ids are
   * stable, the labels depend on the language).
   */
  async function ensureAutoplayOff({ force = false } = {}) {
    if (!force && Date.now() - autoplayCheckedAt < AUTOPLAY_RECHECK) return { ok: true, changed: false, cached: true };
    const settings = document.querySelector('#settingsButton');
    if (!settings) return { ok: false, error: 'The settings button is not on the page.' };
    const closeMenu = () => { if (document.querySelector('[role="menu"]')) pressKey(document.body, 'Escape'); };
    try {
      clickElement(settings);
      if (!await waitUntil(() => document.querySelector('[role="menu"] [role="menuitem"]'), 4000)) {
        return { ok: false, error: 'The settings menu did not open.' };
      }
      const menu = document.querySelector('[role="menu"]');
      // the item has no test id: take the one behind "import playlists", else go by the label
      let item = menu.querySelector('[data-testid$="OverflowMenu_Option_ImportPlaylists"]')?.nextElementSibling;
      if (!item?.querySelector('[role="menuitem"]')) {
        item = [...menu.querySelectorAll('button')].find(b =>
          /auto-?play|automati|lecture auto|riproduzione auto|reproducci[oó]n auto/i.test(b.getAttribute('aria-label') || ''));
      }
      const target = item?.querySelector('[role="menuitem"]');
      if (!target) { closeMenu(); return { ok: false, error: 'The autoplay item is not in the settings menu.' }; }
      clickElement(target);
      const modal = '[data-testid="AutoplayModal"]';
      if (!await waitUntil(() => document.querySelector(modal), 4000)) {
        closeMenu();
        return { ok: false, error: 'The autoplay dialog did not open.' };
      }
      const off = document.querySelector('[data-testid$="Autoplay_DisableButton"]');
      let changed = false;
      if (off) {
        clickElement(off);
        changed = true;
      } else {
        const dismiss = document.querySelector('[data-testid$="Autoplay_DismissButton"]');
        if (dismiss) clickElement(dismiss);
      }
      await waitUntil(() => !document.querySelector(modal), 4000);
      autoplayCheckedAt = Date.now();
      return { ok: true, changed };
    } finally {
      closeMenu();
    }
  }

  // look at it once the page has settled, the setting belongs to the account
  setTimeout(() => ensureAutoplayOff().catch(() => {}), 5000);

  async function handlePlay(message) {
    await ensureAutoplayOff().catch(() => {});
    try {
      await searchInPage(`${message.title} ${message.artist}`);
    } catch (ex) {
      return { ok: false, url: location.href, error: ex.message };
    }
    // A result row is a "CarouselItem". Image, title, artists and the play button are its children,
    // the element named "...SearchResultsWidget_Item" holds the image only.
    const items = await waitForMatches('[data-testid="CarouselItem"]', message.timeout ?? 15000);
    if (items.length === 0) {
      return { ok: false, url: location.href, error: 'No song results appeared on the page.' };
    }
    const candidates = items.map(el => ({
      el,
      title: text(el.querySelector('[data-testid$="Metadata_TrackTitle"]')),
      artists: [...el.querySelectorAll('a[href*="/artists/"]')].map(text).filter(Boolean),
      // like and menu buttons have longer test ids, the play button is the plain "IconButton"
      button: el.querySelector('button[data-testid="IconButton"]'),
    })).filter(c => c.title && c.button);
    if (candidates.length === 0) {
      return { ok: false, url: location.href, error: 'The results have no playable songs.' };
    }
    candidates.forEach(c => { c.score = rate(c, message.title, message.artist); });
    // the first one wins a tie, the page lists the best match first
    const best = candidates.reduce((a, b) => (b.score > a.score ? b : a));
    if (best.score < 100) {
      return { ok: false, url: location.href, candidates: candidates.slice(0, 5).map(c => ({
        title: c.title, artists: c.artists, score: c.score })),
        error: `No result matches "${message.title}" by "${message.artist}".` };
    }
    // stay silent while the song loads and jumps ahead, then the player goes on in the song
    const muted = mediaElements().map(m => [m, m.muted]);
    muted.forEach(([m]) => { m.muted = true; });
    try {
      clickElement(best.button);

      // wait until the mini player shows the song and offers "pause", i.e. it plays
      const end = Date.now() + 25000; // a slow connection can take a while to start
      while (Date.now() < end) {
        const playing = document.querySelector('[data-testid$="MiniPlayer_Pause"]');
        const shown = text(document.querySelector('[data-testid="MiniPlayer_Title"]'));
        if (playing && normalize(shown) === normalize(best.title)) {
          const result = { ok: true, played: { title: best.title, artists: best.artists, score: best.score }, url: location.href };
          try {
            await waitUntil(() => mediaElement()?.duration > 0, 5000);
            const wanted = startFor(mediaElement()?.duration, message.start);
            if (wanted > 0) {
              result.startedAt = await seekTo(wanted);
              // the player may still be starting up and put the playhead back to the beginning
              for (let attempt = 0; attempt < 4; attempt++) {
                await sleep(700);
                const media = mediaElement();
                if (!media || media.currentTime >= result.startedAt - 3) break;
                result.startedAt = await seekTo(wanted);
              }
            }
          } catch (ex) {
            result.seekError = ex.message; // the song plays, only from the beginning
          }
          return result;
        }
        await new Promise(resolve => setTimeout(resolve, 250));
      }
      return { ok: false, url: location.href, played: { title: best.title, artists: best.artists, score: best.score },
        error: 'The song was selected, but the player does not play it.' };
    } finally {
      mediaElements().forEach(m => { m.muted = muted.find(([x]) => x === m)?.[1] ?? false; });
    }
  }

  /** Wait until the selector matches, then answer like renderDom(). */
  async function handleDom(message) {
    if (message.waitFor) {
      const timeout = Math.min(message.timeout || 10000, 30000);
      const end = Date.now() + timeout;
      while (deepQueryAll(message.waitFor).length === 0) {
        if (Date.now() > end) {
          return {
            ok: false,
            url: location.href,
            error: `Timed out after ${timeout} ms waiting for "${message.waitFor}".`,
          };
        }
        await new Promise(resolve => setTimeout(resolve, 200));
      }
    }
    return renderDom(message);
  }

  browser.runtime.onMessage.addListener(message => {
    try {
      switch (message?.cmd) {
        case 'ping':
          return Promise.resolve({
            ok: true,
            url: location.href,
            title: document.title,
            readyState: document.readyState,
          });
        case 'dom':
          return handleDom(message);
        case 'play':
          return handlePlay(message).catch(ex => ({ ok: false, error: `${ex.name}: ${ex.message}` }));
        case 'control':
          return handleControl(message).catch(ex => ({ ok: false, error: `${ex.name}: ${ex.message}` }));
        case 'autoplay':
          return ensureAutoplayOff({ force: true }).catch(ex => ({ ok: false, error: `${ex.name}: ${ex.message}` }));
        case 'act':
          return handleAct(message).catch(ex => ({ ok: false, error: `${ex.name}: ${ex.message}` }));
      }
    } catch (ex) {
      return Promise.resolve({ ok: false, error: `${ex.name}: ${ex.message}` });
    }
    return undefined;
  });
})();
