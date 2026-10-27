import axios from 'axios'
import { message } from 'ant-design-vue'

// 创建 Axios 实例
// baseURL 用相对路径：前端静态文件与后端接口由同一个入口（Nginx / vite preview）对外提供，
// 浏览器只请求当前域名，天然同源，无需 CORS，也不用再写死后端地址。
// 若确实要前后端分离部署，构建时用 VITE_API_BASE_URL 覆盖：
//   VITE_API_BASE_URL=https://api.example.com/api npm run build
const myAxios = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 60000,
  withCredentials: true,  // 必须！携带 Cookie
})

// 全局响应拦截器
myAxios.interceptors.response.use(
  function (response) {
    const { data } = response
    // 未登录
    if (data.code === 40100) {
      if (
        !response.request.responseURL.includes('user/get/login') &&
        !window.location.pathname.includes('/user/login')
      ) {
        message.warning('请先登录')
        window.location.href = `/user/login?redirect=${window.location.href}`
      }
    }
    return response
  },
  function (error) {
    return Promise.reject(error)
  },
)

export default myAxios
