function createConfigModule(ctx, catalogue) {
  const fields = catalogue.fields.filter(field => field.group !== 'logs');
  const fieldByKey = new Map(fields.map(field => [field.key, field]));
  const groups = catalogue.groups.filter(group => group.id !== 'logs');
  const groupById = new Map(groups.map(group => [group.id, group]));
  const views = new Map();
  const listModes = new Map();
  const peerMemory = new Map();
  const simpleKeys = new Set(catalogue.meta?.simpleKeys || ['network_identity.network_name', 'network_identity.network_secret', 'peer']);
  const own = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const make = ctx.el;
  const sampleApps = [
    ['com.android.chrome', 'Chrome', '浏览器'],
    ['org.mozilla.firefox', 'Firefox', '浏览器'],
    ['com.termux', 'Termux', '终端'],
    ['com.plexapp.android', 'Plex', '影音'],
    ['org.videolan.vlc', 'VLC', '播放器'],
    ['com.synology.DSfile', 'DS file', '文件管理'],
    ['com.google.android.apps.docs', 'Google Drive', '云端文件'],
    ['com.microsoft.rdc.androidx', 'Windows App', '远程桌面']
  ];
  const relationPairs = [
    ['dhcp', 'ipv4'],
    ['android.appRoutingMode', 'android.appPackages'],
    ['android.internetRouting', 'exit_nodes'],
    ['android.dnsMode', 'android.dnsServers'],
    ['flags.accept_dns', 'flags.tld_dns_zone'],
    ['secure_mode.enabled', 'secure_mode.local_private_key'],
    ['secure_mode.enabled', 'secure_mode.local_public_key'],
    ['flags.enable_encryption', 'flags.encryption_algorithm'],
    ['flags.disable_udp_hole_punching', 'flags.disable_sym_hole_punching']
  ];
  const optionLabels = {
    all: '全部应用', allowlist: '仅选中的应用', denylist: '排除选中的应用',
    system: '跟随系统', custom: '自定义', bypass: '不经过组网', tunnel: '通过组网', block: '阻断',
    vpn: 'VPN 模式', 'no-tun': '仅代理（NO_TUN）', 'root-tun': 'Root TUN（暂不可用）',
    'overlay-only': '仅组网流量', 'exit-node': '通过互联网出口', None: '不压缩', Zstd: 'Zstd',
    user: '本地配置', web: '受管配置'
  };
  const helpOverrides = {
    instance_name: '此名称仅用于区分本机保存的配置。',
    hostname: '其他设备看到的名称，最多 32 个字符。',
    'network_identity.network_name': '同一网络的设备使用相同名称。',
    'network_identity.network_secret': '同一网络使用相同密码。留空与使用默认值分别保存。',
    dhcp: '关闭后，为本设备设置固定的组网地址。',
    'android.appRoutingMode': '选择哪些应用使用这个网络的 VPN 路由。',
    'android.internetRouting': '组网内流量始终按路由通信。互联网流量可以使用指定出口。',
    'android.dnsMode': '自定义时填写可访问的 DNS 服务器地址。',
    'android.ipv6Policy': '通过互联网出口时，请同时处理非组网 IPv6 流量。',
    'android.autoReconnect': '网络恢复后重试连接；主动断开后不自动重连。',
    peer: '每行一个完整地址，例如 udp://node.example.net:11010。',
    listeners: '允许其他节点连接此设备；每行填写一个监听地址。',
    vpn_portal_config: '允许 WireGuard 客户端经此设备访问虚拟网络。',
    'flags.enable_quic_proxy': '使用 QUIC 转发 TCP 流量，需要对端支持。',
    'flags.instance_recv_bps_limit': '单位为 B/s，可选择不限速。',
    'flags.foreign_relay_bps_limit': '单位为 B/s，可选择不限速。'
  };

  function profile(id) {
    const value = ctx.profile(id);
    value.raw = value.raw || {};
    value.errors = value.errors || {};
    return value;
  }
  function val(id, key, kind = 'draft') { return ctx.value(id, key, kind); }
  function text(node, content) {
    const next = String(content == null ? '' : content);
    if (node.textContent !== next) node.textContent = next;
  }
  function icon(name) { return ctx.icon(name); }
  function markChanged(id) {
    return [...new Set([...ctx.changed(id), ...Object.keys(profile(id).errors)])];
  }
  function optionLabel(value) { return optionLabels[String(value)] || String(value); }
  function defaultLabel(field) {
    if (field.displayDefault) return field.displayDefault;
    const value = field.uiDefault;
    if (value === null || value === undefined) return '未设置';
    if (typeof value === 'boolean') return value ? '开启' : '关闭';
    if (Array.isArray(value)) return value.length ? value.length + ' 个条目' : '空列表';
    return optionLabel(value);
  }
  function availability(field) {
    if (['unsupported', 'managed', 'deprecated', 'conditional'].includes(field.availability)) {
      return field.reason || '当前模式下不可编辑。';
    }
    return '';
  }
  function dependency(id, key) {
    if (key === 'ipv4' && val(id, 'dhcp') === true) return '关闭自动分配 IPv4 后可编辑。';
    if (key === 'android.appPackages' && val(id, 'android.appRoutingMode') === 'all') return '先选择需要包含或排除的应用范围。';
    if (key === 'android.dnsServers' && val(id, 'android.dnsMode') !== 'custom') return '将 DNS 策略设为自定义后可编辑。';
    if (key.startsWith('secure_mode.local_') && val(id, 'secure_mode.enabled') !== true) return '开启安全身份模式后可编辑。';
    if (key === 'flags.encryption_algorithm' && val(id, 'flags.enable_encryption') === false) return '开启通信加密后可编辑。';
    if (key === 'flags.tld_dns_zone' && val(id, 'flags.accept_dns') !== true) return '开启 MagicDNS 后可编辑。';
    if (key === 'flags.disable_sym_hole_punching' && val(id, 'flags.disable_udp_hole_punching') === true) return '开启 UDP 打洞后可编辑。';
    return '';
  }
  function disabledReason(id, field) { return availability(field) || dependency(id, field.key); }
  function modeFor(id, field) {
    const token = id + ':' + field.key;
    if (listModes.has(token)) return listModes.get(token);
    const p = profile(id);
    if (!own(p.draft, field.key)) return 'inherit';
    return Array.isArray(p.draft[field.key]) && p.draft[field.key].length ? 'custom' : 'empty';
  }
  function structured(field) {
    return field.type === 'object' || (!!field.children && field.key !== 'peer');
  }
  function containsSecret(field) {
    return (field.children || []).some(child => child.type === 'secret' || /secret|private_key|password|token/i.test(child.key));
  }
  function formatValue(field, value) {
    if (value === undefined || value === null) return '';
    if (structured(field)) return JSON.stringify(value, null, 2);
    if (Array.isArray(value)) {
      return field.key === 'peer' ? value.map(item => typeof item === 'string' ? item : item.uri || '').join('\n') : value.join('\n');
    }
    return String(value);
  }
  function rawValue(id, field) {
    const p = profile(id);
    if (own(p.raw, field.key)) return p.raw[field.key];
    return own(p.draft, field.key) ? formatValue(field, p.draft[field.key]) : '';
  }
  function validStructure(value) {
    if (value === null) return false;
    if (Array.isArray(value)) return value.every(validStructure);
    if (typeof value === 'object') return Object.values(value).every(validStructure);
    if (typeof value === 'number') return Number.isFinite(value) && (!Number.isInteger(value) || Number.isSafeInteger(value));
    return typeof value === 'string' || typeof value === 'boolean';
  }
  function validIPv4(value) {
    return /^\d{1,3}(\.\d{1,3}){3}$/.test(value) && value.split('.').every(part => Number(part) <= 255);
  }
  function validIPv6(value) {
    if (!value.includes(':') || /[\s/]/.test(value)) return false;
    try { return new URL('http://[' + value + ']/').hostname.length > 2; } catch (_) { return false; }
  }
  function validIP(value) { return validIPv4(value) || validIPv6(value); }
  function validCIDR(value, version) {
    const parts = value.split('/');
    if (parts.length !== 2 || !/^\d+$/.test(parts[1])) return false;
    const v4 = validIPv4(parts[0]);
    return (version === 4 ? v4 : version === 6 ? validIPv6(parts[0]) : validIP(parts[0])) && Number(parts[1]) <= (v4 ? 32 : 128);
  }
  function validURI(value) {
    if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(value) || /\s/.test(value)) return false;
    try { const result = new URL(value); return !!result.hostname || result.protocol === 'ring:'; } catch (_) { return false; }
  }
  function rememberPeers(id) {
    let records = peerMemory.get(id);
    if (!records) { records = new Map(); peerMemory.set(id, records); }
    const p = profile(id);
    [p.saved.peer, p.draft.peer].forEach(list => (list || []).forEach(item => {
      if (item && typeof item === 'object' && item.uri) records.set(item.uri, ctx.clone(item));
    }));
    return records;
  }
  function parse(field, raw, id) {
    const value = String(raw);
    if (field.type === 'number') {
      if (!/^\d+$/.test(value)) return { error: '请输入非负整数。' };
      if (field.storageType === 'decimal-string') {
        try {
          if (BigInt(value) > BigInt(field.unlimitedSentinel || '18446744073709551615')) return { error: '数值超出允许范围。' };
          return { value: value.replace(/^0+(?=\d)/, '') };
        } catch (_) { return { error: '请输入有效的整数。' }; }
      }
      const number = Number(value);
      if (!Number.isSafeInteger(number)) return { error: '整数超出可编辑范围。' };
      if (field.min !== undefined && number < field.min || field.max !== undefined && number > field.max) return { error: field.label + '应在 ' + (field.min ?? 0) + '～' + (field.max ?? '允许上限') + ' 之间。' };
      return { value: number };
    }
    if (structured(field)) {
      if (!value.trim() && field.type === 'object') return { remove: true };
      try {
        const parsed = JSON.parse(value);
        const correctType = field.type === 'object' ? !!parsed && typeof parsed === 'object' && !Array.isArray(parsed) : Array.isArray(parsed);
        if (!correctType || !validStructure(parsed)) return { error: '请输入有效的 JSON ' + (field.type === 'object' ? '对象' : '数组') + '；不接受 null 或超出安全范围的数字。' };
        return { value: parsed };
      } catch (_) { return { error: 'JSON 格式尚未完整，请检查括号、引号和逗号。' }; }
    }
    if (['list', 'optional-list', 'application-list'].includes(field.type)) {
      const rows = value.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
      if (field.key === 'peer') {
        if (rows.some(row => !validURI(row))) return { error: '节点地址需要完整协议和主机，例如 udp://node.example.net:11010。' };
        const records = rememberPeers(id);
        return { value: rows.map(uri => ({ ...(records.get(uri) || {}), uri })) };
      }
      if (['listeners', 'mapped_listeners'].includes(field.key) && rows.some(row => !validURI(row))) return { error: '每行填写一个完整的监听 URI。' };
      if (field.key === 'routes' && rows.some(row => !validCIDR(row))) return { error: '每行填写一个网段，例如 192.168.1.0/24。' };
      if (field.key === 'exit_nodes' && rows.some(row => !validIPv4(row))) return { error: '每行填写一个出口节点的组网 IPv4 地址。' };
      if (field.key === 'android.dnsServers' && rows.some(row => !validIP(row))) return { error: '每行填写一个 DNS 服务器的 IPv4 或 IPv6 地址。' };
      return { value: rows };
    }
    if (field.type === 'cidr' && value && !validCIDR(value, field.key === 'ipv4' ? 4 : field.key === 'ipv6' ? 6 : undefined)) return { error: '请输入完整地址和前缀长度，例如 ' + (field.key.includes('ipv6') ? 'fd00::6/64。' : '10.144.0.6/24。') };
    if (field.type === 'url' && value && !validURI(value)) return { error: '请输入包含协议的完整地址。' };
    if (field.maxLength && [...value].length > field.maxLength) return { error: '最多填写 ' + field.maxLength + ' 个字符。' };
    return { value };
  }

  function validate(id) {
    const p = profile(id);
    const errors = {};
    fields.forEach(field => {
      if (disabledReason(id, field)) return;
      if (own(p.raw, field.key)) {
        const result = parse(field, p.raw[field.key], id);
        if (result.error) errors[field.key] = result.error;
      } else if (own(p.draft, field.key) && !['boolean', 'select', 'application-list'].includes(field.type)) {
        const result = parse(field, formatValue(field, p.draft[field.key]), id);
        if (result.error) errors[field.key] = result.error;
      }
    });
    if (!String(val(id, 'network_identity.network_name') || '').trim()) errors['network_identity.network_name'] = '填写要加入的网络名称。';
    if (val(id, 'dhcp') === false && !val(id, 'ipv4')) errors.ipv4 = errors.ipv4 || '设置静态 IPv4 地址，或开启自动分配。';
    if (val(id, 'android.appRoutingMode') === 'allowlist' && !(val(id, 'android.appPackages') || []).length) errors['android.appPackages'] = '至少选择一个使用 VPN 路由的应用。';
    if (val(id, 'android.dnsMode') === 'custom' && !(val(id, 'android.dnsServers') || []).length) errors['android.dnsServers'] = errors['android.dnsServers'] || '至少填写一个 DNS 服务器地址。';
    if (val(id, 'android.internetRouting') === 'exit-node') {
      if (!(val(id, 'exit_nodes') || []).length) errors.exit_nodes = errors.exit_nodes || '选择通过互联网出口后，至少填写一个出口节点。';
      if (val(id, 'android.ipv6Policy') === 'bypass') errors['android.ipv6Policy'] = '通过出口时，请选择“通过组网”或“阻断”非组网 IPv6。';
    }
    p.errors = errors;
    return errors;
  }

  function write(id, field, value, remove = false, raw) {
    const p = profile(id);
    if (remove) delete p.draft[field.key];
    else p.draft[field.key] = ctx.clone(value);
    if (raw === undefined) delete p.raw[field.key];
    else p.raw[field.key] = raw;
    validate(id);
    ctx.touch(id);
    refreshNetwork(id);
  }
  function writeRaw(id, field, raw) {
    const p = profile(id);
    p.raw[field.key] = raw;
    const parsed = parse(field, raw, id);
    if (!parsed.error) {
      if (parsed.remove) delete p.draft[field.key];
      else p.draft[field.key] = ctx.clone(parsed.value);
    }
    validate(id);
    ctx.touch(id);
    refreshNetwork(id);
  }
  function changedDefault(id, field) {
    const p = profile(id);
    delete p.raw[field.key];
    listModes.delete(id + ':' + field.key);
    write(id, field, null, true);
  }
  function describe(id, key, kind = 'saved') {
    const field = fieldByKey.get(key);
    if (!field) return '未设置';
    const value = val(id, key, kind);
    if (field.secret || /secret|private_key|password|token/i.test(key)) return value === undefined || value === null ? '未设置' : value === '' ? '空密码' : '已设置';
    if (value === null || value === undefined) return '未设置';
    if (typeof value === 'boolean') return (field.invert ? !value : value) ? '开启' : '关闭';
    if (field.unlimitedSentinel && String(value) === field.unlimitedSentinel) return '不限速';
    if (key === 'android.appRoutingMode') {
      const count = (val(id, 'android.appPackages', kind) || []).length;
      return value === 'all' ? '全部应用' : (value === 'allowlist' ? '仅 ' : '排除 ') + count + ' 个应用';
    }
    if (Array.isArray(value)) {
      if (!value.length) return key === 'peer' ? '未配置节点' : '空列表';
      if (key === 'peer') return value.length + ' 个节点';
      if (key === 'exit_nodes') return value.join('、');
      if (key === 'android.appPackages') return '已选 ' + value.length + ' 个应用';
      return value.length + ' 个条目';
    }
    if (typeof value === 'object') {
      if (key === 'acl') {
        const chains = value.acl_v1 && value.acl_v1.chains;
        const rules = Array.isArray(chains) ? chains.reduce((count, chain) => count + (chain.rules || []).length, 0) : null;
        return rules === null ? '已配置' : rules + ' 条规则';
      }
      return '已配置';
    }
    const safeValue = typeof value === 'string' ? mask(value, key) : value;
    return optionLabel(safeValue) + (field.unit && !field.unlimitedSentinel ? ' ' + field.unit : '');
  }

  function goField(id, field) {
    const view = [...views.values()].find(item => item.id === id && ctx.isRouteVisible(item.route.key));
    if (view) focusField(view, field.key);
    else ctx.go({ kind: 'config-group', networkId: id, groupId: field.group, focusKey: field.key });
  }
  function applyChanges(view, mode) {
    const errors = validate(view.id);
    refreshNetwork(view.id);
    if (Object.keys(errors).length) { locateError(view); return; }
    ctx.commit(view.id, mode);
    refreshNetwork(view.id);
  }
  function locateError(view) {
    const key = Object.keys(profile(view.id).errors)[0];
    if (!key) return;
    const entry = view.rows && view.rows.get(key);
    if (entry) {
      if (view.mode === 'simple' && !simpleKeys.has(key)) setMode(view, 'all');
      if (view.mode === 'all') { view.query = ''; view.search.value = ''; view.onlyChanged = false; updateFormLayout(view); }
      let parent = entry.wrap.parentElement;
      while (parent && parent !== view.container) { if (parent.tagName === 'DETAILS') parent.open = true; parent = parent.parentElement; }
      entry.wrap.hidden = false;
      entry.wrap.scrollIntoView({ block: 'center', behavior: 'auto' });
      const control = entry.controls.find(item => !item.disabled);
      if (control) control.focus({ preventScroll: true });
    } else if (fieldByKey.has(key)) goField(view.id, fieldByKey.get(key));
  }
  function discard(id) {
    const p = profile(id);
    p.draft = ctx.clone(p.saved);
    reset(id);
    ctx.touch(id);
    ctx.snackbar('已撤销未保存的修改');
    refreshNetwork(id);
  }
  function syncStatusHost() {
    let visible = false;
    views.forEach(view => {
      if (!view.status) return;
      const active = ctx.isRouteVisible(view.route.key) && view.status.needsAttention;
      view.status.bar.hidden = !active;
      visible = visible || active;
    });
    ctx.configActionHost.hidden = !visible;
  }
  function mountStatus(view) {
    const bar = make('section', 'eu-config-status');
    bar.setAttribute('aria-label', '配置操作');
    bar.dataset.configStatusFor = view.route.key;
    bar.hidden = true;
    const message = make('p', 'eu-config-status-message');
    message.setAttribute('role', 'status');
    message.setAttribute('aria-live', 'polite');
    const actions = make('div', 'eu-actions eu-config-status-actions');
    const primary = ctx.button('保存', () => {
      const runtime = ctx.runtime();
      const retryConnection = runtime?.networkId === view.id && runtime.phase === 'error';
      applyChanges(view, ctx.isRunning(view.id) || retryConnection ? 'apply' : 'save');
    }, 'eu-button eu-primary');
    primary.dataset.configAction = 'apply';
    const save = ctx.button('仅保存', () => applyChanges(view, 'save'), 'eu-button eu-tonal');
    save.dataset.configAction = 'save';
    const revert = ctx.button('撤销', () => discard(view.id), 'eu-button eu-text-button');
    revert.dataset.configAction = 'revert';
    revert.setAttribute('aria-label', '撤销未保存的修改');
    const locate = ctx.button('定位错误', () => locateError(view), 'eu-button eu-text-button');
    locate.dataset.configAction = 'locate';
    const restore = ctx.button('恢复上次配置', () => { ctx.restoreApplied(view.id); reset(view.id); refreshNetwork(view.id); }, 'eu-button eu-text-button');
    restore.dataset.configAction = 'restore';
    restore.setAttribute('aria-label', '恢复上次运行配置');
    actions.append(primary, save, revert, locate, restore);
    bar.append(message, actions);
    view.status = { bar, message, primary, save, revert, locate, restore, needsAttention: false };
    ctx.configActionHost.append(bar);
  }
  function updateStatus(view) {
    if (!view.status) return;
    const p = profile(view.id), changed = ctx.changed(view.id), errors = Object.keys(p.errors);
    const op = ctx.operation(view.id) || { phase: 'idle', message: '' };
    const pending = ctx.pending(view.id), running = ctx.isRunning(view.id);
    const runtime = ctx.runtime();
    const failedCurrent = runtime?.networkId === view.id && runtime.phase === 'error';
    const busy = op.phase === 'applying';
    const retryable = failedCurrent || (running && op.phase === 'error');
    const actionable = changed.length > 0 || (running && pending.length > 0) || retryable;
    const changedCount = new Set([...changed, ...errors]).size;
    let message = errors.length ? errors.length + ' 项需要修正 · 输入已保留' : changed.length ? changed.length + ' 项未保存' : pending.length && running ? '更改已保存 · 待应用' : '';
    if (busy) message = runtime?.phase === 'connecting' ? '正在连接…' : runtime?.phase === 'reconnecting' ? '正在应用并重新连接…' : '正在应用更改…';
    else if (retryable && !errors.length) message = op.phase === 'error' ? op.message || '应用失败，请重试' : '连接尚未恢复 · 请重试连接';
    text(view.status.message, message);
    view.status.message.classList.toggle('eu-sr-only', busy);
    view.status.bar.classList.toggle('eu-config-has-error', !!errors.length || retryable);
    view.status.bar.classList.toggle('eu-config-has-changes', changedCount > 0 || pending.length > 0);
    const changedOrPending = [...new Set([...changed, ...pending])];
    const reconnect = changedOrPending.some(key => { const field = fieldByKey.get(key); return field && !['app-immediate', 'core-hot'].includes(field.apply); });
    text(view.status.primary, busy ? runtime?.phase === 'connecting' ? '正在连接…' : '正在应用…' : failedCurrent ? '重试连接' : running && op.phase === 'error' ? '重试应用' : running ? reconnect ? '应用并重连' : '应用更改' : '保存');
    view.status.primary.disabled = busy || !!errors.length || !actionable;
    view.status.primary.hidden = !actionable && !errors.length && !busy;
    view.status.save.hidden = !running || !changed.length;
    view.status.save.disabled = busy || !!errors.length;
    view.status.revert.hidden = !changedCount;
    view.status.revert.disabled = busy;
    view.status.locate.hidden = !errors.length;
    view.status.locate.disabled = busy;
    view.status.restore.hidden = !retryable || op.phase !== 'error' || !p.lastApplied;
    view.status.restore.disabled = busy;
    view.status.needsAttention = !!(changedCount || busy || (running && pending.length) || retryable);
    syncStatusHost();
  }

  function inputId(field) { return ctx.uid('config-' + field.key.replace(/[^a-z0-9_-]/gi, '-')); }
  function fieldHint(field) { return helpOverrides[field.key] || field.help || ''; }
  function appendOption(select, value, label, disabled) {
    const option = make('option', '', label);
    option.value = value;
    option.disabled = !!disabled;
    select.append(option);
  }
  function makeField(view, field, showTechnical) {
    const id = inputId(field), wrap = make('section', 'eu-config-field');
    wrap.dataset.configKey = field.key;
    const heading = make('div', 'eu-config-field-heading');
    const label = make('label', 'eu-label', field.label);
    label.htmlFor = id;
    const badge = make('span', 'eu-config-draft-mark');
    heading.append(label, badge);
    wrap.append(heading);
    const keyNode = make('code', 'eu-config-key', field.key);
    keyNode.hidden = !showTechnical; wrap.append(keyNode);
    const body = make('div', 'eu-config-input-area');
    const controls = [], syncers = [];
    const entry = { field, wrap, label, badge, controls, syncers, showTechnical, keyNode };
    view.rows.set(field.key, entry);
    const reason = make('p', 'eu-subtle eu-config-help');
    const error = make('p', 'eu-config-field-error');
    error.id = ctx.uid('config-error');
    error.setAttribute('role', 'alert');
    entry.error = error;
    entry.reason = reason;
    function register(control, controlId) {
      control.id = controlId || (controls.length ? ctx.uid('config-input') : id);
      control.dataset.configControl = field.key;
      control.setAttribute('aria-describedby', error.id);
      controls.push(control);
      return control;
    }
    function select(options) {
      const result = register(make('select', 'eu-select cursor-interaction'));
      options.forEach(([value, labelText, off]) => appendOption(result, value, labelText, off));
      return result;
    }
    if (field.type === 'boolean') {
      const row = make('div', 'eu-config-toggle-row');
      const stateLabel = make('label', 'eu-config-toggle-label cursor-interaction');
      stateLabel.htmlFor = id;
      const toggle = register(make('input', 'eu-config-toggle cursor-interaction'));
      toggle.type = 'checkbox';
      toggle.setAttribute('role', 'switch');
      const stateText = make('span');
      stateLabel.append(toggle, stateText);
      toggle.addEventListener('change', () => write(view.id, field, field.invert ? !toggle.checked : toggle.checked));
      row.append(stateLabel);
      body.append(row);
      syncers.push(() => { const current = val(view.id, field.key); toggle.checked = field.invert ? !current : !!current; text(stateText, toggle.checked ? '开启' : '关闭'); });
    } else if (field.type === 'select') {
      const control = select([['inherit', '使用默认 · ' + defaultLabel(field)], ...(field.options || []).map(value => [String(value), optionLabel(value), field.key === 'android.runtimeMode' && value === 'root-tun'])]);
      control.addEventListener('change', () => write(view.id, field, control.value, control.value === 'inherit'));
      body.append(control);
      syncers.push(() => { const p = profile(view.id); control.value = own(p.draft, field.key) ? String(p.draft[field.key]) : 'inherit'; });
    } else if (field.type === 'application-list') {
      const appSummary = make('p', 'eu-config-app-count');
      const search = register(make('input', 'eu-input'), id);
      search.type = 'search'; search.placeholder = '搜索应用名称或包名'; search.setAttribute('aria-label', '搜索应用');
      const list = make('div', 'eu-config-app-list');
      const rows = new Map();
      let appQuery = '';
      function updateApps() {
        const selected = new Set(val(view.id, field.key) || []);
        const available = new Map(sampleApps.map(app => [app[0], app]));
        selected.forEach(packageName => { if (!available.has(packageName)) available.set(packageName, [packageName, packageName, '不在演示应用列表中']); });
        available.forEach(([packageName, name, category]) => {
          if (!rows.has(packageName)) {
            const appRow = make('label', 'eu-config-app-row cursor-interaction');
            const input = make('input', 'eu-config-app-check cursor-interaction');
            input.type = 'checkbox'; input.id = ctx.uid('app'); input.value = packageName;
            appRow.htmlFor = input.id;
            input.setAttribute('aria-label', name);
            const copy = make('span', 'eu-config-app-copy');
            copy.append(make('span', '', name), make('span', 'eu-subtle', category + ' · ' + packageName));
            appRow.append(input, copy);
            input.addEventListener('change', () => {
              const packages = new Set(val(view.id, field.key) || []);
              if (input.checked) packages.add(packageName); else packages.delete(packageName);
              write(view.id, field, [...packages]);
            });
            list.append(appRow);
            rows.set(packageName, { appRow, input, haystack: (name + ' ' + packageName).toLowerCase() });
          }
          const row = rows.get(packageName);
          row.input.checked = selected.has(packageName);
          row.input.disabled = !!disabledReason(view.id, field);
          row.appRow.hidden = !!appQuery && !row.haystack.includes(appQuery);
        });
        rows.forEach((row, packageName) => {
          if (!available.has(packageName)) { row.appRow.hidden = true; row.input.checked = false; }
        });
        text(appSummary, '已选择 ' + selected.size + ' 个应用 · 演示应用列表');
      }
      search.addEventListener('input', () => { appQuery = search.value.trim().toLowerCase(); updateApps(); });
      body.append(appSummary, search, list);
      syncers.push(updateApps);
    } else if (['list', 'optional-list'].includes(field.type)) {
      const emptyLabel = field.key.includes('stun_servers') ? '关闭探测 · 空列表' : field.key === 'routes' ? '手动路由 · 空列表' : '明确设为空列表';
      const mode = select([['inherit', field.key === 'routes' ? '自动接收路由' : '使用默认 · ' + defaultLabel(field)], ['custom', structured(field) ? '自定义条目' : '自定义列表'], ['empty', emptyLabel]]);
      mode.dataset.configListMode = field.key;
      const input = register(make('textarea', 'eu-input eu-config-textarea'), ctx.uid('config-list'));
      const sensitive = structured(field) && containsSecret(field);
      let revealed = !sensitive;
      input.readOnly = !revealed;
      input.rows = structured(field) ? 6 : 3;
      input.spellcheck = false;
      input.setAttribute('aria-label', field.label + '内容');
      input.placeholder = structured(field) ? '[]' : field.key === 'peer' ? 'udp://node.example.net:11010' : '每行一个条目';
      if (structured(field)) body.append(make('p', 'eu-subtle eu-config-expert', 'JSON 数组 · 检查结构格式'));
      input.addEventListener('input', () => { if (!revealed) return; listModes.set(view.id + ':' + field.key, 'custom'); writeRaw(view.id, field, input.value); });
      mode.addEventListener('change', () => {
        const nextMode = mode.value;
        listModes.set(view.id + ':' + field.key, nextMode);
        if (nextMode === 'inherit') write(view.id, field, null, true);
        else if (nextMode === 'empty') write(view.id, field, []);
        else {
          const current = val(view.id, field.key);
          const initial = Array.isArray(current) && current.length ? current : [];
          write(view.id, field, initial, false, formatValue(field, initial));
          input.focus();
        }
      });
      body.append(mode, input);
      if (sensitive) {
        const reveal = ctx.button('显示并编辑敏感内容', () => {
          revealed = !revealed; input.readOnly = !revealed;
          text(reveal, revealed ? '隐藏敏感内容' : '显示并编辑敏感内容');
          reveal.setAttribute('aria-pressed', String(revealed));
          refreshNetwork(view.id);
          if (revealed) input.focus();
        }, 'eu-button eu-text-button');
        reveal.setAttribute('aria-pressed', 'false'); body.append(reveal);
        syncers.push(() => { reveal.hidden = modeFor(view.id, field) !== 'custom'; reveal.disabled = !!disabledReason(view.id, field); });
      }
      syncers.push(() => {
        mode.value = modeFor(view.id, field);
        const simple = view.mode === 'simple' && simpleKeys.has(field.key);
        mode.hidden = simple;
        input.hidden = !simple && mode.value !== 'custom';
        const next = revealed ? rawValue(view.id, field) : JSON.stringify(mask(val(view.id, field.key) || [], field.key), null, 2);
        if (input.value !== next) input.value = next;
      });
      if (field.key === 'peer') {
        const keys = make('details', 'eu-config-peer-keys');
        const summary = make('summary', 'cursor-interaction', '固定对端公钥');
        const keyList = make('div');
        const peerRows = new Map();
        keys.append(summary, keyList);
        body.append(keys);
        syncers.push(() => {
          const list = val(view.id, 'peer') || [];
          const active = new Set();
          list.forEach(item => {
            if (!item || typeof item !== 'object' || !item.uri) return;
            active.add(item.uri);
            if (!peerRows.has(item.uri)) {
              const row = make('div', 'eu-config-peer-key');
              const keyLabel = make('label', 'eu-subtle', mask(item.uri));
              const keyInput = make('input', 'eu-input');
              keyInput.type = 'text'; keyInput.id = ctx.uid('peer-key'); keyInput.autocomplete = 'off';
              keyLabel.htmlFor = keyInput.id; keyInput.placeholder = '不固定公钥';
              keyInput.addEventListener('input', () => {
                const next = ctx.clone(val(view.id, 'peer') || []).map(peer => peer.uri === item.uri ? { ...peer, peer_public_key: keyInput.value } : peer);
                write(view.id, field, next, false, rawValue(view.id, field));
              });
              row.append(keyLabel, keyInput); keyList.append(row);
              peerRows.set(item.uri, { row, keyInput });
            }
            const row = peerRows.get(item.uri);
            row.row.hidden = false;
            const next = item.peer_public_key || ''; if (row.keyInput.value !== next) row.keyInput.value = next;
          });
          peerRows.forEach((row, uri) => { row.row.hidden = !active.has(uri); });
          keys.hidden = !list.length || view.mode === 'simple';
        });
      }
    } else {
      const isObject = field.type === 'object';
      const input = register(make(isObject ? 'textarea' : 'input', 'eu-input' + (isObject ? ' eu-config-textarea' : '')));
      const sensitive = isObject && containsSecret(field);
      let revealed = !sensitive;
      if (sensitive) input.readOnly = true;
      if (isObject) {
        input.rows = 7; input.spellcheck = false; input.placeholder = '未设置';
        body.append(make('p', 'eu-subtle eu-config-expert', 'JSON 对象 · 检查结构格式'));
      } else {
        input.type = field.type === 'secret' ? 'password' : 'text';
        input.autocomplete = 'off';
        input.spellcheck = false;
        input.placeholder = '默认 · ' + defaultLabel(field);
        if (field.type === 'number') input.inputMode = 'numeric';
      }
      input.addEventListener('input', () => { if (revealed) writeRaw(view.id, field, input.value); });
      body.append(input);
      if (sensitive) {
        const reveal = ctx.button('显示并编辑敏感内容', () => {
          revealed = !revealed; input.readOnly = !revealed;
          text(reveal, revealed ? '隐藏敏感内容' : '显示并编辑敏感内容');
          reveal.setAttribute('aria-pressed', String(revealed)); refreshNetwork(view.id);
          if (revealed) input.focus();
        }, 'eu-button eu-text-button');
        reveal.setAttribute('aria-pressed', 'false'); body.append(reveal);
        syncers.push(() => { reveal.disabled = !!disabledReason(view.id, field); });
      }
      syncers.push(() => {
        const next = revealed ? rawValue(view.id, field) : val(view.id, field.key) ? JSON.stringify(mask(val(view.id, field.key), field.key), null, 2) : '';
        if (input.value !== next) input.value = next;
      });
      if (field.unlimitedSentinel) {
        const unlimited = ctx.button('不限速', () => {
          const p = profile(view.id);
          if (String(val(view.id, field.key)) === field.unlimitedSentinel && !own(p.raw, field.key)) {
            p.raw[field.key] = field.unlimitedSentinel;
            refreshNetwork(view.id);
            input.focus();
          } else write(view.id, field, field.unlimitedSentinel);
        }, 'eu-button eu-text-button');
        body.append(unlimited);
        syncers.push(() => {
          const active = String(val(view.id, field.key)) === field.unlimitedSentinel;
          unlimited.setAttribute('aria-pressed', String(active));
          input.hidden = active && !own(profile(view.id).raw, field.key);
          text(unlimited, active && !own(profile(view.id).raw, field.key) ? '不限速 · 设置上限' : '设为不限速');
        });
      }
      if (field.type === 'secret') {
        const reveal = ctx.button('显示密码', () => {
          input.type = input.type === 'password' ? 'text' : 'password';
          text(reveal, input.type === 'password' ? '显示密码' : '隐藏密码');
          reveal.setAttribute('aria-pressed', String(input.type === 'text'));
        }, 'eu-button eu-text-button');
        reveal.setAttribute('aria-pressed', 'false'); body.append(reveal);
        syncers.push(() => { reveal.disabled = !!disabledReason(view.id, field); });
      }
    }
    const resetButton = ctx.button('使用默认', () => changedDefault(view.id, field), 'eu-button eu-text-button eu-config-default');
    entry.resetButton = resetButton;
    body.append(resetButton);
    wrap.append(body, reason, error);
    updateField(view, entry);
    return wrap;
  }

  function updateField(view, entry) {
    const p = profile(view.id), field = entry.field;
    const reason = disabledReason(view.id, field);
    const changed = markChanged(view.id).includes(field.key);
    text(entry.badge, changed ? '未保存' : '');
    entry.badge.hidden = !changed;
    entry.wrap.classList.toggle('eu-config-field-disabled', !!reason);
    const dependent = dependency(view.id, field.key);
    entry.wrap.hidden = !!dependent && view.route.focusKey !== field.key;
    entry.controls.forEach(control => { control.disabled = !!reason; control.setAttribute('aria-invalid', String(!!p.errors[field.key])); });
    text(entry.reason, reason || fieldHint(field));
    entry.reason.hidden = !entry.reason.textContent;
    text(entry.error, p.errors[field.key] || '');
    entry.error.hidden = !p.errors[field.key];
    entry.resetButton.hidden = view.mode === 'simple' || !!reason || !own(p.draft, field.key) || (!changed && !entry.showTechnical);
    entry.resetButton.disabled = !!reason;
    entry.syncers.forEach(sync => sync());
  }
  function matches(field, query) {
    return [field.label, field.key, field.help, ...(field.assistantFields || []), ...(field.children || []).flatMap(child => [child.label, child.key])].filter(Boolean).join(' ').toLowerCase().includes(query);
  }
  function layoutGroup(view, field) {
    if (field.key === 'exit_nodes' && val(view.id, 'android.internetRouting') === 'exit-node') return 'android';
    return field.group;
  }
  function placeRows(view) {
    const signature = view.mode + ':' + (val(view.id, 'android.internetRouting') === 'exit-node');
    if (view.locationSignature === signature) return;
    view.locationSignature = signature;
    if (view.mode === 'simple') {
      simpleKeys.forEach(key => { const entry = view.rows.get(key); if (entry) view.simpleBody.append(entry.wrap); });
    } else {
      groups.forEach(group => {
        let ordered = fields.filter(field => layoutGroup(view, field) === group.id);
        if (group.id === 'android') {
          const exit = ordered.find(field => field.key === 'exit_nodes');
          if (exit) {
            ordered = ordered.filter(field => field !== exit);
            const position = ordered.findIndex(field => field.key === 'android.internetRouting');
            ordered.splice(position + 1, 0, exit);
          }
        }
        ordered.forEach(field => view.groupNodes.get(group.id).body.append(view.rows.get(field.key).wrap));
      });
    }
  }
  function setMode(view, mode) {
    view.mode = mode;
    view.modeButtons.forEach((button, value) => button.setAttribute('aria-selected', String(value === mode)));
    view.simplePanel.hidden = mode !== 'simple';
    view.allPanel.hidden = mode !== 'all';
    view.container.dataset.configMode = mode;
    placeRows(view);
    view.rows.forEach(entry => updateField(view, entry));
    updateFormLayout(view);
  }
  function focusField(view, key) {
    const entry = view.rows.get(key);
    if (!entry) return;
    view.query = '';
    view.onlyChanged = false;
    view.focusKey = key;
    view.search.value = '';
    setMode(view, 'all');
    const group = view.groupNodes.get(layoutGroup(view, entry.field));
    if (group) { group.node.open = true; view.openGroups.add(group.id); }
    entry.wrap.hidden = false;
    entry.wrap.scrollIntoView({ block: 'center', behavior: 'auto' });
    const control = entry.controls.find(item => !item.disabled && !item.hidden);
    if (control) control.focus({ preventScroll: true });
  }
  function updateFormLayout(view) {
    if (!view.modeButtons) return;
    placeRows(view);
    const query = view.query.trim().toLowerCase();
    const dirty = new Set(markChanged(view.id));
    const direct = new Set(fields.filter(field => query ? matches(field, query) : !view.onlyChanged || dirty.has(field.key)).map(field => field.key));
    const visible = new Set(direct);
    if (query || view.onlyChanged) {
      relationPairs.forEach(([parent, child]) => {
        if (visible.has(child) && fieldByKey.has(parent)) visible.add(parent);
        if (visible.has(parent) && fieldByKey.has(child) && !dependency(view.id, child)) visible.add(child);
      });
      if (visible.has('android.internetRouting') && val(view.id, 'android.internetRouting') === 'exit-node') {
        ['exit_nodes', 'android.ipv6Policy', 'android.dnsMode', 'android.dnsServers'].forEach(key => { if (fieldByKey.has(key) && !dependency(view.id, key)) visible.add(key); });
      }
    }
    let shown = 0;
    view.rows.forEach(entry => {
      const field = entry.field;
      const dependencyHidden = !!dependency(view.id, field.key) && !(query && direct.has(field.key)) && view.focusKey !== field.key;
      const display = view.mode === 'simple' ? simpleKeys.has(field.key) : visible.has(field.key) && !dependencyHidden;
      entry.wrap.hidden = !display;
      entry.keyNode.hidden = !(view.mode === 'all' && query && (field.key.toLowerCase().includes(query) || (field.assistantFields || []).some(key => key.includes(query))));
      if (display) shown += 1;
    });
    view.groupNodes.forEach(group => {
      const count = fields.filter(field => layoutGroup(view, field) === group.id && !view.rows.get(field.key).wrap.hidden).length;
      group.node.hidden = count === 0;
      text(group.count, count + ' 项');
      if (query || view.onlyChanged) group.node.open = count > 0;
      else group.node.open = view.openGroups.has(group.id);
    });
    view.searchCount.hidden = !query && !view.onlyChanged;
    text(view.searchCount, query ? '找到 ' + direct.size + ' 项设置' : dirty.size + ' 项未保存的修改');
    view.clearChanged.hidden = !view.onlyChanged;
    view.empty.hidden = shown !== 0 || view.mode !== 'all';
    const p = profile(view.id);
    const address = val(view.id, 'dhcp') ? '自动分配 IPv4（DHCP）' : p.errors.ipv4 ? '固定 IPv4 · 需要修正' : '固定 IPv4 · ' + describe(view.id, 'ipv4', 'draft');
    text(view.addressLabel, address);
    view.addressRow.dataset.addressMode = val(view.id, 'dhcp') ? 'automatic' : 'static';
  }
  function mountConfigPage(view) {
    view.mode = view.route.kind === 'config' && !view.route.focusKey ? 'simple' : 'all';
    view.query = view.route.query || '';
    view.onlyChanged = view.route.filter === 'changed';
    view.focusKey = view.route.focusKey || null;
    view.openGroups = new Set(['identity']);
    view.groupNodes = new Map();
    view.modeButtons = new Map();
    const heading = make('div', 'eu-config-heading');
    const rename = ctx.button('', () => ctx.openRename(view.id, rename), 'eu-text-button');
    rename.dataset.renameConfig = '';
    rename.append(icon('pencil'), make('span', '', '重命名'));
    heading.append(make('h2', 'eu-page-title', '配置'), rename);
    view.container.append(heading);
    const tabs = make('div', 'eu-config-tabs');
    tabs.setAttribute('role', 'tablist');
    tabs.setAttribute('aria-label', '配置范围');
    view.simplePanel = make('div', 'eu-config-simple-panel');
    view.allPanel = make('div', 'eu-config-all-panel');
    [['simple', '基础配置', view.simplePanel], ['all', '完整配置', view.allPanel]].forEach(([value, label, panel]) => {
      panel.id = ctx.uid('config-panel');
      panel.setAttribute('role', 'tabpanel');
      const button = ctx.button(label, () => setMode(view, value), 'eu-config-tab');
      button.setAttribute('role', 'tab');
      button.setAttribute('aria-controls', panel.id);
      button.dataset.configTab = value;
      panel.setAttribute('aria-labelledby', button.id);
      button.addEventListener('keydown', event => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
        event.preventDefault();
        const next = event.key === 'Home' ? 'simple' : event.key === 'End' ? 'all' : value === 'simple' ? 'all' : 'simple';
        setMode(view, next); view.modeButtons.get(next).focus();
      });
      tabs.append(button); view.modeButtons.set(value, button);
    });
    view.container.append(tabs, view.simplePanel, view.allPanel);
    mountStatus(view);
    view.simpleBody = make('div', 'eu-config-simple-body');
    view.addressRow = ctx.button('', () => focusField(view, 'dhcp'), 'eu-config-address-summary');
    view.addressLabel = make('span');
    view.addressRow.append(icon('network'), view.addressLabel, icon('chevron-right'));
    view.simplePanel.append(view.simpleBody, view.addressRow);
    const searchLabel = make('label', 'eu-label', '搜索全部设置');
    view.search = make('input', 'eu-input');
    view.search.type = 'search'; view.search.id = ctx.uid('config-search');
    view.search.dataset.configSearch = 'true'; view.search.placeholder = '名称、用途或参数键，如 MTU';
    view.search.value = view.query; searchLabel.htmlFor = view.search.id;
    view.search.addEventListener('input', () => { view.query = view.search.value; view.focusKey = null; updateFormLayout(view); });
    const searchArea = make('div', 'eu-config-all-search'); searchArea.append(searchLabel, view.search);
    view.searchCount = make('p', 'eu-subtle'); view.searchCount.setAttribute('role', 'status');
    view.clearChanged = ctx.button('显示全部设置', () => { view.onlyChanged = false; updateFormLayout(view); }, 'eu-button eu-text-button');
    view.allPanel.append(searchArea, view.searchCount, view.clearChanged);
    groups.forEach(group => {
      const node = make('details', 'eu-config-inline-group');
      node.dataset.configGroup = group.id;
      const summary = make('summary', 'cursor-interaction');
      const name = make('span', 'eu-config-group-label', group.label);
      const count = make('span', 'eu-subtle');
      summary.append(name, count, icon('chevron-down'));
      const body = make('div', 'eu-config-form');
      fields.filter(field => field.group === group.id).forEach(field => body.append(makeField(view, field, false)));
      node.append(summary, body);
      node.open = view.openGroups.has(group.id);
      node.addEventListener('toggle', () => {
        if (view.query || view.onlyChanged) return;
        if (node.open) view.openGroups.add(group.id); else view.openGroups.delete(group.id);
      });
      const groupView = { id: group.id, node, body, count };
      view.groupNodes.set(group.id, groupView); view.allPanel.append(node);
    });
    view.empty = make('p', 'eu-empty', '没有匹配的设置，试试名称或用途。');
    view.allPanel.append(view.empty);
    const preview = make('details', 'eu-config-more');
    preview.append(make('summary', 'cursor-interaction', '查看脱敏配置'));
    const note = make('p', 'eu-subtle', '只读预览；凭据已隐藏，Android 设置单独保存。');
    const previewInput = make('textarea', 'eu-input eu-config-preview');
    previewInput.rows = 10; previewInput.readOnly = true; previewInput.spellcheck = false;
    previewInput.setAttribute('aria-label', '脱敏 TOML 预览');
    const copy = ctx.button('复制脱敏内容', async () => {
      try { await navigator.clipboard.writeText(previewInput.value); (ctx.toast || ctx.notify)('已复制脱敏配置'); }
      catch (_) { previewInput.focus(); previewInput.select(); (ctx.toast || ctx.notify)('请复制已选中的内容'); }
    }, 'eu-button eu-text-button');
    preview.append(note, previewInput, copy); view.preview = previewInput; view.allPanel.append(preview);
    if (view.focusKey && fieldByKey.has(view.focusKey)) view.openGroups.add(layoutGroup(view, fieldByKey.get(view.focusKey)));
    else if (view.route.groupId && view.groupNodes.has(view.route.groupId)) view.openGroups.add(view.route.groupId);
    setMode(view, view.mode);
    if (view.focusKey && fieldByKey.has(view.focusKey)) setTimeout(() => {
      if (ctx.isRouteVisible(view.route.key)) focusField(view, view.focusKey);
    }, 0);
  }
  function mask(value, key = '') {
    if (/secret|private_key|password|token|configServerUrl/i.test(key)) return '<已隐藏>';
    if (typeof value === 'string' && /^[a-z][a-z0-9+.-]*:\/\//i.test(value)) {
      try {
        const url = new URL(value);
        if (url.username || url.password || url.search || url.hash || (url.pathname && url.pathname !== '/')) return '<含凭据或路径的地址已隐藏>';
      } catch (_) { return '<地址已隐藏>'; }
    }
    if (Array.isArray(value)) return value.map(item => mask(item));
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([childKey, child]) => [childKey, mask(child, childKey)]));
    return value;
  }
  function tomlValue(value) {
    if (Array.isArray(value)) return '[' + value.map(tomlValue).join(', ') + ']';
    if (value && typeof value === 'object') return '{ ' + Object.entries(value).filter(([, child]) => child !== null && child !== undefined).map(([key, child]) => JSON.stringify(key) + ' = ' + tomlValue(child)).join(', ') + ' }';
    return JSON.stringify(value);
  }
  function tomlPreview(id) {
    const p = profile(id), lines = ['# 只读脱敏预览'];
    if (Object.keys(p.errors).length) lines.push('# 尚有待修正输入，以下保留最近有效值');
    fields.filter(field => field.scope === 'core' && own(p.draft, field.key)).forEach(field => {
      const value = mask(p.draft[field.key], field.key);
      if (value !== undefined && value !== null) lines.push(field.key + ' = ' + (field.storageType === 'decimal-string' ? String(value) : tomlValue(value)));
    });
    const appFields = fields.filter(field => field.scope !== 'core' && own(p.draft, field.key));
    if (appFields.length) {
      lines.push('', '# Android 与宿主参数单独保存');
      appFields.forEach(field => lines.push('# ' + field.key + ' = ' + tomlValue(mask(p.draft[field.key], field.key))));
    }
    return lines.join('\n');
  }
  function refreshView(view) {
    updateStatus(view);
    view.summaries.forEach(update => update());
    if (view.rows) view.rows.forEach(entry => updateField(view, entry));
    if (view.modeButtons) updateFormLayout(view);
    if (view.preview) {
      const next = tomlPreview(view.id);
      if (view.preview.value !== next) view.preview.value = next;
    }
  }
  function refreshNetwork(id) {
    views.forEach(view => { if (view.id === id) refreshView(view); });
  }
  function mount(route, container) {
    const view = { route, id: route.networkId, container, rows: new Map(), summaries: [] };
    const key = route.key || ctx.uid('config-route');
    views.set(key, view); container.dataset.configView = key;
    mountConfigPage(view);
    refreshView(view);
    ctx.onCleanup(key, () => {
      view.status?.bar.remove();
      views.delete(key);
      syncStatusHost();
    });
  }
  function refresh(route, container) {
    const view = views.get(route.key) || views.get(container.dataset.configView);
    if (view) refreshView(view);
  }
  function reset(id) {
    const p = profile(id); p.raw = {}; p.errors = {};
    [...listModes.keys()].filter(key => key.startsWith(id + ':')).forEach(key => listModes.delete(key));
    peerMemory.delete(id);
    refreshNetwork(id);
  }
  return { mount, refresh, validate, describe, reset };
}
