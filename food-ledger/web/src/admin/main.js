import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import '../shared/styles/base.css'
import App from './App.vue'
import router from './router'

/*
 * 管理端入口（admin/index.html 指到这里）。
 *
 * 和采购端分成两个 HTML 入口，是因为两个角色面对的首页本来就不同：
 * 采购员只需要「一句话记账」，管理员才需要全量台账和入账/退回/作废。
 *
 * 先等 router.isReady() 再 mount：路由守卫里会静默问一次 /auth/me，
 * 如果先挂载，未登录的人会先看到一瞬间的管理框架再被弹回登录页。
 * 顺序换一下，首屏就是最终那一屏。
 */
const app = createApp(App)
app.use(ElementPlus, { locale: zhCn })
app.use(router)
router.isReady().then(() => app.mount('#app'))
