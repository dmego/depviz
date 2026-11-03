/* Test transport only: the page still runs the repository's real app/scan/engine/views. */
(function () {
  'use strict';
  const nativeFetch = window.fetch.bind(window);
  const fixture = window.__frontendFixture;
  const h = window.__reg = {
    config: fixture.config,
    projects: fixture.projects,
    documents: fixture.documents,
    aliases: fixture.aliases || {},
    calls: [], rules: [], pending: [], events: [],
    status: { status: 'running', progress: 0.2, logLines: [] },
    nextJob: 0,
    hold: function (label, path, query, method) {
      h.rules.push({ label: label, path: path, query: query || {}, method: method || 'GET' });
    },
    release: async function (label, result) {
      const index = h.pending.findIndex(function (p) { return p.label === label; });
      if (index < 0) throw new Error('No held request: ' + label);
      const request = h.pending.splice(index, 1)[0];
      if (result && result.reject) {
        request.reject(new TypeError(result.reject));
      } else if (result && Object.prototype.hasOwnProperty.call(result, 'body')) {
        request.resolve(response(result.body, result.status));
      } else {
        request.resolve(defaultReply(request.url, request.init));
      }
      await Promise.resolve();
    }
  };
  function response(body, status) {
    return new Response(JSON.stringify(body), {
      status: status || 200, headers: { 'Content-Type': 'application/json' }
    });
  }
  function discovery(dir) {
    const root = h.aliases[dir] || dir;
    const doc = h.documents[root];
    if (!doc) return response({ error: 'NOT_FOUND', message: '没有找到 Maven 项目：' + dir }, 404);
    return response({ rootDir: root, selectedDir: dir, rootPom: root + '/pom.xml',
      relocated: dir !== root, moduleCount: doc.modules.length,
      declaredDependencyCount: doc.project.declaredDependencyCount,
      discoverMillis: 1, modules: doc.modules });
  }
  function defaultReply(url, init) {
    const method = init.method || 'GET';
    if (url.pathname === '/api/config') {
      if (method === 'POST') h.config = Object.assign({}, h.config, JSON.parse(init.body));
      return response(h.config);
    }
    if (url.pathname === '/api/projects') return response(h.projects);
    if (url.pathname === '/api/discover') return discovery(url.searchParams.get('dir'));
    if (url.pathname === '/api/graph') {
      const doc = h.documents[url.searchParams.get('projectId')];
      return doc ? response(doc) : response({ message: 'Graph unavailable' }, 404);
    }
    if (url.pathname === '/api/scan') return response({ jobId: 'job-' + (++h.nextJob) });
    if (url.pathname === '/api/scan/status') return response(h.status);
    if (url.pathname === '/api/scan/cancel') return response({ cancelled: true });
    return nativeFetch(url.href, init);
  }
  window.fetch = function (raw, init) {
    init = init || {};
    const url = new URL(typeof raw === 'string' ? raw : raw.url, location.href);
    const method = init.method || 'GET';
    h.calls.push({ path: url.pathname, query: Object.fromEntries(url.searchParams),
      method: method, body: init.body || null });
    const index = h.rules.findIndex(function (rule) {
      return rule.path === url.pathname && rule.method === method &&
        Object.keys(rule.query).every(function (key) { return url.searchParams.get(key) === rule.query[key]; });
    });
    if (index >= 0) {
      const rule = h.rules.splice(index, 1)[0];
      return new Promise(function (resolve, reject) {
        h.pending.push({ label: rule.label, url: url, init: init, resolve: resolve, reject: reject });
      });
    }
    return Promise.resolve(defaultReply(url, init));
  };
})();
