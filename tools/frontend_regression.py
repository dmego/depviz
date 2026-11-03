#!/usr/bin/env python3
"""Eighteen deterministic production Chrome regressions for review #10–17 and search fixes.

Run: python3 tools/frontend_regression.py
Requires Python Playwright, system Chrome, Java and a built target/depviz.jar.
Generates demo graph data from the existing test POM/tree in a temporary directory
with isolated DEPVIZ_HOME; the CLI uses --tree so it never invokes Maven.
Loads the real repository HTML, app, scan, engine and views. Only HTTP transport
is controlled, so promises can finish/reject in an exact order. No app function
is extracted or reimplemented in the tests.
"""
import copy
from functools import lru_cache
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from urllib.parse import quote, urlparse

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
HARNESS = (ROOT / 'tools/frontend_regression_harness.js').read_text()
A, B = '/reg/A', '/reg/B'
CONFIG = dict(maven='/opt/maven/bin/mvn', javaHome='/opt/jdk',
              settingsXml='/reg/settings.xml', localRepo='/reg/repository',
              offline=True, profiles='review', xmx='3g', threads='1C',
              dataDir='/reg/data', logLevel='debug', fileLogLevel='warn')


@lru_cache(maxsize=1)
def demo_document():
    jar = ROOT / 'target/depviz.jar'
    if not jar.is_file():
        raise FileNotFoundError('Build target/depviz.jar before running frontend regressions')
    with TemporaryDirectory(prefix='depviz-frontend-') as directory:
        temp = Path(directory)
        output = temp / 'demo.json'
        subprocess.run(['java', '-jar', str(jar), 'graph',
                        'src/test/resources/projects/demo', '--tree',
                        'src/test/resources/tree/demo.txt', '-o', str(output)],
                       cwd=ROOT, env=dict(os.environ, DEPVIZ_HOME=str(temp / 'home')),
                       check=True)
        return json.loads(output.read_text(encoding='utf-8'))


def fixture(scanned=True):
    docs = {}
    for key in (A, B):
        doc = copy.deepcopy(demo_document())
        doc['project'].update(id=key, name=key.rsplit('/', 1)[-1], rootDir=key)
        docs[key] = doc
    return dict(config=CONFIG, documents=docs, aliases={'/reg/link-A': A},
                projects=[dict(key=k, rootDir=k, name=k.rsplit('/', 1)[-1],
                               moduleCount=1, when='刚刚', scanned=scanned)
                          for k in (A, B)])


def conflict_document(key):
    # split wins at different versions in the two modules; replaced has one loser.
    project = copy.deepcopy(demo_document()['project'])
    project.update(id=key, name='conflict-reset', rootDir=key, moduleCount=2,
                   declaredDependencyCount=3)
    modules = [dict(groupId='regression', artifactId=name, packaging='jar',
                    relPath='.' if i == 0 else name, declaredDeps=2 if i == 0 else 1,
                    childCount=0, declared=[], pomPath='')
               for i, name in enumerate(('module-a', 'module-b'))]
    return dict(project=project, modules=modules, moduleRoots=[0, 4], gas=[
        dict(g='regression', a='module-a', v=['1']),
        dict(g='regression', a='split', v=['1', '2']),
        dict(g='regression', a='replaced', v=['1', '2']),
        dict(g='regression', a='module-b', v=['1'])], occ=[
        dict(i=0, p=-1, ga=0, v=0, e=1, m=0, t='jar'),
        dict(i=1, p=0, ga=1, v=0, e=1, m=0, t='jar', s='compile'),
        dict(i=2, p=1, ga=2, v=1, e=0, m=0, t='jar', s='compile', o='conflict', ob='1'),
        dict(i=3, p=0, ga=2, v=0, e=1, m=0, t='jar', s='compile'),
        dict(i=4, p=-1, ga=3, v=0, e=1, m=1, t='jar'),
        dict(i=5, p=4, ga=1, v=1, e=1, m=1, t='jar', s='compile')])


class Suite:
    def __init__(self, browser):
        self.browser = browser
        self.count = 0
        self.failed = []

    def page(self, data=None, query='', setup=''):
        page = self.browser.new_page(viewport={'width': 1600, 'height': 1000})
        page.set_default_timeout(5000)
        page.errors = []
        page.on('pageerror', lambda error: page.errors.append(str(error)))

        def asset(route):
            path = urlparse(route.request.url).path
            target = ROOT / 'src/main/resources/static' / (path.lstrip('/') or 'index.html')
            if target.is_file():
                route.fulfill(path=str(target))
            else:
                route.fulfill(status=404, body='missing test asset')

        page.route('**/*', asset)
        page.add_init_script('window.__frontendFixture = ' + json.dumps(data or fixture()) + ';\n' + HARNESS + '\n' + setup)
        page.goto('http://frontend.test/' + query, wait_until='load')
        page.evaluate("""() => {
          __reg.messages = [];
          const toast = Views.toast;
          Views.toast = function (message) { __reg.messages.push(message); return toast(message); };
        }""")
        flush(page)
        return page

    def check(self, label, test):
        self.count += 1
        try:
            test()
            print('[OK] ' + label, flush=True)
        except Exception as error:
            self.failed.append(label)
            print('[FAIL] ' + label + ': ' + str(error), flush=True)
        finally:
            for context in self.browser.contexts:
                context.close()


def flush(page):
    page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')


def hold(page, label, path, query=None, method='GET'):
    page.evaluate('(r) => __reg.hold(...r)', [label, path, query or {}, method])


def pending(page, label):
    page.wait_for_function('(label) => __reg.pending.some(p => p.label === label)', arg=label)


def release(page, label, result=None):
    page.evaluate('(r) => __reg.release(...r)', [label, result])
    flush(page)


def calls(page, path, method='GET'):
    return page.evaluate('(r) => __reg.calls.filter(c => c.path === r[0] && c.method === r[1])', [path, method])


def recent(page, name):
    page.locator('.recent-item').filter(has_text=name).first.click()


def close_settings(page):
    page.locator('#dlg-settings [data-close]').click()


def settings_loading(s):
    p = s.page()
    hold(p, 'load', '/api/config')
    p.locator('#btn-settings-home').click()
    pending(p, 'load')
    assert p.locator('#btn-settings-save').is_disabled()
    p.locator('#btn-settings-save').dispatch_event('click')
    assert calls(p, '/api/config', 'POST') == []
    release(p, 'load', {'body': {'message': 'unavailable'}, 'status': 503})
    assert p.locator('#btn-settings-save').is_disabled()
    assert '读取配置失败' in p.locator('#toast').inner_text()
    p.locator('#btn-settings-save').dispatch_event('click')
    assert calls(p, '/api/config', 'POST') == []
    assert not p.errors, p.errors


def settings_sessions(s):
    p = s.page()
    hold(p, 'old', '/api/config')
    p.locator('#btn-settings-home').click()
    close_settings(p)
    hold(p, 'fresh', '/api/config')
    p.locator('#btn-settings-home').click()
    release(p, 'fresh')
    p.locator('#s-profiles').fill('edited-new-session')
    release(p, 'old', {'body': dict(CONFIG, profiles='stale')})
    assert p.locator('#s-profiles').input_value() == 'edited-new-session'
    assert p.locator('#btn-settings-save').is_enabled()
    close_settings(p)
    hold(p, 'old-error', '/api/config')
    p.locator('#btn-settings-home').click()
    close_settings(p)
    hold(p, 'fresh2', '/api/config')
    p.locator('#btn-settings-home').click()
    release(p, 'fresh2')
    release(p, 'old-error', {'reject': 'old connection failed'})
    assert p.locator('#btn-settings-save').is_enabled()
    assert not any('读取配置失败' in m for m in p.evaluate('__reg.messages'))
    hold(p, 'save', '/api/config', method='POST')
    p.locator('#btn-settings-save').click()
    assert p.locator('#btn-settings-save').is_disabled()
    close_settings(p)
    p.locator('#btn-settings-home').click()
    release(p, 'save')
    assert p.locator('#dlg-settings').is_visible()
    assert p.locator('#btn-settings-save').is_enabled()
    hold(p, 'save-error', '/api/config', method='POST')
    p.locator('#btn-settings-save').click()
    release(p, 'save-error', {'reject': 'save connection failed'})
    assert p.locator('#btn-settings-save').is_enabled()
    assert '保存失败' in p.locator('#toast').inner_text()
    assert not p.errors, p.errors


def custom_settings(s):
    p = s.page()
    for xmx in ('3g', '1g'):
        p.evaluate('(v) => { __reg.config.xmx=v; __reg.config.threads="1C"; }', xmx)
        p.locator('#btn-settings-home').click()
        p.wait_for_function('!document.getElementById("btn-settings-save").disabled')
        assert p.locator('#s-xmx').input_value() == xmx
        assert p.locator('#s-threads').input_value() == '1C'
        assert p.locator('#s-xmx option[data-custom]').count() == 1
        p.locator('#s-profiles').fill('profile-only-change')
        p.locator('#btn-settings-save').click()
        p.locator('#dlg-settings').wait_for(state='hidden')
        body = json.loads(calls(p, '/api/config', 'POST')[-1]['body'])
        assert body['xmx'] == xmx and body['threads'] == '1C', body
        assert body['maven'] == CONFIG['maven'] and body['dataDir'] == CONFIG['dataDir']
    assert not p.errors, p.errors


def project_races(s, scanned):
    for old_fails in (False, True):
        p = s.page(fixture(scanned))
        path = '/api/graph' if scanned else '/api/discover'
        field = 'projectId' if scanned else 'dir'
        hold(p, 'A', path, {field: A})
        hold(p, 'B', path, {field: B})
        recent(p, 'A')
        recent(p, 'B')
        pending(p, 'A')
        pending(p, 'B')
        release(p, 'B')
        release(p, 'A', {'reject': 'A failed late'} if old_fails else None)
        if scanned:
            assert p.locator('#project-name').inner_text() == 'B'
            assert p.locator('#view-explore').is_visible()
        else:
            assert 'B' in p.locator('#confirm-title').inner_text()
            hold(p, 'scan', '/api/scan', method='POST')
            p.locator('#btn-scan').click()
            pending(p, 'scan')
            assert calls(p, '/api/scan', 'POST')[-1]['query']['dir'] == B
        assert not any('A failed late' in m for m in p.evaluate('__reg.messages'))
        assert not p.errors, p.errors
        p.close()


def navigation_exit(s):
    for fails in (False, True):
        p = s.page(fixture(False))
        recent(p, 'B')
        p.locator('#stage-confirm').wait_for(state='visible')
        hold(p, 'A', '/api/discover', {'dir': A})
        p.evaluate('() => { document.querySelectorAll(".recent-item")[0].click(); }')
        pending(p, 'A')
        p.locator('#btn-confirm-back').click()
        release(p, 'A', {'reject': 'A failed after back'} if fails else None)
        assert p.locator('#stage-pick').is_visible()
        assert not any('after back' in m for m in p.evaluate('__reg.messages'))
        assert not p.errors, p.errors
        p.close()
    p = s.page()
    recent(p, 'A')
    p.locator('#view-explore').wait_for(state='visible')
    p.locator('#btn-project').click()
    hold(p, 'menu-B', '/api/graph', {'projectId': B})
    p.locator('#pm-list [data-key="/reg/B"]').click()
    pending(p, 'menu-B')
    p.evaluate('document.getElementById("pm-new").click()')
    release(p, 'menu-B')
    assert p.locator('#stage-pick').is_visible()
    assert not p.errors, p.errors


def path_races(s):
    for fails in (False, True):
        p = s.page(fixture(False))
        hold(p, 'path-A', '/api/discover', {'dir': A})
        p.locator('#path-input').fill(A)
        p.locator('#btn-path-go').click()
        p.locator('#path-input').fill(B)
        p.locator('#btn-path-go').click()
        p.locator('#stage-confirm').wait_for(state='visible')
        release(p, 'path-A', {'reject': 'path failed late'} if fails else None)
        assert 'B' in p.locator('#confirm-title').inner_text()
        assert not any('path failed late' in m for m in p.evaluate('__reg.messages'))
        assert not p.errors, p.errors
        p.close()


def deep_links(s):
    for path in (A, '/reg/link-A'):
        data = fixture(False)
        data['projects'] = []
        p = s.page(data, '?project=' + quote(path, safe='') + '&q=jkdb')
        p.locator('#stage-confirm').wait_for(state='visible')
        assert A in p.evaluate('Object.keys(Scan.PROJECTS)')
        assert 'A' in p.locator('#confirm-title').inner_text()
        assert p.locator('#btn-scan').is_enabled()
        assert calls(p, '/api/graph') == []
        assert urlparse(p.url).query.startswith('project=%2Freg%2FA')
        assert not p.errors, p.errors
        p.close()
    p = s.page(query='?project=%2Freg%2Fmissing')
    p.wait_for_function('document.getElementById("toast").textContent.includes("没有找到")')
    assert p.locator('#stage-pick').is_visible()
    assert not p.errors, p.errors
    p.close()
    p = s.page(query='?project=' + quote(A, safe='') + '&q=jkdb')
    p.locator('#view-explore').wait_for(state='visible')
    assert p.locator('#search-input').input_value() == 'jkdb'
    assert p.locator('#result-list .dep-row').count() > 0
    assert not p.errors, p.errors


def stale_deep_link(s):
    setup = "__reg.hold('deep', '/api/discover', {dir:'/reg/link-A'});"
    p = s.page(query='?project=%2Freg%2Flink-A', setup=setup)
    pending(p, 'deep')
    recent(p, 'B')
    p.locator('#view-explore').wait_for(state='visible')
    release(p, 'deep')
    assert p.locator('#project-name').inner_text() == 'B'
    assert 'link-A' in p.url
    assert not p.errors, p.errors
    p.close()
    setup = "__reg.hold('boot', '/api/projects'); __reg.hold('boot2', '/api/projects'); __reg.hold('refresh', '/api/projects');"
    p = s.page(query='?project=%2Freg%2FA', setup=setup)
    pending(p, 'refresh')
    p.locator('#path-input').fill(B)
    p.locator('#btn-path-go').click()
    p.locator('#stage-confirm').wait_for(state='visible')
    release(p, 'refresh')
    assert p.locator('#stage-confirm').is_visible()
    assert 'B' in p.locator('#confirm-title').inner_text()
    assert calls(p, '/api/graph') == []
    assert not p.errors, p.errors


def scan_callbacks(s):
    # Exercise real scan.js directly so the app's navigation guard cannot mask a failure.
    for late in ('reject', 'done', 'failed', 'cancelled'):
        p = s.page()
        hold(p, 'old-status', '/api/scan/status', {'jobId': 'job-1'})
        p.evaluate("""() => {
          const cb = n => ({done:()=>__reg.events.push(n+' done'),
            error:()=>__reg.events.push(n+' error'),cancelled:()=>__reg.events.push(n+' cancelled')});
          __reg.cb = cb; __reg.old = Scan.runScan('/reg/A', cb('A'));
        }""")
        pending(p, 'old-status')
        p.evaluate("() => { Scan.cancel(); Scan.runScan('/reg/B', __reg.cb('B')); }")
        result = {'reject': 'old disconnected'} if late == 'reject' else {
            'body': {'status': late, 'progress': 1, 'logLines': ['STALE LOG'], 'error': {'message': 'old failure'}}}
        release(p, 'old-status', result)
        assert p.evaluate('__reg.events') == ['A cancelled']
        assert 'STALE LOG' not in p.locator('#scan-log').inner_text()
        assert 'B' in p.locator('#scan-title').inner_text()
        p.evaluate('Scan.cancel()')
        assert p.evaluate('__reg.events') == ['A cancelled', 'B cancelled']
        assert not p.errors, p.errors
        p.close()
    p = s.page()
    hold(p, 'post-A', '/api/scan', {'dir': A}, 'POST')
    p.evaluate("""() => {
      const cb = n => ({done:()=>__reg.events.push(n+' done'),
        error:()=>__reg.events.push(n+' error'),cancelled:()=>__reg.events.push(n+' cancelled')});
      __reg.old=Scan.runScan('/reg/A',cb('A')); Scan.cancel();
      __reg.fresh=Scan.runScan('/reg/B',cb('B'));
    }""")
    p.wait_for_function('__reg.fresh.id !== null')
    current_id = p.evaluate('__reg.fresh.id')
    release(p, 'post-A', {'body': {'jobId': 'late-A'}})
    assert p.evaluate('__reg.fresh.id') == current_id
    p.evaluate('Scan.cancel()')
    cancelled = [call['query']['jobId'] for call in calls(p, '/api/scan/cancel', 'POST')]
    assert cancelled == ['late-A', current_id], cancelled
    assert not p.errors, p.errors


def scan_terminal(s):
    for graph_error in (False, True):
        p = s.page()
        p.evaluate('__reg.status = {status:"done",progress:1,logLines:[]}')
        hold(p, 'graph', '/api/graph', {'projectId': A})
        p.evaluate("Scan.runScan('/reg/A', {done:()=>__reg.events.push('done'),error:()=>__reg.events.push('error'),cancelled:()=>__reg.events.push('cancelled')})")
        pending(p, 'graph')
        release(p, 'graph', {'reject': 'graph failed'} if graph_error else None)
        p.evaluate('Scan.cancel()')
        assert p.evaluate('__reg.events') == ['error' if graph_error else 'done']
        assert not p.errors, p.errors
        p.close()
    p = s.page()
    p.evaluate('__reg.status = {status:"done",progress:1,logLines:[]}')
    hold(p, 'graph', '/api/graph', {'projectId': A})
    p.evaluate("Scan.runScan('/reg/A', {done:()=>__reg.events.push('done'),error:()=>__reg.events.push('error'),cancelled:()=>__reg.events.push('cancelled')})")
    pending(p, 'graph')
    p.evaluate('Scan.cancel()')
    release(p, 'graph', {'reject': 'graph failed after cancel'})
    assert p.evaluate('__reg.events') == ['cancelled']
    assert not p.errors, p.errors


def search_page(s):
    p = s.page(query='?project=' + quote(A, safe=''))
    p.locator('#view-explore').wait_for(state='visible')
    # Observe the real renderer's input to await the 250 ms debounced search.
    p.evaluate("""() => {
      const render=Views.renderResults;
      Views.renderResults=function(store,res) { __reg.renderedQuery=res.query; return render.apply(this,arguments); };
    }""")
    return p


def wait_search(p, query):
    p.locator('#search-input').fill(query)
    p.wait_for_function('(q) => __reg.renderedQuery === q', arg=query)


def search(s):
    p = search_page(s)
    for query in ('spcgtw', 'jkdb', 'springcloudgateway'):
        p.locator('#search-input').fill(query)
        p.wait_for_function('(q) => __reg.renderedQuery === q', arg=query)
        assert p.locator('#results-title').inner_text() == '搜索结果 · 2 个坐标'
        assert p.locator('#result-list .dep-row').count() > 0
        p.locator('#search-input').press('Escape')
        p.locator('#suggest-pop').wait_for(state='hidden')
        p.locator('#view-flat').click()
        assert p.locator('#result-list .dep-row').count() == 2
        p.locator('#view-tree').click()
        assert p.locator('#result-list .dep-row').count() > 0
    # 匹配子串高亮：搜 ds，grpc-xds 中的 ds 必须被 <mark> 包住。
    p.locator('#search-input').fill('ds')
    p.wait_for_function('(q) => __reg.renderedQuery === q', arg='ds')
    p.wait_for_function("() => !document.querySelector('#suggest-pop').hidden")
    p.wait_for_timeout(300)
    marks = p.evaluate("() => [...document.querySelectorAll('#suggest-pop mark')].map(m => m.textContent.toLowerCase())")
    assert 'ds' in marks, marks
    p.locator('#suggest-pop .sg-item:not(.is-raw)').first.click()
    assert p.input_value('#search-input') == 'grpc-xds'
    assert p.locator('#result-list .dep-row.is-selected').count() == 1
    # 精确命中叶子、但祖先被 scope 过滤隐藏时，树视图仍要有可点的引入链。
    p.locator('#search-input').fill('spring-aop')
    p.wait_for_function('(q) => __reg.renderedQuery === q', arg='spring-aop')
    p.wait_for_timeout(300)
    assert p.locator('#result-list .dep-row').count() > 0
    leaf = p.locator('#result-list .dep-row').last
    leaf.click()
    assert 'is-selected' in (leaf.get_attribute('class') or '')
    # 1000px 窄一些的桌面窗口里，面板仍要和搜索框对齐。
    p.set_viewport_size({'width': 1000, 'height': 900})
    p.locator('#search-input').fill('spring')
    p.wait_for_function('(q) => __reg.renderedQuery === q', arg='spring')
    p.wait_for_function("() => !document.querySelector('#suggest-pop').hidden")
    p.wait_for_timeout(300)
    box = p.locator('#searchbox').bounding_box()
    pop_box = p.locator('#suggest-pop').bounding_box()
    assert box and pop_box
    assert abs(pop_box['x'] - box['x']) <= 1, (box, pop_box)
    assert abs(pop_box['width'] - box['width']) <= 2, (box, pop_box)
    stats = p.evaluate("""() => {
      const n=260, gas=[{g:'root',a:'parent',v:['1']}], occ=[{i:0,p:-1,ga:0,v:0,e:1,m:0,t:'jar'}];
      for(let i=1;i<=n;i++) { gas.push({g:'group',a:'fuzzy-match-'+i,v:['1']}); occ.push({i,p:0,ga:i,v:0,e:1,m:0,t:'jar',s:'compile'}); }
      const doc={project:{},gas,occ,modules:[{}],moduleRoots:[0]};
      const store=Engine.build(doc), opts={hideTest:false,hideProvided:false};
      const results=Engine.search(store,'fzmt',opts), vis=Engine.treeVisible(store,'fzmt',opts);
      return {search:results.results.length,total:results.total,visible:Array.from(vis).filter(Boolean).length,root:vis[0]};
    }""")
    assert stats['search'] == 200 and stats['visible'] == 261 and stats['root'] == 1, stats
    assert not p.errors, p.errors


def search_panel(s):
    p = search_page(s)
    p.set_viewport_size({'width': 1200, 'height': 900})
    base_list = p.locator('#result-list').bounding_box()
    base_graph = p.locator('.col.graph').bounding_box()
    wait_search(p, 'spring')
    p.wait_for_function("() => !document.querySelector('#suggest-pop').hidden")
    p.wait_for_timeout(300)
    box = p.locator('#searchbox').bounding_box()
    pop_box = p.locator('#suggest-pop').bounding_box()
    list_box = p.locator('#result-list').bounding_box()
    graph_box = p.locator('.col.graph').bounding_box()
    assert box and pop_box and list_box and base_list and base_graph and graph_box
    assert abs(pop_box['x'] - box['x']) <= 1, (box, pop_box)
    assert abs(pop_box['width'] - box['width']) <= 2, (box, pop_box)
    assert abs(list_box['y'] - base_list['y']) <= 1, (base_list, list_box)
    assert abs(graph_box['y'] - base_graph['y']) <= 1, (base_graph, graph_box)
    row = p.locator('#result-list .dep-row').first
    before = row.bounding_box()
    assert before and before['y'] >= pop_box['y'] + pop_box['height'] - 1, (pop_box, before)
    p.mouse.move(before['x'] + before['width'] / 2, before['y'] + before['height'] / 2)
    p.mouse.down()
    p.wait_for_timeout(250)  # 超过输入框 blur 的 120ms 延迟
    assert p.locator('#suggest-pop').is_visible(), 'mousedown 后联想面板不应消失'
    after = row.bounding_box()
    assert before and after and abs(after['y'] - before['y']) <= 1, (before, after)
    p.mouse.up()
    assert 'is-selected' in (row.get_attribute('class') or '')
    assert not p.errors, p.errors
    p.close()


def search_double_click(s):
    p = search_page(s)
    p.set_viewport_size({'width': 1200, 'height': 900})
    wait_search(p, 'spring')
    p.wait_for_function("() => !document.querySelector('#suggest-pop').hidden")
    p.wait_for_timeout(300)
    info = p.evaluate("""() => {
      const rows=[...document.querySelectorAll('#result-list .occ-row')];
      const row=rows.find(x => x.querySelector('[data-twisty]') && x.querySelector('.chev'));
      if (!row) return null;
      const box=row.getBoundingClientRect();
      return {occ:Number(row.dataset.occ), x:box.x+box.width/2, y:box.y+box.height/2};
    }""")
    assert info, 'no expandable tree row'
    # 第一次 click 会 renderList 重建 DOM，Chrome 不再派发 dblclick；
    # 第二击的 detail=2 必须由 click 处理器直接折叠。
    p.mouse.click(info['x'], info['y'], click_count=2)
    p.wait_for_timeout(400)
    assert p.evaluate('(occ) => !!(window.__treeCollapsed && window.__treeCollapsed.has(occ))', info['occ'])
    assert p.locator('#suggest-pop').is_visible(), '双击后让位应保持到结果栏外点击'
    assert not p.errors, p.errors
    p.close()


def search_suggest_and_small(s):
    p = search_page(s)
    p.set_viewport_size({'width': 1200, 'height': 900})
    wait_search(p, 'ds')
    p.wait_for_function("() => !document.querySelector('#suggest-pop').hidden")
    p.wait_for_timeout(200)
    p.locator('#suggest-pop .sg-item:not(.is-raw)').first.click()
    assert p.input_value('#search-input') == 'grpc-xds'
    assert p.evaluate('() => document.activeElement && document.activeElement.id') == 'search-input'
    # 选中项 preventDefault 保住焦点，点输入框本身也要能重开。
    p.locator('#search-input').click()
    p.wait_for_function("() => !document.querySelector('#suggest-pop').hidden")
    # 380px 高窗口：面板放不下时收起，结果首行必须仍可见；放得下时不能压住首行。
    p.set_viewport_size({'width': 1200, 'height': 380})
    wait_search(p, 'spring')
    p.wait_for_timeout(300)
    assert p.locator('#result-list .dep-row').count() > 0
    if p.locator('#suggest-pop').is_visible():
        row = p.locator('#result-list .dep-row').first.bounding_box()
        pop = p.locator('#suggest-pop').bounding_box()
        assert row and pop and row['y'] >= pop['y'] + pop['height'] - 1, (row, pop)
    assert not p.errors, p.errors
    p.close()


def search_vlist_scroll(s):
    p = search_page(s)
    p.set_viewport_size({'width': 1200, 'height': 900})
    wait_search(p, 'spring')
    p.wait_for_function("() => !document.querySelector('#suggest-pop').hidden")
    p.wait_for_timeout(300)
    metrics = p.evaluate("""() => {
      const list=document.querySelector('#result-list');
      return {scrollHeight:list.scrollHeight, clientHeight:list.clientHeight,
              pad:getComputedStyle(list).getPropertyValue('--suggest-pad')};
    }""")
    assert float(metrics['pad'].replace('px', '')) > 0, metrics
    p.evaluate("() => { const list=document.querySelector('#result-list'); list.scrollTop=1e9; }")
    p.wait_for_timeout(200)
    actual = p.evaluate("""() => {
      const list=document.querySelector('#result-list');
      return {scrollTop:list.scrollTop, max:list.scrollHeight-list.clientHeight};
    }""")
    assert abs(actual['scrollTop'] - actual['max']) <= 1, (metrics, actual)
    assert not p.errors, p.errors
    p.close()


def conflicts(s):
    key = '/reg/conflict-reset'
    data = fixture()
    data['documents'][key] = conflict_document(key)
    data['projects'].append(dict(key=key, rootDir=key, name='conflict-reset',
                                moduleCount=2, when='刚刚', scanned=True))
    p = s.page(data, query='?project=' + quote(key, safe=''))
    p.locator('#view-explore').wait_for(state='visible')
    actual = p.evaluate("""key => {
      const result = Engine.conflicts(Engine.build(__reg.documents[key]), '', {});
      return {total: result.total, rows: result.conflicts.map(c => ({
        ga: c.ga, splitWinner: c.splitWinner,
        winners: c.winners.map(v => [v.version, v.winModuleIds]),
        losers: c.losers.map(v => [v.version, v.moduleIds])
      }))};
    }""", key)
    assert actual == {'total': 2, 'rows': [
        {'ga': 'regression:split', 'splitWinner': True,
         'winners': [['1', [0]], ['2', [1]]], 'losers': []},
        {'ga': 'regression:replaced', 'splitWinner': False,
         'winners': [['1', [0]]], 'losers': [['2', [0]]]}]}, actual
    p.locator('#tab-conflicts').click()
    full = p.locator('#conflict-body > tr').count()
    p.locator('label').filter(has=p.locator('#chk-rewritten')).click()
    assert p.locator('#chk-rewritten').is_checked()
    restricted = p.locator('#conflict-body > tr').count()
    assert full == 2 and restricted == 1, (full, restricted)
    assert p.locator('#conflict-body .c-link').all_text_contents() == ['regression:replaced']
    p.locator('#btn-project').click()
    p.locator('#pm-new').click()
    recent(p, 'conflict-reset')
    p.locator('#view-explore').wait_for(state='visible')
    p.locator('#tab-conflicts').click()
    assert not p.locator('#chk-rewritten').is_checked()
    assert p.locator('#conflict-body > tr').count() == full
    assert not p.errors, p.errors


def rescan_reset(s):
    p = s.page(query='?project=' + quote(A, safe=''))
    p.locator('#view-explore').wait_for(state='visible')
    p.evaluate('__reg.status={status:"failed",error:{error:"SCAN_FAILED",message:"offline failure"},logLines:[]}')
    p.locator('#btn-rescan').click()
    p.locator('#scan-fail').wait_for(state='visible')
    p.locator('#btn-scan-back').click()
    recent(p, 'A')
    p.locator('#view-explore').wait_for(state='visible')
    p.evaluate('__reg.status={status:"running",progress:0.2,logLines:[]}')
    p.locator('#btn-rescan').click()
    assert p.locator('#stage-scan').is_visible()
    assert p.locator('#scan-fail').is_hidden()
    assert p.locator('#scan-actions-running').is_visible()
    assert p.locator('#btn-scan-cancel').is_visible()
    assert p.locator('#scan-done').is_hidden()
    p.locator('#btn-scan-cancel').click()
    p.locator('#stage-confirm').wait_for(state='visible')
    hold(p, 'retry', '/api/scan', method='POST')
    p.locator('#btn-scan').click()
    pending(p, 'retry')
    assert calls(p, '/api/scan', 'POST')[-1]['query']['dir'] == A
    assert not p.errors, p.errors


def main():
    demo_document()
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel='chrome', headless=True)
        s = Suite(browser)
        s.check('#10 settings load gate / failure', lambda: settings_loading(s))
        s.check('#10 settings session success/error/save ownership', lambda: settings_sessions(s))
        s.check('#11 custom xmx/threads round trip', lambda: custom_settings(s))
        s.check('#12 cancelled status then/catch ownership', lambda: scan_callbacks(s))
        s.check('#12 graph load success/error/cancel terminal', lambda: scan_terminal(s))
        s.check('#13 cached project A/B success and error', lambda: project_races(s, True))
        s.check('#13 confirmation A/B success and error', lambda: project_races(s, False))
        s.check('#13 back/home invalidates pending navigation', lambda: navigation_exit(s))
        s.check('#13 path input A/B success and error', lambda: path_races(s))
        s.check('#14 first canonical/symlink/invalid/cached deep link', lambda: deep_links(s))
        s.check('#13/#14 stale discovery and boot deep link', lambda: stale_deep_link(s))
        s.check('#15 fuzzy tree/flat uncapped matching production', lambda: search(s))
        s.check('#15 suggestion panel geometry / mousedown', lambda: search_panel(s))
        s.check('#15 double-click tree collapse survives row re-render', lambda: search_double_click(s))
        s.check('#15 suggestion reopen after pick / short window', lambda: search_suggest_and_small(s))
        s.check('#15 virtual list pad contributes to scroll height', lambda: search_vlist_scroll(s))
        s.check('#16 conflict reset on reopen production', lambda: conflicts(s))
        s.check('#17 failure/back/cached/rescan/cancel target production', lambda: rescan_reset(s))
        browser.close()
        print('%d checks, %d failures' % (s.count, len(s.failed)), flush=True)
        return bool(s.failed)


if __name__ == '__main__':
    raise SystemExit(main())
