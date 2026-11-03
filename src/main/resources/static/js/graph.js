/* depviz · Cytoscape 渲染层
 * 使用 dagre 从左到右布局，按 GA 聚合节点并按标签计算宽度。
 */
(function (global) {
  'use strict';

  const NODE_H = 30;
  const NODE_MIN_W = 88;
  const NODE_PAD_X = 24;
  const LABEL_FONT = '11px ui-monospace, SFMono-Regular, Menlo, monospace';

  /** 用 canvas 量标签实际宽度，节点按此撑开，名字完整显示。 */
  let measureCtx = null;
  function textWidth(text) {
    if (!measureCtx) {
      measureCtx = document.createElement('canvas').getContext('2d');
    }
    measureCtx.font = LABEL_FONT;
    return measureCtx.measureText(text).width;
  }

  function nodeWidth(label) {
    return Math.ceil(Math.max(NODE_MIN_W, textWidth(label) + NODE_PAD_X));
  }

  /** 颜色取自 CSS 变量，跟随主题切换。 */
  function tokens() {
    const s = getComputedStyle(document.documentElement);
    const v = function (n) { return s.getPropertyValue(n).trim(); };
    return {
      canvas: v('--g-canvas'),
      rootBg: v('--g-root-bg'),
      rootFg: v('--g-root-fg'),
      nodeBg: v('--g-node-bg'),
      nodeFg: v('--g-node-fg'),
      nodeBorder: v('--g-node-border'),
      edge: v('--g-edge'),
      edgeOmit: v('--g-edge-omit'),
      accent: v('--accent'),
      accentFg: v('--on-accent'),
      targetBorder: v('--g-target-border'),
      selNode: v('--g-sel-node'),
      selTarget: v('--g-sel-target'),
      selRoot: v('--g-sel-root'),
      selPath: v('--g-sel-path'),
      common: v('--orange')
    };
  }

  let cy = null;

  function styleSheet() {
    const c = tokens();
    return [
      {
        selector: 'node',
        style: {
          // 宽度由 JS 按标签实测宽度写入 data.w，名字永远完整显示
          'width': 'data(w)',
          'height': NODE_H,
          'shape': 'round-rectangle',
          'background-color': c.nodeBg,
          'border-width': 1,
          'border-color': c.nodeBorder,
          'label': 'data(label)',
          'color': c.nodeFg,
          'font-family': 'ui-monospace, SFMono-Regular, Menlo, monospace',
          'font-size': 11,
          'text-valign': 'center',
          'text-halign': 'center',
          'text-wrap': 'none',
          'padding': 0,
          'overlay-opacity': 0
        }
      },
      {
        selector: 'node.is-root',
        style: {
          'background-color': c.rootBg,
          'color': c.rootFg,
          'border-color': c.rootBg,
          'font-weight': 600
        }
      },
      {
        selector: 'node.is-target',
        style: {
          'background-color': c.accent,
          'color': c.accentFg,
          'border-width': 2,
          'border-color': c.targetBorder,
          'font-weight': 700
        }
      },
      {
        selector: 'node.is-common',
        style: {
          'border-width': 2,
          'border-color': c.common
        }
      },
      {
        // 单选一个节点：白底 → 紫
        selector: 'node.is-selected',
        style: { 'border-width': 2, 'border-color': c.selNode }
      },
      {
        // 蓝底目标 → 亮琥珀（蓝底上唯一的暖色高反差）
        selector: 'node.is-target.is-selected',
        style: { 'border-width': 2.5, 'border-color': c.selTarget }
      },
      {
        // 深底项目模块 → 亮翠绿
        selector: 'node.is-root.is-selected',
        style: { 'border-width': 2.5, 'border-color': c.selRoot }
      },
      {
        // 选中一条路径时，链上所有节点共享一种高亮色：这是「同一条路径」的视觉信号，
        // 不能沿用上面的按类型配色，否则一条链上会出现三四种颜色。
        selector: 'node.on-selected-path',
        style: { 'border-width': 2.5, 'border-color': c.selPath }
      },
      {
        selector: 'node.is-dim',
        style: { 'opacity': 0.3 }
      },
      {
        selector: 'edge',
        style: {
          'width': 'mapData(weight, 1, 40, 1, 2.6)',
          'line-color': c.edge,
          'target-arrow-color': c.edge,
          'target-arrow-shape': 'triangle',
          'arrow-scale': 0.7,
          'curve-style': 'bezier',
          'opacity': 0.9,
          'overlay-opacity': 0
        }
      },
      {
        selector: 'edge.is-omitted',
        style: { 'line-style': 'dashed', 'line-color': c.edgeOmit, 'target-arrow-color': c.edgeOmit }
      },
      {
        selector: 'edge.is-dim',
        style: { 'opacity': 0.16 }
      },
      {
        selector: 'edge.is-hot',
        style: { 'line-color': c.accent, 'target-arrow-color': c.accent, 'width': 2.6, 'opacity': 1 }
      },
      {
        selector: 'node.is-hot',
        style: { 'opacity': 1, 'border-color': c.accent, 'border-width': 2 }
      }
    ];
  }

  function init(container) {
    cy = global.cytoscape({
      container: container,
      minZoom: 0.15,
      maxZoom: 2.4,
      boxSelectionEnabled: false,
      autounselectify: true,
      style: styleSheet()
    });

    bindHoverCursor(container);
    // 不再在 layoutstop 里 cy.fit()：那会在 render() 算好视口之后又把视图重置掉。
    return cy;
  }

  /** 主题切换后重刷颜色，节点位置与缩放保持不变。 */
  function restyle() {
    if (!cy) return;
    cy.style().fromJson(styleSheet()).update();
  }

  function elements(sub) {
    const nodes = sub.nodes.map(function (n) {
      const classes = [];
      // 只把「纯模块根」画成深色；同时是中间节点时按普通节点画，避免误读为只是入口
      if (n.isRoot && !n.isInner) classes.push('is-root');
      if (n.isTarget) classes.push('is-target');
      if (n.isCommon) classes.push('is-common');
      return {
        group: 'nodes',
        data: Object.assign({}, n, { label: n.label, w: nodeWidth(n.label) }),
        classes: classes.join(' ')
      };
    });
    const edges = sub.edges.map(function (e) {
      const omitted = e.omitted >= e.weight;
      return {
        group: 'edges',
        data: {
          id: e.id,
          source: e.source,
          target: e.target,
          weight: e.weight,
          scope: e.scope
        },
        classes: omitted ? 'is-omitted' : ''
      };
    });
    return nodes.concat(edges);
  }

  function render(sub, opts) {
    opts = opts || {};
    cy.resize();
    cy.elements().remove();
    if (!sub.nodes.length) return;
    cy.add(elements(sub));
    cy.layout({
      name: 'dagre',
      rankDir: 'LR',
      nodeSep: 16,
      rankSep: 46,
      edgeSep: 6,
      animate: false,
      fit: false,
      padding: 44
    }).run();

    // 全图 fit 后文字就太小的话：优先「目标 + 直接父节点」，装不下就退到只看目标。
    // 两种情况都保证目标节点落在视野中央、标签可读。
    const pad = 40;
    const bb = cy.elements().boundingBox();
    const fitZoom = Math.min((cy.width() - pad * 2) / bb.w, (cy.height() - pad * 2) / bb.h);
    // 低于这个缩放比例，标签基本不可读；再小就优先保证目标区域可读。
    const MIN_READABLE = 0.42;
    const targets = cy.nodes('.is-target');
    const anchor = targets.length ? targets : cy.nodes();

    if (fitZoom >= MIN_READABLE) {
      cy.zoom(fitZoom);
      cy.center(cy.elements());
      return;
    }
    const near = targets.length ? targets.union(targets.incomers('node')) : null;
    if (near && near.length) {
      const nb = near.boundingBox();
      const nearZoom = Math.min((cy.width() - pad * 2) / nb.w, (cy.height() - pad * 2) / nb.h, 1);
      if (nearZoom >= MIN_READABLE) {
        cy.zoom(nearZoom);
        cy.center(near);
        return;
      }
    }
    // 父节点太多（如 slf4j-api 有 48 个直接父）：保证目标可读，其余用「全图」「聚焦」看。
    // 居中整张图而不是只居中目标，避免多根布局时左侧被裁掉一半。
    cy.zoom(MIN_READABLE);
    const all = cy.elements().boundingBox();
    const mx = (all.x1 + all.x2) / 2;
    const my = (all.y1 + all.y2) / 2;
    cy.pan({ x: cy.width() / 2 - mx * MIN_READABLE, y: cy.height() / 2 - my * MIN_READABLE });
    void anchor;
  }

  /** 高亮给定 GA 集合与边集合；其余元素降透明度。 */
  function highlightElements(gaIds, edgeKeys) {
    cy.elements().removeClass('is-dim is-hot on-path is-path-hint');
    if (!gaIds || !gaIds.length) return;
    // merge() 返回新集合不改原对象，必须用 union 重新赋值
    let keep = cy.collection();
    gaIds.forEach(function (gaId) {
      const n = cy.getElementById('g' + gaId);
      if (n.length) keep = keep.union(n);
    });
    (edgeKeys || []).forEach(function (key) {
      const parts = key.split('>');
      const e = cy.getElementById('e' + parts[0] + '_' + parts[1]);
      if (e.length) keep = keep.union(e);
    });
    cy.elements().difference(keep).addClass('is-dim');
    keep.edges().addClass('is-hot');
    // 路径上的节点加细边框；目标节点保持自身强调样式
    keep.nodes().not('.is-target').addClass('on-path');
  }

  /** 高亮一条 GA 路径。 */
  function highlightPath(hopGaIds) {
    const edges = [];
    for (let i = 1; i < (hopGaIds || []).length; i++) {
      edges.push(hopGaIds[i - 1] + '>' + hopGaIds[i]);
    }
    highlightElements(hopGaIds, edges);
  }

  function clearHighlight() {
    cy.elements().removeClass('is-dim is-hot');
  }

  function focusPath(hopGaIds) {
    highlightPath(hopGaIds);
    const nodes = (hopGaIds || []).map(function (g) { return cy.getElementById('g' + g); }).filter(function (n) { return n.length; });
    if (!nodes.length) return;
    cy.animate({ fit: { eles: cy.collection(nodes), padding: 120 }, duration: 260 });
  }

  /** 选中单个节点（点击画布节点时调用）。 */
  function selectNode(gaId) {
    cy.nodes('.is-selected, .on-selected-path').removeClass('is-selected on-selected-path');
    forEachGa([gaId], function (n) { n.addClass('is-selected'); });
  }

  /** 选中一组节点（点击路径行时调用：该路径经过的每个节点都加同一色的框）。 */
  function selectNodes(gaIds) {
    cy.nodes('.is-selected, .on-selected-path').removeClass('is-selected on-selected-path');
    forEachGa(gaIds || [], function (n) { n.addClass('on-selected-path'); });
  }

  function clearSelection() {
    cy.nodes('.is-selected, .on-selected-path').removeClass('is-selected on-selected-path');
  }

  function forEachGa(gaIds, fn) {
    gaIds.forEach(function (gaId) {
      const n = cy.getElementById('g' + gaId);
      if (n.length) fn(n);
    });
  }

  /**
   * 悬停提示。
   * 内容只放「这个节点是什么」：groupId / artifactId / 生效版本（模块根再加打包方式）。
   * 300ms 延迟避免快速划过时闪一片；位置固定在节点上方，靠近边界时翻转。
   */
  const TIP_DELAY = 300;
  let tipTimer = 0;
  let tipEl = null;

  function bindHoverCursor(container) {
    tipEl = document.getElementById('node-tip');

    cy.on('mouseover', 'node', function (evt) {
      container.style.cursor = 'pointer';
      const node = evt.target;
      clearTimeout(tipTimer);
      tipTimer = setTimeout(function () { showTip(container, node); }, TIP_DELAY);
    });

    cy.on('mouseout', 'node', function () {
      container.style.cursor = '';
      clearTimeout(tipTimer);
      hideTip();
    });

    // 鼠标直接移出画布时 Cytoscape 不保证触发 node 的 mouseout
    container.addEventListener('mouseleave', function () {
      container.style.cursor = '';
      clearTimeout(tipTimer);
      hideTip();
    });
    cy.on('pan zoom', function () { hideTip(); });
  }

  /** 节点数据 -> 提示内容。 */
  function tipHtml(d) {
    const rows = [];
    if (d.isRoot) {
      rows.push(['groupId', escapeHtml(d.groupId)]);
      rows.push(['artifactId', escapeHtml(d.artifactId || d.label)]);
      rows.push(['打包方式', '<span class="nt-tag">' + escapeHtml(d.packaging || 'jar') + '</span>']);
    } else {
      rows.push(['groupId', escapeHtml(d.groupId)]);
      rows.push(['artifactId', escapeHtml(d.label)]);
      const v = d.versions && d.versions.length
        ? (d.versions.length > 1 ? d.versions[0] + ' 等 ' + d.versions.length + ' 个' : d.versions[0])
        : '—';
      rows.push(['生效版本', escapeHtml(v)]);
    }
    return rows.map(function (r) {
      return '<div class="nt-row"><span class="nt-k">' + r[0] + '</span>' +
        '<span class="nt-v">' + r[1] + '</span></div>';
    }).join('');
  }

  function escapeHtml(v) {
    return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function showTip(container, node) {
    if (!tipEl) return;
    tipEl.innerHTML = tipHtml(node.data());
    tipEl.hidden = false;

    // tipEl 与 #cy 同属 .col.graph（position: relative），所以用相对容器的坐标即可
    const pos = node.renderedPosition();
    const tw = tipEl.offsetWidth;
    const th = tipEl.offsetHeight;

    // #cy 在 .col.graph 内的偏移（上面还有 graph-tools 和 banner）
    const cRect = container.getBoundingClientRect();
    const pRect = tipEl.parentElement.getBoundingClientRect();
    const baseX = cRect.left - pRect.left;
    const baseY = cRect.top - pRect.top;

    let left = baseX + pos.x - tw / 2;
    let top = baseY + pos.y - th - 12;

    // 靠近顶部 → 翻到节点下方
    if (top < baseY + 4) top = baseY + pos.y + 24;
    // 左右夹紧在容器内
    const maxLeft = pRect.width - tw - 8;
    left = Math.max(8, Math.min(left, maxLeft));

    tipEl.style.left = left + 'px';
    tipEl.style.top = top + 'px';
  }

  function hideTip() {
    if (tipEl) tipEl.hidden = true;
  }

  function onNodeTap(fn) {
    cy.on('tap', 'node', function (evt) { fn(evt.target.data()); });
    cy.on('tap', function (evt) { if (evt.target === cy) fn(null); });
  }

  function fit() {
    if (!cy.nodes().length) return;
    cy.fit(undefined, 40);
  }

  /** 把目标节点和它的直接引入者摆到视野中央。 */
  /**
   * 聚焦。按优先级选锚点：
   *   1) 用户当前点选的节点
   *   2) 目标依赖（未点选任何节点时）
   * 锚点连同它的上游（引入者）一起框进视野。
   */
  function focusNodes(anchorIds) {
    let anchor = cy.collection();
    (anchorIds || []).forEach(function (id) {
      const n = cy.getElementById(id);
      if (n.length) anchor = anchor.union(n);
    });
    if (!anchor.length) anchor = cy.nodes('.is-target');
    if (!anchor.length) { fit(); return; }

    const near = anchor.union(anchor.incomers('node'));
    const bb = near.length ? near.boundingBox() : anchor.boundingBox();
    const pad = 70;
    const fitZoom = Math.min(
      (cy.width() - pad * 2) / bb.w,
      (cy.height() - pad * 2) / bb.h
    );
    const zoom = Math.max(0.35, Math.min(fitZoom, 1.15));
    const cx = (bb.x1 + bb.x2) / 2;
    const cyMid = (bb.y1 + bb.y2) / 2;
    cy.animate({
      zoom: zoom,
      pan: { x: cy.width() / 2 - cx * zoom, y: cy.height() / 2 - cyMid * zoom },
      duration: 260
    });
  }

  /** 聚焦目标依赖（未选中任何节点时的默认行为）。 */
  function focusTargets() { focusNodes(null); }

  function resize() {
    if (cy) cy.resize();
  }

  /** 布局变化前记录视口中心对应的图坐标，变化后用它恢复，避免内容跑出画面。 */
  function centerPoint() {
    if (!cy) return null;
    const pan = cy.pan();
    const zoom = cy.zoom();
    return { x: (cy.width() / 2 - pan.x) / zoom, y: (cy.height() / 2 - pan.y) / zoom };
  }

  function restoreCenter(pt) {
    if (!cy || !pt) return;
    cy.resize();
    const zoom = cy.zoom();
    cy.pan({ x: cy.width() / 2 - pt.x * zoom, y: cy.height() / 2 - pt.y * zoom });
  }

  global.Graph = {
    init: init,
    restyle: restyle,
    render: render,
    highlightPath: highlightPath,
    highlightElements: highlightElements,
    clearHighlight: clearHighlight,
    focusPath: focusPath,
    selectNode: selectNode,
    selectNodes: selectNodes,
    clearSelection: clearSelection,
    onNodeTap: onNodeTap,
    fit: fit,
    focusTargets: focusTargets,
    focusNodes: focusNodes,
    resize: resize,
    centerPoint: centerPoint,
    restoreCenter: restoreCenter,
    get cy() { return cy; }
  };
})(window);
