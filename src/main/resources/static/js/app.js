/* depviz · 应用装配与交互状态 */
(function (global) {
  'use strict';

  const V = global.Views;
  const E = global.Engine;
  const $ = V.$;
  const esc = V.esc;
  const num = V.num;
  /** 路径的最后一段（即项目目录名）。空路径与根目录返回空串。Windows 反斜杠也要认。 */
  function baseName(path) {
    if (!path) return '';
    const parts = String(path).split(/[\\/]+/).filter(Boolean);
    return parts.length ? parts[parts.length - 1] : '';
  }

  const state = {
    view: 'select',
    stage: 'pick',
    project: null,
    projectKey: null,      // 当前项目的稳定键（= 根目录绝对路径），由 enterExplore 写入
    store: null,
    search: '',
    listView: 'tree',
    showGroupId: true,
    showScope: true,
    hideTest: true,
    hideProvided: true,
    selected: [],          // 选中的 GA id
    effectiveOnly: true,   // 默认只看生效
    moduleIds: [],         // 选中的模块，空 = 全部
    throughGaId: null,     // 只看经过某节点的路径
    focusedGa: null,
    focusedPath: null,
    expandedModules: false,
    pane: 'paths',
    replacedOnly: false
  };

  let lastSearch = null;
  let suggestSuppressed = false;
  let pointerOnResults = false;   // 按下鼠标时是否落在结果栏，用于延迟的 blur 收起判断
  let lastPaths = null;
  /** 一个作用域只允许当前操作更新界面；开始新操作或退出时统一作废旧回调。 */
  function asyncScope() {
    let version = 0;
    function capture() {
      const stamp = version;
      return {
        guard: function (callback) {
          return function () {
            if (stamp === version) return callback.apply(this, arguments);
          };
        }
      };
    }
    return {
      begin: function () { version++; return capture(); },
      capture: capture,
      invalidate: function () { version++; }
    };
  }

  const navigation = asyncScope();
  const settingsSession = asyncScope();

  /* ================= 主题 ================= */
  const THEME_KEY = 'depviz.theme';

  function applyTheme(theme, announce) {
    document.documentElement.setAttribute('data-theme', theme);
    const icon = theme === 'dark' ? '#i-sun' : '#i-moon';
    const title = theme === 'dark' ? '切换到浅色模式' : '切换到深色模式';
    ['theme-icon', 'theme-icon-home'].forEach(function (id) {
      const el = $(id);
      if (el) el.setAttribute('href', icon);
    });
    ['btn-theme', 'btn-theme-home'].forEach(function (id) {
      const el = $(id);
      if (el) el.setAttribute('title', title);
    });
    try { localStorage.setItem(THEME_KEY, theme); } catch (e) { /* 隐私模式忽略 */ }
    if (global.Graph.cy) global.Graph.restyle();
    if (announce) V.toast(theme === 'dark' ? '深色模式' : '浅色模式');
  }

  function initTheme() {
    let theme = null;
    try { theme = localStorage.getItem(THEME_KEY); } catch (e) { /* 忽略 */ }
    if (!theme) {
      theme = global.matchMedia && global.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
    }
    applyTheme(theme, false);
    function toggleTheme() {
      const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
      applyTheme(next, true);
    }
    $('btn-theme').addEventListener('click', toggleTheme);
    $('btn-theme-home').addEventListener('click', toggleTheme);
    $('btn-settings-home').addEventListener('click', function () { openSettings(); });
  }

  /* ================= 面板尺寸 ================= */
  const WIDTH_KEY = 'depviz.widths';
  const DEFAULT_W = { left: 460, right: 320 };
  const MIN_W = { left: 220, right: 240 };
  const MAX_W = { left: 560, right: 560 };

  function applyWidths(w) {
    document.documentElement.style.setProperty('--w-left', w.left + 'px');
    document.documentElement.style.setProperty('--w-right', w.right + 'px');
    global.Graph.resize();
  }

  function initResizers() {
    let widths = Object.assign({}, DEFAULT_W);
    try {
      const saved = JSON.parse(localStorage.getItem(WIDTH_KEY) || 'null');
      if (saved && saved.left && saved.right) widths = saved;
    } catch (e) { /* 忽略 */ }
    applyWidths(widths);

    [['rs-left', 'left', 1], ['rs-right', 'right', -1]].forEach(function (cfg) {
      const el = $(cfg[0]);
      const side = cfg[1];
      const dir = cfg[2];
      let startX = 0;
      let startW = 0;

      function onMove(e) {
        const dx = (e.clientX - startX) * dir;
        widths[side] = Math.max(MIN_W[side], Math.min(MAX_W[side], startW + dx));
        applyWidths(widths);
        startX = e.clientX;
        startW = widths[side];
        e.preventDefault();
      }

      function onUp() {
        document.removeEventListener('mousemove', onMove);
        document.removeEventListener('mouseup', onUp);
        document.body.classList.remove('is-resizing');
        try { localStorage.setItem(WIDTH_KEY, JSON.stringify(widths)); } catch (e) { /* 忽略 */ }
      }

      el.addEventListener('mousedown', function (e) {
        startX = e.clientX;
        startW = widths[side];
        document.body.classList.add('is-resizing');
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
        e.preventDefault();
      });

      el.addEventListener('dblclick', function () {
        widths[side] = DEFAULT_W[side];
        applyWidths(widths);
        try { localStorage.setItem(WIDTH_KEY, JSON.stringify(widths)); } catch (e) { /* 忽略 */ }
      });
    });
  }

  function setPanel(side, hidden) {
    const center = global.Graph.centerPoint();
    const ws = $('pane-paths');
    ws.classList.toggle(side === 'left' ? 'hide-left' : 'hide-right', hidden);
    $('btn-expand-' + side).hidden = !hidden;
    [30, 260].forEach(function (d) {
      setTimeout(function () { global.Graph.restoreCenter(center); }, d);
    });
  }

  function setFullscreen(on) {
    const center = global.Graph.centerPoint();
    const view = $('view-explore');
    view.classList.toggle('graph-full', on);
    view.classList.toggle('meta-collapsed', on);
    $('fs-icon').setAttribute('href', on ? '#i-contract' : '#i-expand');
    $('fs-label').textContent = on ? '退出全屏' : '全屏';
    [30, 260].forEach(function (d) {
      setTimeout(function () { global.Graph.restoreCenter(center); }, d);
    });
  }

  /* ================= 最近分析的项目 ================= */
  const RECENT_KEY = 'depviz.recent';
  let RECENT = loadRecent();

  function loadRecent() {
    try {
      const raw = localStorage.getItem(RECENT_KEY);
      if (raw) return JSON.parse(raw);
    } catch (e) { /* 忽略 */ }
    return [];
  }

  function saveRecent() {
    try { localStorage.setItem(RECENT_KEY, JSON.stringify(RECENT)); } catch (e) { /* 忽略 */ }
  }

  /** 从服务端拉最近列表；失败时保留 localStorage 里的旧值。 */
  function syncRecent() {
    return fetch('/api/projects').then(function (r) { return r.json(); }).then(function (list) {
      if (!Array.isArray(list)) return;
      RECENT = list.map(function (it) {
        return { key: it.key, name: it.name, moduleCount: it.moduleCount,
                 when: it.when, rootDir: it.rootDir };
      });
      saveRecent();
      renderProjectMenu();
      renderRecentList();
    }).catch(function () { /* 离线兜底：用 localStorage */ });
  }

  /** 首页与顶栏菜单共用同一份最近列表。 */
  function renderRecentList() {
    V.renderRecent(RECENT, function (it) { openProject(it.key); }, removeRecent);
  }

  function removeRecent(key) {
    RECENT = RECENT.filter(function (r) { return r.key !== key; });
    saveRecent();
    renderProjectMenu();
    renderRecentList();
    fetch('/api/projects?id=' + encodeURIComponent(key), { method: 'DELETE' })
      .catch(function () { /* 服务端删不掉也不影响本地视图 */ });
  }

  /** 打开某个项目后把它提到最近列表顶部。 */
  function touchRecent(key) {
    const p = global.Scan.PROJECTS[key];
    if (!p) return;
    RECENT = RECENT.filter(function (r) { return r.key !== key; });
    RECENT.unshift({
      key: key, name: p.name, moduleCount: p.moduleCount, when: '刚刚', rootDir: p.rootDir
    });
    saveRecent();
  }

  function showStage(stage) {
    state.stage = stage;
    ['pick', 'confirm', 'scan'].forEach(function (s) { $('stage-' + s).hidden = s !== stage; });
  }

  function showView(view) {
    navigation.invalidate();
    state.view = view;
    $('view-select').hidden = view !== 'select';
    $('view-explore').hidden = view !== 'explore';
    if (view === 'select') {
      showStage('pick');
      // 回到首页时重拉一次最近列表。只在 boot 时拉一次的话，用户从结果页退回首页
      // 看到的还是启动那一刻的列表 —— 刚分析完的项目要刷新整页才出现。
      syncRecent();
    }
    if (view !== 'explore') closeProjectMenu();
  }

  /**
   * 打开一个**已知**项目。
   *
   * <p>已经分析过的直接进图 —— 图在服务端（{@code data/<项目名>/graph.json}）就有，
   * 联网扫一次 multi-module 项目要几十秒，每次从首页点进来都重扫一遍完全没必要。
   * 要重扫有顶栏的「重新扫描」，那才是明确的动作。
   *
   * <p>{@code scanned} 是服务端给的（`/api/projects`），不是本地猜的：跨标签页、
   * 上一次会话、curl 扫出来的项目在本地都没有痕迹。
   *
   * <p>没分析过（或图丢了）才落确认页：那里有模块清单与「开始分析」。
   */
  function openProject(key, owner, params) {
    owner = owner || navigation.begin();
    const p = global.Scan.PROJECTS[key];
    if (!p) { V.toast('没有找到 Maven 项目：' + key); return; }
    if (p.scanned) {
      global.Scan.loadProject(key).then(owner.guard(function (doc) {
        enterExplore(key, doc);
        if (params) applyLinkSearch(params);
      })).catch(owner.guard(function () {
        showConfirm(key, owner);
      }));
      return;
    }
    showConfirm(key, owner);
  }

  /** 路径输入、目录选择与深链共用一次发现和同一份导航所有权。 */
  function openPathAndGo(dir, params) {
    const owner = navigation.begin();
    global.Scan.openPath(dir, owner.guard(function (key) {
      if (params && key !== dir) {
        const url = new URL(location.href);
        url.searchParams.set('project', key);
        history.replaceState(null, '', url);
      }
      openProject(key, owner, params);
    }), owner.guard(function (err) {
      V.toast(err && err.message ? err.message : '这个目录里没有找到 Maven 项目');
    }));
  }

  /** 确认页：模块清单 + 「开始分析」。没分析过的项目走这里。 */
  function showConfirm(key, owner) {
    const p = global.Scan.PROJECTS[key];
    if (!p) return;
    state.pendingKey = null;
    state.pendingDoc = null;
    $('btn-scan').disabled = true;
    $('confirm-title').textContent = '已定位到 ' + p.name;
    $('confirm-sub').textContent = p.moduleCount + ' 个模块 · ' +
      (p.declaredDependencyCount == null ? '?' : p.declaredDependencyCount) + ' 条声明依赖';
    // 先按已知信息填一版，discover 回来再覆盖 —— 慢的时候不至于空着
    const reloc = $('confirm-relocated');
    reloc.hidden = !p.relocated;
    if (p.relocated && p.selectedDir) {
      reloc.textContent = '你选的是 ' + baseName(p.selectedDir) + '，已向上找到项目根 ' + p.name + '。';
    }

    global.Scan.discover(key).then(owner.guard(function (d) {
      $('confirm-title').textContent = '已定位到 ' + (d.relocated ? baseName(d.rootDir) : p.name);
      $('confirm-sub').textContent = d.moduleCount + ' 个模块 · ' + d.declaredDependencyCount +
        ' 条声明依赖 · ' + d.discoverMillis + ' ms';
      // 真实文案用服务端给的 selectedDir —— 早先这里写死「你选的是 agentscope-core」，
      // 任何别的项目都被叫成 agentscope-core。
      reloc.hidden = !d.relocated;
      if (d.relocated) {
        reloc.textContent = '你选的是 ' + baseName(d.selectedDir) + '，已向上找到项目根 ' +
          baseName(d.rootDir) + '。';
      }
      V.renderModules(d, state.expandedModules);
      state.pendingKey = key;
      $('btn-scan').disabled = false;
      showStage('confirm');
    })).catch(owner.guard(function (err) {
      V.toast(err.message);
    }));
  }

  /* ================= 扫描 ================= */
  /**
   * 发起一次扫描。
   *
   * @param {{offline?: boolean}} opts 传 {@code {offline:false}} 就是「联网重试」
   */
  function startScan(opts) {
    scanProject(state.pendingKey, opts, false);
  }

  /** 确认页、重扫和联网重试共用目标、页面复位和回调所有权。 */
  function scanProject(key, opts, openResult) {
    if (!key) return;
    showView('select');
    const owner = navigation.begin();
    state.pendingKey = key;
    state.pendingDoc = null;
    showStage('scan');
    resetScanStage();
    if (global.resetScanLog) global.resetScanLog();
    state.scanJob = global.Scan.runScan(key, {
      done: owner.guard(function (doc) {
        if (openResult) enterExplore(key, doc);
        else showScanDone(key, doc);
      }),
      error: owner.guard(function (err) { handleScanError(key, err); }),
      cancelled: owner.guard(function () { showConfirm(key, owner); })
    }, opts);
  }

  /**
   * 扫描失败：留在扫描页把原因摊开，而不是 toast 一句就退回选目录页。
   *
   * <p>为什么不再直接退回：用户刚等了几十秒到几分钟，一句「扫描失败」既没说
   * 哪一步失败、也没说为什么 —— 而 Maven 的真实报错就在 {@code err.detail} 里
   * （离线取不到 BOM、连不上私服、依赖坐标写错……）。把它显示出来，用户至少
   * 知道该不该点「联网重试」。
   */
  function handleScanError(key, err) {
    resetScanStageAfterFailure(err);
    // 409（另一个扫描在跑）语义不同：目录是对的，只是排队失败，不算「分析失败」
    if (err && err.code === 'SCAN_IN_PROGRESS') {
      V.toast(err.message || '已有扫描在进行');
      return;
    }
    showScanFailure(err);
  }

  /** 把失败面板填上并显示。 */
  function showScanFailure(err) {
    const msg = (err && err.message) || '扫描失败';
    const detail = (err && err.detail) || '';
    const logFile = (err && err.logFile) || '';
    $('scan-fail').hidden = false;
    $('scan-actions-running').hidden = true;
    $('scan-fail-title').textContent = err && err.code === 'SCAN_FAILED' ? '分析失败' : '出错了';
    $('scan-fail-msg').textContent = msg;
    // 完整日志（含保留下来的 tree.txt）就在这个目录下。没落盘成功时整行隐藏，
    // 而不是显示一个空路径让用户去猜。
    $('scan-fail-path').hidden = !logFile;
    $('scan-fail-path-val').textContent = logFile;
    const more = $('scan-fail-more');
    more.hidden = !detail;
    $('scan-fail-detail').textContent = detail;
    if (detail) more.open = false;
    // 只有「像是仓库里没有东西」时才提议联网：其余失败联网也解决不了。
    // 不匹配 `is missing` —— Maven 的插件版本警告也是这句，会误开按钮。
    const offlineish = /offline|离线|no dependency information available|Non-resolvable|could not be resolved|Cannot access/i.test(msg);
    $('btn-scan-online').hidden = !offlineish;
  }

  /**
   * 扫描完成：停在扫描页展示结果摘要，由用户点「查看结果」再进主页。
   * 直接跳走会打断用户查看 mvn 日志或确认耗时。
   */
  function showScanDone(key, doc) {
    state.pendingDoc = doc;
    state.pendingKey = key;
    const p = doc.project;
    $('scan-done-meta').textContent = p.name + ' · ' + p.moduleCount + ' 个模块 · ' +
      doc.occ.length + ' 条依赖记录 · ' + p.seconds.toFixed(1) + 's';
    $('scan-actions-running').hidden = true;
    $('scan-done').hidden = false;
    $('scan-title').textContent = '分析完成';
    $('scan-sub').textContent = 'mvn dependency:tree -Dverbose · ' + (p.offline ? '离线' : '在线');
  }

  /**
   * 失败后把扫描页恢复成「可以再来一次」的样子。
   *
   * <p>不收拾的话有两种坏状态：普通失败时进度条停在 100%（用户看到「100% + 失败」
   * 自相矛盾）；409 时停在 0% 且只有「取消」按钮，点了没反应，只能刷新页面。
   *
   * <p>409 时留在扫描页（目录是对的，再点一次就能重试），所以还要把开始按钮放回来。
   */
  function resetScanStageAfterFailure(err) {
    const busy = err && err.code === 'SCAN_IN_PROGRESS';
    // 进度条是用 transform: scaleX() 推进的（scan.js 的 progressUi），
    // 这里必须改同一个属性。早先写的是 style.width，而 .progress-fill 的
    // width 恒为 100%，于是「复位」什么也没发生 —— 失败后进度条停在上一次的比例。
    $('progress-fill').style.transform = 'scaleX(0)';
    $('progress').setAttribute('aria-valuenow', '0');
    $('scan-pct').textContent = '0%';
    $('scan-title').textContent = busy ? '已有扫描在进行' : '分析失败';
    $('scan-done').hidden = true;
    // 重试按钮在 #scan-actions-running 里面，所以那个容器不能整个藏起来 ——
    // 藏了按钮永远不可见。改成「容器留着，里面的取消按钮按需显示」。
    $('scan-actions-running').hidden = false;
    $('btn-scan-cancel').hidden = true;
    const retry = $('btn-scan-retry');
    if (retry) retry.hidden = !busy;
  }

  function resetScanStage() {
    const done = $('scan-done');
    if (!done) return;
    done.hidden = true;
    $('scan-actions-running').hidden = false;
    $('btn-scan-cancel').hidden = false;
    $('btn-scan-retry').hidden = true;
    $('scan-log').hidden = false;
    $('scan-title').textContent = '正在分析';
    $('scan-fail').hidden = true;
    $('scan-fail-path').hidden = true;
    $('btn-scan-online').hidden = true;
    $('scan-elapsed').textContent = '已用 0.0s';
  }

  /* ================= 状态 B ================= */
  function enterExplore(key, doc) {
    state.project = doc.project;
    state.projectKey = key;
    touchRecent(key);
    state.store = E.build(doc);
    assertInvariants(state.store);
    conflictCache = null;
    globalConflictCache = null;
    state.selected = [];
    state.search = '';
    state.moduleIds = [];
    state.throughGaId = null;
    state.effectiveOnly = true;
    // 必须与下面那条 `$('chk-rewritten').checked = false` 成对：过滤读的是 state，
    // 复选框只是显示。早先只重置了 DOM，换项目后「只看被取代」还生效着，
    // 界面却显示未勾选 —— 实测 agentscope 的 144 行冲突被静默滤成 22 行。
    state.replacedOnly = false;
    state.pane = 'paths';
    showView('explore');

    $('project-name').textContent = doc.project.name;
    $('search-input').value = '';
    $('btn-search-clear').hidden = true;
    $('chk-effective').checked = true;
    $('chk-rewritten').checked = false;

    V.renderMeta(doc.project, hasWarning(doc.project));
    syncRetryButton(doc.project);
    V.renderMetaDialog(doc.project);

    global.ModulePicker.bind(doc.modules, function (ids) {
      state.moduleIds = ids;
      $('module-btn-label').textContent = global.ModulePicker.label();
      onScopeChange();
    });
    $('module-btn-label').textContent = '全部模块';

    $('results-empty').hidden = false;
    $('results-title').textContent = '全部依赖';
    renderGraphPlaceholder();
    setPane('paths');
    setDrawer(false, true);
    $('graph-scope').textContent = '未选择依赖';
    $('path-list').innerHTML = '';
    $('drawer-sub').textContent = '';
    clearFilterChip();
    V.clearDetail();
    doSearch(true);
    $('search-input').focus();
  }

  /**
   * 「联网重试」按钮只在**扫描缺依赖**时出现。
   *
   * 触发条件是 warnings 里有「未能解析」（后端 `MvnRunner` 统计的），不是 Maven 退出码 ——
   * 缺依赖时退出码照样是 0。不要用 `is missing` 匹配：Maven 对**插件版本没写**这类模型警告
   * 用的是同一句措辞，dynamic-tp 一个项目就能凑出 83 行，按钮会无故出现且点了也没用。
   */
  function syncRetryButton(p) {
    const btn = $('btn-retry-online');
    if (!btn) return;
    const missing = (p.warnings || []).some(function (w) {
      return w.indexOf('未能解析') >= 0;
    });
    btn.hidden = !missing;
  }

  function hasWarning(p) {
    return p.incomplete || p.graphMode === 'DAG' || p.parseCoverage < 1;
  }

  function renderGraphPlaceholder() {
    $('graph-empty').hidden = false;
    if (global.Graph.cy) global.Graph.cy.elements().remove();
  }

  /* ---------- 项目下拉菜单 ---------- */

  /**
   * 当前项目的稳定键。
   *
   * 不能按 `name` 反查 `Scan.PROJECTS`：两个同名目录（`~/a/demo` 与 `~/b/demo`）
   * 会命中错的；`syncRecent()` 覆盖后字段缺失也会返回 null，
   * 而 null 会让 POM 弹窗静默失效。`enterExplore` 拿到的 key 本来就是权威值。
   */
  function currentProjectKey() {
    return state.projectKey;
  }

  function toggleProjectMenu() {
    if ($('project-menu').hidden) openProjectMenu(); else closeProjectMenu();
  }

  function openProjectMenu() {
    V.hideSuggest();
    renderProjectMenu();
    $('project-menu').hidden = false;
    $('btn-project').setAttribute('aria-expanded', 'true');
  }

  function closeProjectMenu() {
    $('project-menu').hidden = true;
    $('btn-project').setAttribute('aria-expanded', 'false');
  }

  function renderProjectMenu() {
    const cur = currentProjectKey();
    $('pm-list').innerHTML = RECENT.length
      ? RECENT.map(function (r) {
          return '<li class="pm-item' + (r.key === cur ? ' is-current' : '') + '">' +
            '<button class="pm-pick" data-key="' + esc(r.key) + '" role="menuitem">' +
              '<span class="pm-name">' + esc(r.name) + '</span>' +
              '<span class="pm-meta">' + r.moduleCount + ' 模块 · ' + esc(r.when) + '</span>' +
            '</button>' +
            '<button class="icon-btn sm pm-del" data-del="' + esc(r.key) + '" title="从列表中移除" aria-label="移除 ' + esc(r.name) + '">' +
              '<svg class="icon" aria-hidden="true"><use href="#i-trash"/></svg>' +
            '</button>' +
          '</li>';
        }).join('')
      : '<li class="pm-empty">还没有分析记录</li>';
  }

  function setListView(mode) {
    state.listView = mode;
    $('view-flat').setAttribute('aria-pressed', String(mode === 'flat'));
    $('view-tree').setAttribute('aria-pressed', String(mode === 'tree'));
    renderList();
  }

  /** 只有一个模块参与统计时，模块数是恒等于 1 的噪音，不显示。 */
  function isSingleModule() {
    return state.moduleIds.length === 1 || state.store.modules.length === 1;
  }

  /**
   * 当前作用域。所有查询都从这里取，保证同一时刻全站看到的是同一套过滤条件。
   *
   * <p>hideTest / hideProvided 放在这里而不是只放在 renderResults 的 opts 里：
   * 左栏列表、画布、右栏、路径列表、冲突表都是从 scopeOpts() 取过滤条件的，
   * 只传给列表会让「列表少了一半、画布还是全图」。
   */
  function scopeOpts() {
    return {
      effectiveOnly: state.effectiveOnly,
      moduleIds: state.moduleIds,
      hideTest: state.hideTest,
      hideProvided: state.hideProvided
    };
  }

  /**
   * 统一的刷新入口。
   *
   * 过滤条件目前有：搜索词、模块范围、只看生效、列表显示项（groupId/scope/test/provided）。
   * 任何过滤条件变化都只调用这里，由它一次性决定刷新范围 —— 调用点各自决定刷哪几块
   * 时，加一个新条件总会漏掉某个视图（冲突页不随搜索变、树不随模块变都是这么来的）：
   *   list    —— 左侧依赖列表
   *   graph   —— 画布与右栏（选中了依赖时）
   *   paths   —— 底部路径列表
   *   conflicts —— 版本冲突表（当前面板是它时）
   *   meta    —— 搜索框标题、模块选择器的命中态
   */
  function refresh(opts) {
    opts = opts || {};
    if (!state.store) return;
    const parts = opts.parts || ['list', 'meta'];
    if (parts.indexOf('list') >= 0) doSearch(opts.keepSelection !== false, { silent: true });
    if (parts.indexOf('graph') >= 0 && state.selected.length) refreshSelection();
    if (parts.indexOf('paths') >= 0 && state.selected.length) refreshPaths();
    if (parts.indexOf('conflicts') >= 0 && state.pane === 'conflicts') renderConflicts();
  }

  /** 模块 / 生效开关变化。 */
  function onScopeChange() {
    refresh({ parts: ['list', 'meta', 'graph', 'paths'], keepSelection: true });
    if (state.pane === 'conflicts') refresh({ parts: ['conflicts'] });
  }

  /* ---------- 搜索 ---------- */
  /** 空查询 = 浏览全部依赖；有查询 = 过滤。两者都受模块 / 生效开关约束。 */
  function doSearch(keepSelection, internal) {
    const q = $('search-input').value.trim();
    state.search = q;
    $('btn-search-clear').hidden = !q;
    const res = E.search(state.store, q, scopeOpts());
    lastSearch = res;
    if (!keepSelection) state.selected = [];
    $('results-title').textContent = q
      ? '搜索结果 · ' + res.totalGas + ' 个坐标'
      : '全部依赖 · ' + res.totalGas + ' 个坐标';
    renderList();
    global.ModulePicker.setHit(res.hitModuleIds);
    // 选中联想项后不再自动重开；用户再次输入时才恢复
    if (q && !suggestSuppressed && document.activeElement === $('search-input')) {
      V.renderSuggest(state.store, q, res.results, applySuggest);
    } else {
      V.hideSuggest();
    }
    // 冲突页由 refresh() 统一调度，这里不直接刷新
    if (!(internal && internal.silent) && state.pane === 'conflicts') renderConflicts();
  }

  /**
   * 选中联想项：用完整 artifactId 搜索并选中该坐标。
   * gaId 由建议项直接带过来，不依赖「搜索结果第一条恰好同名」——同名 artifactId
   * 可能属于多个 groupId，排在第一条的不一定是用户点的那个。
   * 注意顺序——必须先置 suggestSuppressed，否则 doSearch 里看到输入框仍聚焦，
   * 会重新弹出下拉，表现为「点了没反应」。
   */
  function applySuggest(text, gaId) {
    suggestSuppressed = true;
    $('search-input').value = text;
    doSearch();
    V.hideSuggest();
    let chosen = null;
    if (gaId != null) {
      chosen = lastSearch.results.find(function (r) { return r.gaId === gaId; }) || null;
    }
    if (!chosen) {
      chosen = lastSearch.results.find(function (r) { return r.artifactId === text; }) || null;
    }
    if (chosen) {
      state.selected = [chosen.gaId];
      renderList();
      refreshSelection();
    }
  }

  /**
   * 左栏的渲染选项。抽出来是为了让「点冲突表里的一行」与正常搜索走<b>同一套</b>
   * 过滤条件：早先那条路径只传了 {@code {singleModule}}，于是点过去之后左栏退化成
   * 平铺、丢了模块范围与冲突标红 —— 同一屏上两个入口给出两种结果。
   */
  function listOpts() {
    return {
      treeView: state.listView === 'tree',
      showGroupId: state.showGroupId,
      showScope: state.showScope,
      hideTest: state.hideTest,
      hideProvided: state.hideProvided,
      singleModule: isSingleModule(),
      // 树的可见性与搜索、模块、scope 过滤必须同源，否则会出现
      // 「列表只剩 1 条、树里却是全部」的不一致
      moduleIds: state.moduleIds,
      conflictGas: conflictGaSet(),
      globalConflictGas: globalConflictSet()
    };
  }

  function renderList() {
    if (!lastSearch) return;
    V.renderResults(state.store, lastSearch, state.selected, listOpts());
  }

  /**
   * 「模块内 GA 真的存在多版本」的键集合，用于把版本号标红。
   * 不能用全局多版本：实测 144 个多版本 GA 里 130 个是各模块各用一套，
   * 真正的模块内冲突只占 3.6%（250 / 6869）。
   */
  let conflictCache = null;
  /** 全局多版本 GA（平铺列表用，没有模块上下文）。 */
  let globalConflictCache = null;
  function globalConflictSet() {
    if (globalConflictCache) return globalConflictCache;
    globalConflictCache = new Set();
    state.store.gas.forEach(function (g, gi) { if (g.v.length > 1) globalConflictCache.add(gi); });
    return globalConflictCache;
  }

  function conflictGaSet() {
    if (conflictCache) return conflictCache;
    conflictCache = new Set();
    const seen = new Map();
    state.store.occ.forEach(function (o) {
      const k = o.m + '|' + o.ga;
      let set = seen.get(k);
      if (!set) { set = new Set(); seen.set(k, set); }
      set.add(o.v);
    });
    seen.forEach(function (versions, k) {
      if (versions.size > 1) conflictCache.add(k);
    });
    return conflictCache;
  }

  /* ---------- 选中 ---------- */
  function toggleGa(gaId, additive) {
    const id = Number(gaId);
    if (additive) {
      const at = state.selected.indexOf(id);
      if (at >= 0) state.selected.splice(at, 1);
      else state.selected.push(id);
    } else {
      state.selected = state.selected.length === 1 && state.selected[0] === id ? [] : [id];
    }
    renderList();
    state.throughGaId = null;
    refreshSelection();
  }

  function refreshSelection() {
    if (!state.selected.length) {
      renderGraphPlaceholder();
      $('graph-scope').textContent = '未选择依赖';
      $('path-list').innerHTML = '';
      $('drawer-sub').textContent = '';
      $('common-banner').hidden = true;
      clearFilterChip();
      V.clearDetail();
      return;
    }
    const store = state.store;
    const opts = scopeOpts();
    const common = E.commonAncestors(store, state.selected, opts);
    const commonGaIds = common.commonAncestors.map(function (c) { return c.gaId; });
    const sub = E.subgraph(store, state.selected, commonGaIds, opts);
    state.subgraph = sub;
    state.common = common;

    $('graph-empty').hidden = true;
    global.Graph.render(sub);

    const names = state.selected.map(function (gi) { return store.gas[gi].a; });
    const scopeBits = [sub.nodes.length + ' 个坐标'];
    if (sub.rootCount > 1) scopeBits.unshift(sub.rootCount + ' 个模块根');
    scopeBits.push(num(sub.rawOccurrences) + ' 条路径');
    $('graph-scope').textContent = names.join(' + ') + ' · ' + scopeBits.join(' · ');

    const banner = $('common-banner');
    if (state.selected.length > 1) {
      if (common.noCommonAncestor) {
        banner.textContent = '这些依赖来自不同模块，没有共同引入者';
      } else {
        const list = common.commonAncestors;
        banner.textContent = '共同引入者 ' + list.slice(0, 2).map(function (c) { return c.ga; }).join(' · ') +
          (list.length > 2 ? ' 等 ' + list.length + ' 个' : '');
        banner.title = list.map(function (c) { return c.ga + '（深度 ' + c.depth + '）'; }).join('\n');
      }
      banner.hidden = false;
    } else {
      banner.hidden = true;
    }

    refreshPaths();

    // 默认不高亮任何连线：用户点节点或路径行时才高亮
    global.Graph.clearHighlight();
    state.focusedPath = null;

    if (state.selected.length === 1) {
      const sum = E.nodeSummary(store, state.selected[0], opts);
      state.focusedGa = state.selected[0];
      V.renderNodeDetail(sum, 'target');
    } else {
      V.clearDetail();
      state.focusedGa = null;
    }
  }

  function refreshPaths() {
    if (!state.selected.length) return;
    const opts = Object.assign({}, scopeOpts(), { throughGaId: state.throughGaId });
    const res = E.pathsFor(state.store, state.selected, opts);
    lastPaths = res;
    V.renderPaths(state.store, res, opts);
    updateFilterChip();
  }

  function updateFilterChip() {
    const chip = $('path-filter-chip');
    if (state.throughGaId == null) { chip.hidden = true; return; }
    const g = state.store.gas[state.throughGaId];
    $('path-filter-label').textContent = '经过 ' + g.a;
    chip.hidden = false;
  }

  function clearFilterChip() {
    state.throughGaId = null;
    $('path-filter-chip').hidden = true;
  }

  /* ---------- 事件：画布节点 ---------- */
  /**
   * 点击节点的三类语义：
   *   目标   → 高亮全部路径（这就是「它怎么进来的」的完整答案）
   *   中间节点 → 只高亮经过它的路径
   *   模块根 → 只高亮这个模块内的路径
   */
  function onNodeTap(payload) {
    // 点画布即退出「单条路径」模式，并收起可能挡在画布上的联想
    clearPathSelection();
    V.hideSuggest();
    if (!payload) {
      state.focusedGa = null;
      global.Graph.clearHighlight();
      V.clearDetail();
      return;
    }
    const gaId = payload.gaId;
    const opts = scopeOpts();
    state.focusedGa = gaId;

    global.Graph.selectNode(gaId);

    /*
     * 节点身份可能重叠：
     *   - 目标依赖：用户选中的那个
     *   - 模块根：自身是一个模块的起点
     *   - 中间节点：被别的模块/依赖引入过
     *
     * 61 个模块里有 30 个同时是「根」和「中间节点」（如 agentscope-core）。
     * 点击模块根时按「模块详情」展示——它同时包含「直接依赖」和「被引用」两个维度，
     * 不会丢失中间节点的信息。
     */
    if (payload.isRoot) {
      const modId = payload.rootModuleId != null ? payload.rootModuleId : null;
      if (modId != null) {
        V.renderModuleDetail(E.moduleDetail(state.store, modId, opts));
        const gaIds = rootPathNodes(modId);
        global.Graph.highlightElements(gaIds.nodes, gaIds.edges);
        state.focusedRoot = modId;
        state.focusedModule = modId;
      }
      return;
    }
    state.focusedModule = null;

    if (payload.isTarget) {
      V.renderNodeDetail(E.nodeSummary(state.store, gaId, opts), 'target');
      const all = E.elementsThrough(state.store, gaId, state.selected, opts);
      global.Graph.highlightElements(all.nodes, all.edges);
      state.focusedRoot = null;
      return;
    }

    if (payload.isInner) {
      V.renderNodeDetail(E.nodeSummary(state.store, gaId, opts), 'middle');
      const th = E.elementsThrough(state.store, gaId, state.selected, opts);
      global.Graph.highlightElements(th.nodes, th.edges);
      state.focusedRoot = null;
      return;
    }
  }

  /** 右栏点「被引用」里的模块名：在画布上找该模块的根节点，没有就提示。 */
  function focusModuleOnCanvas(moduleId) {
    const mod = state.store.modules[moduleId];
    const name = mod ? mod.artifactId : '该模块';
    const cy = global.Graph.cy;
    if (!cy) { V.toast('这个模块不在当前画布上'); return; }
    const node = cy.nodes().filter(function (n) {
      const d = n.data();
      return d.isRoot && d.rootModuleId === moduleId;
    })[0];
    if (!node) {
      V.toast('「' + name + '」不在当前画布上');
      return;
    }
    onNodeTap(node.data());
    global.Graph.focusNodes([node.id()]);
  }

  /** 右栏点上下游依赖名：在画布上选中它并显示它的详情。 */
  function selectNodeFromPanel(gaId) {
    const node = global.Graph.cy ? global.Graph.cy.getElementById('g' + gaId) : null;
    if (!node || !node.length) {
      V.toast('这个依赖不在当前画布上');
      return;
    }
    onNodeTap(node.data());
    // 若不在视野内，轻微聚焦过去
    const bb = node.renderedBoundingBox();
    const cy = global.Graph.cy;
    if (bb.x1 < 0 || bb.x2 > cy.width() || bb.y1 < 0 || bb.y2 > cy.height()) {
      global.Graph.focusNodes(['g' + gaId]);
    }
  }

  /** 点「最浅 / 最深」的数字：高亮该深度的全部路径。 */
  function highlightDepth(kind) {
    if (state.focusedGa == null) return;
    const sum = E.nodeSummary(state.store, state.focusedGa, scopeOpts());
    const d = kind === 'shallow' ? sum.shallowest : sum.deepest;
    if (!d || !d.occIds.length) return;

    // 合并这些 occurrence 的 GA 链，去重后一起高亮
    const nodeSet = new Set();
    const edgeSet = new Set();
    d.occIds.forEach(function (oid) {
      const path = E.pathsByOccIds(state.store, [oid])[0];
      path.forEach(function (h, i) {
        nodeSet.add(h.gaId);
        if (i > 0) edgeSet.add(path[i - 1].gaId + '>' + h.gaId);
      });
    });
    global.Graph.highlightElements(Array.from(nodeSet), Array.from(edgeSet));
    V.toast((kind === 'shallow' ? '最浅' : '最深') + '深度的 ' + d.occIds.length + ' 条路径已高亮');
  }

  /** 退出「单条路径」模式：清掉行的选中态与右栏的路径视图。 */
  function clearPathSelection() {
    state.focusedPath = null;
    document.querySelectorAll('.path-row-item.is-active').forEach(function (el) {
      el.classList.remove('is-active');
    });
  }

  /** 某个模块根对应的路径元素（只取该模块的路径）。 */
  function rootPathNodes(moduleId) {
    const nodes = new Set();
    const edges = new Set();
    state.selected.forEach(function (gi) {
      (state.store.byGa.get(gi) || []).forEach(function (oid) {
        const o = state.store.occ[oid];
        if (o.m !== moduleId) return;
        if (state.effectiveOnly && !o.e) return;
        let cur = oid;
        while (cur >= 0) {
          nodes.add(state.store.occ[cur].ga);
          const p = state.store.parent[cur];
          if (p >= 0 && state.store.occ[p].ga !== state.store.occ[cur].ga) {
            edges.add(state.store.occ[p].ga + '>' + state.store.occ[cur].ga);
          }
          cur = p;
        }
      });
    });
    return { nodes: Array.from(nodes), edges: Array.from(edges) };
  }

  /* ---------- 弹层 ---------- */
  function openModal(id) {
    settingsSession.invalidate();
    settingsCache = null;
    $('mask').hidden = false;
    document.querySelectorAll('.modal').forEach(function (m) { m.hidden = m.id !== id; });
    const dlg = $(id);
    const auto = dlg.querySelector('input, button');
    if (auto) setTimeout(function () { auto.focus(); }, 20);
  }

  function closeModals() {
    settingsSession.invalidate();
    settingsCache = null;
    $('mask').hidden = true;
    document.querySelectorAll('.modal').forEach(function (m) { m.hidden = true; });
  }

  /* ================= 页内目录浏览器 ================= */

  /**
   * 目录浏览器的状态。{@code path} 是当前所在目录（服务端返回的规范化绝对路径）。
   *
   * <p>为什么自己画而不用系统目录框：服务端弹 AWT {@code FileDialog} 在 macOS 上
   * 会跳出一个 **java 图标**的窗口 —— 用户以为是另一个程序在要权限；而且它要求
   * 有窗口会话，SSH/headless 直接失效。浏览器确实拿不到本地绝对路径
   * （{@code webkitdirectory} 只给相对路径、{@code showDirectoryPicker} 只给 handle），
   * 但那是说不能从浏览器**读**目录，不妨碍由服务端**列**出来再交给前端画。
   */
  const dirState = { path: '', parent: null, loading: false };

  function openDirPicker() {
    dirState.path = '';
    dirState.parent = null;
    openModal('dlg-pick');
    loadDir('');
  }

  function loadDir(path) {
    if (dirState.loading) return;
    dirState.loading = true;
    renderDirLoading();
    global.Scan.listDir(path).then(function (d) {
      dirState.loading = false;
      dirState.path = d.path;
      dirState.parent = d.parent || null;
      renderDir(d);
    }).catch(function (err) {
      dirState.loading = false;
      $('dir-list').innerHTML = '<li class="dir-empty">' + esc(err.message || '读不了这个目录') + '</li>';
      $('dir-status').textContent = '';
    });
  }

  function renderDirLoading() {
    $('dir-list').innerHTML = '<li class="dir-loading">读取中…</li>';
    $('dir-crumbs').textContent = dirState.path || '';
    $('btn-dir-use').disabled = true;
  }

  function renderDir(d) {
    const input = $('dir-input');
    input.value = d.path;
    // 输入框放进长路径后会自动滚到最右，只显示「…/projects/demo」这半截。
    // 路径从头读才有意义，但 scrollLeft 要在值渲染完才生效，所以放到下一帧。
    input.scrollLeft = 0;
    requestAnimationFrame(function () { input.scrollLeft = 0; });
    renderRoots(d.roots);
    renderCrumbs(d.crumbs);
    $('btn-dir-up').disabled = !d.parent;
    $('btn-dir-use').disabled = false;
    $('dir-status').textContent = d.hasPom
      ? (d.hasModules ? '这个目录有 pom.xml（含 <modules>）' : '这个目录有 pom.xml')
      : '这个目录没有 pom.xml';
    $('dir-status').classList.toggle('is-ok', !!d.hasPom);
    const items = d.dirs.map(function (it) {
      const badge = it.hasPom ? '<span class="dir-badge">pom</span>' : '';
      return '<li><button class="dir-item" data-path="' + esc(it.path) + '">' +
        '<svg class="icon" aria-hidden="true"><use href="#i-folder"/></svg>' +
        '<span class="dir-name">' + esc(it.name) + '</span>' + badge + '</button></li>';
    });
    if (!items.length) {
      items.push('<li class="dir-empty">这个目录下没有子目录。' +
        (d.hasPom ? '可以直接点「使用此目录」。' : '') + '</li>');
    }
    $('dir-list').innerHTML = items.join('');
    $('dir-list').scrollTop = 0;
  }

  /**
   * 盘符条：Windows 上每个盘一个按钮，点一下直接跳过去。
   *
   * <p>为什么需要：从 {@code C:\Users\me} 一路点「上一级」到 {@code C:\} 之后就没有
   * 父目录了，光靠点击永远到不了 D 盘。服务端只给一个根（macOS/Linux 的 {@code /}）时
   * 整条隐藏，免得占一行没用的东西。
   */
  function renderRoots(roots) {
    const box = $('dir-roots');
    if (!roots || roots.length < 2) {
      box.hidden = true;
      box.innerHTML = '';
      return;
    }
    box.hidden = false;
    box.innerHTML = roots.map(function (r) {
      const active = r.path === dirState.path ? ' is-active' : '';
      return '<button class="dir-root' + active + '" data-path="' + esc(r.path) + '">' +
        esc(r.name) + '</button>';
    }).join('');
  }

  /**
   * 面包屑：每一段都能点回去。只显示最近 4 段，再长的路径缩成 …。
   *
   * <p>层级由服务端算好（{@code crumbs:[{name,path}]}）—— 前端不能自己按 {@code /}
   * 切：Windows 的路径是 {@code D:\code\Demo}，整串会被当成一段，点哪都回不去。
   */
  function renderCrumbs(crumbs) {
    const all = crumbs || [];
    if (!all.length) {
      $('dir-crumbs').innerHTML = '';
      return;
    }
    // 太长时保留「根 + … + 最后几层」：根永远可点（Windows 上就是盘符），
    // 中间被折叠掉的那一截点不到也没关系，左上角的「上一级」能一层层退回去。
    const keep = 4;
    const collapsed = all.length > keep + 1;
    const shown = collapsed
      ? [all[0], { name: '…', path: '', ellipsis: true }].concat(all.slice(all.length - (keep - 1)))
      : all;
    // 分隔符跟着路径走：Windows 上是反斜杠，写死 "/" 会显示成「D:\ / code / Demo」
    const sep = all[all.length - 1].path.indexOf('\\') >= 0 ? '\\' : '/';
    const out = [];
    shown.forEach(function (c, i) {
      if (c.ellipsis) {
        out.push('<span class="dir-crumb-sep">' + esc(c.name) + '</span>');
        return;
      }
      // 两段之间才补分隔符；前一段是根（名字本身就以 "/" 或 "D:\" 结尾）就别补，
      // 否则会显示成「/ /」。折叠时前一段是「…」，必须补。
      if (i > 0) {
        const prev = shown[i - 1];
        if (prev.ellipsis || !/[\\/]$/.test(prev.name)) {
          out.push('<span class="dir-crumb-sep">' + esc(sep) + '</span>');
        }
      }
      out.push('<button class="dir-crumb' + (i === shown.length - 1 ? ' is-last' : '') +
        '" data-path="' + esc(c.path) + '">' + esc(c.name) + '</button>');
    });
    $('dir-crumbs').innerHTML = out.join('');
  }

  /** 手输路径跳转：后端会做规范化，写错一个字符就退到它的父目录。 */
  function gotoDir(raw) {
    const v = (raw || '').trim();
    if (!v) return;
    loadDir(v);
  }

  /* ================= 绑定 ================= */
  function bindSelectView() {
    // 「选择 Maven 项目目录」直接开页内目录浏览器 —— 不弹任何系统窗口。
    $('btn-pick').addEventListener('click', openDirPicker);

    // 上一级用服务端给的 parent：前端按 '/' 截断在 Windows 上会切不动
    $('btn-dir-up').addEventListener('click', function () {
      if (dirState.parent) loadDir(dirState.parent);
    });

    $('btn-dir-home').addEventListener('click', function () { loadDir(''); });

    $('btn-dir-go').addEventListener('click', function () { gotoDir($('dir-input').value); });
    $('dir-input').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') gotoDir($('dir-input').value);
    });

    $('dir-roots').addEventListener('click', function (e) {
      const b = e.target.closest('[data-path]');
      if (b) loadDir(b.dataset.path);
    });

    $('btn-dir-use').addEventListener('click', function () {
      const path = dirState.path;
      if (!path) return;
      closeModals();
      openPathAndGo(path);
    });

    // 点目录进去；点面包屑退回某一级
    $('dir-crumbs').addEventListener('click', function (e) {
      const b = e.target.closest('[data-path]');
      if (b) loadDir(b.dataset.path);
    });
    $('dir-list').addEventListener('click', function (e) {
      const b = e.target.closest('[data-path]');
      if (b) loadDir(b.dataset.path);
    });

    function submitPath() {
      const v = $('path-input').value.trim();
      if (!v) { $('path-input').focus(); return; }
      openPathAndGo(v);
    }
    $('btn-path-go').addEventListener('click', submitPath);
    $('path-input').addEventListener('keydown', function (e) { if (e.key === 'Enter') submitPath(); });

    $('btn-scan').addEventListener('click', startScan);
    $('btn-scan-cancel').addEventListener('click', function () { global.Scan.cancel(); });
    $('btn-scan-retry').addEventListener('click', function () {
      // 只在 409 之后可见：同一个目录再发起一次，这次槽位应该空出来了
      startScan();
    });
    // 失败面板上的三个动作
    $('btn-scan-retry2').addEventListener('click', function () { startScan(); });
    $('btn-scan-back').addEventListener('click', function () { showView('select'); });
    $('btn-scan-online').addEventListener('click', function () {
      // offline:false 覆盖服务端配置，只对本次生效 —— 服务端的「设置」不该被
      // 一次失败重试偷偷改掉
      startScan({ offline: false });
    });
    $('btn-view-result').addEventListener('click', function () {
      if (!state.pendingDoc) return;
      resetScanStage();
      touchRecent(state.pendingKey);
      enterExplore(state.pendingKey, state.pendingDoc);
      state.pendingDoc = null;
    });
    $('btn-scan-again').addEventListener('click', function () {
      resetScanStage();
      startScan();
    });
    $('btn-confirm-back').addEventListener('click', function () { showView('select'); });
    $('btn-confirm-more').addEventListener('click', function () {
      const owner = navigation.capture();
      state.expandedModules = !state.expandedModules;
      global.Scan.discover(state.pendingKey).then(owner.guard(function (d) {
        V.renderModules(d, state.expandedModules);
      })).catch(owner.guard(function (err) { V.toast(err.message); }));
    });
    $('btn-log-toggle').addEventListener('click', function () {
      const log = $('scan-log');
      log.hidden = !log.hidden;
      this.setAttribute('aria-expanded', String(!log.hidden));
      this.textContent = log.hidden ? '查看 Maven 输出' : '收起 Maven 输出';
    });
    // 每次开始扫描都把日志重置为展开
    function resetScanLog() {
      $('scan-log').hidden = false;
      $('btn-log-toggle').setAttribute('aria-expanded', 'true');
      $('btn-log-toggle').textContent = '收起 Maven 输出';
    }
    global.resetScanLog = resetScanLog;

    renderRecentList();
  }

  /* ================= 启动自检：五条数据恒等式 ================= */

  /**
   * 加载 graph.json 后立刻核对五条恒等式。
   *
   * 必须用 effectiveOnly=false 跑全量：版本清单刻意不受 effectiveOnly 影响
   * （否则左栏标红说有多版本、右栏只列一个），拿 true 去比会误报
   * （实测 agentscope 278/640、demo 71/208 失败）。
   * 失败不抛异常，只 toast + console.warn，让 UI 仍然可用。
   */
  function assertInvariants(store) {
    const bad = [];
    const opts = { effectiveOnly: false, moduleIds: [] };
    store.gas.forEach(function (ga, gi) {
      const s = E.summarize(store, gi, opts);
      const vOcc = s.versions.reduce(function (n, v) { return n + v.occurrences; }, 0);
      const vMod = s.versions.reduce(function (n, v) { return n + v.modules; }, 0);
      if (vOcc !== s.occurrences) {
        bad.push(ga.g + ':' + ga.a + ' Σversions.occurrences=' + vOcc + ' ≠ ' + s.occurrences);
      }
      if (vMod !== s.moduleCount) {
        bad.push(ga.g + ':' + ga.a + ' Σversions.modules=' + vMod + ' ≠ ' + s.moduleCount);
      }
    });
    let withParent = 0;
    store.occ.forEach(function (o) { if (o.p >= 0) withParent++; });
    // 第 5 条是**全项目求和**：Σ over all GA of Σ(upstream[].paths) === 有父节点的 occurrence 数。
    // 逐 GA 比是错的 —— 单个 GA 的 upstream.paths 之和没有理由等于全项目的 15294。
    let sumUpPaths = 0;
    store.gas.forEach(function (ga, gi) {
      const n = E.nodeSummary(store, gi, opts);
      if (n.upstream.length !== n.parentCount) {
        bad.push(ga.g + ':' + ga.a + ' upstream=' + n.upstream.length + ' ≠ parentCount=' + n.parentCount);
      }
      if (n.downstream.length !== n.childCount) {
        bad.push(ga.g + ':' + ga.a + ' downstream=' + n.downstream.length + ' ≠ childCount=' + n.childCount);
      }
      n.upstream.forEach(function (u) { sumUpPaths += u.paths; });
    });
    if (sumUpPaths !== withParent) {
      bad.push('Σupstream.paths(全项目)=' + sumUpPaths + ' ≠ 有父 occurrence 数=' + withParent);
    }
    if (bad.length) {
      console.warn('[depviz] 数据自检发现 ' + bad.length + ' 处不一致', bad.slice(0, 10));
      V.toast('数据自检发现 ' + bad.length + ' 处不一致（详见控制台）');
    }
    return bad;
  }

  /* ================= 设置面板（持久化到 config.json） ================= */

  /** DOM id 与配置键的对应；顺序即写回顺序。 */
  const SETTINGS_FIELDS = [
    ['s-mvn', 'maven', 'text'], ['s-java', 'javaHome', 'text'],
    ['s-settings', 'settingsXml', 'text'], ['s-repo', 'localRepo', 'text'],
    ['s-offline', 'offline', 'bool'],
    ['s-profiles', 'profiles', 'text'],
    ['s-xmx', 'xmx', 'text'],
    ['s-threads', 'threads', 'text'],
    ['s-data', 'dataDir', 'text'],
    ['s-loglevel', 'logLevel', 'text'], ['s-fileloglevel', 'fileLogLevel', 'text']
  ];

  let settingsCache = null;

  /**
   * 打开设置弹窗：先把配置读进来，读成功才允许保存。
   *
   * <p><b>不能先开弹窗再异步回填</b>：保存按钮读的是 DOM 里的当前值，而弹窗一打开
   * 那些输入框是「空」的（只有 placeholder）。GET 慢一点、或者干脆失败（服务重启、
   * 5xx）时用户点保存，就会把 maven / JAVA_HOME / settings / 仓库 / profiles 全提交成
   * 空串，dataDir 也会被打回默认值 —— 实测一次就能清空整份配置，而且没有任何提示。
   * 所以：打开时禁用保存，回填成功再启用；读取失败保持禁用并说明原因。
   *
   * <p>select 的选项是固定清单，但配置允许自由字符串（`xmx=3g`、`threads=1C`）。
   * 直接 `select.value = '3g'` 会让浏览器把选中项清成 -1，之后保存就把合法值提交成空串，
   * 后端归一成 auto / 串行。所以遇到清单里没有的值先补一个临时 option。
   */
  function openSettings() {
    settingsCache = null;
    const saveBtn = $('btn-settings-save');
    if (saveBtn) saveBtn.disabled = true;
    openModal('dlg-settings');
    const owner = settingsSession.begin();
    fetch('/api/config').then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(owner.guard(function (cfg) {
      settingsCache = cfg;
      SETTINGS_FIELDS.forEach(function (f) {
        const el = $(f[0]);
        if (!el) return;
        if (f[2] === 'bool') { el.checked = !!cfg[f[1]]; return; }
        const val = cfg[f[1]] == null ? '' : String(cfg[f[1]]);
        if (el.tagName === 'SELECT') fillSelect(el, val);
        el.value = val;
      });
      if (saveBtn) saveBtn.disabled = false;
    })).catch(owner.guard(function () {
      if (saveBtn) saveBtn.disabled = true;
      V.toast('读取配置失败，已禁用保存（关闭后重试）');
    }));
  }

  /**
   * 让 select 能显示一个不在选项清单里的值：补一个 `（配置值）` 的临时项。
   *
   * <p>每次重填前先清掉上次补的项，否则弹窗开几次就会堆一串同名 option。
   */
  function fillSelect(el, val) {
    Array.prototype.slice.call(el.options).forEach(function (o) {
      if (o.dataset.custom === '1') el.removeChild(o);
    });
    if (!val) return;
    for (let i = 0; i < el.options.length; i++) {
      if (el.options[i].value === val) return;
    }
    const o = document.createElement('option');
    o.value = val;
    o.textContent = val + '（配置值）';
    o.dataset.custom = '1';
    el.appendChild(o);
  }

  /**
   * 首页「高级设置」面板显示当前配置。
   *
   * <p>这块面板早先写的是硬编码假值（`自动探测 · 3.9.16`、作者机器的
   * temurin 路径、`~/.m2/settings.xml（已配置阿里云镜像）`），没有任何 JS
   * 更新它 —— 用户看到的是别人机器的设置。现在从 `/api/config` 取真值。
   *
   * <p>空值显示「自动探测」而不是空白：这四个字段留空的语义就是自动探测。
   */
  /** 堆上限在首页面板里的可读写法。「自动」不写死值 —— 具体分档由机器内存决定。 */
  function xmxText(xmx) {
    if (!xmx || xmx === 'auto') return '自动（按物理内存分档）';
    if (xmx === 'off' || xmx === '-' || xmx === 'none') return '不注入';
    return '-Xmx' + xmx;
  }

  /** 并行度在首页面板里的可读写法。空串就是串行 —— 后端把 1/off 也归一到这个值。 */
  function threadsText(threads) {
    return threads ? '-T ' + threads : '串行（不加 -T）';
  }

  function renderAdvanced(cfg) {
    const AUTO = '自动探测';
    const pairs = [
      ['adv-mvn', cfg.maven || AUTO],
      ['adv-java', cfg.javaHome || '继承当前环境'],
      ['adv-settings', cfg.settingsXml || '默认 ~/.m2/settings.xml'],
      ['adv-repo', cfg.localRepo || '默认 ~/.m2/repository'],
      ['adv-profiles', cfg.profiles || '无'],
      ['adv-offline', cfg.offline ? '-o 已启用' : '在线'],
      ['adv-xmx', xmxText(cfg.xmx)],
      ['adv-threads', threadsText(cfg.threads)],
      ['adv-log', (cfg.logLevel || 'info') + ' / ' + (cfg.fileLogLevel || 'info')]
    ];
    pairs.forEach(function (pr) {
      const el = $(pr[0]);
      if (el) el.textContent = pr[1];
    });
  }

  function loadAdvanced() {
    fetch('/api/config').then(function (r) { return r.json(); })
      .then(renderAdvanced)
      .catch(function () { /* 面板保持占位符，不打扰用户 */ });
  }

  function saveSettings() {
    // 兜底门禁：没有成功读到配置就绝不提交。否则提交的是弹窗刚打开时那几个
    // 空输入框，会把用户真实的 Maven 路径 / profiles 全部清空（见 openSettings）。
    if (!settingsCache) {
      V.toast('配置尚未加载完成，请稍后重试');
      return;
    }
    const owner = settingsSession.begin();
    const saveBtn = $('btn-settings-save');
    saveBtn.disabled = true;
    const body = {};
    SETTINGS_FIELDS.forEach(function (f) {
      const el = $(f[0]);
      if (!el) return;
      body[f[1]] = f[2] === 'bool' ? !!el.checked : el.value;
    });
    // 未知字段由后端保留回写，这里只送界面提供的键
    fetch('/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(owner.guard(function (cfg) {
      settingsCache = cfg;
      renderAdvanced(cfg);
      V.toast('设置已保存');
      closeModals();
    })).catch(owner.guard(function (err) {
      saveBtn.disabled = false;
      V.toast('保存失败：' + err.message);
    }));
  }

  /** 绑定设置弹窗的保存操作。 */
  function bindSettings() {
    const dlg = $('dlg-settings');
    if (!dlg) return;
    const save = $('btn-settings-save');
    if (save) save.addEventListener('click', function (e) { e.preventDefault(); saveSettings(); });
  }

  function bindExplore() {
    const input = $('search-input');
    const debouncedSearch = V.debounce(function () {
      refresh({ parts: ['list', 'meta', 'conflicts'] });
    }, 250);
    input.addEventListener('input', function () {
      suggestSuppressed = false;   // 用户继续输入，重新允许联想
      debouncedSearch();
    });
    input.addEventListener('blur', function () {
      // 延迟关闭，让下拉项的 click 先触发；点在结果栏上时不能收——
      // 否则结果列表的让位空间先消失、结果行在鼠标抬起前上移，点击会落到别的行上。
      setTimeout(function () {
        if (!pointerOnResults) V.hideSuggest();
      }, 120);
    });
    document.addEventListener('mousedown', function (e) {
      pointerOnResults = !!(e.target.closest && e.target.closest('#result-list'));
    }, true);
    document.addEventListener('mouseup', function (e) {
      // 在结果栏外松开鼠标后清掉按下标记，避免一次“按下结果栏、松开别处”
      // 让 blob 的延迟收起永久失效。
      if (!(e.target.closest && e.target.closest('#result-list'))) pointerOnResults = false;
    }, true);
    function openSuggest() {
      suggestSuppressed = false;
      if (input.value.trim() && lastSearch) {
        V.renderSuggest(state.store, input.value, lastSearch.results, applySuggest);
      }
    }
    input.addEventListener('focus', openSuggest);
    // 选中联想项时 pop.onmousedown 的 preventDefault 会保住输入框焦点，
    // 之后再点输入框不会触发 focus，需要 click 单独重开。
    input.addEventListener('click', function () {
      if ($('suggest-pop').hidden) openSuggest();
    });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        if (!$('suggest-pop').hidden) { V.hideSuggest(); return; }
        input.value = ''; doSearch(); input.blur();
      }
    });
    $('btn-search-clear').addEventListener('click', function () {
      input.value = '';
      doSearch();
      // 必须是 V.hideSuggest()：views.js 里的函数挂在 V 上，裸调会抛
      // ReferenceError，后面的 input.focus() 也跟着不执行 —— 表现是
      // 「点清空按钮，输入框失去焦点，而且控制台有红字」。
      V.hideSuggest();
      input.focus();
    });
    document.addEventListener('keydown', function (e) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        input.focus();
        input.select();
      }
    });

    $('view-flat').addEventListener('click', function () { setListView('flat'); });
    $('view-tree').addEventListener('click', function () { setListView('tree'); });

    $('btn-list-opts').addEventListener('click', function () {
      const box = $('list-opts');
      box.hidden = !box.hidden;
      this.setAttribute('aria-expanded', String(!box.hidden));
    });
    [['opt-groupid', 'showGroupId'], ['opt-scope', 'showScope']].forEach(function (pair) {
      const el = $(pair[0]);
      el.checked = state[pair[1]];
      el.addEventListener('change', function () {
        state[pair[1]] = this.checked;
        // 纯展示项：只影响行里显不显示 groupId / scope 两列
        refresh({ parts: ['list'] });
      });
    });

    // test / provided 是**过滤**而不是展示项，所以改动后画布、右栏、路径列表
    // 也必须跟着变 —— 它们都从 scopeOpts() 取条件，只刷列表会让「列表少一半、
    // 画布还是全图」。
    [['opt-test', 'hideTest'], ['opt-provided', 'hideProvided']].forEach(function (pair) {
      const el = $(pair[0]);
      el.checked = state[pair[1]];
      el.addEventListener('change', function () {
        state[pair[1]] = this.checked;
        onScopeChange();
      });
    });

    $('btn-project').addEventListener('click', function (e) {
      e.stopPropagation();
      toggleProjectMenu();
    });
    $('pm-new').addEventListener('click', function () {
      closeProjectMenu();
      showView('select');
      showStage('pick');
      state.selected = [];
    });
    $('pm-list').addEventListener('click', function (e) {
      const del = e.target.closest('[data-del]');
      if (del) {
        e.stopPropagation();
        removeRecent(del.dataset.del);
        return;
      }
      const item = e.target.closest('[data-key]');
      if (!item) return;
      const key = item.dataset.key;
      closeProjectMenu();
      if (key === currentProjectKey()) return;
      // 走统一的入口：分析过的直接进图，没分析过的落确认页。
      // 从菜单切换项目不该等于强制重扫 —— 重扫是顶栏那个明确的按钮。
      showView('select');
      openProject(key);
    });
    document.addEventListener('click', function (e) {
      if (e.target.closest('.project-picker')) return;
      closeProjectMenu();
    });

    const listEl = $('result-list');
    listEl.addEventListener('click', function (e) {
      const twisty = e.target.closest('[data-twisty]');
      if (twisty) {
        const occId = Number(twisty.dataset.twisty);
        const set = global.__treeCollapsed || (global.__treeCollapsed = new Set());
        if (set.has(occId)) set.delete(occId); else set.add(occId);
        renderList();
        // 不收起联想：收起会让结果行在鼠标抬起前上移。
        e.stopPropagation();
        return;
      }
      const row = e.target.closest('.dep-row');
      if (!row) { V.hideSuggest(); pointerOnResults = false; return; }
      if (e.detail > 1) {
        // 第一次 click 的 toggleGa() 会 renderList() 重建行 DOM，Chrome 在
        // 目标元素被替换后不再派发 dblclick。双击折叠只能在这里处理：
        // 第二击的 detail 仍为 2，新行元素带着同样的 data-occ。
        if (e.detail === 2 && row.dataset.occ != null) {
          const occId = Number(row.dataset.occ);
          const set = global.__treeCollapsed || (global.__treeCollapsed = new Set());
          if (set.has(occId)) set.delete(occId); else set.add(occId);
          renderList();
        }
        return;
      }
      toggleGa(row.dataset.ga, e.metaKey || e.ctrlKey || e.shiftKey);
      // 不收起联想：让位一旦消失，结果行会在双击的第二下之前上移，
      // 第二击就落到别的行。让位保留到点击结果栏以外的位置。
    });

    $('results-empty').addEventListener('click', function (e) {
      const chip = e.target.closest('[data-q]');
      if (!chip) return;
      input.value = chip.dataset.q;
      doSearch();
    });

    $('chk-effective').addEventListener('change', function () {
      state.effectiveOnly = this.checked;
      onScopeChange();
    });

    $('btn-drawer').addEventListener('click', function () { setDrawer(); });

    $('btn-fit').addEventListener('click', function () { global.Graph.fit(); });
    $('btn-focus').addEventListener('click', function () {
      if (!global.Graph.cy || !global.Graph.cy.nodes().length) { V.toast('先选择一个依赖'); return; }
      // 有选中的节点就聚焦它，否则聚焦目标依赖
      if (state.focusedGa != null) global.Graph.focusNodes(['g' + state.focusedGa]);
      else global.Graph.focusTargets();
    });
    $('btn-fullscreen').addEventListener('click', function () {
      setFullscreen(!$('view-explore').classList.contains('graph-full'));
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && $('view-explore').classList.contains('graph-full')) {
        setFullscreen(false);
      }
    });
    $('btn-collapse-left').addEventListener('click', function () { setPanel('left', true); });
    $('btn-expand-left').addEventListener('click', function () { setPanel('left', false); });
    $('btn-collapse-right').addEventListener('click', function () { setPanel('right', true); });
    $('btn-expand-right').addEventListener('click', function () { setPanel('right', false); });

    $('btn-meta').addEventListener('click', function () { openModal('dlg-meta'); });
    $('btn-retry-online').addEventListener('click', function () {
      scanProject(currentProjectKey(), { offline: false }, true);
    });
    $('btn-meta-bar').addEventListener('click', function () {
      const collapsed = $('view-explore').classList.toggle('meta-collapsed');
      this.setAttribute('aria-pressed', String(!collapsed));
      this.classList.toggle('is-off', collapsed);
      setTimeout(function () { global.Graph.resize(); }, 220);
    });
    $('btn-settings').addEventListener('click', function () { openSettings(); });
    $('btn-rescan').addEventListener('click', function () {
      scanProject(currentProjectKey(), null, true);
    });

    $('tab-paths').addEventListener('click', function () { setPane('paths'); });
    $('tab-conflicts').addEventListener('click', function () { setPane('conflicts'); });

    $('chk-rewritten').addEventListener('change', function () {
      state.replacedOnly = this.checked;
      renderConflicts();
    });
    $('conflict-body').addEventListener('click', function (e) {
      // 「仅被引入、未生效」→ 解释为什么没生效
      const dead = e.target.closest('[data-vexp]');
      if (dead) {
        e.stopPropagation();
        openVersionExplain(Number(dead.dataset.ga), dead.dataset.vexp);
        return;
      }
      // 版本后面的「N 个模块在用」→ 列出这些模块
      const cvBtn = e.target.closest('[data-cver]');
      if (cvBtn) {
        e.stopPropagation();
        openConflictVersionModules(cvBtn.dataset.cver);
        return;
      }
      const b = e.target.closest('[data-ga]');
      if (!b) return;
      const gi = Number(b.dataset.ga);
      setPane('paths');
      state.selected = [gi];
      const g = state.store.gas[gi];
      if (!$('search-input').value.trim()) {
        $('search-input').value = g.a;
        doSearch(true);
      } else if (lastSearch) {
        V.renderResults(state.store, lastSearch, state.selected, listOpts());
      }
      refreshSelection();
    });

    // 右栏动作
    $('detail-body').addEventListener('click', function (e) {
      // 1a) 「被引用」里的模块名 → 找该模块在画布上的根节点
      const modBtn = e.target.closest('[data-module]');
      if (modBtn) {
        focusModuleOnCanvas(Number(modBtn.dataset.module));
        return;
      }

      // 1b) 依赖名（上下游清单 / 路径链）→ 跳到画布上那个节点
      const nb = e.target.closest('.d-nb-name, .d-chain .c-art');
      if (nb) {
        const gaId = Number(nb.dataset.ga);
        selectNodeFromPanel(gaId);
        return;
      }

      // 2) 显示更多 / 显示更少（右栏与弹窗共用）
      const more = e.target.closest('[data-act="more"]');
      if (more) {
        toggleMore(more);
        return;
      }

      const b = e.target.closest('[data-act]');
      if (!b) return;
      const act = b.dataset.act;

      if (act === 'search') {
        const sum = E.nodeSummary(state.store, state.focusedGa, scopeOpts());
        $('search-input').value = sum.artifactId;
        doSearch(true);
        refreshSelection();
      }

      if (act === 'copy-gav') {
        const sum = E.nodeSummary(state.store, state.focusedGa, scopeOpts());
        const v = (sum.versions.filter(function (x) { return x.effective > 0; })[0] || sum.versions[0]);
        V.copy(sum.groupId + ':' + sum.artifactId + ':' + (v ? v.version : ''));
      }

      if (act === 'modules') {
        const sum = E.nodeSummary(state.store, state.focusedGa, scopeOpts());
        state.modBreakdown = sum;
        V.renderModuleBreakdown(sum);
        openModal('dlg-modules');
      }

      if (act === 'version-modules') {
        const sum = E.nodeSummary(state.store, state.focusedGa, scopeOpts());
        state.modBreakdown = sum;
        V.renderModuleBreakdown(sum, b.dataset.version);
        openModal('dlg-modules');
      }

      if (act === 'filter-through') {
        state.throughGaId = state.focusedGa;
        refreshPaths();
        $('drawer').classList.remove('collapsed');
      }

      if (act === 'depth') {
        highlightDepth(b.dataset.kind);
      }

      if (act === 'copy-path' && state.focusedPath) {
        V.copy(state.focusedPath.hops.map(function (h) { return h.artifactId + ':' + h.version; }).join(' → '));
      }

      if (act === 'focus-path' && state.focusedPath) {
        global.Graph.focusPath(state.focusedPath.hops.map(function (h) { return h.gaId; }));
      }

      if (act === 'pom' && state.focusedModule != null) {
        // POM 不在图数据里（2 MB 的 JSON 再塞 224 KB 源码不值），点开时现读磁盘
        openPomDialog(state.focusedModule);
      }

      if (act === 'vexp' && b.dataset.version) {
        openVersionExplain(state.focusedGa, b.dataset.version);
        return;
      }

      if (act === 'declared' && state.focusedModule != null) {
        const mod = state.store.modules[state.focusedModule];
        V.renderDeclaredDialog(mod);
        openModal('dlg-declared');
      }

      if (act === 'only-module' && state.focusedModule != null) {
        global.ModulePicker.setSelection([state.focusedModule]);
        state.moduleIds = [state.focusedModule];
        $('module-btn-label').textContent = global.ModulePicker.label();
        onScopeChange();
        V.toast('已限定到该模块');
      }

      if (act === 'grep-gav' && state.focusedPath) {
        const lastHop = state.focusedPath.hops[state.focusedPath.hops.length - 1];
        $('search-input').value = lastHop.artifactId;
        doSearch(true);
      }
    });

    $('btn-only-these').addEventListener('click', function () {
      const ids = state.modBreakdown.modules.map(function (m) { return m.moduleId; });
      global.ModulePicker.setSelection(ids);
      $('module-btn-label').textContent = global.ModulePicker.label();
      state.moduleIds = ids;
      closeModals();
      onScopeChange();
    });

    $('btn-clear-filter').addEventListener('click', function () {
      clearFilterChip();
      refreshPaths();
      global.Graph.clearHighlight();
      if (state.selected.length === 1) {
        V.renderNodeDetail(E.nodeSummary(state.store, state.selected[0], scopeOpts()), 'target');
      }
    });

    /**
     * 画布高亮的优先级：点击选中的路径 > 悬停预览 > 全部路径。
     * 鼠标移出列表或某一行时，回落到「已选中」或「全部」，不能直接清空。
     */
    const list = $('path-list');

    /** 恢复到当前状态：选中的路径 > 无高亮。选中框同步跟随。 */
    function restoreHighlight() {
      const active = list.querySelector('.path-row-item.is-active');
      if (active) {
        const chain = active.dataset.gaChain.split(',').map(Number);
        global.Graph.highlightPath(chain);
        global.Graph.selectNodes(chain);
      } else {
        global.Graph.clearHighlight();
        if (state.focusedGa != null) global.Graph.selectNode(state.focusedGa);
        else global.Graph.clearSelection();
      }
    }

    list.addEventListener('mouseover', function (e) {
      const row = e.target.closest('.path-row-item');
      if (!row) return;
      list.querySelectorAll('.is-hover').forEach(function (el) { el.classList.remove('is-hover'); });
      row.classList.add('is-hover');
      // 悬停只是预览，已选中的那条不因悬停别的行而被覆盖掉选中态
      global.Graph.highlightPath(row.dataset.gaChain.split(',').map(Number));
    });
    list.addEventListener('mouseleave', function () {
      list.querySelectorAll('.is-hover').forEach(function (el) { el.classList.remove('is-hover'); });
      restoreHighlight();
    });
    /**
     * 路径行点击是切换语义：
     *   第一次点 → 画布只显示这一条路径，右栏显示这条路径
     *   再点同一条 → 取消，回到全部路径
     */
    list.addEventListener('click', function (e) {
      const row = e.target.closest('.path-row-item');
      if (!row) return;
      if (e.target.closest('[data-act="copy-path"]')) {
        V.copy(row.dataset.chain.split(' ').join(' → '));
        return;
      }
      const occId = Number(row.dataset.occ);
      const chain = row.dataset.gaChain.split(',').map(Number);
      const same = state.focusedPath && state.focusedPath.occurrenceId === occId;

      list.querySelectorAll('.is-active').forEach(function (el) { el.classList.remove('is-active'); });
      list.querySelectorAll('.is-hover').forEach(function (el) { el.classList.remove('is-hover'); });

      if (same) {
        state.focusedPath = null;
        restoreHighlight();
        if (state.selected.length === 1) {
          // 回到「选中依赖」的状态：只保留目标节点的框
          global.Graph.selectNode(state.selected[0]);
          V.renderNodeDetail(E.nodeSummary(state.store, state.selected[0], scopeOpts()), 'target');
        } else {
          global.Graph.clearSelection();
          V.clearDetail();
        }
        return;
      }

      row.classList.add('is-active');
      const path = lastPaths.paths[Number(row.dataset.idx)];
      state.focusedPath = Object.assign({ index: Number(row.dataset.idx) }, path);
      V.renderPathDetail(state.focusedPath);
      global.Graph.highlightPath(chain);
      // 这条路径经过的每个节点都加选中框，扫视时能看出整条链
      global.Graph.selectNodes(chain);
    });

    /** 恢复「所有选中目标的全部路径」高亮。 */
    function highlightAllPaths() {
      if (!state.selected.length) { global.Graph.clearHighlight(); return; }
      const all = E.elementsThrough(state.store, state.selected[0], state.selected, scopeOpts());
      global.Graph.highlightElements(all.nodes, all.edges);
    }
    list.addEventListener('keydown', function (e) {
      const row = e.target.closest('.path-row-item');
      if (!row) return;
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); row.click(); }
    });

    // 弹窗内的「显示更多」独立绑定（不在 #detail-body 内）
    $('vexp-body').addEventListener('click', function (e) {
      const more = e.target.closest('[data-act="more"]');
      if (more) toggleMore(more);
    });

    $('mask').addEventListener('click', closeModals);
    document.querySelectorAll('[data-close]').forEach(function (b) {
      b.addEventListener('click', function (e) { e.preventDefault(); closeModals(); });
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !$('mask').hidden) closeModals();
    });
    $('btn-copy-cmd').addEventListener('click', function () {
      V.copy(document.querySelector('.scan-cmd').textContent);
    });

    // 鼠标进入画布即收起搜索联想，避免挡住图
    $('cy').addEventListener('mouseenter', function () { V.hideSuggest(); });
    // 窗口尺寸变化后左栏宽度可能变，旧位置可能重新压住结果列表
    global.addEventListener('resize', function () { V.hideSuggest(); });

    global.Graph.onNodeTap(onNodeTap);

    // 点击工作区其他位置时收起联想。结果栏除外：mousedown 就收起会让结果
    // 列表的让位空间消失、结果行在鼠标抬起前整体上移，点击落到别的行上——
    // 看起来就是「点了没反应」。结果栏内的收起交给 click 处理，此时选中已经生效。
    document.addEventListener('mousedown', function (e) {
      if ($('suggest-pop').hidden) return;
      if (e.target.closest('#searchbox') || e.target.closest('#suggest-pop')) return;
      if (e.target.closest('#result-list')) return;
      V.hideSuggest();
    });
  }

  function renderConflicts() {
    // 「版本冲突」本身就是在问多版本，不能叠加「只看生效」——那会把多数 GA 塌成单版本
    const data = E.conflicts(state.store, state.search, { moduleIds: state.moduleIds });
    state.conflictData = data;
    V.renderConflicts(data, { replacedOnly: state.replacedOnly });
  }

  /** 「显示更多 / 更少」：展开同一区块内隐藏的条目。 */
  function toggleMore(btn) {
    const block = btn.closest('.d-block') || btn.parentElement;
    if (!block) return;
    const items = block.querySelectorAll('.is-extra');
    const expanding = btn.getAttribute('aria-expanded') !== 'true';
    items.forEach(function (li) { li.hidden = !expanding; });
    btn.setAttribute('aria-expanded', String(expanding));
    btn.textContent = expanding ? '显示更少' : '显示更多';
  }

  /** 打开「为什么这个版本没生效」弹窗。 */
  /**
   * 点「POM 路径」：先拉磁盘上的原文，再弹窗。
   * 读不到就 toast —— 静默无反应是最糟的交互。
   */
  function openPomDialog(moduleId) {
    const mod = state.store.modules[moduleId];
    const key = currentProjectKey();
    if (!key) { V.toast('项目未就绪'); return; }
    if (mod.pomText) { V.renderPomDialog(mod); openModal('dlg-pom'); return; }
    V.toast('正在读取 ' + mod.artifactId + '/pom.xml …');
    fetch('/api/pom?projectId=' + encodeURIComponent(key) + '&moduleId=' + moduleId)
      .then(function (r) {
        if (!r.ok) {
          // 服务端的 message 比状态码有用得多（"扫描结果已失效，请重新扫描"
          // 对比 "HTTP 404"），尽量把它带出来。
          return r.json().catch(function () { return {}; }).then(function (body) {
            throw new Error(body.message || ('读不到 POM（HTTP ' + r.status + '）'));
          });
        }
        return r.json();
      })
      .then(function (d) {
        mod.pomText = d.pomText;     // 回填：同一个模块再点就不用再请求
        mod.pomPath = d.pomPath;
        V.renderPomDialog(mod);
        openModal('dlg-pom');
      })
      .catch(function (err) { V.toast(err.message); });
  }

  function openVersionExplain(gaId, version) {
    const data = E.explainVersion(state.store, gaId, version, { moduleIds: state.moduleIds });
    if (!data) { V.toast('没有找到该版本的数据'); return; }
    V.renderVersionExplain(data);
    openModal('dlg-vexp');
  }

  /** 冲突表里点「N 个模块在用」：列出使用该版本的模块。 */
  function openConflictVersionModules(key) {
    const parts = key.split('|');
    const gi = Number(parts[0]);
    const version = parts.slice(1).join('|');
    const c = state.conflictData.conflicts.filter(function (x) { return x.gaId === gi; })[0];
    if (!c) return;
    const v = c.versions.filter(function (x) { return x.version === version; })[0];
    if (!v) return;

    const list = (v.winModuleIds || v.moduleIds || []).map(function (mid) {
      return { artifactId: state.store.modules[mid].artifactId, paths: 0 };
    });
    $('modules-title').textContent = c.ga + ' ' + version + ' 被 ' + list.length + ' 个模块使用';
    $('modules-body').innerHTML = '<ul class="mbreak-list">' + list.map(function (m) {
      return '<li><span class="mb-name">' + V.esc(m.artifactId) + '</span></li>';
    }).join('') + '</ul>';
    $('modules-note').textContent = '版本 ' + version + ' · ' + list.length + ' 个模块';
    $('btn-only-these').hidden = true;
    openModal('dlg-modules');
  }

  function setPane(pane) {
    state.pane = pane;
    $('pane-paths').hidden = pane !== 'paths';
    $('pane-conflicts').hidden = pane !== 'conflicts';
    $('drawer').hidden = pane !== 'paths';
    $('tab-paths').setAttribute('aria-selected', String(pane === 'paths'));
    $('tab-conflicts').setAttribute('aria-selected', String(pane === 'conflicts'));
    if (pane === 'conflicts') {
      renderConflicts();
    }
    if (pane === 'paths') setTimeout(function () { global.Graph.resize(); }, 0);
  }

  function setDrawer(collapsed, force) {
    const drawer = $('drawer');
    const btn = $('btn-drawer');
    if (force) drawer.classList.toggle('collapsed', collapsed);
    else drawer.classList.toggle('collapsed');
    btn.setAttribute('aria-expanded', String(!drawer.classList.contains('collapsed')));
  }

  /* ================= 启动 ================= */
  function boot() {
    initTheme();
    global.Graph.init($('cy'));
    bindSelectView();
    bindExplore();
    bindSettings();
    initResizers();
    showView('select');
    // 最近项目与高级设置都来自后端；拿不到也不阻塞（首次启动本来就是空的）
    syncRecent();
    loadAdvanced();
    const owner = navigation.capture();
    global.Scan.refreshProjects().catch(function () { }).then(owner.guard(deepLink));
  }

  function deepLink() {
    const params = new URLSearchParams(location.search);
    const theme = params.get('theme');
    if (theme === 'light' || theme === 'dark') applyTheme(theme, false);
    const key = params.get('project');
    if (!key) return;
    if (global.Scan.PROJECTS[key]) openProject(key, null, params);
    else openPathAndGo(key, params);
  }

  function applyLinkSearch(params) {
    const q = params.get('q');
    if (!q) return;
    $('search-input').value = q;
    doSearch();
    const ga = params.get('sel');
    if (ga && lastSearch) {
      const hit = lastSearch.results.filter(function (r) { return r.ga === ga; })[0];
      if (hit) toggleGa(hit.gaId, false);
    }
  }

  document.addEventListener('DOMContentLoaded', boot);
})(window);
