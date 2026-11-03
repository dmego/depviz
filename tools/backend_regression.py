#!/usr/bin/env python3
"""审查问题回归：真实 Maven/HTTP、重启后的 POM 身份、跨 JVM 任务和 Origin 防护。

先运行 mvn -B -o package；运行期间只写独立临时目录，不读取用户配置。
需要本地缓存的 dependency:3.6.0、junit:4.13.2，以及 Playwright + Chrome。
"""
import contextlib
import http.server
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / 'target' / 'depviz.jar'
CHECKS = 0


def check(condition, message):
    global CHECKS
    if not condition:
        raise AssertionError(message)
    CHECKS += 1
    print('[OK] ' + message, flush=True)


def until(predicate, message, seconds=20):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = predicate()
        if value:
            return value
        time.sleep(.05)
    raise AssertionError(message)


class Server:
    def __init__(self, home):
        self.home = home
        home.mkdir(parents=True, exist_ok=True)
        self.log_path = home / ('server-%d.log' % time.time_ns())
        self.log = self.log_path.open('w')
        self.proc = subprocess.Popen(
            ['java', '-Djava.awt.headless=true', '-jar', str(JAR),
             'serve', '--port', '0', '--no-browser'],
            stdout=self.log, stderr=subprocess.STDOUT,
            env=dict(os.environ, DEPVIZ_HOME=str(home)))
        try:
            self.url = until(self._url, '服务未能启动：' + str(home), 30)
        except BaseException:
            self.close()
            raise

    def _url(self):
        content = self.log_path.read_text()
        if self.proc.poll() is not None:
            raise AssertionError('服务提前退出：' + content)
        match = re.search(r'http://127\.0\.0\.1:\d+', content)
        return match.group(0) if match else None

    def api(self, path, method='GET', body=None):
        data = json.dumps(body).encode() if body is not None else (b'' if method == 'POST' else None)
        req = urllib.request.Request(self.url + path, data=data, method=method)
        return json.load(urllib.request.urlopen(req, timeout=10))

    def scan(self, directory):
        job = self.api('/api/scan?dir=' + quote(directory), 'POST')['jobId']
        def finished():
            status = self.api('/api/scan/status?jobId=' + job)
            return status if status['status'] != 'running' else None
        status = until(finished, '扫描未结束：' + str(directory), 90)
        if status['status'] != 'done':
            raise AssertionError('扫描失败：' + json.dumps(status, ensure_ascii=False))
        # 终态发布后 finally 仍会短暂执行日志留档，下一次扫描应等待目录回收。
        until(lambda: not list((self.home / 'data' / 'jobs').glob('scan-*')),
              '任务目录未释放')
        return self.graph(directory)

    def graph(self, key):
        return self.api('/api/graph?projectId=' + quote(key))

    def close(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)
        self.log.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def quote(value):
    return urllib.parse.quote(str(value), safe='')


def pom(path, artifact, extra='', group='test.review', version='1'):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('<project><modelVersion>4.0.0</modelVersion><groupId>' + group
                    + '</groupId><artifactId>' + artifact + '</artifactId><version>' + version + '</version>'
                    + '<packaging>pom</packaging>' + extra + '</project>', encoding='utf-8')


def dependency(artifact, version='1'):
    return ('<dependencies><dependency><groupId>test.review</groupId><artifactId>'
            + artifact + '</artifactId><version>' + version + '</version><type>pom</type>'
            + '</dependency></dependencies>')


def assert_identity(server, key, expected, label):
    graph = server.graph(key)
    modules = graph['modules']
    roots = graph['moduleRoots']
    check(len(modules) == len(roots) == len(expected), label + '：模块槽位与根一一对应')
    # 旧的单版本夹具用 artifactId 简写；多版本夹具显式提供完整 GAV。
    identities = {identity if ':' in identity else 'test.review:' + identity + ':1': path
                  for identity, path in expected.items()}
    seen = set()
    for mid, oid in enumerate(roots):
        occurrence = graph['occ'][oid]
        ga = graph['gas'][occurrence['ga']]
        module = modules[mid]
        name = ga['a']
        version = ga['v'][occurrence['v']]
        gav = ga['g'] + ':' + name + ':' + version
        check(occurrence['m'] == mid and module['groupId'] == ga['g']
              and module['artifactId'] == name, label + '：模块编号与 GA 对齐 ' + gav)
        check(module['pomPath'] == identities[gav], label + '：实际 POM 身份 ' + gav)
        text = server.api('/api/pom?projectId=' + quote(key) + '&moduleId=' + str(mid))
        model = ET.fromstring(text['pomText'])
        check(model.findtext('artifactId') == name and model.findtext('version') == version
              and Path(text['pomPath']).resolve() == (key / identities[gav]).resolve(),
              label + '：HTTP 读取正确 GAV 的 POM ' + gav)
        seen.add(gav)
    check(seen == set(identities), label + '：无遗漏或额外模块')
    check(all(0 <= node['m'] < len(modules) for node in graph['occ']), label + '：无越界模块编号')
    return graph


def maven_cases(base):
    home = base / 'real-home'
    home.mkdir()
    (home / 'config.json').write_text(json.dumps({
        'offline': True, 'xmx': 'off', 'dataDir': str(home / 'data')}))
    cases = []
    ordinary = base / 'ordinary'
    pom(ordinary / 'pom.xml', 'root', '<modules><module>app</module><module>lib</module></modules>')
    pom(ordinary / 'app/pom.xml', 'app', dependency('lib'))
    pom(ordinary / 'lib/pom.xml', 'lib')
    cases.append((ordinary, {'root': 'pom.xml', 'lib': 'lib/pom.xml', 'app': 'app/pom.xml'}, '普通 reactor', ''))

    versions = base / 'same-ga-versions'
    pom(versions / 'pom.xml', 'root', '<modules><module>v1</module><module>v2</module></modules>')
    pom(versions / 'v1/pom.xml', 'core', dependency('core', '2'))
    pom(versions / 'v2/pom.xml', 'core', version='2')
    cases.append((versions, {'root': 'pom.xml', 'test.review:core:1': 'v1/pom.xml',
                             'test.review:core:2': 'v2/pom.xml'}, '同 GA 不同版本 reactor', ''))

    profiled = base / 'profiled'
    pom(profiled / 'pom.xml', 'root', '<modules><module>known</module></modules>'
        '<profiles><profile><id>default</id><activation><activeByDefault>true</activeByDefault>'
        '</activation><modules><module>profiled</module></modules></profile></profiles>')
    pom(profiled / 'known/pom.xml', 'known', dependency('profiled'))
    pom(profiled / 'profiled/pom.xml', 'profiled')
    cases.append((profiled, {'root': 'pom.xml', 'known': 'known/pom.xml',
                            'profiled': 'profiled/pom.xml'}, '默认 profile', ''))

    explicit = base / 'explicit'
    pom(explicit / 'pom.xml', 'root', '<profiles><profile><id>default</id>'
        '<activation><activeByDefault>true</activeByDefault></activation>'
        '<modules><module>default</module></modules></profile><profile><id>extra</id>'
        '<modules><module>extra</module></modules></profile></profiles>')
    pom(explicit / 'default/pom.xml', 'default')
    pom(explicit / 'extra/pom.xml', 'extra')
    cases.append((explicit, {'root': 'pom.xml', 'extra': 'extra/pom.xml'}, '显式 profile', 'extra'))

    prop = base / 'property'
    pom(prop / 'pom.xml', 'root', '<modules><module>property</module><module>known</module></modules>')
    pom(prop / 'property/pom.xml', 'property',
        '<properties><module.group>test.review</module.group></properties>', '${module.group}')
    pom(prop / 'known/pom.xml', 'known', dependency('property'))
    cases.append((prop, {'root': 'pom.xml', 'property': 'property/pom.xml',
                        'known': 'known/pom.xml'}, '属性坐标', ''))

    custom = base / 'custom'
    pom(custom / 'pom.xml', 'root', '<modules><module>child/custom.xml</module></modules>')
    pom(custom / 'child/custom.xml', 'custom')
    cases.append((custom, {'root': 'pom.xml', 'custom': 'child/custom.xml'}, '自定义 POM', ''))

    outer = base / 'outer'
    pom(outer / 'pom.xml', 'outer', '<modules><module>included</module></modules>')
    pom(outer / 'included/pom.xml', 'included')
    standalone = outer / 'included/standalone'
    pom(standalone / 'pom.xml', 'standalone')
    cases.append((standalone, {'standalone': 'pom.xml'}, '未列入 reactor 的独立项目', ''))

    unicode = base / 'unicode'
    pom(unicode / 'pom.xml', 'unicode', '<dependencies><dependency><groupId>junit</groupId>'
        '<artifactId>junit</artifactId><version>4.13.2</version></dependency></dependencies>'
        '<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>'
        '<artifactId>maven-dependency-plugin</artifactId><configuration><tokens>extended</tokens>'
        '</configuration></plugin></plugins></build>')
    cases.append((unicode, {'unicode': 'pom.xml'}, 'Unicode 树线', ''))

    with Server(home) as server:
        for key, expected, label, profiles in cases:
            server.api('/api/config', 'POST', {'profiles': profiles})
            discovered = server.api('/api/discover?dir=' + quote(key))
            check(Path(discovered['rootDir']).resolve() == key.resolve(), label + '：选择正确工程根')
            server.scan(key)
            graph = assert_identity(server, key, expected, label)
            if key == ordinary:
                check([m['artifactId'] for m in graph['modules']][:2] == ['lib', 'app'],
                      '依赖顺序确实不同于磁盘声明顺序')
            if key == versions:
                core_versions = [graph['gas'][graph['occ'][oid]['ga']]['v'][graph['occ'][oid]['v']]
                                 for oid in graph['moduleRoots']
                                 if graph['gas'][graph['occ'][oid]['ga']]['a'] == 'core']
                check(core_versions == ['2', '1'], '同 GA 的 Maven 版本顺序确实与声明顺序相反')
            if key == unicode:
                check(any(g['g'] == 'junit' and g['a'] == 'junit' for g in graph['gas'])
                      and all(not any(c in g['g'] for c in '└├│─') for g in graph['gas']),
                      'Unicode 依赖保留正确 GA，不生成伪模块')
    with Server(home) as restored:
        for key, expected, label, _ in cases:
            assert_identity(restored, key, expected, '重启恢复 ' + label)


def multi_instance(base):
    if os.name == 'nt':
        print('[SKIP] 双 JVM 屏障夹具依赖 POSIX 可执行脚本；跨进程目录/存储测试仍由 Java 单测覆盖')
        return
    home = base / 'shared-home'
    home.mkdir()
    fake = home / 'fake-maven'
    fake.write_text('#!' + sys.executable + '\n' + '''import pathlib,sys,time
if '-v' in sys.argv:
 print('Apache Maven 3.9.16'); raise SystemExit(0)
root=pathlib.Path.cwd()
out=pathlib.Path(next(a.split('=',1)[1] for a in sys.argv if a.startswith('-DoutputFile=')))
with out.open('a') as f: f.write('test.review:'+root.name+':pom:1\\n')
coord=pathlib.Path(__file__).resolve().parent
(coord/(root.name+'.started')).write_text(str(out))
end=time.monotonic()+30
while not (coord/(root.name+'.release')).exists() and time.monotonic()<end: time.sleep(.02)
print('BUILD SUCCESS')
''')
    fake.chmod(0o755)
    (home / 'config.json').write_text(json.dumps({
        'maven': str(fake), 'dataDir': str(home / 'data'), 'xmx': 'off', 'offline': True}))
    for name in ['alpha', 'beta']:
        pom(home / name / 'pom.xml', name)
    with contextlib.ExitStack() as stack:
        a, b = [stack.enter_context(Server(home)) for _ in range(2)]
        ja = a.api('/api/scan?dir=' + quote(home / 'alpha'), 'POST')['jobId']
        until(lambda: (home / 'alpha.started').exists(), 'A 未启动')
        jb = b.api('/api/scan?dir=' + quote(home / 'beta'), 'POST')['jobId']
        until(lambda: (home / 'beta.started').exists(), 'B 未启动')
        oa, ob = [(home / (n + '.started')).read_text() for n in ['alpha', 'beta']]
        check(ja == jb and oa != ob, '同名 j1 在两个 JVM 中使用不同任务目录')
        (home / 'alpha.release').touch()
        until(lambda: a.api('/api/scan/status?jobId=' + ja)['status'] == 'done', 'A 未结束')
        until(lambda: not Path(oa).exists(), 'A 未清理自己的任务目录')
        a.close()
        # 第一实例退出后启动第三实例，仍不能清掉第二实例正在使用的输出。
        c = stack.enter_context(Server(home))
        check(Path(ob).is_file(), '第一 JVM 退出、第三 JVM 启动后保留 B 的活跃目录')
        (home / 'beta.release').touch()
        until(lambda: b.api('/api/scan/status?jobId=' + jb)['status'] == 'done', 'B 未结束')
        assert_identity(b, home / 'beta', {'beta': 'pom.xml'}, '第二 JVM 扫描结果')
        records = json.loads((home / 'data/projects.json').read_text())
        check({r['key'] for r in records} == {str(home / n) for n in ['alpha', 'beta']},
              '两个 JVM 的项目记录都保留在磁盘')
        c.close()
        with Server(home) as restored:
            assert_identity(restored, home / 'alpha', {'alpha': 'pom.xml'}, '第一 JVM 扫描结果')


def origin_guard(base):
    from playwright.sync_api import sync_playwright
    class Page(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            body = b'<!doctype html><title>Origin regression</title>'
            self.send_response(200)
            self.send_header('Content-Type', 'text/html')
            self.send_header('Content-Length', str(len(body)))
            if self.path == '/sandbox':
                self.send_header('Content-Security-Policy', 'sandbox allow-scripts')
            self.end_headers()
            self.wfile.write(body)
        def log_message(self, *args):
            pass
    with Server(base / 'origin-home') as server:
        with http.server.ThreadingHTTPServer(('127.0.0.1', 0), Page) as attacker:
            thread = threading.Thread(target=attacker.serve_forever, daemon=True)
            thread.start()
            try:
                with sync_playwright() as p:
                    with p.chromium.launch(channel='chrome', headless=True) as browser:
                        page = browser.new_page()
                        page.goto('http://127.0.0.1:%d/' % attacker.server_port)
                        target = server.url + '/api/config'
                        with page.expect_response(lambda r: r.url == target) as response:
                            # no-cors 仍发送简单 POST 和真实 Origin；DevTools 可观察被拒响应。
                            # 默认 cors 模式会在网络收到 403 后隐藏 Playwright 的 response 事件。
                            page.evaluate('''target => { fetch(target, {mode:'no-cors',method:'POST',
                              headers:{'Content-Type':'text/plain'},
                              body:JSON.stringify({profiles:'ordinary-cross-origin'})}).catch(()=>{}); }''', target)
                        check(response.value.status == 403, 'Chrome 普通跨域配置写入被拒绝')
                        # CSP sandbox 同样产生 opaque origin；顶层页面避免子框架网络事件丢失。
                        page.goto('http://127.0.0.1:%d/sandbox' % attacker.server_port)
                        session = page.context.new_cdp_session(page)
                        requests, headers = {}, {}
                        session.on('Network.requestWillBeSent', lambda event:
                                   requests.update({event['requestId']: event['request']['url']}))
                        # Origin 由浏览器网络层添加，Playwright request.headers 中可能没有。
                        session.on('Network.requestWillBeSentExtraInfo', lambda event:
                                   headers.update({event['requestId']: {
                                       key.lower(): value for key, value in event['headers'].items()}}))
                        session.send('Network.enable')
                        try:
                            with page.expect_response(lambda r: r.url == target) as response:
                                page.evaluate('''target => fetch(target, {mode:'no-cors',method:'POST',
                                  headers:{'Content-Type':'text/plain'},
                                  body:JSON.stringify({profiles:'opaque-origin',maven:'/tmp/harmless-marker'})
                                  }).then(r=>r.type)''', target)
                            check(any(requests.get(request_id) == target and values.get('origin') == 'null'
                                      for request_id, values in headers.items()),
                                  'Chrome sandbox 实际发送 Origin:null')
                            check(response.value.status == 403, 'Chrome sandbox Origin:null 配置写入被拒绝')
                        finally:
                            session.detach()
                        cfg = server.api('/api/config')
                        check(cfg['profiles'] == '' and cfg['maven'] == '', '被拒绝请求没有更改配置')
            finally:
                attacker.shutdown()
                thread.join(timeout=5)


def main():
    if not JAR.is_file():
        raise SystemExit('请先执行 mvn -B -o package')
    with tempfile.TemporaryDirectory(prefix='depviz-backend-regression-') as directory:
        base = Path(directory).resolve()
        maven_cases(base)
        multi_instance(base)
        origin_guard(base)
    print('\n全部通过：%d 项后端集成断言' % CHECKS)


if __name__ == '__main__':
    main()
