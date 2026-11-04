import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import '../shared/styles/base.css'
import App from './App.vue'

// 采购录入端：单页两页签，不需要路由。
// 采购员只有两件事：记一笔、看自己记了什么。
createApp(App).use(ElementPlus, { locale: zhCn }).mount('#app')