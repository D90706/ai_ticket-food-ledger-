import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import '../shared/styles/base.css'
import App from './App.vue'

// 用户端：单页，没有路由——顾客只有一条路径：选类型 → 对话。
createApp(App).use(ElementPlus, { locale: zhCn }).mount('#app')
