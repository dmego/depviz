/* depviz · 后端适配层
 *
 * 这个文件是前端与 Java 后端之间**唯一**的接口层。上层（app.js / views.js）只认识：
 *
 *   Scan.PROJECTS          同步可读的项目表（app.js 在渲染路径上同步读它）
 *   Scan.discover(key)     -> Promise<discover 响应>
 *   Scan.loadProject(key)  -> Promise<graph JSON>
 *   Scan.runScan(key, cb)  -> job（可 cancel）
 *   Scan.cancel()
 *
 * 数据来自 HTTP，异步响应通过 remember 写回同步的 PROJECTS 表。
 * 本层也更新扫描页的标题、日志、进度条和跟随滚动。
 */
(function (global) {
  'use strict';

  const $ = function (id) { return document.getElementById(id); };

  /** 同步可读的项目表。app.js:192/215/330/786/990/1366 在渲染路径上直接读它。 */
  const PROJECTS = {};

  const cache = {};
  let job = null;

  /* ================= 项目表 ================= */

  /** 把后端返回的 rootDir 映射成稳定的 key（前端全篇用它当项目标识）。 */
  function keyOf(rootDir) {
    return rootDir;
  }

  function nameOf(rootDir) {
    // 只看分隔符，不假设是 POSIX：Windows 的 D:\code\Demo 里一个 / 都没有，
    // 按 / 切会把整串当成目录名，界面上就显示成「正在分析 D:\code\Demo」
    const parts = String(rootDir || '').split(/[\\/]+/).filter(Boolean);
    return parts.length ? parts[parts.length - 1] : String(rootDir || '');
  }

  /** 把一个已知项目写进同步表。name 必须在返回前就绪。 */
  function remember(rootDir, patch) {
    const key = keyOf(rootDir);
    const cur = PROJECTS[key] || { key: key, name: nameOf(rootDir), rootDir: rootDir };
    const next = Object.assign(cur, patch);
    next.key = key;
    next.rootDir = rootDir;
    next.name = next.name || nameOf(rootDir);
    PROJECTS[key] = next;
    return key;
  }

  function api(path, init) {
    return fetch(path, Object.assign({ headers: { 'Accept': 'application/json' } }, init || {}))
      .then(function (r) {
        return r.text().then(function (text) {
          let body = null;
          try { body = text ? JSON.parse(text) : null; } catch (e) { body = null; }
          if (!r.ok) {
            const err = new Error((body && body.message) || ('请求失败：' + path));
            err.code = (body && body.error) || '';
            err.status = r.status;
            throw err;
          }
          return body;
        });
      });
  }

  /* ================= 发现 ================= */

  /**
   * 定位项目根并读模块清单。
   * app.js:223 / app.js:811 用；返回字段必须是 02 §6.5 的 discover 形状。
   */
  function discover(key) {
    const dir = PROJECTS[key] ? PROJECTS[key].rootDir : key;
    return api('/api/discover?dir=' + encodeURIComponent(dir)).then(function (d) {
      remember(d.rootDir, {
        name: nameOf(d.rootDir),
        moduleCount: d.moduleCount,
        declaredDependencyCount: d.declaredDependencyCount,
        selectedDir: d.selectedDir,
        relocated: d.relocated
      });
      return d;
    });
  }

  /* ================= 图数据 ================= */

  /** app.js:1367 的深链与「查看结果」用；返回 engine.js 直接吃的 doc。 */
  function loadProject(key) {
    if (cache[key]) return Promise.resolve(cache[key]);
    return api('/api/graph?projectId=' + encodeURIComponent(key)).then(function (doc) {
      cache[key] = doc;
      remember(doc.project && doc.project.rootDir || key, {
        name: doc.project ? doc.project.name : nameOf(key),
        moduleCount: doc.project ? doc.project.moduleCount : 0,
        declaredDependencyCount: doc.project ? doc.project.declaredDependencyCount : 0,
        scanned: true
      });
      return doc;
    });
  }

  function forget(key) {
    delete cache[key];
  }

  /* ================= 扫描 ================= */

  const POLL_MS = 400;
  const MAX_LOG_LINES = 200;

  /** 扫描页的进度条与耗时读数。scan.js 自持，因为只有它知道任务在跑。 */
  function progressUi() {
    const fill = $('progress-fill');
    const prog = $('progress');
    const pct = $('scan-pct');
    return {
      set: function (ratio) {
        const v = Math.max(0, Math.min(100, Math.round((ratio < 0 ? 0 : ratio) * 100)));
        if (fill) fill.style.transform = 'scaleX(' + (v / 100) + ')';
        if (prog) prog.setAttribute('aria-valuenow', String(v));
        if (pct) pct.textContent = v + '%';
      }
    };
  }

  /** 日志区：先写 textContent 再滚，scrollHeight 才是最新值。 */
  function logUi() {
    const el = $('scan-log');
    let follow = true;
    if (el) {
      el.onscroll = function () {
        follow = el.scrollHeight - el.scrollTop - el.clientHeight <= 24;
      };
    }
    return {
      clear: function () { if (el) el.textContent = ''; },
      append: function (lines) {
        if (!el || !lines || !lines.length) return;
        el.textContent += lines.join('\n') + '\n';
        if (follow) el.scrollTop = el.scrollHeight;
      }
    };
  }

  /**
   * 起一次扫描并轮询到终态。
   *
   * @param callbacks { done(doc), error(err), cancelled() }
   * @param opts      { offline?: boolean } —— 「联网重试」传 false，其余走服务端配置
   */
  function runScan(key, callbacks, opts) {
    if (job) job.cancel();
    const started = performance.now();
    const ring = progressUi();
    const logger = logUi();
    let terminal = false;
    let offset = 0;
    let timer = 0;
    let tick = 0;
    const p = PROJECTS[key] || { name: nameOf(key), moduleCount: 0 };

    function active() { return !terminal && job === current; }
    function halt() {
      clearTimeout(timer);
      clearInterval(tick);
    }
    function settle(kind, value) {
      if (!active()) return;
      terminal = true;
      halt();
      job = null;
      callbacks[kind](value);
    }
    function cancelBackend(id) {
      api('/api/scan/cancel?jobId=' + encodeURIComponent(id), { method: 'POST' })
        .catch(function () { /* 本地已取消，不再更新界面 */ });
    }
    const current = {
      id: null,
      cancel: function () {
        if (!active()) return;
        if (current.id) cancelBackend(current.id);
        settle('cancelled');
      }
    };
    job = current;

    if ($('scan-title')) {
      $('scan-title').textContent = '正在分析 ' + (p.name || nameOf(key)) +
        (p.moduleCount ? '（' + p.moduleCount + ' 个模块）' : '');
    }
    if ($('scan-sub')) $('scan-sub').textContent = 'mvn dependency:tree -Dverbose';
    logger.clear();
    ring.set(0);
    tick = setInterval(function () {
      const el = $('scan-elapsed');
      if (el) el.textContent = '已用 ' + ((performance.now() - started) / 1000).toFixed(1) + 's';
    }, 100);

    function fail(err) { settle('error', err); }
    function finish() {
      halt();
      ring.set(1);
      loadProject(key).then(function (doc) {
        if (!active()) return;
        const el = $('scan-elapsed');
        if (el) el.textContent = '已用 ' + ((performance.now() - started) / 1000).toFixed(1) + 's';
        settle('done', doc);
      }).catch(fail);
    }
    function poll(jobId) {
      if (!active()) return;
      api('/api/scan/status?jobId=' + encodeURIComponent(jobId) +
          '&offset=' + offset).then(function (st) {
        if (!active()) return;
        if (st.logLines && st.logLines.length) {
          logger.append(st.logLines);
          offset += st.logLines.length;
        }
        if (typeof st.progress === 'number') ring.set(st.progress);
        if (st.status === 'done') { finish(); return; }
        if (st.status === 'failed') {
          const e = st.error || {};
          const err = new Error(e.message || e.error || '扫描失败');
          err.code = e.error || '';
          err.detail = e.detail || '';
          err.logFile = st.logFile || '';
          fail(err);
          return;
        }
        if (st.status === 'cancelled') { settle('cancelled'); return; }
        timer = setTimeout(function () { poll(jobId); }, POLL_MS);
      }).catch(fail);
    }

    let url = '/api/scan?dir=' + encodeURIComponent(PROJECTS[key] ? PROJECTS[key].rootDir : key);
    if (opts && typeof opts.offline === 'boolean') {
      url += '&offline=' + (opts.offline ? '1' : '0');
    }
    api(url, { method: 'POST' }).then(function (r) {
      if (!active()) { cancelBackend(r.jobId); return; }
      current.id = r.jobId;
      forget(key);
      poll(r.jobId);
    }).catch(fail);
    return current;
  }

  function cancel() {
    if (job) job.cancel();
  }

  /* ================= 项目列表 / 打开路径 ================= */

  /**
   * 把后端已知项目灌进同步表。启动时调一次。
   *
   * <p>{@code scanned} 取服务端给的 {@code it.scanned}（= 图在不在服务端内存里），
   * 不是本地猜测：本地标志只反映「本页面加载过图」，跨标签页 / curl / 上一次会话
   * 扫过的项目在本地都是 false。服务端重启时恢复持久化图，只有没有可用图的项目
   * 才返回 {@code scanned=false}，深链落到确认页等待扫描。
   */
  function refreshProjects() {
    return api('/api/projects').then(function (list) {
      (list || []).forEach(function (it) {
        const dir = it.rootDir || it.key;
        // 只降级、不覆盖：本次进程里真的扫过的项目（scanned 已是 true）
        // 不能被这条恢复逻辑打回 false，否则深链永远进不了图。
        // 判「扫过没有」必须用 keyOf() 归一化后的 key，不能用原始 rootDir 串。
        const known = PROJECTS[keyOf(dir)];
        remember(dir, {
          name: it.name,
          moduleCount: it.moduleCount,
          when: it.when,
          known: true,
          scanned: !!it.scanned || !!(known && known.scanned)
        });
      });
      return list;
    });
  }

  /** 用户在路径框里输入后调用：先 discover，成功即注册为可打开项目。 */
  function openPath(dir, done, fail) {
    api('/api/discover?dir=' + encodeURIComponent(dir)).then(function (d) {
      const key = remember(d.rootDir, {
        name: nameOf(d.rootDir),
        moduleCount: d.moduleCount,
        declaredDependencyCount: d.declaredDependencyCount,
        selectedDir: d.selectedDir,
        relocated: d.relocated,
        known: true
      });
      done(key, d);
    }).catch(fail);
  }

  // 把用户输入/地址栏里的路径解析成登记用的 key（服务端会做规范化：macOS 上
  // /tmp 是 /private/tmp 的软链，不解析就永远匹配不上已扫描的项目）。
  //
  // 解析成功必须**顺手登记**：深链指向一个从没打开过的项目时，refreshProjects 里
  // 不会有它（/api/projects 只列扫过的），resolveKey 只返回路径又不写 PROJECTS，
  // 于是 openProject 因为查不到条目直接 return —— 界面静默停在首页，地址栏却带着
  // ?project=…，看起来像什么都没发生。登记后它与 openPath 的入口等价，会落到
  // 确认页；`known: true` 表示服务端确认过这是一个 Maven 项目。
  function resolveKey(path) {
    if (!path) return Promise.resolve(null);
    return fetch('/api/discover?dir=' + encodeURIComponent(path))
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d || !d.rootDir) return null;
        return remember(d.rootDir, {
          name: nameOf(d.rootDir),
          moduleCount: d.moduleCount,
          declaredDependencyCount: d.declaredDependencyCount,
          selectedDir: d.selectedDir,
          relocated: d.relocated,
          known: true
        });
      })
      .catch(function () { return null; });
  }

  /**
   * 列一层目录，供页内目录浏览器使用。
   *
   * <p>替换掉的旧接口是 {@code POST /api/pick-dir}：那条路要靠服务端弹 AWT 目录框，
   * macOS 上会跳出一个 **java 图标**的窗口，而且必须有窗口会话（SSH / headless 用不了）。
   * 现在后端只读文件系统、前端自己画列表，浏览器仍然拿不到绝对路径 —— 是服务端告诉它的。
   *
   * @param {string} path 绝对路径；空串表示从用户主目录开始
   * @return {Promise<{path,name,parent,home,hasPom,hasModules,dirs:[{name,path,hasPom}]}>}
   */
  function listDir(path) {
    const q = path ? '?path=' + encodeURIComponent(path) : '';
    return api('/api/list-dir' + q);
  }

  global.Scan = {
    PROJECTS: PROJECTS,
    discover: discover,
    loadProject: loadProject,
    runScan: runScan,
    cancel: cancel,
    refreshProjects: refreshProjects,
    openPath: openPath,
    listDir: listDir,
    resolveKey: resolveKey,
    remember: remember,
    forget: forget,
    MAX_LOG_LINES: MAX_LOG_LINES
  };
})(window);
