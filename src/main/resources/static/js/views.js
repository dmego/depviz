/* depviz · DOM 渲染
 * 右栏只放聚合事实（版本 / 深度 / 路径数 / 模块数），不出现路径与注解原文。
 * 路径是底部列表的专属职责；右栏仅在点选单条路径时展示缩进链。
 */
(function (global) {
  'use strict';

  const $ = function (id) { return document.getElementById(id); };
  const esc = function (s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  };
  const num = function (n) { return Number(n).toLocaleString('en-US'); };

  function debounce(fn, ms) {
    let t = 0;
    return function () {
      const args = arguments, self = this;
      clearTimeout(t);
      t = setTimeout(function () { fn.apply(self, args); }, ms);
    };
  }

  let toastTimer = 0;
  function toast(msg) {
    const el = $('toast');
    el.textContent = msg;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, 1800);
  }

  function copy(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () { toast('已复制'); }, function () { toast('复制失败'); });
    } else {
      const ta = document.createElement('textarea');
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); toast('已复制'); } catch (e) { toast('复制失败'); }
      ta.remove();
    }
  }

  /* ---------- 结果列表 ---------- */

  /**
   * 左栏依赖树。结构是「依赖的包含关系」，不是 groupId 分组。
   * 两个视图：
   *   flat —— 依赖的扁平列表（仍然只显示名字）
   *   tree —— 完整依赖树，按深度展开，可折叠
   * 行数可达上万，用虚拟滚动。
   */
  const ROW_H = 24;
  const OVERSCAN = 12;
  let scrollBound = false;

  function renderResults(store, res, selectedGaIds, opts) {
    const list = $('result-list');
    $('results-empty').hidden = !!res.results.length;
    if (!res.results.length) {
      list.innerHTML = res.query
        ? '<li class="results-empty-msg">没找到 <code>' + esc(res.query) + '</code>。' +
          '<br><span class="muted">试试更短的片段，或检查是否被模块 / profile 排除。</span></li>'
        : '';
      return;
    }

    const rows = opts.treeView
      ? buildTreeRows(store, res, opts)
      : buildFlatRows(store, res, opts);

    virtualRender(list, rows, selectedGaIds, opts);
  }

  /**
   * 扁平列表。
   *
   * <p>{@code res.results} 已经按搜索、模块、生效、scope 过滤过一遍（过滤发生在
   * {@code Engine.search} → {@code summarize} → {@code inScope} 里），所以这里只管
   * 把每个 GA 折成一行：取一个代表版本。scope 过滤<b>不在这里做</b> —— 在引擎里做是
   * 有意的，否则树视图与平铺视图会用两套不同的过滤逻辑，同一个开关在两种视图下
   * 给出不同结果（这就是「隐藏 test 在列表里没效果」的老 bug）。
   */
  function buildFlatRows(store, res) {
    return res.results.map(function (r) {
      // 平铺列表用生效版本；多版本时取模块数最多的那个
      const vs = r.versions.slice().sort(function (a, b) {
        return (b.effective > 0 ? 1 : 0) - (a.effective > 0 ? 1 : 0) || b.modules - a.modules;
      });
      const v = vs[0];
      return {
        kind: 'dep',
        gaId: r.gaId,
        artifactId: r.artifactId,
        groupId: r.groupId,
        version: v ? v.version : '',
        scope: r.scopes && r.scopes.length ? r.scopes[0] : '',
        minDepth: r.minDepth,
        moduleCount: r.moduleCount,
        depth: 0
      };
    });
  }

  /** 深度优先展开整棵树，只收可见节点。 */
  function buildTreeRows(store, res, opts) {
    const vis = global.Engine.treeVisible(store, res.query, opts);
    const collapsed = global.__treeCollapsed || (global.__treeCollapsed = new Set());
    const rows = [];

    function walk(oid) {
      const o = store.occ[oid];
      const g = store.gas[o.ga];
      const kids = global.Engine.childrenOf(store, oid);
      let shown = 0;
      const shownKids = [];
      for (let i = 0; i < kids.length; i++) {
        if (vis[kids[i]]) { shown++; shownKids.push(kids[i]); }
      }
      rows.push({
        kind: 'occ',
        occId: oid,
        gaId: o.ga,
        moduleId: o.m,
        artifactId: g.a,
        groupId: g.g,
        version: g.v[o.v],
        scope: o.s || '',
        effective: !!o.e,
        depth: store.depth[oid],
        childCount: shown,
        hasKids: shown > 0,
        collapsed: collapsed.has(oid)
      });
      if (collapsed.has(oid)) return;
      for (let i = 0; i < shownKids.length; i++) walk(shownKids[i]);
    }

    for (let i = 0; i < store.roots.length; i++) {
      if (vis[store.roots[i]]) walk(store.roots[i]);
    }
    return rows;
  }

  /**
   * 虚拟滚动：只渲染可视区 ± overscan 的行。
   * rows 变化时重建，滚动时只换内容。
   */
  function virtualRender(container, rows, selectedGaIds, opts) {
    const total = rows.length;
    // 每次重建都从 -1 开始，保证首帧一定重绘（旧实现用 0/0 初始化，空列表或
    // 行数变化时 start===first && end===last 会直接跳过，界面看起来没反应）
    let first = -1;
    let last = -1;
    let pad = 0;

    function readPad() {
      pad = parseFloat(getComputedStyle(container).getPropertyValue('--suggest-pad')) || 0;
    }

    container.classList.add('vlist');
    container.innerHTML =
      '<div class="vlist-spacer" style="height:' + (total * ROW_H) + 'px"></div>' +
      '<div class="vlist-window"></div>';
    const win = container.lastChild;

    function paint() {
      const viewH = container.clientHeight || 600;
      const scrollTop = container.scrollTop;
      const start = Math.max(0, Math.floor(Math.max(0, scrollTop - pad) / ROW_H) - OVERSCAN);
      const end = Math.min(total, Math.ceil(Math.max(0, scrollTop - pad + viewH) / ROW_H) + OVERSCAN);
      if (start === first && end === last) return;
      first = start;
      last = end;

      let html = '';
      for (let i = start; i < end; i++) html += rowHtml(rows[i], i, selectedGaIds, opts);
      win.innerHTML = html;
      win.style.transform = 'translateY(' + (start * ROW_H) + 'px)';
    }

    container.onscroll = function () { requestAnimationFrame(paint); };
    container.__vlistRepaint = function () {
      readPad();
      first = -1; last = -1;
      paint();
    };
    readPad();
    paint();
    // 容器尺寸可能还未定型（首次进入 / 面板宽度变化），下一帧再补一次
    requestAnimationFrame(function () {
      readPad();
      first = -1; last = -1;
      paint();
    });
  }

  /* ---------- 搜索联想 ---------- */

  /** 用 <mark> 标出建议项里与输入匹配的子串。 */
  function markQuery(text, q) {
    const lower = String(text).toLowerCase();
    const needle = String(q).toLowerCase();
    if (!needle) return esc(text);
    let out = '';
    let pos = 0;
    while (true) {
      const idx = lower.indexOf(needle, pos);
      if (idx < 0) break;
      out += esc(text.slice(pos, idx)) + '<mark>' + esc(text.slice(idx, idx + needle.length)) + '</mark>';
      pos = idx + needle.length;
    }
    return out + esc(text.slice(pos));
  }

  /**
   * 联想面板浮在搜索框正下方，不改变顶栏和画布布局。
   * 只把左栏结果列表的内容向下让开——面板盖住的是结果栏顶部，
   * 让开后才能点到下面的结果行；画布和详情栏保持原位。
   */
  function placeSuggest(pop) {
    const list = $('result-list');
    pop.style.left = '';
    pop.style.width = '';
    pop.hidden = false;
    if (!list) return true;

    const popRect = pop.getBoundingClientRect();
    const listRect = list.getBoundingClientRect();
    const pad = Math.max(0, Math.ceil(popRect.bottom + 4 - listRect.top));
    // 列表至少留一行在面板下方；连这一行都放不下时干脆收起面板，
    // 否则短窗口里首行会被推到可视区外，面板却仍盖住整个列表。
    const maxPad = Math.max(0, list.clientHeight - ROW_H);
    if (pad > maxPad) return false;

    // 虚拟列表的绝对定位窗口不响应容器 padding，用自定义属性让窗口和
    // 占位块一起下移；普通列表退回 padding-top。
    if (list.classList.contains('vlist')) {
      list.scrollTop = 0;
      list.style.setProperty('--suggest-pad', pad + 'px');
      if (list.__vlistRepaint) list.__vlistRepaint();
    } else {
      list.style.paddingTop = pad + 'px';
    }
    return true;
  }

  /**
   * 下拉建议：第一项是用户原词（直接搜索），后面是匹配到的 artifactId。
   */
  function renderSuggest(store, query, results, onPick) {
    const pop = $('suggest-pop');
    const q = query.trim();
    if (!q) { hideSuggest(); return; }

    // 去重并排除与用户输入完全相同的项；带上 gaId，点选时直接选中该坐标，
    // 不再靠「第一条结果恰好同名」来猜。
    const seen = new Set();
    const items = [];
    results.slice(0, 12).forEach(function (r) {
      const n = r.artifactId;
      if (seen.has(n) || n === q) return;
      seen.add(n);
      items.push({ name: n, gaId: r.gaId });
    });

    if (!items.length && !results.length) { hideSuggest(); return; }

    let html = '<button class="sg-item is-raw" data-raw="1" role="option">' +
      '<svg class="icon" aria-hidden="true"><use href="#i-search"/></svg>' +
      '<span class="sg-text">' + esc(q) + '</span>' +
      '<span class="sg-hint">搜索全部匹配</span></button>';
    items.forEach(function (item) {
      html += '<button class="sg-item" data-name="' + esc(item.name) + '" data-ga="' + item.gaId + '" role="option">' +
        '<span class="sg-text">' + markQuery(item.name, q) + '</span></button>';
    });
    pop.innerHTML = html;
    if (!placeSuggest(pop)) {
      hideSuggest();
      return;
    }
    $('searchbox').setAttribute('aria-expanded', 'true');

    // 防止按下鼠标时输入框失焦、blur 的延时收起抢在 click 前面。
    pop.onmousedown = function (e) {
      if (e.target.closest('.sg-item')) e.preventDefault();
    };
    pop.onclick = function (e) {
      const btn = e.target.closest('.sg-item');
      if (!btn) return;
      hideSuggest();
      if (btn.dataset.raw) {
        onPick(q, null);
      } else {
        $('search-input').value = btn.dataset.name;
        onPick(btn.dataset.name, btn.dataset.ga ? Number(btn.dataset.ga) : null);
      }
    };
  }

  function hideSuggest() {
    const pop = $('suggest-pop');
    if (!pop) return;
    const list = $('result-list');
    const hasPad = list && (list.style.getPropertyValue('--suggest-pad') || list.style.paddingTop);
    // 已经收起且没有让位空间时直接返回，避免 resize / 画布 mouseenter /
    // 每次普通行点击都无意义地重建整个虚拟列表。
    if (pop.hidden && !hasPad) return;
    pop.hidden = true;
    if (list) {
      list.style.removeProperty('--suggest-pad');
      list.style.removeProperty('padding-top');
      if (list.__vlistRepaint) list.__vlistRepaint();
    }
    $('searchbox').setAttribute('aria-expanded', 'false');
  }

  /**
   * 一行显示成 `groupId:artifactId:version [scope]`。
   * groupId 始终显示；版本号始终显示，多版本 GA 的版本号标红。
   */
  function rowHtml(r, index, selectedGaIds, opts) {
    const sel = selectedGaIds.indexOf(r.gaId) >= 0;
    // 冲突判定按「模块内是否多版本」，树行有 moduleId，平铺行退化为全局
    const conflicted = r.moduleId != null
      ? !!(opts.conflictGas && opts.conflictGas.has(r.moduleId + '|' + r.gaId))
      : !!(opts.globalConflictGas && opts.globalConflictGas.has(r.gaId));
    const gid = opts.showGroupId
      ? '<span class="dep-gid-inline">' + esc(r.groupId) + ':</span>' : '';
    const label = gid + '<span class="dep-art">' + esc(r.artifactId) + '</span>' +
      ':<span class="dep-ver' + (conflicted ? ' is-conflict' : '') + '"' +
      (conflicted ? ' title="同坐标存在多个版本"' : '') + '>' + esc(r.version) + '</span>';
    const scopeHtml = opts.showScope && r.scope
      ? '<span class="dep-scope sc-' + esc(r.scope) + '">' + esc(r.scope) + '</span>' : '';

    if (r.kind === 'occ') {
      const twisty = r.hasKids
        ? '<svg class="icon chev" aria-hidden="true" style="transform:rotate(' + (r.collapsed ? '-90deg' : '0deg') + ')"><use href="#i-chevron"/></svg>'
        : '<span class="twisty-space"></span>';
      return '<div class="dep-row occ-row' + (sel ? ' is-selected' : '') +
        (r.effective ? '' : ' is-omit') + '" data-idx="' + index + '" data-occ="' + r.occId +
        '" data-ga="' + r.gaId + '" style="padding-left:' + (4 + r.depth * 11) + 'px">' +
        '<button class="twisty" data-twisty="' + r.occId + '" tabindex="-1">' + twisty + '</button>' +
        '<span class="dep-label">' + label + '</span>' + scopeHtml +
        '</div>';
    }
    return '<div class="dep-row' + (sel ? ' is-selected' : '') + '" data-idx="' + index +
      '" data-ga="' + r.gaId + '" style="padding-left:12px">' +
      '<span class="dep-label">' + label + '</span>' + scopeHtml +
      '</div>';
  }

  /* ---------- 右栏详情 ---------- */

  const ROLE_LABEL = { target: '搜索结果', middle: '中间节点', root: '模块根' };

  function detailHeader(sum, role) {
    let html = '<div class="d-head">';
    html += '<div class="d-ga">' + esc(sum.ga) + '</div>';
    html += '<div class="d-sub"><span class="pill role">' + ROLE_LABEL[role] + '</span>';
    if (sum.conflicted) html += '<span class="pill warn">' + sum.versions.length + ' 个版本</span>';
    html += '</div></div>';
    return html;
  }

  function versionsBlock(sum) {
    let html = '<section class="d-block"><h3>版本</h3><dl class="d-kv">';
    sum.versions.forEach(function (v) {
      const live = v.effective > 0;
      html += '<dt>' + (live ? '<span class="dot-live"></span>' : '') + esc(v.version) + '</dt>';
      html += '<dd>' + (v.modules > 0 ? v.modules + ' 个模块' : '—') +
        '<span class="sep">·</span>深度 ' + v.minDepth + '</dd>';
    });
    html += '</dl></section>';
    return html;
  }

  /**
   * 节点详情（左栏点击 / 画布点击共用）。
   * 结构：身份 → 规模 → 上游 → 下游。
   *   - 深度拆成「最浅 / 最深」两行，点数字可高亮对应路径
   *   - 模块数点击弹窗
   *   - 上下游各列前 3 条，超出折叠；名称可点击跳到画布
   */
  const NEIGHBOR_LIMIT = 3;

  function renderNodeDetail(sum, role) {
    const body = $('detail-body');

    // ---- 头部：GroupId / ArtifactId 两行，标签与「版本号」同款加粗 ----
    let html = '<div class="d-head"><dl class="d-kv d-kv-head">';
    html += '<dt>GroupId</dt><dd class="mono">' + esc(sum.groupId) + '</dd>';
    html += '<dt>ArtifactId</dt><dd class="mono d-art">' + esc(sum.artifactId) + '</dd>';
    html += '</dl></div>';

    // ---- 版本号：直接列版本，主版本在最前；多版本时标题旁加标记 ----
    html += '<section class="d-block"><h3>版本号' +
      (sum.conflicted ? '<span class="d-badge-warn">存在多版本</span>' : '') + '</h3>';
    html += versionList(sum);
    html += '</section>';

    // ---- 规模：可达路径与引入模块并排、最浅与最深深度并排 ----
    const sh = sum.shallowest, dp = sum.deepest;
    html += '<section class="d-block"><div class="d-pairs">';
    html += pair('可达路径', num(sum.occurrences) + ' 条',
                 '引入模块', '<button class="d-num" data-act="modules" title="查看模块分布">' +
                   sum.moduleCount + ' 个</button>');
    if (sh) {
      html += pair('最浅深度', depthBtn(sh, 'shallow'),
                   '最深深度', dp ? depthBtn(dp, 'deep') : '—');
    }
    html += '</div></section>';

    html += neighborBlock('上游依赖', sum.upstream, 'upstream');
    html += neighborBlock('下游依赖', sum.downstream, 'downstream', sum.isRoot);

    html += '<div class="d-actions">';
    html += iconBtn('search', 'i-search', '设为搜索目标');
    html += iconBtn('copy-gav', 'i-copy', '复制 GAV');
    html += iconBtn('filter-through', 'i-filter', '只看经过它的路径');
    html += '</div>';
    body.innerHTML = html;
  }

  /** 操作行里的图标按钮，靠 title 提供文字。 */
  function iconBtn(act, icon, label) {
    return '<button class="act-btn" data-act="' + act + '" title="' + label + '" aria-label="' + label + '">' +
      '<svg class="icon" aria-hidden="true"><use href="#' + icon + '"/></svg></button>';
  }

  /** 并排两列：左对、右对。 */
  function pair(l1, v1, l2, v2) {
    return '<div class="d-pair">' +
      '<div class="d-cell"><span class="d-lbl">' + l1 + '</span><span class="d-val">' + v1 + '</span></div>' +
      '<div class="d-cell"><span class="d-lbl">' + l2 + '</span><span class="d-val">' + v2 + '</span></div>' +
      '</div>';
  }

  /**
   * 版本列表（单版本也走这里，保证结构一致）。
   * 多模块项目里「生效」按模块判定，不同模块可能各用一个版本，
   * 所以标「主版本 / 少数」而不是「生效」。
   */
  function versionList(sum) {
    // 只有真正生效的版本参与主/次判定；全被剪的版本单独标「未生效」
    const liveVersions = sum.versions.filter(function (v) { return v.effective > 0; });
    const maxMods = liveVersions.length
      ? Math.max.apply(null, liveVersions.map(function (v) { return v.modules; }))
      : 0;

    let html = '<ul class="d-verlist">';
    sum.versions.forEach(function (v) {
      const dead = v.effective === 0;
      const major = !dead && v.modules === maxMods;
      const cls = dead ? 'is-dead' : major ? 'is-live' : '';
      const modCell = dead
        ? '<button class="d-num dv-mods is-dead" data-act="vexp" data-version="' + esc(v.version) + '" ' +
            'title="查看为什么没有生效">未生效</button>'
        : '<button class="d-num dv-mods" data-act="version-modules" data-version="' + esc(v.version) + '" ' +
            'title="查看该版本覆盖的模块">' + v.modules + ' 个模块</button>';
      let tag = '<span></span>';
      if (sum.conflicted && !dead) {
        tag = major ? '<span class="dv-tag">主版本</span>' : '<span class="dv-tag is-minor">少数</span>';
      }
      html += '<li class="' + cls + '">' +
        '<span class="dv-name">' + esc(v.version) + '</span>' +
        modCell + tag +
        '</li>';
    });
    html += '</ul>';
    return html;
  }

  function depthBtn(d, kind) {
    if (!d) return '—';
    return '<button class="d-num" data-act="depth" data-kind="' + kind + '" title="高亮对应路径">' +
      d.depth + '</button>' +
      (d.occIds.length > 1 ? '<span class="d-of">（' + d.occIds.length + ' 条）</span>' : '');
  }

  /** 上游 / 下游清单：前 3 条 + 折叠。 */
  function neighborBlock(title, list, key, isRoot) {
    let html = '<section class="d-block"><h3>' + title;
    if (list.length) html += '<span class="d-count">' + list.length + ' 个</span>';
    html += '</h3>';

    if (!list.length) {
      html += '<p class="d-empty">' + (key === 'upstream' && isRoot
        ? '这是项目模块本身，没有上级依赖'
        : key === 'upstream' ? '没有已知的引入者'
        : '这是一个叶子依赖，没有下级依赖') + '</p>';
      html += '</section>';
      return html;
    }

    // 全部条目放进同一个列表；超出的初始隐藏。这样展开/收起只切换 li 的可见性，
    // 按钮始终位于列表下方，不会插到条目中间造成割裂。
    const hasMore = list.length > NEIGHBOR_LIMIT;
    // 上游的数字是「多少条路径经由它进来」，下游是「在多少条路径上带出了它」
    const isUp = key === 'upstream';
    html += '<ul class="d-nb" data-key="' + key + '">';
    list.forEach(function (n, i) {
      const extra = i >= NEIGHBOR_LIMIT;
      const unit = isUp ? ' 条路径' : ' 次';
      const tip = isUp
        ? n.paths + ' 条路径经由 ' + n.artifactId + ' 到达当前依赖'
        : '当前依赖在 ' + n.paths + ' 条路径上带出了 ' + n.artifactId;
      // 只有次数 > 1 时才显示，避免整列都是「1」的噪音
      const numHtml = n.paths > 1
        ? '<span class="d-nb-n" title="' + esc(tip) + '">' + n.paths + unit + '</span>'
        : '';
      html += '<li' + (extra ? ' class="is-extra" hidden' : '') + '>' +
        '<button class="d-nb-name" data-ga="' + n.gaId + '" title="' + esc(n.ga) + '">' +
        esc(n.artifactId) + '</button>' + numHtml + '</li>';
    });
    html += '</ul>';
    if (hasMore) {
      html += '<button class="d-more" data-act="more" aria-expanded="false">显示更多</button>';
    }
    html += '</section>';
    return html;
  }

  /**
   * 模块根详情。结构与普通依赖对称：
   *   头部 → 模块信息 → 直接依赖（下游）→ 被引用（上游）→ 操作行
   * 模块根的上游恒为空，所以「被引用」是它独有的维度。
   */
  function renderModuleDetail(mod) {
    const body = $('detail-body');

    // ---- 头部 ----
    let html = '<div class="d-head"><dl class="d-kv d-kv-head">';
    html += '<dt>GroupId</dt><dd class="mono">' + esc(mod.groupId) + '</dd>';
    html += '<dt>ArtifactId</dt><dd class="mono d-art">' + esc(mod.artifactId) + '</dd>';
    html += '</dl></div>';

    // ---- 模块信息 ----
    html += '<section class="d-block"><h3>模块信息</h3><dl class="d-kv d-kv-narrow">';
    html += '<dt>打包方式</dt><dd><span class="pk-tag">' + esc(mod.packaging) + '</span></dd>';
    // 右栏窄：只显示「模块目录/pom.xml」，完整相对路径放 title；弹窗标题显示完整路径
    const fullRel = (mod.relPath && mod.relPath !== '.' ? mod.relPath : mod.artifactId) + '/pom.xml';
    const shortRel = mod.artifactId + '/pom.xml';
    html += '<dt>POM 路径</dt><dd>' +
      // POM 不随图数据下发，按钮恒可点：点击时由 app.js 现读磁盘
      '<button class="d-num d-pom" data-act="pom" title="' + esc(fullRel) + '">' + esc(shortRel) + '</button>' +
      '</dd>';
    html += '<dt>声明依赖</dt><dd>' +
      (mod.declaredDeps
        ? '<button class="d-num" data-act="declared">' + mod.declaredDeps + ' 条</button>'
        : '0 条') +
      '</dd>';
    html += '</dl></section>';

    // ---- 直接依赖（= 下游） ----
    html += neighborBlock('直接依赖', mod.direct, 'downstream', true);

    // ---- 被引用（= 上游，模块独有） ----
    html += referrerBlock(mod.referrers);

    // ---- 操作行 ----
    html += '<div class="d-actions">';
    html += iconBtn('copy-gav', 'i-copy', '复制坐标');
    html += iconBtn('only-module', 'i-filter', '只看这个模块');
    html += '</div>';
    body.innerHTML = html;
  }

  /** 「被引用」清单：引用方是模块而非 GA，单独渲染。 */
  function referrerBlock(list) {
    let html = '<section class="d-block"><h3>被引用';
    if (list.length) html += '<span class="d-count">' + list.length + ' 个模块</span>';
    html += '</h3>';
    if (!list.length) {
      html += '<p class="d-empty">没有被其他模块引用（可能是独立入口）</p></section>';
      return html;
    }
    const hasMore = list.length > NEIGHBOR_LIMIT;
    html += '<ul class="d-nb">';
    list.forEach(function (r, i) {
      const extra = i >= NEIGHBOR_LIMIT;
      // 可点击：若该模块不在画布上，点击后给出提示
      html += '<li' + (extra ? ' class="is-extra" hidden' : '') + '>' +
        '<button class="d-nb-name" data-module="' + r.moduleId + '" title="' + esc(r.artifactId) + '">' +
        esc(r.artifactId) + '</button></li>';
    });
    html += '</ul>';
    if (hasMore) html += '<button class="d-more" data-act="more" aria-expanded="false">显示更多</button>';
    html += '</section>';
    return html;
  }

  /**
   * POM 弹窗：XML 高亮 + 行号。
   * 只做轻量着色（标签名 / 属性名 / 属性值 / 注释），不引第三方高亮库。
   */
  function renderPomDialog(mod) {
    $('pom-title').textContent = (mod.relPath && mod.relPath !== '.' ? mod.relPath : mod.artifactId) + '/pom.xml';
    const lines = (mod.pomText || '').split('\n');
    const html = lines.map(function (line, i) {
      return '<div class="pom-line"><span class="pom-ln">' + (i + 1) + '</span>' +
        '<span class="pom-code">' + highlightXml(line) + '</span></div>';
    }).join('');
    $('pom-body').innerHTML = '<div class="pom-view">' + html + '</div>';
    $('pom-note').textContent = lines.length + ' 行 · ' + mod.artifactId;
  }

  /**
   * 极简 XML 着色。
   * 逐行扫描：切成「标签」与「普通文本」，标签内再分标签名 / 属性名 / 属性值。
   */
  function highlightXml(line) {
    const t = line.trim();
    if (t.startsWith('<!--') || t.startsWith('-->') || t.endsWith('-->')) {
      return '<span class="x-com">' + esc(line) + '</span>';
    }

    let out = '';
    let i = 0;
    while (i < line.length) {
      const lt = line.indexOf('<', i);
      if (lt < 0) { out += esc(line.slice(i)); break; }
      out += esc(line.slice(i, lt));

      const gt = line.indexOf('>', lt);
      if (gt < 0) { out += esc(line.slice(lt)); break; }

      out += renderTag(line.slice(lt, gt + 1));
      i = gt + 1;
    }
    return out;
  }

  /** 渲染 `<...>` 或 `</...>` 或 `<?...?>`。 */
  function renderTag(raw) {
    // raw 形如 "<a b="c">" / "</a>" / "<?xml v="1"?>"
    const closing = raw.startsWith('</');
    const decl = raw.startsWith('<?');
    // 去掉 < / </ / <? 与 > / /> / ?>
    const start = (closing || decl) ? 2 : 1;
    let end = raw.length - 1;
    if (raw.endsWith('?>') || raw.endsWith('/>')) end = raw.length - 2;
    const body = raw.slice(start, end);

    // 标签名
    const m = /^[\w:.-]+/.exec(body);
    if (!m) return '<span class="x-com">' + esc(raw) + '</span>';
    const name = m[0];
    let rest = body.slice(name.length);

    // 属性：name="value" / name='value'
    let attrsHtml = '';
    const attrRe = /([\w:.-]+)(\s*=\s*)("[^"]*"|'[^']*')|([^]+?)/g;
    let mm;
    let pos = 0;
    const attrOnly = /([\w:.-]+)(\s*=\s*)("[^"]*"|'[^']*')/g;
    let lastEnd = 0;
    while ((mm = attrOnly.exec(rest)) !== null) {
      attrsHtml += esc(rest.slice(lastEnd, mm.index));
      attrsHtml += '<span class="x-attr">' + esc(mm[1]) + '</span>' + esc(mm[2]) +
        '<span class="x-val">' + esc(mm[3]) + '</span>';
      lastEnd = mm.index + mm[0].length;
    }
    attrsHtml += esc(rest.slice(lastEnd));
    void attrRe; void pos;

    const open = closing ? '&lt;/' : decl ? '&lt;?' : '&lt;';
    const close = raw.endsWith('?>') ? '?&gt;' : raw.endsWith('/>') ? '/&gt;' : '&gt;';
    return open + '<span class="x-tag">' + esc(name) + '</span>' + attrsHtml + close;
  }

  /**
   * 「为什么这个版本没生效」弹窗。
   * 左右路径由引擎保证来自同一模块，跨模块不比较深度。
   */
  function renderVersionExplain(data) {
    const d = data;
    // 标题：未生效的版本 + 版本号
    $('vexp-title').textContent = '未生效的版本 ' + d.version + ' 为什么没有生效';

    let html = '';

    // 规则说明 + 结论
    html += '<p class="vexp-conclusion"><span class="vexp-badge">' +
      (d.reason === 'conflict' ? '版本冲突' : d.reason === 'duplicate' ? '重复引入' : '未采用') +
      '</span><span>' + esc(d.conclusion) + '</span></p>';

    // 仲裁规则
    html += '<div class="vexp-rule"><strong>判定规则</strong>' +
      'Maven 对同一坐标只保留一个版本：<b>深度小者胜</b>（离项目根更近）；' +
      '深度相同则<b>先声明者胜</b>。仲裁按模块逐个进行，不同模块可以各自选不同版本。</div>';

    // 并排两条最短路径
    html += '<div class="vexp-cols">';
    html += pathColumn('未生效的版本', d.version, d.loserPath, 'is-lose');
    html += pathColumn('生效的版本', d.winnerVersion, d.winnerPath, 'is-win');
    html += '</div>';

    // 深度对比
    if (d.loserPath && d.winnerPath && d.loserPath.moduleId === d.winnerPath.moduleId) {
      const diff = d.loserPath.depth - d.winnerPath.depth;
      html += '<p class="vexp-diff">' +
        (diff > 0
          ? '<strong>深度 ' + d.loserPath.depth + '</strong> vs <strong>' + d.winnerPath.depth + '</strong>' +
            '：这个版本比 ' + esc(d.winnerVersion) + ' 深 <strong>' + diff + '</strong> 层，因此落败'
          : diff === 0
            ? '两者深度相同（' + d.loserPath.depth + '），' + esc(d.winnerVersion) + ' 先被声明，因此落败'
            : '这个版本更浅（' + d.loserPath.depth + ' vs ' + d.winnerPath.depth + '）') +
        '</p>';
    }

    // 逐模块对账
    if (d.moduleRows && d.moduleRows.length) {
      const LIMIT = 5;
      html += '<section class="d-block"><h3>逐模块对比' +
        '<span class="d-count">' + d.moduleRows.length + ' 个模块</span></h3>' +
        '<div class="vexp-tbl-head"><span>模块</span><span>本版本</span><span>对手</span><span>结果</span></div>' +
        '<ul class="vexp-tbl">';
      d.moduleRows.forEach(function (r, i) {
        const extra = i >= LIMIT;
        const verdict = r.verdict === 'lose' ? '<span class="v-lose">落败</span>'
          : r.verdict === 'win' ? '<span class="v-win">胜出</span>'
          : r.verdict === 'tie' ? '<span class="v-tie">同深度</span>'
          : r.verdict === 'missing' ? '<span class="v-only">无胜出路径</span>'
          : '<span class="v-only">独占</span>';
        html += '<li' + (extra ? ' class="is-extra" hidden' : '') + '>' +
          '<span class="vt-mod">' + esc(r.artifactId) + '</span>' +
          '<span class="vt-d">' + (r.mineDepth == null ? '—' : r.mineDepth) + '</span>' +
          '<span class="vt-d">' + (r.theirDepth == null ? '—' : r.theirDepth + ' (' + esc(r.theirVersion) + ')') + '</span>' +
          '<span class="vt-v">' + verdict + '</span></li>';
      });
      html += '</ul>';
      if (d.moduleRows.length > LIMIT) {
        html += '<button class="d-more" data-act="more" aria-expanded="false" ' +
          'data-target="vexp">显示更多</button>';
      }
      html += '</section>';
    }

    $('vexp-body').innerHTML = html;
    $('vexp-note').textContent = data.ga;
  }

  /** 弹窗里的一列路径。 */
  function pathColumn(label, version, path, cls) {
    let html = '<div class="vexp-col ' + cls + '">';
    html += '<div class="vexp-col-head">' + label + '<strong>' + esc(version) + '</strong>' +
      (path ? '<span class="vexp-depth">深度 ' + path.depth + '</span>' : '') + '</div>';
    if (!path) {
      html += '<p class="d-empty">没有找到对应路径</p>';
    } else {
      html += '<ol class="vexp-chain">';
      path.hops.forEach(function (h, i) {
        const last = i === path.hops.length - 1;
        html += '<li' + (last ? ' class="is-end"' : '') + '>' + esc(h.artifactId) + '</li>';
      });
      html += '</ol>';
      html += '<div class="vexp-mod">模块 ' + esc(path.module) + '</div>';
    }
    html += '</div>';
    return html;
  }

  /** 声明依赖弹窗：pom 里手写的 <dependency> 清单。 */
  function renderDeclaredDialog(mod) {
    $('declared-title').textContent = mod.artifactId + ' 的声明依赖';
    const list = mod.declared || [];
    $('declared-body').innerHTML = list.length
      ? '<ul class="decl-list">' + list.map(function (d) {
          const gav = [d.groupId, d.artifactId, d.version].filter(Boolean).join(':');
          return '<li><span class="decl-gav">' + esc(gav) + '</span>' +
            (d.scope ? '<span class="decl-scope">' + esc(d.scope) + '</span>' : '') +
            (d.optional === 'true' ? '<span class="decl-opt">optional</span>' : '') +
            '</li>';
        }).join('') + '</ul>'
      : '<p class="d-empty">这个模块的 pom 里没有直接声明依赖（可能全部继承自父 pom）</p>';
    $('declared-note').textContent = list.length + ' 条（仅 pom 中定义的，不含继承与传递依赖）';
  }

  /**
   * 单条路径详情（点底部路径行触发）。
   * 回答的是「这一条链路本身」，不是节点聚合信息：
   *   - 每一跳的 version / scope，可点击跳到画布节点
   *   - 为什么生效 / 为什么不生效
   *   - 该条路径自己的 Maven 注解
   */
  function renderPathDetail(path) {
    const body = $('detail-body');
    const last = path.hops[path.hops.length - 1];

    // ---- 头部：序号 + 状态 + 深度 + 模块 ----
    let html = '<div class="d-head">';
    html += '<div class="d-ga">路径 #' + (path.index + 1) + '</div>';
    html += '<div class="d-ver-line">';
    html += path.effective
      ? '<span class="pill ok">生效</span>'
      : '<span class="pill no">' + (path.omitReason === 'conflict' ? '被取代' : '重复引入') + '</span>';
    html += '<span>深度 ' + path.depth + '</span>';
    html += '<span class="mono">' + esc(path.module) + '</span>';
    html += '</div></div>';

    // ---- 引入链：每跳一行，可点击 ----
    html += '<section class="d-block"><h3>引入链<span class="d-count">' + path.hops.length + ' 跳</span></h3>';
    html += '<ol class="d-chain">';
    path.hops.forEach(function (h, i) {
      const isFirst = i === 0;
      const isLast = i === path.hops.length - 1;
      const tag = isFirst ? '<span class="c-tag">根</span>'
        : isLast ? '<span class="c-tag is-target">目标</span>' : '';
      html += '<li class="' + (isLast ? 'is-target' : '') + '">' +
        tag +
        '<button class="c-art" data-ga="' + h.gaId + '" title="' + esc(h.ga) + '">' +
          esc(h.artifactId) + '</button>' +
        '<span class="c-ver">' + esc(h.version) + '</span>' +
        (h.scope ? '<span class="c-scope">' + esc(h.scope) + '</span>' : '') +
        '</li>';
    });
    html += '</ol></section>';

    // ---- 结论：一句话说清这条路径意味着什么 ----
    html += '<section class="d-block"><h3>说明</h3><p class="d-explain">' +
      pathExplain(path) + '</p></section>';

    // ---- 底部操作行 ----
    html += '<div class="d-actions">';
    html += iconBtn('copy-path', 'i-copy', '复制这条路径');
    html += iconBtn('focus-path', 'i-focus', '在画布中聚焦这条路径');
    html += iconBtn('grep-gav', 'i-search', '用目标 GAV 搜索');
    html += '</div>';
    body.innerHTML = html;
    void last;
  }

  /** 用一句话解释这条路径为什么生效 / 为什么不生效。 */
  function pathExplain(path) {
    const last = path.hops[path.hops.length - 1];
    const root = path.hops[0];
    // 三个关键值统一用「」括起并高亮：目标 GAV、来源模块、中间依赖数量
    const gav = '<span class="ex-key">「' + esc(last.artifactId) + ' ' + esc(last.version) + '」</span>';
    const mid = path.hops.length - 2;
    const from = '由 <span class="ex-key">「' + esc(root.artifactId) + '」</span> 经 ' +
      '<span class="ex-key">「' + mid + '」</span> 个中间依赖引入';

    if (path.effective) {
      return '这条路径让项目真正用上了 ' + gav + '，' + from + '。';
    }
    if (path.omitReason === 'conflict') {
      return '这条路径也能到达 ' + gav + '，但它的版本被别的路径仲裁掉了，' + from + '，不参与最终构建。';
    }
    return '这条路径也到达 ' + gav + '，但同一个包已经从另一条更短的路径进来过（Maven 判定重复），' +
      from + '，不再展开。';
  }

  function clearDetail() {
    $('detail-body').innerHTML =
      '<p class="placeholder">点画布上的节点，或左侧的结果，查看坐标、版本与影响范围。</p>';
  }

  /* ---------- 路径列表 ---------- */

  function renderPaths(store, result, opts) {
    const list = $('path-list');
    list.innerHTML = '';
    $('drawer-sub').textContent = result.total
      ? num(result.total) + ' 条路径' + (result.truncated ? '（显示前 ' + result.paths.length + ' 条）' : '')
      : '';
    if (!result.total) {
      list.innerHTML = '<div class="results-empty-msg">当前条件下没有路径。</div>';
      return;
    }
    const frag = document.createDocumentFragment();
    result.paths.forEach(function (p, idx) {
      const row = document.createElement('div');
      row.className = 'path-row-item' + (p.effective ? '' : ' is-omitted-row');
      row.tabIndex = 0;
      row.setAttribute('role', 'button');
      row.dataset.occ = p.occurrenceId;
      row.dataset.idx = idx;
      row.dataset.chain = p.hops.map(function (h) { return h.artifactId + ':' + h.version; }).join(' ');
      row.dataset.gaChain = p.hops.map(function (h) { return h.gaId; }).join(',');

      // 单行：#序号 生效/重复引入 完整链路 深度 N
      // 首跳是引入该依赖的模块/根，是「谁引入的」这个问题的答案，必须保留；
      // 只把它弱化成灰色，让中间链路更易扫读。
      const hops = p.hops;
      const chain = hops.map(function (h, i) {
        const last = i === hops.length - 1;
        const cls = last ? 'self' : (i === 0 ? 'root' : '');
        return '<span class="' + cls + '">' + esc(h.artifactId) + '</span>';
      }).join('<span class="sep">→</span>');

      const stateCls = p.effective ? 'yes' : 'no';
      const stateText = p.effective ? '生效'
        : (p.omitReason === 'conflict' ? '被取代' : '重复引入');

      row.innerHTML =
        '<span class="p-idx">#' + (idx + 1) + '</span>' +
        '<span class="p-eff ' + stateCls + '"><i></i>' + stateText + '</span>' +
        '<span class="p-chain" title="' + esc(p.module) + '">' + chain + '</span>' +
        '<span class="p-depth">深度 ' + p.depth + '</span>' +
        '<button class="icon-btn p-copy" aria-label="复制这条路径" data-act="copy-path" title="复制这条路径">' +
        '<svg class="icon" aria-hidden="true"><use href="#i-copy"/></svg></button>';
      frag.appendChild(row);
    });
    list.appendChild(frag);
  }

  /* ---------- 冲突表 ---------- */

  /**
   * 版本表。
   * 两种情况分开表达，避免左右两列列同一个集合：
   *   - 各模块不同（split）：一行列全部版本 + 各自覆盖的模块数
   *   - 唯一胜出：只标出胜出版本，其余版本列出被哪些模块引入却没生效
   */
  function renderConflicts(data, opts) {
    const body = $('conflict-body');
    const split = data.conflicts.filter(function (c) { return c.splitWinner; }).length;
    let rows = data.conflicts;
    if (opts.replacedOnly) rows = rows.filter(function (c) { return c.losers.length > 0; });

    $('conflict-count').textContent = data.total;
    $('conflict-sub').textContent = data.total
      ? '（' + split + ' 个各模块自用一套 · ' + (data.total - split) + ' 个单一版本胜出）' +
        (rows.length !== data.total ? ' · 当前过滤后 ' + rows.length + ' 个' : '')
      : '';

    if (!rows.length) {
      body.innerHTML = '<tr><td colspan="3" class="c-muted">没有符合条件的版本差异。</td></tr>';
      return;
    }

    body.innerHTML = rows.map(function (c) {
      const tag = c.splitWinner
        ? '<span class="c-tag">各模块不同</span>'
        : '<span class="c-tag is-replaced">版本被取代</span>';

      // 两列：版本号 | 使用情况。使用情况列固定宽度，保证两种文案左对齐
      const versions = c.versions.slice().sort(function (a, b) {
        return b.moduleCount - a.moduleCount || b.occurrences - a.occurrences;
      }).map(function (v) {
        const live = v.winModules > 0;
        const cls = live ? 'is-win' : 'is-lose';
        const note = live
          ? '<button class="cv-n cv-link" data-cver="' + esc(c.gaId + '|' + v.version) + '" ' +
            'title="查看使用该版本的模块">' + v.winModules + ' 个模块在用</button>'
          : '<button class="cv-n cv-link cv-dead" data-vexp="' + esc(v.version) + '" ' +
            'data-ga="' + c.gaId + '" title="查看为什么没有生效">仅被引入、未生效</button>';
        return '<span class="cv ' + cls + '">' +
          '<span class="cv-v">' + esc(v.version) + '</span>' +
          note +
          '</span>';
      }).join('');

      return '<tr>' +
        '<td class="c-ga"><button class="c-link" data-ga="' + c.gaId + '">' + esc(c.ga) + '</button>' + tag + '</td>' +
        '<td class="c-vers" colspan="2">' + versions + '</td>' +
        '</tr>';
    }).join('');
  }

  /* ---------- 元数据条 ---------- */

  /** 堆上限的可读写法。用户可以在设置里选「不注入」，那时 xmx 是空串。 */
  function xmxLabel(xmx) {
    return xmx ? '-Xmx' + xmx : '未注入';
  }

  /**
   * Maven 并行度（{@code -T}）的可读写法。空串 = 串行。
   *
   * <p>不写成「1 线程」：后端把 {@code 1} 归一成了不加参数，串行就是不并行，
   * 说「1 个线程」会让人以为 Maven 真的开了线程池。
   */
  function threadsLabel(threads) {
    return threads ? '-T ' + threads : '串行';
  }

  function renderMeta(p, warning) {
    const parts = [
      'Maven ' + p.maven,
      'JDK ' + p.jdk,
      '插件 ' + p.plugin,
      'profiles: ' + (p.profiles.length ? p.profiles.join(',') : '无'),
      p.offline ? '离线' : '在线',
      xmxLabel(p.xmx),
      threadsLabel(p.threads),
      p.moduleCount + ' 模块',
      p.seconds.toFixed(1) + 's'
    ];
    $('meta-text').textContent = parts.join(' · ');
    document.querySelector('.metabar').classList.toggle('warn', !!warning);
    $('meta-warn-icon').hidden = !warning;
  }

  function renderMetaDialog(p) {
    // 优先展示后端记录的实际命令；缺少命令时按扫描参数展示。
    const cmd = p.command || ('mvn -B ' + (p.offline ? '-o ' : '') +
      (p.threads ? '-T ' + p.threads + ' ' : '') +
      'org.apache.maven.plugins:maven-dependency-plugin:' + p.plugin + ':tree ' +
      '-Dverbose -DoutputFile=<data>/tree.txt -DappendOutput=true');
    $('meta-body').innerHTML = '<div class="scan-detail"><dl>' +
      '<dt>项目</dt><dd class="mono">' + esc(p.rootDir) + '</dd>' +
      '<dt>Maven</dt><dd>' + esc(p.maven) + '</dd>' +
      '<dt>JDK</dt><dd>' + esc(p.jdk) + '</dd>' +
      '<dt>插件</dt><dd>' + esc(p.plugin) + '</dd>' +
      '<dt>profiles</dt><dd>' + (p.profiles.length ? esc(p.profiles.join(', ')) : '无') + '</dd>' +
      '<dt>网络</dt><dd>' + (p.offline ? '离线（-o）' : '在线') + '</dd>' +
      '<dt>堆上限</dt><dd>' + esc(xmxLabel(p.xmx)) + '</dd>' +
      '<dt>并行度</dt><dd>' + esc(threadsLabel(p.threads)) + '</dd>' +
      '<dt>模块</dt><dd>' + p.moduleCount + '</dd>' +
      '<dt>耗时</dt><dd>' + p.seconds.toFixed(1) + 's</dd>' +
      '<dt>解析覆盖</dt><dd>' + Math.round(p.parseCoverage * 100) + '%</dd>' +
      '<dt>图模式</dt><dd>' + esc(p.graphMode) + '</dd>' +
      '<dt>依赖解析</dt><dd>' + (p.incomplete ? '不完整' : '完整') + '</dd>' +
      '</dl><div class="scan-cmd">' + esc(cmd) + '</div>' +
      (p.warnings.length ? '<div class="scan-warn">' + p.warnings.map(esc).join('<br>') + '</div>' : '') +
      '</div>';
  }

  /* ---------- 模块分布弹窗 ---------- */

  /**
   * 模块分布弹窗。version 指定时只列该版本覆盖的模块。
   */
  function renderModuleBreakdown(sum, version) {
    const list = version
      ? sum.modules.filter(function (m) { return m.version === version; })
      : sum.modules;
    const title = version
      ? sum.artifactId + ' ' + version + ' 覆盖 ' + list.length + ' 个模块'
      : sum.artifactId + ' 出现在 ' + sum.moduleCount + ' 个模块';
    $('modules-title').textContent = title;
    // 版本视图下每个模块通常只有 1 条路径，条数没有信息量，不显示
    $('modules-body').innerHTML = list.length
      ? '<ul class="mbreak-list">' + list.map(function (m) {
          return '<li><span class="mb-name">' + esc(m.artifactId) + '</span>' +
            (version ? '' : '<span class="mb-count">' + m.paths + ' 条</span>') + '</li>';
        }).join('') + '</ul>'
      : '<p class="d-empty">没有匹配的模块</p>';
    $('modules-note').textContent = version
      ? '版本 ' + version + ' · ' + list.length + ' 个模块'
      : '共 ' + sum.moduleCount + ' 个模块 · ' + num(sum.occurrences) + ' 条可达路径';
    $('btn-only-these').hidden = !!version;   // 版本视图下不提供「只看这些模块」
  }

  /* ---------- 模块树（扫描前确认） ---------- */

  function renderModules(discover, expanded) {
    const list = $('module-list');
    const shown = expanded ? discover.modules : discover.modules.slice(0, 8);
    list.innerHTML = shown.map(function (m) {
      const parent = m.packaging === 'pom';
      return '<li class="module-row' + (parent ? ' is-parent' : '') + '">' +
        '<span class="mname" title="' + esc(m.relPath) + '">' + esc(m.artifactId) + '</span>' +
        '<span class="mpack">' + esc(m.packaging) + '</span>' +
        '<span class="mdeps">' + (m.declaredDeps ? m.declaredDeps + ' 条' : '—') + '</span></li>';
    }).join('');
    $('module-count').textContent = '共 ' + discover.modules.length + ' 个模块';
    $('btn-confirm-more').hidden = discover.modules.length <= 8;
    $('btn-confirm-more').textContent = expanded ? '收起' : '展开全部';
  }

  /**
   * 首页「最近打开」。每行右侧有删除按钮，删除只移除记录、不删磁盘上的项目。
   * onOpen(item) / onRemove(key)
   */
  function renderRecent(items, onOpen, onRemove) {
    const list = $('recent-list');
    list.innerHTML = '';
    if (!items.length) {
      list.innerHTML = '<li class="recent-empty">还没有打开过项目</li>';
      return;
    }
    items.forEach(function (it) {
      const li = document.createElement('li');
      li.className = 'recent-row';

      const b = document.createElement('button');
      b.className = 'recent-item';
      b.type = 'button';
      b.innerHTML = '<span class="recent-name">' + esc(it.name) + '</span>' +
        '<span class="recent-meta">' + it.moduleCount + ' 模块</span>' +
        '<span class="recent-time">' + esc(it.when) + '</span>';
      b.addEventListener('click', function () { onOpen(it); });
      li.appendChild(b);

      if (onRemove) {
        const del = document.createElement('button');
        del.className = 'icon-btn sm recent-del';
        del.title = '从列表中移除';
        del.setAttribute('aria-label', '移除 ' + it.name);
        del.innerHTML = '<svg class="icon" aria-hidden="true"><use href="#i-trash"/></svg>';
        del.addEventListener('click', function (e) {
          e.stopPropagation();
          onRemove(it.key);
        });
        li.appendChild(del);
      }
      list.appendChild(li);
    });
  }

  global.Views = {
    $: $, esc: esc, num: num, debounce: debounce, toast: toast, copy: copy,
    renderResults: renderResults,
    renderNodeDetail: renderNodeDetail,
    renderModuleDetail: renderModuleDetail,
    renderPomDialog: renderPomDialog,
    renderDeclaredDialog: renderDeclaredDialog,
    renderVersionExplain: renderVersionExplain,
    renderPathDetail: renderPathDetail,
    clearDetail: clearDetail,
    renderPaths: renderPaths,
    renderConflicts: renderConflicts,
    renderMeta: renderMeta,
    renderMetaDialog: renderMetaDialog,
    renderModuleBreakdown: renderModuleBreakdown,
    renderModules: renderModules,
    renderSuggest: renderSuggest,
    hideSuggest: hideSuggest,
    renderRecent: renderRecent
  };
})(window);
