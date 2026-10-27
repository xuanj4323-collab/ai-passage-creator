import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'

import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import 'dayjs/locale/zh-cn'

// 设计令牌必须晚于 antd reset 引入，否则会被 reset 的默认值覆盖
import '@/assets/design-tokens.css'

import '@/access'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(Antd)

app.mount('#app')
