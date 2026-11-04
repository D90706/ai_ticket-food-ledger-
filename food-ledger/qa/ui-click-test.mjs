/**
 * 界面点击冒烟测试：用 CDP 真的去点按钮，而不是只看代码连线。
 *
 * 为什么要有它：
 * 「按钮在这儿、行为却没连上」这类问题，构建通过、代码看着也对，
 * 只有真点一下才会暴露（手填结果卡上的「去我的记录」就是这么坏的）。
 *
 * 前置：后端 8081、前端 5174 已启动。
 * 用法：node qa/ui-click-test.mjs
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const DEV = 'http://localhost:5174'
const DEBUG_PORT = 9333
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = mkdtempSync(join(tmpdir(), 'edge-cdp-'))

const results = []
function check(id, desc, ok, actual) {
  results.push({ id, desc, ok, actual })
  console.log(`  [${ok ? 'PASS' : 'FAIL'}] ${id}  ${desc}  →  ${actual}`)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function jsonList() {
  const r = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/list`)
  return r.json()
}

class Cdp {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
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
      expression,
      returnByValue: true,
      awaitPromise: true,
      userGesture: true,
    })
    if (r.exceptionDetails) {
      throw new Error('JS error: ' + (r.exceptionDetails.text || '') +
        ' ' + (r.exceptionDetails.exception?.description || ''))
    }
    return r.result.value
  }

  async waitFor(expression, label, timeoutMs = 15000) {
    const start = Date.now()
    while (Date.now() - start < timeoutMs) {
      if (await this.evaluate(expression)) return true
      await sleep(200)
    }
    throw new Error('等待超时：' + label)
  }
}

/** 在页面里按文字找按钮并点击（比选 nth-child 稳） */
const clickByText = (text) => `
(() => {
  const btns = [...document.querySelectorAll('button')];
  const b = btns.find(x => (x.innerText || '').replace(/\\s+/g, '').includes('${text}'));
  if (!b) return false;
  if (b.disabled) return 'disabled';
  b.click();
  return true;
})()`

/** 给 el-input 赋值：必须用原生 setter + input 事件，Vue 的 v-model 才认 */
const fillInput = (selector, value) => `
(() => {
  const el = document.querySelector(${JSON.stringify(selector)});
  if (!el) return false;
  // input 和 textarea 的 value setter 在不同的原型上，
  // 拿错原型会抛 Illegal invocation（不是静默失败，但报错信息完全指不到这里）
  const proto = el.tagName === 'TEXTAREA'
    ? window.HTMLTextAreaElement.prototype
    : window.HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
  setter.call(el, ${JSON.stringify(value)});
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return true;
})()`

const activeTab = `
(() => {
  const on = document.querySelector('.tabs button.on');
  return on ? on.innerText.trim() : null;
})()`

async function main() {
  console.log('='.repeat(72))
  console.log('界面点击冒烟测试')
  console.log('='.repeat(72))

  const edge = spawn(EDGE, [
    '--headless=new',
    '--no-sandbox',
    '--disable-gpu',
    `--remote-debugging-port=${DEBUG_PORT}`,
    `--user-data-dir=${PROFILE}`,
    '--window-size=1440,900',
    'about:blank',
  ], { stdio: 'ignore' })

  let cdp
  try {
    // 等调试端口起来
    let targets = null
    for (let i = 0; i < 40; i++) {
      try {
        targets = await jsonList()
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

    // ---------- 登录 ----------
    await cdp.send('Page.navigate', { url: DEV + '/' })
    await cdp.waitFor(`document.readyState === 'complete'`, '页面加载')
    const login = await cdp.evaluate(`
      fetch('/api/auth/login', {method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({username: 'caigou', password: '123456'})})
        .then(r => r.json()).then(x => x.code)`)
    check('UI-01', '采购员登录（接口层）', login === 0, 'code=' + login)

    // ---------- 手填页 ----------
    await cdp.send('Page.navigate', { url: DEV + '/?tab=manual' })
    await cdp.waitFor(`!!document.querySelector('.ledger-form')`, '手填表单渲染')
    check('UI-02', '手填页按模板渲染', true, '表单已出现')

    // 名称唯一化，便于在列表里确认是自己这条
    const name = 'UI测试鸡-' + Date.now().toString().slice(-5)
    const filled = await cdp.evaluate(fillInput('.ledger-form input.el-input__inner', name))
    check('UI-03', '填写名称', filled === true, filled ? name : '未找到名称输入框')

    const clickedSubmit = await cdp.evaluate(clickByText('提交台账记录'))
    check('UI-04', '点击「提交台账记录」', clickedSubmit === true, String(clickedSubmit))

    await cdp.waitFor(`document.body.innerText.includes('已记入台账')`, '提交结果卡')
    check('UI-05', '出现提交结果卡', true, '已记入台账')

    // ---------- 本次修复的按钮 ----------
    const tabBefore = await cdp.evaluate(activeTab)
    const clickedGo = await cdp.evaluate(clickByText('去「我的记录」'))
    check('UI-06', '点击「去我的记录」', clickedGo === true, '点击前页签=' + tabBefore)

    let tabAfter = null
    try {
      await cdp.waitFor(`document.querySelector('.tabs button.on')?.innerText.includes('我的记录')`,
        '页签切换到我的记录', 6000)
      tabAfter = await cdp.evaluate(activeTab)
    } catch { tabAfter = await cdp.evaluate(activeTab) }
    check('UI-07', '页签切到「我的记录」', tabAfter === '我的记录',
      `点击后页签=${tabAfter}`)

    let inList = false
    try {
      await cdp.waitFor(`document.body.innerText.includes(${JSON.stringify(name)})`,
        '列表出现刚提交的记录', 8000)
      inList = true
    } catch { inList = false }
    check('UI-08', '列表里能看到刚提交的那条', inList, inList ? name : '未出现 ' + name)

    // ---------- 对话里传凭证照片（用 CDP 直接给 file input 塞文件） ----------
    await cdp.send('Page.navigate', { url: DEV + '/?tab=entry' })
    await cdp.waitFor(`!!document.querySelector('input[type=file]')`, '对话页的上传控件')
    await cdp.evaluate(`
      (() => {
        const b = [...document.querySelectorAll('button')].find(x => x.innerText.includes('传凭证'));
        return !!b;
      })()`).then((has) => check('UI-10', '对话页有「传凭证」按钮', has === true, String(has)))

    // 先发一句缺凭证的话，让追问点出凭证
    await cdp.evaluate(fillInput('textarea', '今天从城南肉联厂进了20斤土豆，批号UI-CERT'))
    await cdp.evaluate(clickByText('发送'))
    // 注意不能等「凭证」两个字：上传按钮上就写着「传凭证」，
    // 那样条件立刻为真，等于没等 AI 回复（这条用例第一版就是这么假通过的）
    await cdp.waitFor(`document.body.innerText.includes('还需要确认')`, 'AI 追问出现', 20000)
    const hinted = await cdp.evaluate(`document.body.innerText.includes('传照片')`)
    check('UI-11', '追问里提示可以传照片', hinted === true,
      hinted ? '有提示' : '追问里没有「传照片」的提示')

    // 造一张真 PNG，塞进 file input 触发上传
    const pngPath = join(PROFILE, 'cert-test.png')
    writeFileSync(pngPath, Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFElEQVR4nGP8z8DAwMDAxMDAwMAAAA4AAf4Z0v0AAAAASUVORK5CYII=',
      'base64'))
    const doc = await cdp.send('DOM.getDocument', { depth: -1 })
    const node = await cdp.send('DOM.querySelector', {
      nodeId: doc.root.nodeId, selector: 'input[type=file]' })
    await cdp.send('DOM.setFileInputFiles', { nodeId: node.nodeId, files: [pngPath] })

    let uploaded = false
    try {
      await cdp.waitFor(`document.body.innerText.includes('已上传凭证')`, '凭证上传成功', 15000)
      uploaded = true
    } catch { uploaded = false }
    check('UI-12', '上传凭证后界面提示已上传', uploaded, uploaded ? '已上传凭证' : '未出现提示')

    let certGone = false
    try {
      await cdp.waitFor(`document.body.innerText.includes('凭证已收到')`, '后端回应已收到凭证', 15000)
      certGone = true
    } catch { certGone = false }
    check('UI-13', '后端确认凭证已收到（不再追问凭证）', certGone,
      certGone ? '凭证已收到' : '未出现')

    // ---------- 我的记录：补录抽屉必须有全部模板字段的输入框 ----------
    await cdp.send('Page.navigate', { url: DEV + '/?tab=mine' })
    await cdp.waitFor(`document.querySelectorAll('.el-table__row').length > 0`, '我的记录列表')
    const rowCount = await cdp.evaluate(`document.querySelectorAll('.el-table__row').length`)
    check('UI-14', '我的记录有数据', rowCount > 0, rowCount + ' 行')

    await cdp.evaluate(`document.querySelector('.el-table__row').click()`)
    await cdp.waitFor(`!!document.querySelector('.el-drawer')`, '补录抽屉打开', 10000)
    await cdp.waitFor(`document.body.innerText.includes('台账内容')`, '抽屉内容渲染', 10000)

    // 这三个字段曾经完全没有输入框（补录页写死了 17 个字段，
    // 而「扩展字段」只渲染已经存在的键），采购员因此永远补不到「记录完整」
    for (const [label, id] of [['产地', 'UI-15'], ['冷链到货温度', 'UI-16'], ['农残检测报告号', 'UI-17']]) {
      const found = await cdp.evaluate(`
        (() => {
          const items = [...document.querySelectorAll('.el-drawer .el-form-item')];
          return items.some(it => (it.innerText || '').includes(${JSON.stringify(label)}));
        })()`)
      check(id, `补录抽屉有「${label}」输入项`, found === true, found ? '有' : '缺 ← 补不了')
    }

    // ---------- 草稿暂存：离开再回来不能丢 ----------
    await cdp.send('Page.navigate', { url: DEV + '/?tab=manual' })
    await cdp.waitFor(`!!document.querySelector('.ledger-form input.el-input__inner')`, '手填表单')
    const draftName = '草稿测试-' + Date.now().toString().slice(-5)
    await cdp.evaluate(fillInput('.ledger-form input.el-input__inner', draftName))

    // 切到「我的记录」再切回「手填」
    await cdp.evaluate(clickByText('我的记录'))
    await sleep(600)
    await cdp.evaluate(clickByText('手填'))
    await sleep(800)
    const afterSwitch = await cdp.evaluate(
      `document.querySelector('.ledger-form input.el-input__inner')?.value || ''`)
    check('UI-18', '切页签后手填内容还在', afterSwitch === draftName,
      afterSwitch ? `值=${afterSwitch}` : '值已丢失')

    // 整页刷新后还在
    await cdp.send('Page.reload')
    await cdp.waitFor(`!!document.querySelector('.ledger-form input.el-input__inner')`, '刷新后表单', 15000)
    let afterReload = ''
    try {
      await cdp.waitFor(
        `(document.querySelector('.ledger-form input.el-input__inner')?.value || '') === ${JSON.stringify(draftName)}`,
        '刷新后草稿恢复', 8000)
      afterReload = draftName
    } catch {
      afterReload = await cdp.evaluate(
        `document.querySelector('.ledger-form input.el-input__inner')?.value || ''`)
    }
    check('UI-19', '刷新页面后手填内容还在', afterReload === draftName,
      afterReload === draftName ? '已恢复' : `值=${afterReload || '空'}`)

    // 「记一笔」的输入框同样要保住
    await cdp.evaluate(clickByText('记一笔'))
    await cdp.waitFor(`!!document.querySelector('textarea')`, '记一笔输入框', 10000)
    const typed = '还没发出去的一句话-' + Date.now().toString().slice(-4)
    await cdp.evaluate(fillInput('textarea', typed))
    await cdp.evaluate(clickByText('手填'))
    await sleep(600)
    await cdp.evaluate(clickByText('记一笔'))
    await sleep(800)
    const afterBack = await cdp.evaluate(`document.querySelector('textarea')?.value || ''`)
    check('UI-20', '记一笔未发送的草稿切页签后还在', afterBack === typed,
      afterBack ? `值=${afterBack}` : '值已丢失')

    // ---------- AI 录入的结果卡也有同样按钮 ----------
    await cdp.send('Page.navigate', { url: DEV + '/?tab=entry' })
    await cdp.waitFor(`!!document.querySelector('textarea, .el-input__inner')`, 'AI 录入页')
    check('UI-09', 'AI 录入页可打开', true, '已打开')
  } catch (e) {
    check('UI-XX', '执行中断', false, String(e.message || e))
  } finally {
    try { cdp?.ws.close() } catch { /* ignore */ }
    edge.kill('SIGKILL')
    await sleep(300)
    try { rmSync(PROFILE, { recursive: true, force: true }) } catch { /* ignore */ }
  }

  const passed = results.filter((r) => r.ok).length
  console.log('\n' + '='.repeat(72))
  console.log(`结果：${passed}/${results.length} 通过`)
  const failed = results.filter((r) => !r.ok)
  if (failed.length) {
    console.log('失败：')
    failed.forEach((r) => console.log(`  ${r.id} ${r.desc} → ${r.actual}`))
  }
  process.exit(passed === results.length ? 0 : 1)
}

main()
