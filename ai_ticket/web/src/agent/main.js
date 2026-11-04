import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import '../shared/styles/base.css'
import App from './App.vue'
import router from './router'

// 人工端：多页应用，靠路由在「工单 / 模板」之间切
createApp(App)
  .use(router)
  .use(ElementPlus, { locale: zhCn })
  .mount('#app')
