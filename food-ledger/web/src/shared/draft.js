/**
 * 录入草稿的本地暂存。
 *
 * 为什么需要：切页签时 App.vue 用 v-if 销毁子组件，刷新页面也会重挂载，
 * 于是「记一笔」说到一半的话、手填填了一半的表都会没。
 * 台账录入常常被电话或别的事打断，重新打一遍是最让人烦躁的那种失去。
 *
 * 为什么用 sessionStorage 而不是 localStorage：
 * 录入内容包含供应商电话、金额等经营信息，食堂的终端往往多人共用。
 * sessionStorage 随标签页关闭而清空，不会把上一个人的草稿留给下一个人。
 *
 * 键里带用户名：同一台机器上两个人先后登录，草稿不会串。
 */
const PREFIX = 'ledger-draft:'

function storage() {
  try {
    return window.sessionStorage
  } catch {
    return null // 隐私模式下可能拿不到
  }
}

function keyOf(name, user) {
  return PREFIX + name + ':' + (user || 'anon')
}

export function loadDraft(name, user) {
  const s = storage()
  if (!s) return null
  try {
    const raw = s.getItem(keyOf(name, user))
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function saveDraft(name, user, value) {
  const s = storage()
  if (!s) return
  try {
    s.setItem(keyOf(name, user), JSON.stringify(value))
  } catch {
    /* 配额满或不可写就放弃暂存，不能因为存草稿把录入搞挂 */
  }
}

export function clearDraft(name, user) {
  const s = storage()
  if (!s) return
  try {
    s.removeItem(keyOf(name, user))
  } catch {
    /* ignore */
  }
}

/** 登出时清掉本用户的全部草稿：共享终端上不留痕 */
export function clearAllDrafts(user) {
  const s = storage()
  if (!s) return
  const suffix = ':' + (user || 'anon')
  const keys = []
  for (let i = 0; i < s.length; i++) {
    const k = s.key(i)
    if (k && k.startsWith(PREFIX) && k.endsWith(suffix)) keys.push(k)
  }
  keys.forEach((k) => s.removeItem(k))
}
