/* depviz · 查询引擎
 * 消费后端图 JSON，提供模块过滤、依赖搜索、路径与版本仲裁查询。
 */
(function (global) {
  'use strict';

  const MAX_RESULTS = 200;
  const MAX_PATH_ROWS = 400;

  function build(doc) {
    const occ = doc.occ;
    const n = occ.length;
    const parent = new Int32Array(n);
    const depth = new Int32Array(n);
    const byGa = new Map();
    for (let i = 0; i < n; i++) {
      const o = occ[i];
      parent[i] = o.p;
      // 深度 = 这条链上的第几个构件，模块根记为 1。
      // 例：a2a-server → core → databind → jackson-core = 4
      depth[i] = o.p < 0 ? 1 : depth[o.p] + 1;
      let arr = byGa.get(o.ga);
      if (!arr) { arr = []; byGa.set(o.ga, arr); }
      arr.push(i);
    }
    // 父子索引：解析顺序是 DFS 前序，父节点的下标一定小于子节点
    const children = new Map();
    const roots = [];
    for (let i = 0; i < n; i++) {
      const p = parent[i];
      if (p < 0) { roots.push(i); continue; }
      let arr = children.get(p);
      if (!arr) { arr = []; children.set(p, arr); }
      arr.push(i);
    }
    return {
      project: doc.project,
      modules: doc.modules,
      gas: doc.gas,
      gaKey: doc.gas.map(function (g) { return g.g + ':' + g.a; }),
      occ: occ,
      parent: parent,
      depth: depth,
      byGa: byGa,
      children: children,
      roots: roots
    };
  }

  /* ---------------- 依赖树（按包含关系） ---------------- */

  /**
   * 计算树里应该出现哪些 occurrence。
   * 规则：自己命中、或子树里有命中 → 可见（这样搜索时能看到完整引入链）。
   * 过滤：模块范围、scope。
   *
   * <p>{@code hideTest} 的默认值与 {@link hiddenScopes} 一致（都要「显式 true 才隐藏」）。
   * 早先这里是 {@code !== false}，与 search 路径的 {@code === true} 相反 —— 同一个
   * opts 对象在两种视图里含义不同，任何只传一半参数的调用方都会得到两种结果。
   */
  function treeVisible(store, q, opts) {
    opts = opts || {};
    const n = store.occ.length;
    const needle = String(q || '').trim().toLowerCase();
    const modSet = moduleSet(opts.moduleIds);
    const hidden = hiddenScopes(opts);
    // 命中集合必须与 search 用**同一套规则**（matchGas：先完整 GA 子串，零命中再退回
    // artifactId 子序列）。早先这里自己又写了一遍「完整 GA 子串」，于是搜 `spcgtw`
    // 时搜索结果有 2 个 GA、树视图却一个节点都不显示，而空态提示因为 results 非空
    // 也不出现 —— 界面只剩一片空白。
    //
    // 不让 views.js 直接把 res.results 传进来：results 被 MAX_RESULTS=200 截断，
    // 大范围查询会漏掉后半截命中。
    const hitSet = needle ? new Set(matchGas(store, needle)) : null;

    const selfMatch = new Uint8Array(n);
    const allowed = new Uint8Array(n);
    for (let i = 0; i < n; i++) {
      const o = store.occ[i];
      selfMatch[i] = !needle || hitSet.has(o.ga) ? 1 : 0;
      let ok = !modSet || modSet.has(o.m);
      if (ok && hidden && o.s && hidden.has(o.s)) ok = false;
      allowed[i] = ok ? 1 : 0;
    }

    const vis = new Uint8Array(n);
    for (let i = n - 1; i >= 0; i--) {
      if (!allowed[i]) continue;
      let v = selfMatch[i];
      if (!v) {
        const kids = store.children.get(i);
        if (kids) {
          for (let k = 0; k < kids.length; k++) {
            if (vis[kids[k]]) { v = 1; break; }
          }
        }
      }
      vis[i] = v;
    }
    return vis;
  }

  function childrenOf(store, oid) {
    return store.children.get(oid) || [];
  }

  /* ---------------- 模块树 ---------------- */

  /** 用 relPath 还原模块层级；pom 模块本身也是一个可选节点。 */
  function moduleTree(store) {
    const nodes = store.modules.map(function (m, i) {
      return {
        id: i,
        artifactId: m.artifactId,
        groupId: m.groupId,
        relPath: m.relPath,
        packaging: m.packaging,
        declaredDeps: m.declaredDeps,
        children: []
      };
    });
    const byPath = new Map();
    store.modules.forEach(function (m, i) { byPath.set(m.relPath, i); });

    const roots = [];
    store.modules.forEach(function (m, i) {
      if (m.relPath === '.') { roots.push(nodes[i]); return; }
      const cut = m.relPath.lastIndexOf('/');
      const parentPath = cut < 0 ? '.' : m.relPath.slice(0, cut);
      const pi = byPath.get(parentPath);
      if (pi != null && pi !== i) nodes[pi].children.push(nodes[i]);
      else roots.push(nodes[i]);
    });
    return roots;
  }

  /* ---------------- 作用域 ---------------- */

  function moduleSet(moduleIds) {
    return moduleIds && moduleIds.length ? new Set(moduleIds) : null;
  }

  /**
   * 「隐藏 test / provided」对应的 scope 集合。
   *
   * <p>**只在调用方显式传了 hideTest / hideProvided 时才生效**（任一为 true 即过滤）。
   * 这一点和 {@code treeVisible} 不同——树视图从第一天起就默认隐藏 test
   * （{@code opts.hideTest !== false}），而 {@code search} 从来没按 scope 过滤过。
   * 把默认值改成「藏」会让所有老调用方（含 engine_crosscheck 与启动自检）
   * 的结果集体变样，所以这里选择显式开启：UI 通过 {@code scopeOpts()}
   * 统一传这两个值，两种视图才真正一致。
   */
  function hiddenScopes(opts) {
    if (!opts) return null;
    const hideTest = opts.hideTest === true;
    const hideProvided = opts.hideProvided === true;
    if (!hideTest && !hideProvided) return null;
    const set = new Set();
    if (hideTest) set.add('test');
    if (hideProvided) set.add('provided');
    return set;
  }

  /**
   * 当前作用域判定：生效开关 + 模块范围 + scope 隐藏。
   *
   * @param hidden 由 {@link hiddenScopes} 产出，null 表示不按 scope 过滤
   */
  function inScope(store, oid, modSet, effectiveOnly, hidden) {
    const o = store.occ[oid];
    if (effectiveOnly && !o.e) return false;
    if (hidden && o.s && hidden.has(o.s)) return false;
    return !modSet || modSet.has(o.m);
  }

  /** 该 occurrence 的祖先链上是否经过某个 GA。 */
  function passesThrough(store, oid, gaId) {
    let cur = oid;
    while (cur >= 0) {
      if (store.occ[cur].ga === gaId) return true;
      cur = store.parent[cur];
    }
    return false;
  }

  /* ---------------- 搜索 ---------------- */

  function isSubsequence(needle, hay) {
    let i = 0;
    for (let j = 0; j < hay.length && i < needle.length; j++) {
      if (hay[j] === needle[i]) i++;
    }
    return i === needle.length;
  }

  function matchGas(store, q) {
    const hits = [];
    for (let gi = 0; gi < store.gaKey.length; gi++) {
      if (store.gaKey[gi].toLowerCase().indexOf(q) >= 0) hits.push(gi);
    }
    if (hits.length) return hits;
    for (let gi = 0; gi < store.gaKey.length; gi++) {
      if (isSubsequence(q, store.gas[gi].a.toLowerCase())) hits.push(gi);
    }
    return hits;
  }

  /**
   * 一个 GA 在当前作用域下的聚合摘要。
   * 所有计数都受 opts 过滤，界面显示的数字与画布/路径列表完全一致。
   */
  function summarize(store, gi, opts) {
    opts = opts || {};
    const modSet = moduleSet(opts.moduleIds);
    const hidden = hiddenScopes(opts);
    const ids = store.byGa.get(gi) || [];
    const modules = new Set();
    const versions = new Map();
    let occurrences = 0;
    let effectiveCount = 0;
    let minDepth = 99;
    let maxDepth = 0;

    for (let k = 0; k < ids.length; k++) {
      const oid = ids[k];
      const o = store.occ[oid];
      if (!inScope(store, oid, modSet, opts.effectiveOnly, hidden)) {
        // 主统计（路径数/深度/模块）遵守 effectiveOnly；
        // 但版本清单要完整——只按模块范围过滤，不受生效开关影响
        if (inScope(store, oid, modSet, false, hidden)) collectVersion(oid, o, false);
        continue;
      }
      occurrences++;
      if (o.e) effectiveCount++;
      modules.add(o.m);
      const d = store.depth[oid];
      minDepth = Math.min(minDepth, d);
      maxDepth = Math.max(maxDepth, d);
      collectVersion(oid, o, true);
    }

    function collectVersion(oid, o, inScopeNow) {
      let v = versions.get(o.v);
      if (!v) {
        v = { vid: o.v, label: store.gas[gi].v[o.v], occurrences: 0, effective: 0, mods: new Set(), minDepth: 99 };
        versions.set(o.v, v);
      }
      v.occurrences++;
      v.minDepth = Math.min(v.minDepth, store.depth[oid]);
      if (o.e) { v.effective++; if (inScopeNow) v.mods.add(o.m); }
    }

    // 版本列表始终覆盖「当前模块范围内出现过的所有版本」，不受 effectiveOnly 影响。
    // 否则会出现：左栏标红说有多版本，右栏却只显示一个（因为另一个版本全被剪了）。
    const vlist = Array.from(versions.values()).map(function (v) {
      return {
        version: v.label,
        occurrences: v.occurrences,
        effective: v.effective,
        modules: v.mods.size,
        minDepth: v.minDepth === 99 ? 0 : v.minDepth,
        // 该版本在当前作用域下是否全被剪掉
        dropped: v.effective === 0
      };
    }).sort(function (a, b) {
      return (b.effective > 0 ? 1 : 0) - (a.effective > 0 ? 1 : 0)
        || b.effective - a.effective
        || b.occurrences - a.occurrences;
    });

    // scope 分布（按出现次数降序），用于平铺列表显示
    const scopeCount = new Map();
    for (let k = 0; k < ids.length; k++) {
      const oid = ids[k];
      if (!inScope(store, oid, modSet, opts.effectiveOnly, hidden)) continue;
      const sc = store.occ[oid].s || '';
      if (!sc) continue;
      scopeCount.set(sc, (scopeCount.get(sc) || 0) + 1);
    }
    const scopes = Array.from(scopeCount.entries())
      .sort(function (a, b) { return b[1] - a[1]; })
      .map(function (e) { return e[0]; });

    return {
      ga: store.gaKey[gi],
      gaId: gi,
      groupId: store.gas[gi].g,
      artifactId: store.gas[gi].a,
      occurrences: occurrences,
      effectiveCount: effectiveCount,
      moduleIds: Array.from(modules),
      moduleCount: modules.size,
      versions: vlist,
      conflicted: vlist.length > 1,
      scopes: scopes,
      minDepth: minDepth === 99 ? 0 : minDepth,
      maxDepth: maxDepth
    };
  }

  /** 浏览模式：不输入关键词时列出全部依赖，同样受模块 / 生效过滤约束。 */
  function browse(store, opts) {
    opts = opts || {};
    const results = [];
    for (let gi = 0; gi < store.gas.length; gi++) {
      const r = summarize(store, gi, opts);
      if (r.occurrences > 0) results.push(r);
    }
    results.sort(function (a, b) { return a.ga.localeCompare(b.ga); });
    const hitModuleIds = [];
    const seen = new Set();
    results.forEach(function (r) {
      r.moduleIds.forEach(function (m) { if (!seen.has(m)) { seen.add(m); hitModuleIds.push(m); } });
    });
    return {
      query: '',
      browsing: true,
      totalOccurrences: results.reduce(function (n, r) { return n + r.occurrences; }, 0),
      totalGas: results.length,
      truncated: false,
      results: results,
      hitModuleIds: hitModuleIds
    };
  }

  function search(store, q, opts) {
    opts = opts || {};
    const needle = String(q || '').trim().toLowerCase();
    if (!needle) return browse(store, opts);
    const hits = matchGas(store, needle);
    const results = hits.map(function (gi) { return summarize(store, gi, opts); })
      .filter(function (r) { return r.occurrences > 0; });

    const sort = opts.sort || 'occ';
    results.sort(function (a, b) {
      if (sort === 'depth') return a.minDepth - b.minDepth || b.occurrences - a.occurrences;
      if (sort === 'name') return a.ga < b.ga ? -1 : a.ga > b.ga ? 1 : 0;
      return b.occurrences - a.occurrences || a.ga.localeCompare(b.ga);
    });

    let total = 0;
    results.forEach(function (r) { total += r.occurrences; });
    const truncated = results.length > MAX_RESULTS;

    // 命中模块集合用于模块选择器置灰，始终基于未过滤的命中结果
    const hitModuleIds = [];
    const seen = new Set();
    results.forEach(function (r) {
      (store.byGa.get(r.gaId) || []).forEach(function (oid) {
        const m = store.occ[oid].m;
        if (!seen.has(m)) { seen.add(m); hitModuleIds.push(m); }
      });
    });

    return {
      query: q,
      totalOccurrences: total,
      totalGas: results.length,
      truncated: truncated,
      results: truncated ? results.slice(0, MAX_RESULTS) : results,
      hitModuleIds: hitModuleIds
    };
  }

  /* ---------------- 路径 ---------------- */

  function hop(store, oid) {
    const o = store.occ[oid];
    const g = store.gas[o.ga];
    return {
      occurrenceId: oid,
      gaId: o.ga,
      ga: store.gaKey[o.ga],
      artifactId: g.a,
      groupId: g.g,
      version: g.v[o.v],
      type: o.t || 'jar',
      classifier: o.c || '',
      scope: o.s || '',
      effective: !!o.e,
      omitReason: o.o || '',
      omittedBy: o.ob || '',
      versionManagedFrom: o.vm || '',
      annotation: o.ann || '',
      depth: store.depth[oid],
      module: store.modules[o.m] ? store.modules[o.m].artifactId : '',
      moduleId: o.m,
      isRoot: o.p < 0,
      parentId: o.p
    };
  }

  function pathOf(store, oid) {
    const hops = [];
    let cur = oid;
    while (cur >= 0) {
      hops.push(hop(store, cur));
      cur = store.parent[cur];
    }
    hops.reverse();
    return hops;
  }

  /**
   * 选中 GA 的全部路径。opts: {effectiveOnly, moduleIds, throughGaId}
   * throughGaId 表示只保留祖先链上经过该节点的路径。
   */
  function pathsFor(store, gaIds, opts) {
    opts = opts || {};
    const modSet = moduleSet(opts.moduleIds);
    const hidden = hiddenScopes(opts);
    const out = [];
    for (let k = 0; k < gaIds.length; k++) {
      const gi = gaIds[k];
      const ids = store.byGa.get(gi) || [];
      for (let j = 0; j < ids.length; j++) {
        const oid = ids[j];
        if (!inScope(store, oid, modSet, opts.effectiveOnly, hidden)) continue;
        if (opts.throughGaId != null && !passesThrough(store, oid, opts.throughGaId)) continue;
        out.push({
          occurrenceId: oid,
          gaId: gi,
          effective: !!store.occ[oid].e,
          omitReason: store.occ[oid].o || '',
          depth: store.depth[oid],
          moduleId: store.occ[oid].m,
          module: store.modules[store.occ[oid].m].artifactId,
          hops: pathOf(store, oid)
        });
      }
    }
    out.sort(function (a, b) {
      return a.depth - b.depth
        || (b.effective ? 1 : 0) - (a.effective ? 1 : 0)
        || a.moduleId - b.moduleId
        || a.occurrenceId - b.occurrenceId;
    });
    return {
      ga: gaIds.map(function (gi) { return store.gaKey[gi]; }),
      total: out.length,
      truncated: out.length > MAX_PATH_ROWS,
      paths: out.slice(0, MAX_PATH_ROWS)
    };
  }

  /**
   * 计算「经过某个 GA 的路径」对应的聚合图元素集合。
   * 直接在 occurrence 树上沿 parent 指针走，避免 Cytoscape 在 DAG 上重复计数。
   */
  function elementsThrough(store, gaId, targetGaIds, opts) {
    opts = opts || {};
    const modSet = moduleSet(opts.moduleIds);
    const nodes = new Set();
    const edges = new Set();
    let paths = 0;

    for (let k = 0; k < targetGaIds.length; k++) {
      const ids = store.byGa.get(targetGaIds[k]) || [];
      for (let j = 0; j < ids.length; j++) {
        const oid = ids[j];
        if (!inScope(store, oid, modSet, opts.effectiveOnly, hiddenScopes(opts))) continue;
        // 从目标往上溯，确认这条链是否经过 gaId
        const chain = [];
        let cur = oid;
        let hit = false;
        while (cur >= 0) {
          chain.push(cur);
          if (store.occ[cur].ga === gaId) hit = true;
          cur = store.parent[cur];
        }
        if (!hit) continue;
        paths++;
        // chain[0] 是目标，chain[i] 是它的祖先；边方向是「父 > 子」
        for (let i = 0; i < chain.length; i++) {
          nodes.add(store.occ[chain[i]].ga);
          if (i > 0) {
            const parentGa = store.occ[chain[i]].ga;
            const childGa = store.occ[chain[i - 1]].ga;
            if (parentGa !== childGa) edges.add(parentGa + '>' + childGa);
          }
        }
      }
    }
    return { nodes: Array.from(nodes), edges: Array.from(edges), paths: paths };
  }

  /** 所有选中目标的完整路径（不经过任何额外节点）。 */
  function allPathElements(store, targetGaIds, opts) {
    return elementsThrough(store, targetGaIds[0], targetGaIds, opts);
  }

  /** 经过某个节点的路径条数（用于右栏与画布标题）。 */
  function countThrough(store, gaId, opts) {
    opts = opts || {};
    const modSet = moduleSet(opts.moduleIds);
    const hidden = hiddenScopes(opts);
    const ids = store.byGa.get(gaId) || [];
    let total = 0;
    let effective = 0;
    const mods = new Set();
    for (let k = 0; k < ids.length; k++) {
      const oid = ids[k];
      if (!inScope(store, oid, modSet, opts.effectiveOnly, hidden)) continue;
      total++;
      if (store.occ[oid].e) effective++;
      mods.add(store.occ[oid].m);
    }
    return { paths: total, effective: effective, modules: mods.size };
  }

  /* ---------------- 公共祖先 ---------------- */

  function commonAncestors(store, gaIds, opts) {
    opts = opts || {};
    if (gaIds.length < 2) return { commonAncestors: [], noCommonAncestor: false };
    const modSet = moduleSet(opts.moduleIds);
    const hidden = hiddenScopes(opts);
    const groups = gaIds.map(function (gi) {
      const set = new Set();
      (store.byGa.get(gi) || []).forEach(function (oid) {
        if (!inScope(store, oid, modSet, opts.effectiveOnly, hidden)) return;
        let p = store.parent[oid];
        while (p >= 0) { set.add(p); p = store.parent[p]; }
      });
      return set;
    });
    const agg = new Map();
    groups.forEach(function (set, gIdx) {
      set.forEach(function (oid) {
        const o = store.occ[oid];
        let e = agg.get(o.ga);
        if (!e) { e = { gaId: o.ga, occurrences: 0, minDepth: 99, groups: new Set(), modules: [] }; agg.set(o.ga, e); }
        e.occurrences++;
        e.minDepth = Math.min(e.minDepth, store.depth[oid]);
        e.groups.add(gIdx);
        if (e.modules.indexOf(o.m) < 0) e.modules.push(o.m);
      });
    });
    const list = [];
    agg.forEach(function (e) {
      if (e.groups.size < 2) return;
      list.push({
        ga: store.gaKey[e.gaId],
        gaId: e.gaId,
        covers: e.groups.size,
        occurrences: e.occurrences,
        depth: e.minDepth,
        modules: e.modules.map(function (m) { return store.modules[m].artifactId; })
      });
    });
    list.sort(function (a, b) { return b.covers - a.covers || b.depth - a.depth || b.occurrences - a.occurrences; });
    return { commonAncestors: list, noCommonAncestor: list.length === 0 };
  }

  /* ---------------- 子图 ---------------- */

  /**
   * 所有选中 GA 的祖先链按 GA 聚合。
   * 多模块选择时天然产生多个根节点（每个模块一棵独立树），不设虚拟根。
   */
  function subgraph(store, gaIds, commonGaIds, opts) {
    opts = opts || {};
    const target = new Set(gaIds);
    const common = new Set(commonGaIds || []);
    const modSet = moduleSet(opts.moduleIds);
    const hidden = hiddenScopes(opts);
    const nodes = new Map();
    const edges = new Map();
    let rawOccurrences = 0;

    function touch(oid) {
      const o = store.occ[oid];
      let n = nodes.get(o.ga);
      if (!n) {
        n = {
          gaId: o.ga, ga: store.gaKey[o.ga], count: 0, effective: 0, omitted: 0,
          versions: [], scopes: [], depth: 99,
          rootCount: 0, innerCount: 0, direct: false, modules: [], rootModule: null
        };
        nodes.set(o.ga, n);
      }
      n.count++;
      if (o.e) n.effective++; else n.omitted++;
      if (n.versions.indexOf(o.v) < 0) n.versions.push(o.v);
      if (o.s && n.scopes.indexOf(o.s) < 0) n.scopes.push(o.s);
      // 同一个 GA 可能同时是模块根（1 个）和被别处引入的依赖（多个）
      if (o.p < 0) { n.rootCount++; n.rootModule = o.m; } else n.innerCount++;
      if (store.depth[oid] === 2) n.direct = true;
      n.depth = Math.min(n.depth, store.depth[oid]);
      if (n.modules.indexOf(o.m) < 0) n.modules.push(o.m);
      return n;
    }

    for (let k = 0; k < gaIds.length; k++) {
      const ids = store.byGa.get(gaIds[k]) || [];
      for (let j = 0; j < ids.length; j++) {
        const oid = ids[j];
        if (!inScope(store, oid, modSet, opts.effectiveOnly, hidden)) continue;
        rawOccurrences++;
        let cur = oid;
        touch(cur);
        while (store.parent[cur] >= 0) {
          const p = store.parent[cur];
          touch(p);
          const sk = store.occ[p].ga;
          const tk = store.occ[cur].ga;
          if (sk !== tk) {
            const ek = sk + '>' + tk;
            let e = edges.get(ek);
            if (!e) { e = { sourceGa: sk, targetGa: tk, weight: 0, omitted: 0, scope: '' }; edges.set(ek, e); }
            e.weight++;
            if (!store.occ[cur].e) e.omitted++;
            e.scope = store.occ[cur].s || e.scope;
          }
          cur = p;
        }
      }
    }

    const nodeList = [];
    nodes.forEach(function (n) {
      const g = store.gas[n.gaId];
      nodeList.push({
        id: 'g' + n.gaId,
        gaId: n.gaId,
        ga: n.ga,
        label: g.a,
        groupId: g.g,
        versions: n.versions.map(function (v) { return g.v[v]; }),
        scopes: n.scopes,
        count: n.count,
        effective: n.effective,
        omitted: n.omitted,
        depth: n.depth === 99 ? 0 : n.depth,
        // 身份：一个 GA 可以既是模块根、又是被别处引入的中间节点
        isRoot: n.rootCount > 0,
        isInner: n.innerCount > 0,
        rootCount: n.rootCount,
        innerCount: n.innerCount,
        isTarget: target.has(n.gaId),
        isCommon: common.has(n.gaId),
        direct: n.direct,
        moduleIds: n.modules,
        // 根节点所属模块（点击模块根时用它，而不是 moduleIds[0]）
        rootModuleId: n.rootModule,
        // 模块根需要展示打包方式
        packaging: n.rootModule != null ? (store.modules[n.rootModule] || {}).packaging : ''
      });
    });
    const edgeList = [];
    edges.forEach(function (e) {
      edgeList.push({
        id: 'e' + e.sourceGa + '_' + e.targetGa,
        source: 'g' + e.sourceGa,
        target: 'g' + e.targetGa,
        weight: e.weight,
        omitted: e.omitted,
        scope: e.scope
      });
    });
    let rootCount = 0;
    nodeList.forEach(function (n) { if (n.isRoot) rootCount++; });
    return {
      nodes: nodeList,
      edges: edgeList,
      rawOccurrences: rawOccurrences,
      rootCount: rootCount,
      collapsed: nodeList.length > 300
    };
  }

  /* ---------------- 冲突 ---------------- */

  /**
   * 冲突表。
   * 注意：多模块项目里「生效」不是全局唯一的——每个模块有自己的胜出版本。
   * 所以这里按模块判定：一个版本在多少个模块里胜出，而不是「有生效记录 / 没有」。
   */
  function conflicts(store, q, opts) {
    opts = opts || {};
    const modSet = moduleSet(opts.moduleIds);
    const needle = String(q || '').trim().toLowerCase();
    const list = [];
    for (let gi = 0; gi < store.gas.length; gi++) {
      const g = store.gas[gi];
      if (g.v.length < 2) continue;
      const key = store.gaKey[gi];
      if (needle && key.toLowerCase().indexOf(needle) < 0) continue;

      // 每个版本 -> 在哪些模块里胜出、出现在哪些模块里
      const byVersion = new Map();
      (store.byGa.get(gi) || []).forEach(function (oid) {
        const o = store.occ[oid];
        if (modSet && !modSet.has(o.m)) return;
        let v = byVersion.get(o.v);
        if (!v) { v = { vid: o.v, occ: 0, winMods: new Set(), allMods: new Set(), minDepth: 99 }; byVersion.set(o.v, v); }
        v.occ++;
        v.allMods.add(o.m);
        if (o.e) v.winMods.add(o.m);
        v.minDepth = Math.min(v.minDepth, store.depth[oid]);
      });
      if (byVersion.size < 2) continue;

      const versions = Array.from(byVersion.values()).map(function (v) {
        return {
          version: g.v[v.vid],
          occurrences: v.occ,
          winModules: v.winMods.size,
          moduleCount: v.allMods.size,
          minDepth: v.minDepth === 99 ? 0 : v.minDepth,
          // 该版本覆盖的模块（用于「N 个模块在用」的弹窗）
          moduleIds: Array.from(v.allMods),
          winModuleIds: Array.from(v.winMods)
        };
      }).sort(function (a, b) {
        return b.winModules - a.winModules || b.moduleCount - a.moduleCount || b.occurrences - a.occurrences;
      });

      const winners = versions.filter(function (v) { return v.winModules > 0; });
      const losers = versions.filter(function (v) { return v.winModules === 0; });
      list.push({
        ga: key,
        gaId: gi,
        versions: versions,
        winners: winners,
        losers: losers,
        // 有多个版本在不同模块里分别胜出 = 真正的多版本共存
        splitWinner: winners.length > 1
      });
    }
    list.sort(function (a, b) {
      return (b.splitWinner ? 1 : 0) - (a.splitWinner ? 1 : 0)
        || b.losers.length - a.losers.length
        || a.ga.localeCompare(b.ga);
    });
    return { total: list.length, conflicts: list };
  }

  /* ---------------- 节点摘要 ---------------- */

  /**
   * 右栏用。只有聚合事实，不含单条 occurrence 的随机抽样。
   * 额外给出模块分布与不同直接父节点的数量。
   */
  /**
   * 右栏用的节点详情。除聚合规模外，还要给出：
   *   - 上游：谁引入了它（按 GA 聚合，带路径条数与最短路径）
   *   - 下游：它引入了谁
   *   - 最浅/最深对应的路径，供 UI 点击后高亮
   */
  function nodeSummary(store, gaId, opts) {
    const sum = summarize(store, gaId, opts);
    const modSet = moduleSet(opts && opts.moduleIds);
    const hidden = hiddenScopes(opts);
    const byModule = new Map();
    const byVersionModule = new Map();  // "version|moduleId" -> 路径数
    const upstream = new Map();   // 父 GA -> { paths, minDepth, occIds }
    const downstream = new Map(); // 子 GA -> { paths }
    let shallowest = null;
    let deepest = null;

    (store.byGa.get(gaId) || []).forEach(function (oid) {
      const o = store.occ[oid];
      if (!inScope(store, oid, modSet, opts && opts.effectiveOnly, hidden)) return;

      let e = byModule.get(o.m);
      if (!e) {
        e = { moduleId: o.m, artifactId: store.modules[o.m].artifactId, paths: 0, effective: 0 };
        byModule.set(o.m, e);
      }
      e.paths++;
      if (o.e) e.effective++;

      // 版本 × 模块 的分布，供「点某版本的模块数」弹窗使用
      const vk = store.gas[gaId].v[o.v] + '|' + o.m;
      byVersionModule.set(vk, (byVersionModule.get(vk) || 0) + 1);

      const d = store.depth[oid];
      if (!shallowest || d < shallowest.depth) shallowest = { depth: d, occIds: [oid] };
      else if (d === shallowest.depth) shallowest.occIds.push(oid);
      if (!deepest || d > deepest.depth) deepest = { depth: d, occIds: [oid] };
      else if (d === deepest.depth) deepest.occIds.push(oid);

      // 上游：直接父节点
      if (o.p >= 0) {
        const pk = store.occ[o.p].ga;
        let u = upstream.get(pk);
        if (!u) { u = { gaId: pk, paths: 0, minDepth: 99 }; upstream.set(pk, u); }
        u.paths++;
        u.minDepth = Math.min(u.minDepth, store.depth[o.p]);
      }

      // 下游：直接子节点
      (store.children.get(oid) || []).forEach(function (cid) {
        const c = store.occ[cid];
        if (modSet && !modSet.has(c.m)) return;
        if (opts && opts.effectiveOnly && !c.e) return;
        const ck = c.ga;
        let dn = downstream.get(ck);
        if (!dn) { dn = { gaId: ck, paths: 0 }; downstream.set(ck, dn); }
        dn.paths++;
      });
    });

    function toList(map, names) {
      return Array.from(map.values()).map(function (v) {
        return {
          gaId: v.gaId,
          ga: store.gaKey[v.gaId],
          artifactId: store.gas[v.gaId].a,
          groupId: store.gas[v.gaId].g,
          paths: v.paths
        };
      }).sort(function (a, b) { return b.paths - a.paths || a.artifactId.localeCompare(b.artifactId); });
    }

    // 每个模块展开成「模块 × 版本」的记录，便于按版本过滤
    const modList = [];
    byVersionModule.forEach(function (paths, k) {
      const parts = k.split('|');
      const moduleId = Number(parts.pop());
      const version = parts.join('|');
      const mod = store.modules[moduleId];
      modList.push({ moduleId: moduleId, artifactId: mod.artifactId, version: version, paths: paths });
    });
    sum.modules = modList.sort(function (a, b) {
      return b.paths - a.paths || a.artifactId.localeCompare(b.artifactId);
    });
    sum.upstream = toList(upstream);
    sum.downstream = toList(downstream);
    sum.parentCount = sum.upstream.length;
    sum.childCount = sum.downstream.length;
    sum.shallowest = shallowest;
    sum.deepest = deepest;
    sum.isRoot = (store.byGa.get(gaId) || []).some(function (oid) { return store.occ[oid].p < 0; });
    return sum;
  }

  /**
   * 解释某个版本为什么没生效。
   * 深度比较只使用同一模块中的未生效路径与胜出路径。
   */
  function explainVersion(store, gaId, version, opts) {
    opts = opts || {};
    const modSet = moduleSet(opts.moduleIds);
    const g = store.gas[gaId];
    const allIds = (store.byGa.get(gaId) || []).filter(function (oid) {
      // 解释版本时不按 scope 过滤：这条记录要回答「为什么这个版本没生效」，
      // 而 test/provided 的开关是「列表里想不想看到它」，两者无关。
      return inScope(store, oid, modSet, false, null);
    });
    const ids = allIds.filter(function (oid) { return g.v[store.occ[oid].v] === version; });
    if (!ids.length) return null;

    const effectiveIds = ids.filter(function (oid) { return store.occ[oid].e; });
    const isLoser = effectiveIds.length === 0;
    const reasons = isLoser ? ids.map(function (oid) { return store.occ[oid].o || ''; }) : [];
    const reason = reasons.indexOf('conflict') >= 0 ? 'conflict'
      : reasons.indexOf('duplicate') >= 0 ? 'duplicate' : isLoser ? 'unknown' : '';

    /** 在给定 occurrence 集合里取最短路径。 */
    function shortest(list) {
      let best = null;
      list.forEach(function (oid) {
        if (!best || store.depth[oid] < store.depth[best]) best = oid;
      });
      return best == null ? null : {
        occId: best,
        depth: store.depth[best],
        hops: pathOf(store, best),
        module: store.modules[store.occ[best].m].artifactId,
        moduleId: store.occ[best].m
      };
    }

    // ---- 按模块逐一对账：Maven 的仲裁是按模块进行的 ----
    const moduleRows = [];
    const byMod = new Map();
    allIds.forEach(function (oid) {
      const m = store.occ[oid].m;
      if (!byMod.has(m)) byMod.set(m, { mod: m, mine: [], same: [], their: [] });
      const rec = byMod.get(m);
      if (g.v[store.occ[oid].v] === version) {
        rec.mine.push(oid);
        if (store.occ[oid].e) rec.same.push(oid);
      }
      else if (store.occ[oid].e) rec.their.push(oid);
    });
    byMod.forEach(function (rec) {
      // 只统计「该版本真的出现过」的模块；其余模块只是出现了别的版本，与本次判断无关
      if (!rec.mine.length) return;
      const mine = shortest(rec.mine);
      const same = shortest(rec.same);
      const their = shortest(rec.their);
      moduleRows.push({
        moduleId: rec.mod,
        artifactId: store.modules[rec.mod].artifactId,
        mineDepth: mine.depth,
        theirDepth: their ? their.depth : null,
        theirVersion: their ? g.v[store.occ[their.occId].v] : '',
        mineOccId: mine.occId,
        sameOccId: same ? same.occId : null,
        theirOccId: their ? their.occId : null,
        verdict: !their ? same ? 'only' : 'missing'
          : mine.depth > their.depth ? 'lose'
          : mine.depth < their.depth ? 'win' : 'tie'
      });
    });
    moduleRows.sort(function (a, b) {
      return (b.mineDepth || 0) - (a.mineDepth || 0) || a.artifactId.localeCompare(b.artifactId);
    });

    const loseModules = moduleRows.filter(function (r) { return r.verdict === 'lose'; });
    const tieModules = moduleRows.filter(function (r) { return r.verdict === 'tie'; });
    const missingModules = moduleRows.filter(function (r) { return r.verdict === 'missing'; });
    const onlyModules = moduleRows.filter(function (r) { return r.verdict === 'only'; });
    const representative = reason === 'duplicate'
      ? moduleRows.find(function (r) { return r.sameOccId != null; })
      : moduleRows.find(function (r) { return r.verdict === 'lose' || r.verdict === 'tie'; });
    const loserPath = isLoser
      ? representative ? shortest([representative.mineOccId]) : shortest(ids)
      : shortest(ids);
    const winnerOccId = !representative ? null
      : reason === 'duplicate' ? representative.sameOccId : representative.theirOccId;
    const winnerPath = winnerOccId == null ? null : shortest([winnerOccId]);
    const winnerVersion = winnerPath
      ? g.v[store.occ[winnerPath.occId].v]
      : isLoser ? '—' : version;

    // ---- 结论：讲清规则，而不是只报一个差值 ----
    let conclusion = '';
    if (!isLoser) {
      conclusion = '这个版本在构建中生效。';
    } else {
      if (reason === 'conflict') {
        conclusion = 'Maven 在每个模块内单独仲裁：同一坐标只保留离根最近的那一条。' +
          '这个版本在 ' + moduleRows.length + ' 个模块中出现；' +
          (loseModules.length ? loseModules.length + ' 个模块中它比各自胜出的版本更深；' : '') +
          (tieModules.length ? tieModules.length + ' 个模块中深度相同，由先声明的版本胜出；' : '') +
          (missingModules.length ? missingModules.length + ' 个模块没有可比较的胜出路径；' : '') +
          '逐模块路径与结果见下表。';
      } else if (reason === 'duplicate') {
        conclusion = 'Maven 将这条 occurrence 标记为重复引入（duplicate）。' +
          '查看逐模块表；只有存在同模块生效路径时，才用路径深度解释仲裁原因。';
      } else {
        conclusion = '这个版本在构建中未被采用。';
      }
    }

    return {
      ga: store.gaKey[gaId],
      gaId: gaId,
      version: version,
      isLoser: isLoser,
      reason: reason,
      conclusion: conclusion,
      winnerVersion: winnerVersion,
      loserPath: loserPath,
      winnerPath: winnerPath,
      moduleRows: moduleRows,
      loseCount: loseModules.length,
      onlyCount: onlyModules.length,
      modules: moduleRows.map(function (r) {
        return { moduleId: r.moduleId, artifactId: r.artifactId, paths: 1 };
      })
    };
  }

  /** 某些 occurrence 的完整路径，供「点最浅/最深」时高亮。 */
  function pathsByOccIds(store, occIds) {
    return (occIds || []).map(function (oid) { return pathOf(store, oid); });
  }

  /** 某个模块内、针对选中 GA 的路径统计。 */
  /**
   * 模块根详情。
   * 与普通依赖对称：模块根没有上游（它就是树起点），所以重点在「直接依赖」，
   * 另外补一个普通依赖没有的维度——「被哪些模块引用」。
   *
   * opts: { effectiveOnly, moduleIds }（moduleIds 用于「被引用」的范围过滤）
   */
  function moduleDetail(store, moduleId, opts) {
    opts = opts || {};
    const mod = store.modules[moduleId];

    // ---- 该模块自己的树 ----
    let treeSize = 0;
    const directMap = new Map();   // 直接依赖 GA -> 路径数
    let rootGa = null;
    store.occ.forEach(function (o, oid) {
      if (o.m !== moduleId) return;
      treeSize++;
      if (o.p < 0) { rootGa = o.ga; return; }
      if (opts.effectiveOnly && !o.e) return;
      // 直接依赖 = 根的直接子节点
      if (store.parent[oid] >= 0 && store.occ[store.parent[oid]].p < 0) {
        const k = o.ga;
        directMap.set(k, (directMap.get(k) || 0) + 1);
      }
    });

    // ---- 被哪些模块引用（该模块作为中间节点出现） ----
    const referrers = new Map();   // 引用方模块 id -> { paths, gaIds:Set }
    if (rootGa != null) {
      (store.byGa.get(rootGa) || []).forEach(function (oid) {
        const o = store.occ[oid];
        if (o.p < 0) return;                       // 自己的根不算
        if (o.m === moduleId) return;
        if (opts.effectiveOnly && !o.e) return;
        let r = referrers.get(o.m);
        if (!r) { r = { moduleId: o.m, paths: 0 }; referrers.set(o.m, r); }
        r.paths++;
      });
    }

    return {
      moduleId: moduleId,
      artifactId: mod.artifactId,
      groupId: mod.groupId,
      relPath: mod.relPath,
      packaging: mod.packaging,
      declaredDeps: mod.declaredDeps,
      // POM 原文与手写依赖：右栏的「POM 路径 / 声明依赖」弹窗要用
      pomText: mod.pomText || '',
      declared: mod.declared || [],
      direct: Array.from(directMap.entries()).map(function (e) {
        return {
          gaId: e[0],
          ga: store.gaKey[e[0]],
          artifactId: store.gas[e[0]].a,
          paths: e[1]
        };
      }).sort(function (a, b) { return b.paths - a.paths || a.artifactId.localeCompare(b.artifactId); }),
      referrers: Array.from(referrers.values()).map(function (r) {
        return {
          moduleId: r.moduleId,
          artifactId: store.modules[r.moduleId].artifactId,
          paths: r.paths
        };
      }).sort(function (a, b) { return b.paths - a.paths || a.artifactId.localeCompare(b.artifactId); })
    };
  }

  global.Engine = {
    build: build,
    moduleTree: moduleTree,
    treeVisible: treeVisible,
    childrenOf: childrenOf,
    search: search,
    browse: browse,
    summarize: summarize,
    pathsFor: pathsFor,
    countThrough: countThrough,
    elementsThrough: elementsThrough,
    commonAncestors: commonAncestors,
    subgraph: subgraph,
    conflicts: conflicts,
    nodeSummary: nodeSummary,
    pathsByOccIds: pathsByOccIds,
    explainVersion: explainVersion,
    moduleDetail: moduleDetail,
    hop: hop,
    MAX_PATH_ROWS: MAX_PATH_ROWS
  };
})(window);
