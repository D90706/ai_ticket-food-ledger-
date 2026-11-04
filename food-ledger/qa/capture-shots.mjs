/**
 * 用 CDP 驱动 headless Edge，把项目真实界面截成图，供答辩 PPT 使用。
 *
 * 为什么不用「打开页面直接截图」：不少页面需要交互（发一句话、点一行、
 * 打开抽屉）才有内容；只看首页截不到真正的功能界面。
 *
 * 前置：后端 8081、前端 5174 已启动。
 * 用法：node qa/capture-shots.mjs
 * 输出：docs/screenshots/*.png
 */

import { spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const DEV = 'http://localhost:5174'
const DEBUG_PORT = 9444
const EDGE_NAME = 'msedge.exe'
const EDGE_PATHS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
]
const OUT_DIR = 'docs/screenshots'
const PROFILE = mkdtempSync(join(tmpdir(), 'edge-shot-'))
const VIEWPORT = { width: 1440, height: 900 }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

class Cdp {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    ws.addEventListener('message', (ev) => {
      const m = JSON.parse(ev.data)
      if (m.id && this.pending.has(m.id)) {
        const { resolve, reject } = this.pending.get(m.id)
        this.pending.delete(m.id)
        m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result)
      }
    })
  }

  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id)
          reject(new Error('CDP timeout: ' + method))
        }
      }, 30000)
    })
  }

  async evaluate(expression) {
    const r = await this.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true, userGesture: true,
    })
    if (r.exceptionDetails) {
      throw new Error('JS: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text))
    }
    return r.result.value
  }

  async waitFor(expr, label, timeout = 15000) {
    const t0 = Date.now()
    while (Date.now() - t0 < timeout) {
      if (await this.evaluate(expr)) return true
      await sleep(200)
    }
    throw new Error('等待超时：' + label)
  }

  async shot(name) {
    const r = await this.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
    const file = join(OUT_DIR, name + '.png')
    writeFileSync(file, Buffer.from(r.data, 'base64'))
    console.log('  ✓ ' + name + '.png')
  }
}

const clickByText = (text) => `
(() => {
  const b = [...document.querySelectorAll('button')]
    .find(x => (x.innerText || '').replace(/\\s+/g, '').includes('${text}'));
  if (!b) return 'notfound';
  if (b.disabled) return 'disabled';
  b.click();
  return 'ok';
})()`

const fill = (selector, value) => `
(() => {
  const el = document.querySelector(${JSON.stringify(selector)});
  if (!el) return false;
  const proto = el.tagName === 'TEXTAREA'
    ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(value)});
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return true;
})()`

async function main() {
  mkdirSync(OUT_DIR, { recursive: true })

  let edgeBin = EDGE_PATHS.find((p) => {
    try { return require('node:fs').existsSync(p) } catch { return false }
  }) || EDGE_PATHS[0]

  const edge = spawn(edgeBin, [
    '--headless=new', '--no-sandbox', '--disable-gpu',
    `--remote-debugging-port=${DEBUG_PORT}`,
    `--user-data-dir=${PROFILE}`,
    `--window-size=${VIEWPORT.width},${VIEWPORT.height}`,
    'about:blank',
  ], { stdio: 'ignore' })

  let cdp
  try {
    let targets = null
    for (let i = 0; i < 60; i++) {
      try {
        targets = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/list`)).json()
        if (targets.some((t) => t.type === 'page')) break
      } catch { /* 还没起来 */ }
      await sleep(250)
    }
    const page = targets?.find((t) => t.type === 'page')
    if (!page) throw new Error('连不上 Edge 调试端口')

    const ws = new WebSocket(page.webSocketDebuggerUrl)
    await new Promise((res, rej) => {
      ws.addEventListener('open', res, { once: true })
      ws.addEventListener('error', rej, { once: true })
    })
    cdp = new Cdp(ws)
    await cdp.send('Page.enable')
    await cdp.send('Runtime.enable')
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: VIEWPORT.width, height: VIEWPORT.height,
      deviceScaleFactor: 1, mobile: false,
    })

    // ---------- 采购端登录 ----------
    await cdp.send('Page.navigate', { url: DEV + '/' })
    await cdp.waitFor(`document.readyState === 'complete'`, '首页')
    await cdp.evaluate(`
      fetch('/api/auth/login', {method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify({username:'caigou', password:'123456'})}).then(r=>r.json())`)

    const onlyAdmin = process.env.ONLY_ADMIN === '1'
    if (!onlyAdmin) {

    // 1 记一笔：先发一句不全的，截「追问」状态
    await cdp.send('Page.navigate', { url: DEV + '/?tab=entry' })
    await cdp.waitFor(`!!document.querySelector('textarea')`, '记一笔输入框')
    await cdp.evaluate(fill('textarea', '老张送了一箱土豆'))
    await cdp.evaluate(clickByText('发送'))
    await cdp.waitFor(`document.body.innerText.includes('还需要确认')`, '追问回复', 20000)
    await sleep(600)
    await cdp.shot('01-采购端-记一笔-追问')

    // 2 记一笔：说全了直接出单，截「结果卡」
    await cdp.evaluate(clickByText('重新开始'))
    await sleep(1200)
    await cdp.waitFor(`!!document.querySelector('textarea')`, '输入框（新一轮）')
    await cdp.evaluate(fill('textarea',
      '今天从城南肉联厂进了20斤冷鲜猪肉，规格散装，单价28块5，生产日期2026-10-26，'
      + '批号P20261026-3，保质期冷藏3天，进货日期2026-10-28，供货者地址城南大道88号，'
      + '电话13800001111，检疫证明编号SC-2026-A-1234，到期日2026-10-30'))
    await cdp.evaluate(clickByText('发送'))
    await cdp.waitFor(`document.body.innerText.includes('已记入台账')`, '出单结果卡', 25000)
    await sleep(600)
    await cdp.shot('02-采购端-记一笔-结果卡')

    // 3 手填页（含凭证上传区与模板字段）
    await cdp.send('Page.navigate', { url: DEV + '/?tab=manual' })
    await cdp.waitFor(`!!document.querySelector('.ledger-form')`, '手填表单')
    await sleep(700)
    await cdp.shot('03-采购端-手填')

    // 4 我的记录列表
    await cdp.send('Page.navigate', { url: DEV + '/?tab=mine' })
    await cdp.waitFor(`document.querySelectorAll('.el-table__row').length > 0`, '我的记录')
    await sleep(600)
    await cdp.shot('04-采购端-我的记录')

    // 5 我的记录 -> 补录抽屉（模板字段 + 原文依据 + 凭证）
    await cdp.evaluate(`document.querySelector('.el-table__row').click()`)
    await cdp.waitFor(`!!document.querySelector('.el-drawer')`, '补录抽屉')
    await sleep(900)
    await cdp.shot('05-采购端-补录抽屉')
    }   // end if(!onlyAdmin)

    // ---------- 管理端 ----------
    // 先把会话切成管理员，再进 /admin/：管理端守卫会把非 MANAGER 送回录入端，
    // 顺序反了就永远停在登录页（这个坑踩过一次）
    await cdp.evaluate(`
      fetch('/api/auth/login', {method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify({username:'guanli', password:'123456'})}).then(r=>r.json())`)
    await sleep(400)

    // 6 台账列表
    await cdp.send('Page.navigate', { url: DEV + '/admin/#/ledger' })
    await cdp.waitFor(`document.querySelectorAll('.el-table__row').length > 0`, '台账列表')
    await sleep(800)
    await cdp.shot('06-管理端-台账列表')

    // 7 台账详情（AI 记录，带级别提示、审核按钮、凭证与依据）
    await cdp.send('Page.navigate', { url: DEV + '/admin/#/ledger/314' })
    await cdp.waitFor(`document.body.innerText.includes('台账内容')`, '台账详情', 15000)
    await sleep(900)
    await cdp.shot('07-管理端-台账详情')

    // 8 统计
    await cdp.send('Page.navigate', { url: DEV + '/admin/#/stats' })
    await cdp.waitFor(`document.body.innerText.includes('采购金额') || document.body.innerText.includes('记录数')`, '统计页', 15000)
    await sleep(900)
    await cdp.shot('08-管理端-统计')

    // 9 供应商（证照预警）
    await cdp.send('Page.navigate', { url: DEV + '/admin/#/suppliers' })
    await cdp.waitFor(`document.querySelectorAll('.el-table__row').length > 0`, '供应商列表', 15000)
    await sleep(800)
    await cdp.shot('09-管理端-供应商与证照')

    console.log('全部截图完成')
  } catch (e) {
    console.error('失败：' + (e.message || e))
    process.exitCode = 1
  } finally {
    try { cdp?.ws.close() } catch { /* ignore */ }
    edge.kill('SIGKILL')
    await sleep(300)
    try { rmSync(PROFILE, { recursive: true, force: true }) } catch { /* ignore */ }
  }
}

main()
