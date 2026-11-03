#!/usr/bin/env python3
"""前端端到端检查 —— 用真浏览器验证「后端 + 前端」整条链路。

使用仓库内的 Maven 项目实际扫描，检查依赖树、画布、自检、POM 弹窗、重扫和深链导航。

用法：
    python3 tools/e2e_check.py                      # 自己起服务（临时 home + 随机空闲端口）
    python3 tools/e2e_check.py http://host:port      # 用已经在跑的服务

不传参数时会自己挑一个当前空闲的端口，并把 --user.home 指到临时目录 ——
写死端口会在多台并发跑的时候连到别人的服务上，那时「超时」是环境的错，
不是产品的错，但看上去一模一样。
"""
import os
import socket
import subprocess
import sys
import tempfile
import time

from playwright.sync_api import sync_playwright

# 默认用仓库内的 fixture 项目（任何机器都在）；想扫真实项目时用 --project 覆盖。
PROJECT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       'src/test/resources/projects/demo')
# 软链指向被测项目本身，由脚本自己建：不能假设机器上已经有 /tmp/demo-link，
# 也不能假设它指向哪个目录（早先它指向作者的 ~/vibeCoding/demo，而 PROJECT 换成 fixture 后两边就不一致了）。
SYMLINK = os.path.join(tempfile.gettempdir(), 'depviz-e2e-link')

failures = []


JAR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                  'target', 'depviz.jar')


def free_port():
    """让内核挑一个当前空闲的端口，而不是写死一个可能被别人占着的。"""
    s = socket.socket()
    try:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]
    finally:
        s.close()


def start_server():
    """起一个隔离的实例，返回 (base_url, proc, home_dir)。"""
    if not os.path.isfile(JAR):
        sys.exit('找不到 %s，先跑 mvn -B -o -q -DskipTests package' % JAR)
    home = tempfile.mkdtemp(prefix='depviz-e2e-')
    port = free_port()
    proc = subprocess.Popen(
        ['java', '-Duser.home=' + home, '-Djava.awt.headless=true', '-jar', JAR,
         'serve', '--port', str(port), '--no-browser'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        env=dict(os.environ, DEPVIZ_HOME=os.path.join(home, '.depviz')))
    base = 'http://127.0.0.1:%d' % port
    deadline = time.time() + 30
    while time.time() < deadline:
        try:
            import urllib.request
            urllib.request.urlopen(base + '/api/health', timeout=1).read()
            return base, proc, home
        except Exception:
            time.sleep(0.3)
    proc.kill()
    proc.wait(timeout=10)
    sys.exit('服务 30 秒内没起来（端口 %d）' % port)


def check(name, ok, detail=''):
    mark = 'OK  ' if ok else 'FAIL'
    print('[%s] %s %s' % (mark, name, detail))
    if not ok:
        failures.append(name + (' — ' + detail if detail else ''))


def main(base):
    with sync_playwright() as pw:
        # 用系统 Chrome：无头 shell 未下载时也能跑
        browser = pw.chromium.launch(channel='chrome', headless=True)
        page = browser.new_page(viewport={'width': 1600, 'height': 950})
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.on('console', lambda m: errors.append('console.error: ' + m.text)
                if m.type == 'error' else None)

        page.goto(base, wait_until='networkidle')
        check('首页加载', page.locator('#view-select').is_visible())
        check('首屏无 JS 报错', not errors, '; '.join(errors[:3]))

        # ---- 先确保项目扫过（脚本自己触发，不依赖调用者先手动扫）----
        # 早先直接跳深链：数据目录是新的时 /api/graph 404，Engine.build 拿到错误对象
        # 就崩在 `doc.occ.length` 上，报的是「Cannot read properties of undefined」，
        # 完全看不出根因是「没扫过」。
        job = page.evaluate('''async (proj) => {
          const r = await fetch('/api/scan?dir=' + encodeURIComponent(proj), {method: 'POST'});
          if (!r.ok) return {error: 'HTTP ' + r.status};
          return await r.json();
        }''', PROJECT)
        check('触发扫描', 'jobId' in job, str(job)[:80])
        for _ in range(120):
            st = page.evaluate('''async (id) => (await fetch('/api/scan/status?jobId=' + id)).json()''',
                               job.get('jobId'))
            if st.get('status') in ('done', 'failed', 'cancelled'):
                break
            page.wait_for_timeout(500)
        check('扫描完成', st.get('status') == 'done', str(st.get('status')) + ' ' + str(st.get('error'))[:80])

        # ---- 已分析过的项目：从首页进，直接进图，不重扫 ----
        # 用户原话：「已经分析完的项目……从首页进去是不是直接使用已有的分析文件
        # 直接进去就行？」联网扫一个多模块项目要几十秒，图就在 data/<项目名>/graph.json。
        # 这里同时钉住「不重扫」这件事：进入后不应该停在确认页 / 扫描页。
        page.goto(base, wait_until='networkidle')
        page.fill('#path-input', PROJECT)
        page.click('#btn-path-go')
        page.wait_for_timeout(2500)
        check('已分析过的项目直接进图，不落确认页',
              page.locator('#view-explore').is_visible()
              and not page.locator('#stage-confirm').is_visible(),
              'explore=%s confirm=%s' % (page.locator('#view-explore').is_visible(),
                                         page.locator('#stage-confirm').is_visible()))

        # ---- 首页「最近打开」同理：点进去直接进图 ----
        page.goto(base, wait_until='networkidle')
        page.wait_for_timeout(800)
        recent = page.locator('#recent-list .recent-item')
        if recent.count():
            recent.first.click()
            page.wait_for_timeout(2000)
            check('最近打开点进去直接进图', page.locator('#view-explore').is_visible())
        else:
            check('最近打开有记录', False, '列表为空')

        # ---- 顶栏「重新扫描」：走完整扫描页 → 必须能点进结果 ----
        # 这条路径专门钉住一个真 bug：scan.js 的 finish() 里曾调用 stop()（把 cancelled 置 true），
        # 紧接着 loadProject().then() 的 `if (cancelled) return` 把 done 回调吞掉 ——
        # 表现是进度 100%、日志跑完，但「分析完成」永不出现，用户进不去结果。
        if not page.locator('#view-explore').is_visible():
            page.goto(base + '/?project=' + PROJECT, wait_until='networkidle')
            page.wait_for_timeout(2000)
        page.click('#btn-rescan')
        page.wait_for_timeout(500)
        check('重新扫描进入扫描页', page.locator('#stage-scan').is_visible())
        # 重扫走的是「扫描完直接回图」那条路（`#btn-rescan` 的 done 回调是 enterExplore），
        # 而不是确认页发起的扫描（那条会停在扫描页给「查看结果」按钮）。
        try:
            page.wait_for_selector('#view-explore:not([hidden])', timeout=90000)
            arrived = True
        except Exception:
            arrived = False
        check('重扫完成后回到浏览视图', arrived,
              'pct=%s status=%s' % (page.locator('#scan-pct').inner_text(),
                                    page.locator('#scan-fail-msg').inner_text()[:60]))

        # ---- 通过深链直接进入已扫描项目 ----
        page.goto(base + '/?project=' + PROJECT,
                  wait_until='networkidle')
        page.wait_for_timeout(2500)
        check('进入浏览视图', page.locator('#view-explore').is_visible(),
              'select=%s' % page.locator('#view-select').is_visible())

        rows = page.locator('#result-list .dep-row').count()
        check('左栏树有渲染', rows > 0, '%d 行' % rows)

        meta = page.locator('#meta-text').inner_text()
        check('元数据条有内容', len(meta) > 10, meta[:60])
        check('元数据条填了 Maven 版本', 'Maven ' in meta and 'Maven ·' not in meta, meta[:80])
        check('元数据条填了耗时', '0.0s' not in meta, meta[:80])

        # 未选依赖时画布显示空态，选中后才出子图。
        check('未选择时显示空态', page.locator('#graph-empty').is_visible())

        # ---- 五条自检恒等式（在浏览器里跑真数据）----
        bad = page.evaluate('''() => {
          const E = window.Engine, st = window.__store || null;
          return st ? 'store-exposed' : 'no-store';
        }''')
        # 前端未暴露 store，改为用 engine 自己建
        identity = page.evaluate('''async (proj) => {
          const doc = await (await fetch('/api/graph?projectId=' + encodeURIComponent(proj))).json();
          const st = window.Engine.build(doc);
          const out = {};
          let badA = 0, badB = 0, badC = 0, badD = 0, badE = 0;
          for (let gi = 0; gi < st.gas.length; gi++) {
            const s = window.Engine.summarize(st, gi, { effectiveOnly: false });
            let vSum = 0, vOcc = 0, vMods = 0;
            for (const v of s.versions) { vSum += v.effective; vOcc += v.occurrences; vMods += v.modules; }
            if (vOcc !== s.occurrences) badA++;
            if (vSum > s.occurrences) badB++;
            const n = window.Engine.nodeSummary(st, gi, { effectiveOnly: false });
            if (n.upstream.length !== n.parentCount) badC++;
            if (n.downstream.length !== n.childCount) badD++;
            let upPaths = 0, occWithParent = 0;
            for (const u of n.upstream) upPaths += u.paths;
            for (const o of st.occ) if (o.ga === gi && o.p >= 0) occWithParent++;
            if (upPaths !== occWithParent) badE++;
          }
          out.badOcc = badA; out.badEff = badB; out.badUp = badC;
          out.badDown = badD; out.badUpPaths = badE;
          out.ga = st.gas.length;
          return out;
        }''', PROJECT)
        check('恒等式 Σversions.occurrences===occurrences',
              identity['badOcc'] == 0, '%d 个 GA 违反' % identity['badOcc'])
        check('恒等式 上游长度===parentCount',
              identity['badUp'] == 0, '%d 个 GA 违反' % identity['badUp'])
        check('恒等式 下游长度===childCount',
              identity['badDown'] == 0, '%d 个 GA 违反' % identity['badDown'])
        check('恒等式 Σupstream.paths===有父的 occurrence',
              identity['badUpPaths'] == 0, '%d 个 GA 违反' % identity['badUpPaths'])

        # ---- 搜索 ----
        # 先记画布位置，再打开联想面板；否则基准本身已经被面板推下去，检查失效。
        base_graph = page.locator('.col.graph').bounding_box()
        page.fill('#search-input', 'netty')
        page.wait_for_timeout(1200)
        hits = page.locator('#result-list .dep-row').count()
        check('搜索 netty 有结果', hits > 0, '%d 行' % hits)

        # ---- 选中一个依赖 → 画布出子图 ----
        # 联想面板浮在搜索框下方，只让结果列表内容下沉；画布不能整体下移。
        search_box = page.locator('#searchbox').bounding_box()
        pop_box = page.locator('#suggest-pop').bounding_box()
        graph_box = page.locator('.col.graph').bounding_box()
        row_box = page.locator('#result-list .dep-row').first.bounding_box()
        pop_visible = page.locator('#suggest-pop').is_visible()
        aligned = (search_box and pop_box
                   and abs(pop_box['x'] - search_box['x']) <= 1
                   and abs(pop_box['width'] - search_box['width']) <= 2)
        clears = (pop_visible and aligned and row_box and pop_box and base_graph and graph_box
                  and abs(graph_box['y'] - base_graph['y']) <= 1
                  and row_box['y'] >= pop_box['y'] + pop_box['height'] - 1)
        check('联想面板浮层不挤画布且首行可点', clears, '%s / %s / %s' % (search_box, pop_box, row_box))
        page.locator('#result-list .dep-row').first.click()
        page.wait_for_timeout(1200)
        nodes = page.evaluate('window.Graph.cy ? window.Graph.cy.nodes().length : -1')
        check('选中后画布有节点', nodes > 0, '%d 个' % nodes)

        # ---- 点画布模块根 → 右栏模块详情 + POM 按钮 ----
        root_pos = page.evaluate('''() => {
          const cy = window.Graph.cy;
          const n = cy && cy.nodes().filter(x => x.data('isRoot'))[0];
          return n ? n.renderedPosition() : null;
        }''')
        cy_box = page.locator('#cy').bounding_box()
        check('所选子图包含模块根节点', root_pos is not None)
        if root_pos and cy_box:
            page.mouse.click(cy_box['x'] + root_pos['x'], cy_box['y'] + root_pos['y'])
            page.wait_for_timeout(500)
        detail = page.locator('#detail-body').inner_text()
        check('右栏有内容', len(detail) > 5, detail[:60].replace('\n', ' '))
        pom_button = page.locator('#detail-body button[data-act="pom"]')
        has_pom_path = pom_button.count() > 0 and pom_button.inner_text().endswith('/pom.xml')
        check('POM 路径按模块区分', has_pom_path,
              pom_button.inner_text() if pom_button.count() else '按钮缺失')
        if has_pom_path:
            pom_button.click()
            page.wait_for_timeout(300)
            pom_title = page.locator('#dlg-pom .modal-head h2.mono').inner_text()
            pom_lines = page.locator('#dlg-pom .pom-line').count()
            modal_style = page.locator('#dlg-pom').evaluate('''d => ({
              headMin: getComputedStyle(d.querySelector('.modal-head')).minHeight,
              footMin: getComputedStyle(d.querySelector('.modal-foot')).minHeight,
              bottomClose: Array.from(d.querySelectorAll('.modal-foot button'))
                .some(b => b.textContent.trim() === '关闭')
            })''')
            check('POM 按模块完整路径读取且弹窗布局收紧',
                  pom_title.endswith('/pom.xml') and pom_lines > 0
                  and modal_style['headMin'] == '34px'
                  and modal_style['footMin'] == '32px'
                  and not modal_style['bottomClose'],
                  '%s，%d 行' % (pom_title, pom_lines))
            page.locator('#dlg-pom [data-close]').click()

        page.fill('#search-input', '')
        page.press('#search-input', 'Enter')
        page.wait_for_timeout(300)

        # ---- 冲突页 ----
        page.click('#tab-conflicts')
        page.wait_for_timeout(900)
        crows = page.locator('#conflict-body tr').count()
        check('冲突页有行', crows > 0, '%d 行' % crows)
        ctext = page.locator('#conflict-count').inner_text()
        check('冲突计数有内容', len(ctext) > 0, ctext)
        check('冲突筛选文案使用「版本被取代」',
              page.locator('#chk-rewritten').locator('xpath=..').inner_text().strip() == '只看版本被取代')

        conflict_check = page.evaluate('''async (proj) => {
          const doc = await (await fetch('/api/graph?projectId=' + encodeURIComponent(proj))).json();
          const st = window.Engine.build(doc);
          const cs = window.Engine.conflicts(st, '').conflicts;
          let sample = null;
          for (const c of cs) {
            for (const v of c.losers) {
              const d = window.Engine.explainVersion(st, c.gaId, v.version, { moduleIds: [] });
              if (d && d.loserPath && d.winnerPath) {
                sample = { gaId: c.gaId, version: v.version, moduleId: d.loserPath.moduleId };
                break;
              }
            }
            if (sample) break;
          }
          return { replaced: cs.filter(c => c.losers.length > 0).length, sample };
        }''', PROJECT)
        check('发现可供核对的同模块版本解释样例',
              conflict_check['sample'] is not None, str(conflict_check['sample']))
        page.locator('#chk-rewritten + .toggle-track + .toggle-text').click()
        page.wait_for_timeout(300)
        filtered_rows = page.locator('#conflict-body tr').count()
        check('「只看版本被取代」按真实落败版本过滤',
              filtered_rows == conflict_check['replaced'],
              '%d 行，期望 %d 行' % (filtered_rows, conflict_check['replaced']))
        if conflict_check['sample']:
            buttons = page.locator('#conflict-body button[data-vexp]')
            selected = False
            for i in range(buttons.count()):
                button = buttons.nth(i)
                if (button.get_attribute('data-ga') == str(conflict_check['sample']['gaId'])
                        and button.get_attribute('data-vexp') == conflict_check['sample']['version']):
                    button.click()
                    selected = True
                    break
            page.wait_for_timeout(200)
            modules = page.locator('#vexp-body .vexp-mod').all_inner_texts()
            same_module = len(modules) == 2 and modules[0] == modules[1]
            check('版本解释只比较同一模块内的路径',
                  selected and same_module,
                  ' / '.join(modules) if modules else '没有配对路径')
            if page.locator('#dlg-vexp').is_visible():
                page.locator('#dlg-vexp [data-close]').click()
        # ---- 符号链接路径的深链（macOS 的 /tmp -> /private/tmp）----
        # 服务端把 projectId 规范化为真实路径，用户地址栏里写的是软链路径。
        # 没有「按目录名兜底」时页面会静默落回选择页 —— 图就在服务端内存里，用户却进不去。
        page.goto(base + '/?project=' + PROJECT, wait_until='networkidle')
        page.wait_for_timeout(1200)
        check('深链（规范化路径）直接进图', page.locator('#view-explore').is_visible())

        # 软链路径：/tmp 在 macOS 上是 /private/tmp 的软链，服务端存的是规范化后的 key。
        # 先在服务端登记软链项目（scan 内部会规范化），再用软链路径深链。
        page.evaluate('''async (proj) => {
          await fetch('/api/scan?dir=' + encodeURIComponent(proj), {method: 'POST'});
        }''', SYMLINK)
        # 服务端会把软链规范化成真实路径，所以 projects 里出现的是 name='demo'，
        # 不是 'demo-link' —— 等 'demo-link' 会一直等到超时。这里只等「有一个 scanned 的项目」。
        for _ in range(120):
            if page.evaluate('''async () => {
                 const r = await fetch('/api/projects');
                 const list = await r.json();
                 return list.some(x => x.scanned);
               }'''):
                break
            page.wait_for_timeout(500)
        page.goto(base + '/?project=' + SYMLINK, wait_until='networkidle')
        page.wait_for_timeout(1500)
        check('深链（软链路径）也能进图', page.locator('#view-explore').is_visible())

        check('全程无 JS 报错', not errors, '; '.join(errors[:3]))

        page.screenshot(path='/tmp/depviz-explore.png', full_page=False)
        browser.close()

    print()
    if failures:
        print('失败 %d 项：' % len(failures))
        for f in failures:
            print('  - ' + f)
        return 1
    print('全部通过')
    return 0


def ensure_symlink():
    """让 SYMLINK 指向被测项目。

    软链由脚本自己建，不假设机器上已经有：早先写死 `/tmp/demo-link`，
    而它指向作者的 `~/vibeCoding/demo`；PROJECT 换成仓库内 fixture 之后
    两边指的就不是同一个项目了，那条检查会在别的机器上莫名其妙地失败。
    """
    try:
        if os.path.islink(SYMLINK) or os.path.exists(SYMLINK):
            os.unlink(SYMLINK)
        os.symlink(os.path.abspath(PROJECT), SYMLINK)
    except OSError as e:
        print('（无法创建软链 %s：%s —— 跳过软链深链检查）' % (SYMLINK, e))


if __name__ == '__main__':
    arg = sys.argv[1] if len(sys.argv) > 1 else ''
    if arg:
        sys.exit(main(arg))
    ensure_symlink()
    base, proc, home = start_server()
    print('（自己起的实例：%s，临时 home=%s）' % (base, home))
    try:
        sys.exit(main(base))
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except Exception:
            proc.kill()
            proc.wait(timeout=10)
