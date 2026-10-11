function createMonitorModule(ctx) {
  const logViews = new Map();
  const chartViews = new Set();
  const trafficByNetwork = new Map();
  const sessionsByNetwork = new Map();
  const logs = [];
  const capture = { core: 'info', app: 'info', capacity: 400 };
  const priorities = { trace: 0, debug: 1, info: 2, warn: 3, error: 4 };
  const number = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 1 });
  let logSequence = 0;
  let discarded = 0;
  let tickNumber = 0;
  let disposed = false;
  let lastScrollY = typeof window !== 'undefined' ? window.scrollY : 0;
  const el = ctx.el;
  const setText = (node, value) => { if (node && node.textContent !== String(value)) node.textContent = String(value); };
  const monoNow = () => performance.now();
  const wallNow = () => Date.now();
  const timeLabel = value => new Date(value).toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const visible = node => !!node && !document.hidden && !node.closest('[hidden]');
  const label = (id, text) => { const node = el('label', 'eu-label', text); node.htmlFor = id; return node; };
  function choice(value, text) { const node = el('option', '', text); node.value = value; return node; }
  function makeSelect(title, values, current, onChange) {
    const wrap = el('div', 'eu-field');
    const select = el('select', 'eu-select cursor-interaction'); select.id = ctx.uid('monitor-select');
    values.forEach(([value, text]) => select.append(choice(value, text))); select.value = current;
    select.addEventListener('change', () => onChange(select.value));
    wrap.append(label(select.id, title), select); return { wrap, select };
  }
  function redact(value, key = '') {
    if (/secret|password|private.?key|token|credential|authorization/i.test(key)) return '[已隐藏]';
    if (Array.isArray(value)) return value.map(item => redact(item));
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([name, item]) => [name, redact(item, name)]));
    if (typeof value !== 'string') return value;
    return value.replace(/-----BEGIN[\s\S]*?-----END[^-]*-----/g, '[已隐藏密钥]')
      .replace(/(authorization\s*[=:]\s*)(?:bearer|basic)\s+[^\s,;]+/gi, '$1[已隐藏]')
      .replace(/\bBearer\s+[A-Za-z0-9._~+\/-]+=*/gi, 'Bearer [已隐藏]')
      .replace(/\b(?:https?|tcp|udp|ws|wss|quic):\/\/[^\s"'<>]+/gi, raw => {
        try { const uri = new URL(raw); return uri.username || uri.password || uri.search || uri.hash || uri.pathname && uri.pathname !== '/' ? uri.protocol + '//[已隐藏敏感地址]' : raw; }
        catch (_) { return '[已隐藏地址]'; }
      })
      .replace(/((?:secret|password|private[_-]?key|token|authorization)\s*[=:]\s*)(?:"[^"]*"|'[^']*'|[^\s,;]+)/gi, '$1[已隐藏]');
  }
  function latestSession(id) { const sessions = sessionsByNetwork.get(id) || []; return sessions[sessions.length - 1] || null; }
  function networkSession(id) {
    const runtime = ctx.runtime();
    if (runtime && runtime.networkId === id && runtime.sessionId) return runtime.sessionId;
    return latestSession(id)?.id || null;
  }
  function addLog(source, level, module, message, details = {}, networkId = null, peerId = null) {
    if (disposed) return;
    source = source === 'core' ? 'core' : 'app';
    level = Object.hasOwn(priorities, level) ? level : 'info';
    if (priorities[level] < priorities[capture[source]]) return;
    const safe = redact(details || {});
    const sessionId = networkId === null ? null : details?.sessionId || details?.session_id || networkSession(networkId);
    const entry = { id: ++logSequence, time: wallNow(), source, level, module: redact(module), message: redact(message), details: safe, networkId, peerId, sessionId };
    logs.push(entry);
    while (logs.length > capture.capacity) { logs.shift(); discarded++; }
    for (const view of logViews.values()) {
      if (!visible(view.container)) continue;
      if (view.mode === 'live') syncLogs(view); else updateLogStatus(view);
    }
  }
  function matchesLog(view, entry) {
    if (view.route.kind === 'scoped-logs') {
      if (entry.networkId !== view.route.networkId || entry.peerId !== view.route.peerId) return false;
      if (view.fixedSession && entry.sessionId !== view.fixedSession) return false;
    } else if (view.scope === 'global') {
      if (entry.networkId !== null) return false;
    } else {
      if (entry.networkId !== view.route.networkId) return false;
      if (view.scope === 'current' && entry.sessionId !== (view.mode === 'reading' ? view.readingSession : networkSession(view.route.networkId))) return false;
    }
    if (view.source !== 'all' && entry.source !== view.source) return false;
    if (view.level !== 'all' && priorities[entry.level] < priorities[view.level]) return false;
    const text = [entry.message, entry.module, entry.source === 'core' ? 'EasyTier' : 'App', JSON.stringify(entry.details)].join(' ').toLowerCase();
    return !view.query || text.includes(view.query.toLowerCase());
  }
  function scopeText(view) {
    if (view.route.kind === 'scoped-logs') {
      const peer = ctx.peer(view.route.networkId, view.route.peerId);
      const runtime = ctx.runtime();
      const current = runtime?.networkId === view.route.networkId && runtime.sessionId === view.fixedSession && runtime.phase === 'connected';
      return ctx.networkLabel(view.route.networkId) + ' · ' + (peer?.name || view.route.peerId) + ' · ' + (view.fixedSession ? current ? '本次连接' : '所选连接已结束' : '尚无连接');
    }
    if (view.scope === 'global') return '全局 · 不属于某个网络的进程记录';
    if (view.scope === 'history') return ctx.networkLabel(view.route.networkId) + ' · 全部连接';
    if (view.mode === 'reading' && view.readingSession !== networkSession(view.route.networkId)) return ctx.networkLabel(view.route.networkId) + ' · 上一次连接 · 回看中';
    const runtime = ctx.runtime();
    return ctx.networkLabel(view.route.networkId) + ' · ' + (runtime?.networkId === view.route.networkId && runtime.phase === 'connected' ? '本次连接' : latestSession(view.route.networkId) ? '最近连接' : '尚无连接');
  }
  function setReading(view) {
    if (view.mode === 'reading') return;
    view.mode = 'reading'; view.boundary = logSequence; view.readingSession = networkSession(view.route.networkId);
    view.list.style.overflowAnchor = 'auto';
    view.container.dataset.monitorMode = 'reading'; updateLogStatus(view);
  }
  function resumeLogs(view) {
    view.mode = 'live'; view.boundary = logSequence; view.limit = 16;
    view.readingSession = null; view.list.style.overflowAnchor = 'none';
    view.container.dataset.monitorMode = 'live';
    syncLogs(view, true);
    view.anchor.scrollIntoView({ block: 'start', behavior: 'auto' });
  }
  function updateLogStatus(view) {
    const pending = logs.reduce((count, entry) => count + (entry.id > view.boundary && matchesLog(view, entry) ? 1 : 0), 0);
    const newerSession = view.route.kind === 'logs' && view.scope === 'current' && view.mode === 'reading' && view.readingSession !== networkSession(view.route.networkId);
    setText(view.scopeLabel, scopeText(view));
    setText(view.stateLabel, view.mode === 'live' ? '最新在前 · 实时更新' : '正在阅读 · 采集继续');
    setText(view.pause, view.mode === 'live' ? '暂停跟随' : '回到最新');
    view.pause.setAttribute('aria-pressed', String(view.mode === 'reading'));
    view.pending.hidden = view.mode !== 'reading' || !newerSession && pending === 0;
    setText(view.pending, newerSession ? '新连接已开始 · 查看本次日志' : pending + ' 条新日志 · 回到最新');
    setText(view.retention, '最多保留 ' + capture.capacity + ' 条' + (discarded ? ' · 已滚动移除 ' + discarded + ' 条较早记录' : '') + ' · 记录已脱敏');
    for (const [key, control] of Object.entries(view.captureControls || {})) if (control.value !== capture[key]) control.value = capture[key];
    if (view.scopeControl) {
      const option = view.scopeControl.querySelector('option[value="current"]');
      if (option) setText(option, ctx.isRunning(view.route.networkId) ? '本次连接' : latestSession(view.route.networkId) ? '最近连接' : '尚无连接');
    }
  }
  async function copyLog(entry, button) {
    const payload = [timeLabel(entry.time), entry.source === 'core' ? 'EasyTier' : 'App', entry.level.toUpperCase(), entry.module, entry.message, JSON.stringify(entry.details, null, 2)].join('\n');
    try {
      if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable');
      await navigator.clipboard.writeText(payload); ctx.notify('已复制这条日志');
    } catch (_) {
      let area = button.parentElement.querySelector('textarea');
      if (!area) { area = el('textarea', 'eu-input eu-log-copy'); area.readOnly = true; area.rows = 6; area.id = ctx.uid('log-copy'); area.setAttribute('aria-label', '可复制的脱敏日志'); button.parentElement.append(area); }
      area.value = payload; area.focus(); area.select(); ctx.notify('已选中日志，可使用系统复制');
    }
  }
  function logRow(entry, view) {
    const row = el('details', 'eu-log-entry'); row.dataset.logId = entry.id; row.id = ctx.uid('log-entry');
    const summary = el('summary', 'cursor-interaction');
    const meta = el('div', 'eu-log-meta');
    const time = el('time', 'eu-log-time', timeLabel(entry.time)); time.dateTime = new Date(entry.time).toISOString();
    const level = el('span', 'eu-log-level', entry.level.toUpperCase()); level.dataset.level = entry.level;
    meta.append(time, el('span', 'eu-log-source', entry.source === 'core' ? 'EasyTier' : 'App'), level);
    summary.append(meta, el('span', 'eu-log-message', entry.message));
    const content = el('div', 'eu-log-body');
    content.append(el('p', 'eu-subtle', entry.module), el('p', 'eu-subtle', (entry.networkId === null ? '全局' : ctx.networkLabel(entry.networkId)) + (entry.sessionId ? ' · 连接 ' + entry.sessionId : '') + ' · #' + entry.id));
    if (Object.keys(entry.details).length) {
      const fields = el('dl', 'eu-log-fields');
      Object.entries(entry.details).forEach(([key, value]) => { fields.append(el('dt', '', key), el('dd', '', typeof value === 'object' ? JSON.stringify(value) : String(value))); });
      content.append(fields);
    }
    const copy = ctx.button('复制这条日志', () => copyLog(entry, copy), 'eu-button eu-text-button'); copy.prepend(ctx.icon('copy')); content.append(copy);
    row.append(summary, content);
    summary.addEventListener('click', () => setReading(view));
    row.addEventListener('toggle', () => { if (row.open) { view.open.add(entry.id); setReading(view); } else view.open.delete(entry.id); });
    row.addEventListener('focusin', () => setReading(view));
    return row;
  }
  function syncLogs(view, filterChanged = false) {
    if (!visible(view.container)) return;
    updateLogStatus(view);
    if (view.mode === 'live') view.boundary = logSequence;
    if (view.mode === 'reading' && !filterChanged && view.renderedOnce) return;
    const chosen = logs.filter(entry => entry.id <= view.boundary && matchesLog(view, entry)).slice(-view.limit).reverse();
    const keep = new Set(chosen.map(entry => entry.id));
    const focused = document.activeElement?.closest?.('[data-log-id]');
    const focusId = focused ? Number(focused.dataset.logId) : null;
    for (const [id, node] of view.nodes) {
      if (!keep.has(id) && (!node.open && id !== focusId || filterChanged)) { node.remove(); view.nodes.delete(id); }
    }
    let cursor = view.list.firstElementChild;
    chosen.forEach(entry => {
      let node = view.nodes.get(entry.id);
      if (!node) { node = logRow(entry, view); view.nodes.set(entry.id, node); }
      if (node !== cursor) view.list.insertBefore(node, cursor); else cursor = cursor.nextElementSibling;
      node.open = view.open.has(entry.id);
    });
    view.empty.hidden = chosen.length > 0;
    const matches = logs.filter(entry => entry.id <= view.boundary && matchesLog(view, entry));
    view.older.hidden = matches.length <= view.limit;
    view.renderedOnce = true;
    updateLogStatus(view);
  }
  function mountLogs(route, container) {
    const view = { route, container, source: 'all', level: 'all', query: '', scope: 'current', mode: 'live', boundary: logSequence, limit: 16, fixedSession: route.kind === 'scoped-logs' ? route.sessionId || networkSession(route.networkId) : null, nodes: new Map(), open: new Set(), renderedOnce: false };
    container.classList.add('eu-monitor-logs'); container.dataset.monitorMode = 'live';
    const header = el('div', 'eu-title-row'); header.append(el('h2', '', route.kind === 'scoped-logs' ? '相关日志' : '运行日志')); container.append(header);
    view.scopeLabel = el('p', 'eu-subtle eu-log-scope'); container.append(view.scopeLabel);
    if (route.kind !== 'scoped-logs') {
      const scope = makeSelect('记录范围', [['current', '本次连接'], ['history', '此网络全部连接'], ['global', '全局']], view.scope, value => { view.scope = value; view.limit = 16; view.boundary = logSequence; view.readingSession = networkSession(view.route.networkId); syncLogs(view, true); });
      view.scopeControl = scope.select; scope.select.dataset.logScope = ''; container.append(scope.wrap);
    }
    const sources = el('div', 'eu-chips eu-log-sources'); sources.setAttribute('aria-label', '日志来源');
    [['all', '全部'], ['core', 'EasyTier'], ['app', 'App']].forEach(([value, text]) => {
      const button = ctx.button(text, () => { view.source = value; view.limit = 16; for (const other of sources.children) other.setAttribute('aria-pressed', String(other === button)); syncLogs(view, true); }, 'eu-button eu-chip');
      button.setAttribute('aria-pressed', String(value === 'all')); button.dataset.logSource = value; sources.append(button);
    }); container.append(sources);
    const filters = el('div', 'eu-log-filters');
    const searchWrap = el('div', 'eu-field'); const search = el('input', 'eu-input'); search.id = ctx.uid('log-search'); search.type = 'search'; search.placeholder = '消息、模块或 IP'; search.dataset.logSearch = ''; search.autocomplete = 'off';
    searchWrap.append(label(search.id, '搜索日志'), search); filters.append(searchWrap);
    search.addEventListener('input', () => { view.query = search.value.trim(); view.limit = 16; syncLogs(view, true); });
    const level = makeSelect('显示级别', [['all', '全部级别'], ['info', '信息及以上'], ['warn', '警告及以上'], ['error', '仅错误']], 'all', value => { view.level = value; view.limit = 16; syncLogs(view, true); }); level.select.dataset.logLevel = ''; filters.append(level.wrap); container.append(filters);
    view.anchor = el('div', 'eu-log-reading-bar');
    view.stateLabel = el('span', 'eu-subtle');
    view.pause = ctx.button('暂停跟随', () => view.mode === 'live' ? setReading(view) : resumeLogs(view), 'eu-button eu-text-button'); view.pause.dataset.logFollow = ''; view.pause.setAttribute('aria-pressed', 'false');
    view.anchor.append(view.stateLabel, view.pause); container.append(view.anchor);
    view.pending = ctx.button('', () => resumeLogs(view), 'eu-button eu-tonal eu-log-pending'); view.pending.dataset.logPending = ''; view.pending.hidden = true; view.pending.setAttribute('aria-live', 'polite'); container.append(view.pending);
    view.list = el('div', 'eu-log-list'); view.list.dataset.logList = ''; view.list.style.overflowAnchor = 'none'; container.append(view.list);
    view.empty = el('div', 'eu-empty', '当前范围没有匹配的日志'); view.empty.dataset.logEmpty = ''; container.append(view.empty);
    view.older = ctx.button('查看更早日志', () => { setReading(view); view.limit += 16; syncLogs(view, true); }, 'eu-button eu-text-button'); view.older.dataset.logOlder = ''; container.append(view.older);
    const settings = el('details', 'eu-log-settings'); const summary = el('summary', 'cursor-interaction', '日志采集设置'); summary.prepend(ctx.icon('sliders-horizontal')); settings.append(summary);
    const controls = el('div', 'eu-log-settings-body'); controls.append(el('p', 'eu-subtle', '全局设置，仅影响后续记录。上方筛选不改变采集。'));
    view.captureControls = {};
    for (const [key, title] of [['core', 'EasyTier 采集级别'], ['app', 'App 采集级别']]) {
      const config = makeSelect(title, [['error', 'Error'], ['warn', 'Warn'], ['info', 'Info'], ['debug', 'Debug'], ...(key === 'core' ? [['trace', 'Trace']] : [])], capture[key], value => {
        capture[key] = value; setText(view.settingsFeedback, (key === 'core' ? 'EasyTier' : 'App') + ' 已改为 ' + value.toUpperCase() + '，已有记录保留。');
        for (const other of logViews.values()) updateLogStatus(other);
      }); config.select.dataset.captureLevel = key; view.captureControls[key] = config.select; controls.append(config.wrap);
    }
    view.retention = el('p', 'eu-subtle'); controls.append(view.retention);
    view.settingsFeedback = el('p', 'eu-subtle'); view.settingsFeedback.setAttribute('role', 'status'); controls.append(view.settingsFeedback); settings.append(controls); container.append(settings);
    logViews.set(route.key, view); syncLogs(view, true);
    ctx.onCleanup(route.key, () => logViews.delete(route.key));
  }

  function simulatedRates(elapsed, id) {
    const phase = [...String(id)].reduce((sum, char) => sum + char.charCodeAt(0), 0) % 11;
    const x = elapsed / 1000 + phase;
    const cycle = ((x % 90) + 90) % 90;
    const multiplier = id === 'home' ? 1 : .42;
    // 示意业务下载、上传与闲置阶段；不把底层保活计入 VPN 业务流量。
    if (cycle >= 13 && cycle < 36) {
      const envelope = Math.sin(Math.PI * (cycle - 13) / 23);
      return { rx: multiplier * envelope * (620 + 85 * Math.sin(x * .8)), tx: multiplier * envelope * (38 + 12 * Math.sin(x * .6)) };
    }
    if (cycle >= 39 && cycle < 49) {
      const envelope = Math.sin(Math.PI * (cycle - 39) / 10);
      return { rx: multiplier * envelope * 115, tx: multiplier * envelope * 72 };
    }
    if (cycle >= 64 && cycle < 78) {
      const envelope = Math.sin(Math.PI * (cycle - 64) / 14);
      return { rx: multiplier * envelope * 47, tx: multiplier * envelope * (360 + 55 * Math.sin(x * .7)) };
    }
    return { rx: 0, tx: 0 };
  }
  function appendSample(data, mono, wall, nextRx, nextTx, forceGap = false) {
    const previous = data.previous;
    const dt = previous ? (mono - previous.mono) / 1000 : 0;
    const valid = !forceGap && previous && dt > 0 && dt <= 3 && nextRx >= previous.rx && nextTx >= previous.tx;
    const sample = { mono, wall, rx: valid ? (nextRx - previous.rx) / dt / 1024 : null, tx: valid ? (nextTx - previous.tx) / dt / 1024 : null };
    data.samples.push(sample);
    data.samples = data.samples.filter(item => item.mono >= mono - 65000);
    data.rxBytes = nextRx; data.txBytes = nextTx;
    data.previous = { mono, rx: nextRx, tx: nextTx }; data.lastMono = mono; data.lastWall = wall;
  }
  function newSession(runtime) {
    if (!runtime?.networkId || !runtime.sessionId) return;
    const old = trafficByNetwork.get(runtime.networkId);
    const key = runtime.sessionId + ':' + runtime.generation;
    if (old?.key === key) return;
    const mono = monoNow(), wall = wallNow();
    const historySeconds = !old && runtime.phase === 'connected' ? Math.max(0, Math.min(60, Math.floor((wall - Number(runtime.startedAt || wall)) / 1000))) : 0;
    const data = { networkId: runtime.networkId, sessionId: runtime.sessionId, key, generation: runtime.generation, active: true, samples: [], rxBytes: 0, txBytes: 0, previous: null, lastMono: mono, lastWall: wall, startedMono: mono - historySeconds * 1000 };
    for (let seconds = historySeconds; seconds >= 0; seconds--) {
      const at = mono - seconds * 1000, rates = simulatedRates(at - data.startedMono, data.networkId);
      appendSample(data, at, wall - seconds * 1000, data.rxBytes + Math.round(rates.rx * 1024), data.txBytes + Math.round(rates.tx * 1024));
    }
    if (!historySeconds) { data.rxBytes = 0; data.txBytes = 0; data.previous = { mono, rx: 0, tx: 0 }; data.samples = [{ mono, wall, rx: null, tx: null }]; }
    trafficByNetwork.set(runtime.networkId, data);
    const sessions = sessionsByNetwork.get(runtime.networkId) || [];
    if (!sessions.some(session => session.id === runtime.sessionId)) sessions.push({ id: runtime.sessionId, startedAt: wall, endedAt: null });
    sessionsByNetwork.set(runtime.networkId, sessions.slice(-8));
    addLog('core', 'info', 'easytier::instance', '网络实例已进入运行状态', { sessionId: runtime.sessionId }, runtime.networkId);
    addLog('app', 'info', 'VpnService', '虚拟网卡与路由已就绪', { sessionId: runtime.sessionId }, runtime.networkId);
    const nas = ctx.peer(runtime.networkId, 'nas');
    if (nas) addLog('core', 'info', 'easytier::peer', '已连接 ' + nas.name, { address: nas.ip, sessionId: runtime.sessionId }, runtime.networkId, 'nas');
    addLog('core', 'info', 'easytier::route', '组网路由已同步', { sessionId: runtime.sessionId }, runtime.networkId);
    for (const view of chartViews) { if (view.networkId === runtime.networkId) drawChart(view); }
  }
  function stopSession(networkId) {
    const data = trafficByNetwork.get(networkId);
    if (data && data.active) {
      appendSample(data, monoNow(), wallNow(), data.rxBytes, data.txBytes, true); data.active = false; data.previous = null;
      const session = latestSession(networkId); if (session) session.endedAt = wallNow();
    }
    for (const view of chartViews) if (view.networkId === networkId) drawChart(view);
  }
  function snapshot(networkId) {
    const data = trafficByNetwork.get(networkId);
    if (!data?.active || !ctx.isRunning(networkId) || ctx.runtime()?.sessionId !== data.sessionId) return null;
    const latest = data.samples[data.samples.length - 1];
    const sample = latest && monoNow() - latest.mono <= 3000 ? latest : null;
    return { rxRate: sample?.rx ?? null, txRate: sample?.tx ?? null, rxBytes: data.rxBytes, txBytes: data.txBytes };
  }
  function svgNode(tag, attrs = {}, content) {
    const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, String(value));
    if (content !== undefined) node.textContent = String(content); return node;
  }
  function scale(domain, range) {
    if (typeof d3 !== 'undefined') return d3.scaleLinear().domain(domain).range(range);
    return value => range[0] + (value - domain[0]) / (domain[1] - domain[0]) * (range[1] - range[0]);
  }
  function chartSource(view) {
    const data = trafficByNetwork.get(view.networkId);
    const runtime = ctx.runtime();
    if (!data?.active || !ctx.isRunning(view.networkId) || runtime?.sessionId !== data.sessionId) return null;
    return { samples: data.samples, end: monoNow(), key: data.key };
  }
  function drawChart(view) {
    if (!visible(view.container)) return;
    const source = chartSource(view);
    view.empty.hidden = !!source; view.chartBox.hidden = !source; view.metrics.hidden = !source;
    view.status.hidden = true;
    if (!source) { setText(view.rx, '—'); setText(view.tx, '—'); view.container.dataset.trafficState = 'disconnected'; return; }
    const end = source.end, start = end - 60000;
    const samples = source.samples.filter(sample => sample.mono >= start && sample.mono <= end);
    const width = Math.max(220, Math.round(view.chartBox.getBoundingClientRect().width || 340));
    const height = 250, left = 64, right = width - 12, top = 20, bottom = 220;
    const values = samples.flatMap(sample => [sample.rx, sample.tx]).filter(value => Number.isFinite(value));
    const peak = values.length ? Math.max(...values) : 0, maximum = peak > 0 ? Math.ceil(peak * 1.12 / 10) * 10 : 1;
    const x = scale([start, end], [left, right]), y = scale([0, maximum], [bottom, top]);
    view.svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height); view.svg.style.height = height + 'px';
    for (const rect of [view.frame, view.clipRect]) for (const [key, value] of Object.entries({ x: left, y: top, width: right - left, height: bottom - top })) rect.setAttribute(key, value);
    [0, maximum / 2, maximum].forEach((value, index) => {
      const grid = view.grids[index]; grid.setAttribute('x1', left); grid.setAttribute('x2', right); grid.setAttribute('y1', y(value)); grid.setAttribute('y2', y(value));
      const tick = view.yTicks[index]; tick.setAttribute('x', left - 8); tick.setAttribute('y', y(value) + 4); setText(tick, number.format(value));
    });
    const marks = [[start, '60 秒前'], [(start + end) / 2, '30 秒前'], [end, '现在']];
    marks.forEach(([at, text], index) => { const tick = view.xTicks[index]; tick.setAttribute('x', x(at)); tick.setAttribute('y', bottom + 23); tick.setAttribute('visibility', index === 1 && width < 310 ? 'hidden' : 'visible'); setText(tick, text); });
    for (const key of ['rx', 'tx']) {
      let path = '';
      if (typeof d3 !== 'undefined') path = d3.line().defined(sample => Number.isFinite(sample[key])).x(sample => x(sample.mono)).y(sample => y(sample[key]))(samples) || '';
      else { let started = false; for (const sample of samples) { if (!Number.isFinite(sample[key])) { started = false; continue; } path += (started ? 'L' : 'M') + x(sample.mono).toFixed(2) + ',' + y(sample[key]).toFixed(2); started = true; } }
      view.paths[key].setAttribute('d', path);
    }
    const latest = samples[samples.length - 1];
    const fresh = latest && end - latest.mono <= 3000 && Number.isFinite(latest.rx) && Number.isFinite(latest.tx);
    setText(view.rx, fresh ? number.format(latest.rx) : '—');
    setText(view.tx, fresh ? number.format(latest.tx) : '—');
    view.status.hidden = !!fresh;
    if (!fresh) setText(view.status, values.length ? '暂无新采样' : '正在等待流量数据');
    view.container.dataset.trafficState = fresh ? 'ready' : 'waiting';
  }
  function makeChart(container, networkId, routeKey) {
    const view = { container, networkId, routeKey, resize: null };
    container.classList.add('eu-monitor-traffic'); container.dataset.trafficNetwork = networkId;
    const head = el('div', 'eu-title-row'); head.append(el('h2', '', '实时流量')); container.append(head);
    container.append(el('p', 'eu-subtle', '本机 VPN 流量 · 最近 60 秒'));
    view.empty = el('div', 'eu-empty', '连接此网络后显示实时流量'); container.append(view.empty);
    view.metrics = el('div', 'eu-traffic-values');
    for (const key of ['rx', 'tx']) {
      const metric = el('div', 'eu-traffic-value'); const name = el('span', 'eu-subtle'); const swatch = el('span', 'eu-chart-swatch eu-chart-swatch-' + key); name.append(swatch, document.createTextNode(key === 'rx' ? '下行' : '上行'));
      const data = el('div', 'eu-traffic-number'); view[key] = el('span', '', '—'); view[key].dataset.trafficRate = key; data.append(view[key], el('span', 'eu-traffic-unit', 'KiB/s')); metric.append(name, data); view.metrics.append(metric);
    } container.append(view.metrics);
    view.chartBox = el('div', 'eu-chart-box');
    view.svg = svgNode('svg', { class: 'eu-traffic-chart', role: 'img', 'aria-label': '本机 VPN 最近 60 秒上下行流量趋势，实线下行，虚线上行' });
    view.svg.append(svgNode('title', {}, '本机 VPN 流量'), svgNode('desc', {}, '最近 60 秒，单位 KiB/s。实线下行，虚线上行。缺少采样时显示断点。'));
    const clipId = ctx.uid('traffic-clip'); const defs = svgNode('defs'); const clip = svgNode('clipPath', { id: clipId }); view.clipRect = svgNode('rect'); clip.append(view.clipRect); defs.append(clip); view.svg.append(defs);
    view.frame = svgNode('rect', { fill: 'none', class: 'eu-chart-frame', 'data-chart-frame': '' }); view.svg.append(view.frame);
    view.grids = []; view.yTicks = []; view.xTicks = [];
    for (let index = 0; index < 3; index++) {
      const grid = svgNode('line', { class: 'eu-chart-grid' }), tick = svgNode('text', { 'text-anchor': 'end', class: 'eu-chart-tick' }); view.grids.push(grid); view.yTicks.push(tick); view.svg.append(grid, tick);
    }
    view.svg.append(svgNode('text', { x: 0, y: 12, 'data-axis': 'y', class: 'eu-chart-axis' }, 'KiB/s'));
    for (let index = 0; index < 3; index++) { const tick = svgNode('text', { 'text-anchor': index === 0 ? 'start' : index === 2 ? 'end' : 'middle', class: 'eu-chart-tick', ...(index === 1 ? { 'data-axis': 'x' } : {}) }); view.xTicks.push(tick); view.svg.append(tick); }
    const lines = svgNode('g', { 'clip-path': 'url(#' + clipId + ')' }); view.paths = {};
    for (const key of ['rx', 'tx']) { view.paths[key] = svgNode('path', { class: 'eu-chart-line eu-chart-line-' + key, fill: 'none' }); lines.append(view.paths[key]); } view.svg.append(lines);
    view.chartBox.append(view.svg); container.append(view.chartBox);
    view.status = el('p', 'eu-subtle eu-traffic-status'); view.status.hidden = true; container.append(view.status);
    chartViews.add(view);
    if (typeof ResizeObserver !== 'undefined') { view.resize = new ResizeObserver(() => drawChart(view)); view.resize.observe(view.chartBox); }
    if (routeKey) ctx.onCleanup(routeKey, () => { view.resize?.disconnect(); chartViews.delete(view); });
    drawChart(view); return view;
  }
  function mountOverview(container, networkId) { return makeChart(container, networkId, ctx.currentRoute()?.key); }
  function mount(route, container) {
    if (route.kind === 'logs' || route.kind === 'scoped-logs') mountLogs(route, container);
  }
  function refresh(route, container) {
    if (route.kind === 'logs' || route.kind === 'scoped-logs') { const view = logViews.get(route.key); if (view) syncLogs(view); }
    for (const chart of chartViews) if (chart.container === container || chart.routeKey === route.key) drawChart(chart);
  }
  function tick() {
    if (disposed) return;
    const runtime = ctx.runtime(); const mono = monoNow(), wall = wallNow(); tickNumber++;
    if (runtime?.phase === 'connected') {
      const data = trafficByNetwork.get(runtime.networkId);
      if (data?.active && data.sessionId === runtime.sessionId) {
        const dt = (mono - data.lastMono) / 1000;
        if (document.hidden) { data.previous = null; data.lastMono = mono; }
        else if (dt > 0) {
          const rates = simulatedRates(mono - data.startedMono, data.networkId);
          appendSample(data, mono, wall, data.rxBytes + Math.round(rates.rx * 1024 * dt), data.txBytes + Math.round(rates.tx * 1024 * dt), dt > 3);
        }
        if (tickNumber % 7 === 0) addLog('core', 'info', 'easytier::peer_manager', '设备连接状态已更新', { sessionId: runtime.sessionId }, runtime.networkId);
        if (tickNumber % 11 === 0) addLog('app', 'info', 'NetworkMonitor', '底层网络保持可用', { transport: 'Wi-Fi', sessionId: runtime.sessionId }, runtime.networkId);
        if (tickNumber % 3 === 0) addLog('app', 'debug', 'TrafficSampler', '已更新本机 VPN 流量采样', { sessionId: runtime.sessionId }, runtime.networkId);
      }
    }
    if (document.hidden) return;
    for (const chart of chartViews) if (visible(chart.container)) drawChart(chart);
    for (const view of logViews.values()) if (visible(view.container)) { if (view.mode === 'live') syncLogs(view); else updateLogStatus(view); }
  }
  function onScroll() {
    const next = window.scrollY;
    if (next > lastScrollY + 2) for (const view of logViews.values()) {
      if (view.mode === 'live' && visible(view.container) && view.list.getBoundingClientRect().top < -48) setReading(view);
    }
    lastScrollY = next;
  }
  if (typeof window !== 'undefined') window.addEventListener('scroll', onScroll, { passive: true });
  function dispose() { disposed = true; if (typeof window !== 'undefined') window.removeEventListener('scroll', onScroll); for (const view of chartViews) view.resize?.disconnect(); chartViews.clear(); logViews.clear(); }
  addLog('app', 'info', 'AppLifecycle', '应用已就绪', { component: 'Android 客户端' });
  return { mount, mountOverview, refresh, tick, addLog, newSession, stopSession, snapshot, dispose };
}
