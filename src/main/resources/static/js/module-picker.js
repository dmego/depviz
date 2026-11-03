/* depviz · 模块树选择器
 * 父子级联：勾父模块 = 勾全部子模块；子模块取消 = 父模块变部分选中。
 * 每个父模块带一个「只选自己」的小按钮，用于只看 pom 模块自身的依赖树。
 */
(function (global) {
  'use strict';

  const $ = function (id) { return document.getElementById(id); };
  const esc = function (s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  };

  let roots = [];
  let selected = new Set();   // module id
  let hit = new Set();        // 当前搜索命中的 module id
  let collapsed = new Set();
  let query = '';
  let onChange = function () {};
  /**
   * 监听器是否已挂上。
   *
   * <p>{@code bind()} 每次打开项目都会被调用（模块清单变了），但 DOM 元素是同一批。
   * 早先没有这道守卫：切 N 个项目后，点一次复选框会触发 N 次 toggle —— 勾上立刻被
   * 第二份监听器取消，表现是「点了没反应」。监听器本身只依赖模块级变量
   * （roots/selected/query），所以挂一次就够，数据更新靠重新赋值。
   */
  let bound = false;

  function walk(nodes, fn) {
    nodes.forEach(function (n) { fn(n); walk(n.children, fn); });
  }

  function indexById() {
    const map = new Map();
    walk(roots, function (n) { map.set(n.id, n); });
    return map;
  }

  function descendantIds(node) {
    const out = [];
    walk(node.children, function (c) { out.push(c.id); });
    return out;
  }

  /** 父模块的三态：全选 / 部分 / 未选。 */
  function stateOf(node, sel) {
    sel = sel || selected;
    const kids = descendantIds(node);
    if (!kids.length) return sel.has(node.id) ? 'on' : 'off';
    let on = 0;
    kids.forEach(function (id) { if (sel.has(id)) on++; });
    // 子孙全选即显示为选中；父模块自身的树是否包含由「仅自身」控制
    if (on === kids.length) return 'on';
    if (on === 0 && !sel.has(node.id)) return 'off';
    return 'partial';
  }

  function toggle(node, forceOn) {
    const ids = descendantIds(node);
    const target = forceOn != null ? forceOn : stateOf(node) !== 'on';
    if (target) {
      selected.add(node.id);
      ids.forEach(function (id) { selected.add(id); });
    } else {
      selected.delete(node.id);
      ids.forEach(function (id) { selected.delete(id); });
    }
    // 子模块变化后修正祖先的选中态
    syncAncestors();
    render();
    emit();
  }

  /** 父模块的选中状态由子模块推导：全选则父选中，否则父未选中（但仍可单独选自己）。 */
  function syncAncestors() {
    const map = indexById();
    const parents = [];
    walk(roots, function (n) { if (n.children.length) parents.push(n); });
    parents.sort(function (a, b) { return b.relPath.split('/').length - a.relPath.split('/').length; });
    parents.forEach(function (n) {
      const kids = n.children.map(function (c) { return c.id; });
      const all = kids.every(function (id) { return selected.has(id); });
      if (all) selected.add(n.id);
      else selected.delete(n.id);
    });
    void map;
  }

  function render() {
    const tree = $('mp-tree');
    const q = query.trim().toLowerCase();
    const rows = [];

    function visible(node) {
      if (!q) return true;
      if (node.artifactId.toLowerCase().indexOf(q) >= 0) return true;
      return node.children.some(visible);
    }

    function row(node, level) {
      if (!visible(node)) return;
      const st = stateOf(node);
      const isCollapsed = collapsed.has(node.id);
      const hasKids = node.children.length > 0;
      const isHit = !hit.size || hit.has(node.id);
      rows.push(
        '<div class="mp-row' + (isHit ? '' : ' is-miss') + '" role="treeitem" data-id="' + node.id + '"' +
        ' style="padding-left:' + (8 + level * 16) + 'px" aria-selected="' + (st === 'on') + '">' +
          (hasKids
            ? '<button class="mp-twisty" data-twisty="' + node.id + '" aria-label="' + (isCollapsed ? '展开' : '折叠') + '">' +
              '<svg class="icon chev" aria-hidden="true" style="transform:rotate(' + (isCollapsed ? '-90deg' : '0deg') + ')"><use href="#i-chevron"/></svg></button>'
            : '<span class="mp-twisty"></span>') +
          '<button class="mp-check" data-check="' + node.id + '" role="checkbox" aria-checked="' + (st === 'on') + '">' +
            '<span class="mp-box ' + st + '">' +
              (st === 'on' ? '<svg class="icon" aria-hidden="true"><use href="#i-check"/></svg>' : st === 'partial' ? '<i></i>' : '') +
            '</span>' +
          '</button>' +
          '<button class="mp-name" data-check="' + node.id + '" title="' + esc(node.relPath) + '">' + esc(node.artifactId) + '</button>' +
          (hasKids ? '<span class="mp-kids">' + node.children.length + '</span>' : '') +
          (hasKids ? '<button class="mp-self" data-self="' + node.id + '" title="只选这个模块本身">仅自身</button>' : '') +
        '</div>'
      );
      if (hasKids && !isCollapsed) node.children.forEach(function (c) { row(c, level + 1); });
    }

    roots.forEach(function (r) { row(r, 0); });
    tree.innerHTML = rows.join('') || '<p class="mp-empty">没有匹配的模块</p>';

    const n = selected.size;
    $('mp-count').textContent = n ? '已选 ' + n + ' / ' + total() : '未选择（= 全部）';
    updateHint();
  }

  let allIds = [];
  function total() { return allIds.length; }

  function updateHint() {
    const hint = $('mp-hint');
    if (!selected.size) {
      hint.textContent = '不选 = 看全部模块';
      hint.className = 'mp-hint';
      return;
    }
    if (!hit.size) { hint.textContent = ''; return; }
    const miss = Array.from(selected).filter(function (id) { return !hit.has(id); });
    if (miss.length) {
      const names = miss.slice(0, 2).map(function (id) {
        const node = byId().get(id);
        return node ? node.artifactId : id;
      });
      hint.textContent = '已选 ' + selected.size + ' 个，其中 ' + miss.length + ' 个没有命中' +
        (names.length ? '（' + names.join('、') + (miss.length > 2 ? ' 等' : '') + '）' : '');
      hint.className = 'mp-hint is-warn';
    } else {
      hint.textContent = '';
      hint.className = 'mp-hint';
    }
  }

  let idMap = null;
  function byId() {
    if (!idMap) idMap = indexById();
    return idMap;
  }

  function emit() {
    onChange(Array.from(selected));
  }

  function setHit(ids) {
    hit = new Set(ids || []);
    render();
  }

  function setSelection(ids) {
    // 精确设置：不做祖先推断，避免「只看这些模块」被悄悄加进父模块
    selected = new Set(ids || []);
    render();
  }

  function open(onChangeFn) {
    if (onChangeFn) onChange = onChangeFn;
    $('module-pop').hidden = false;
    $('btn-modules').setAttribute('aria-expanded', 'true');
    render();
    $('mp-filter').focus();
  }

  function close() {
    $('module-pop').hidden = true;
    $('btn-modules').setAttribute('aria-expanded', 'false');
  }

  function isOpen() { return !$('module-pop').hidden; }

  function bind(projectModules, onChangeFn) {
    onChange = onChangeFn;
    roots = global.Engine.moduleTree({ modules: projectModules });
    allIds = [];
    walk(roots, function (n) { allIds.push(n.id); });
    idMap = null;
    selected = new Set();
    collapsed = new Set();

    // 切项目时把过滤词与折叠状态清掉：否则在 A 项目里搜过 "core"，切到 B 项目
    // 打开模块选择器是空的，看起来像「B 没有模块」。
    query = '';
    $('mp-filter').value = '';

    if (bound) {
      render();
      return;
    }
    bound = true;

    const tree = $('mp-tree');

    tree.addEventListener('click', function (e) {
      const tw = e.target.closest('[data-twisty]');
      if (tw) {
        const id = Number(tw.dataset.twisty);
        if (collapsed.has(id)) collapsed.delete(id); else collapsed.add(id);
        render();
        return;
      }
      const self = e.target.closest('[data-self]');
      if (self) {
        const id = Number(self.dataset.self);
        const node = byId().get(id);
        const kids = descendantIds(node);
        selected.add(id);
        kids.forEach(function (k) { selected.delete(k); });
        render();
        emit();
        return;
      }
      const chk = e.target.closest('[data-check]');
      if (chk) {
        const node = byId().get(Number(chk.dataset.check));
        toggle(node);
      }
    });

    $('mp-filter').addEventListener('input', function () {
      query = this.value;
      render();
    });

    $('mp-all').addEventListener('click', function () {
      selected = new Set(allIds);
      render();
      emit();
    });

    $('mp-none').addEventListener('click', function () {
      selected = new Set();
      render();
      emit();
    });

    $('btn-modules').addEventListener('click', function () {
      if (isOpen()) close(); else open();
    });

    $('mp-apply').addEventListener('click', close);

    document.addEventListener('mousedown', function (e) {
      if (!isOpen()) return;
      if (e.target.closest('#module-pop') || e.target.closest('#btn-modules')) return;
      close();
    });

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && isOpen()) close();
    });

    render();
  }

  function label() {
    if (!selected.size) return '全部模块';
    if (selected.size === 1) {
      const node = byId().get(Array.from(selected)[0]);
      return node ? node.artifactId : '1 个模块';
    }
    return selected.size + ' 个模块';
  }

  global.ModulePicker = {
    bind: bind,
    setHit: setHit,
    setSelection: setSelection,
    getSelection: function () { return Array.from(selected); },
    label: label,
    close: close,
    isOpen: isOpen
  };
})(window);
