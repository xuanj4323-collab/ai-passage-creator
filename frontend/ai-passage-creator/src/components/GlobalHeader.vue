<template>
  <a-layout-header class="header">
    <div class="header-inner">
      <!-- 品牌：纯色字标，不用图片 Logo -->
      <RouterLink to="/" class="brand">
        <span class="brand-mark">智</span>
        <span class="brand-name">智能图文编写</span>
      </RouterLink>

      <!-- 导航 -->
      <a-menu
        v-model:selectedKeys="selectedKeys"
        mode="horizontal"
        :items="menuItems"
        class="nav"
        @click="handleMenuClick"
      />

      <!-- 用户区 -->
      <div class="user-area">
        <template v-if="loginUserStore.loginUser.id">
          <a-dropdown placement="bottomRight">
            <button type="button" class="user-button">
              <a-avatar :size="28" class="user-avatar">{{ avatarInitial }}</a-avatar>
              <span class="user-name">{{ loginUserStore.loginUser.userName ?? '无名' }}</span>
              <span v-if="loginUserStore.loginUser.userRole === 'vip'" class="vip-tag">VIP</span>
            </button>
            <template #overlay>
              <a-menu>
                <a-menu-item key="profile" @click="goProfile">
                  <UserOutlined />
                  个人中心
                </a-menu-item>
                <a-menu-item @click="doLogout">
                  <LogoutOutlined />
                  退出登录
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </template>
        <template v-else>
          <a-button type="primary" @click="goLogin">登录</a-button>
        </template>
      </div>
    </div>
  </a-layout-header>
</template>

<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { useRouter } from 'vue-router'
import { type MenuProps, message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser.ts'
import { userLogout } from '@/api/userController.ts'
import {
  EditOutlined,
  HomeOutlined,
  LogoutOutlined,
  UnorderedListOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'

const loginUserStore = useLoginUserStore()
const router = useRouter()
// 当前选中菜单
const selectedKeys = ref<string[]>([router.currentRoute.value.path])
// 监听路由变化，更新当前选中菜单
router.afterEach((to) => {
  selectedKeys.value = [to.path]
})

// 菜单配置项
const originItems = [
  {
    key: '/',
    icon: () => h(HomeOutlined),
    label: '主页',
    title: '主页',
  },
  {
    key: '/create',
    icon: () => h(EditOutlined),
    label: '创作文章',
    title: '创作文章',
  },
  {
    key: '/article/list',
    icon: () => h(UnorderedListOutlined),
    label: '我的文章',
    title: '我的文章',
  },
  {
    key: '/vip',
    label: '会员中心',
    title: '会员中心',
  },
  {
    key: '/admin/statistics',
    label: '数据统计',
    title: '数据统计',
  },
  {
    key: '/admin/userManage',
    label: '用户管理',
    title: '用户管理',
  },
]

// 过滤菜单项：仅管理员可见后台入口
const filterMenus = (menus = [] as MenuProps['items']) => {
  return menus?.filter((menu) => {
    const menuKey = menu?.key as string
    if (menuKey?.startsWith('/admin')) {
      const loginUser = loginUserStore.loginUser
      if (!loginUser || loginUser.userRole !== 'admin') {
        return false
      }
    }
    return true
  })
}

// 展示在菜单的路由数组
const menuItems = computed<MenuProps['items']>(() => filterMenus(originItems))

// 无头像时用用户名首字，避免 antd 默认的蓝底图标
const avatarInitial = computed(() => {
  const name = loginUserStore.loginUser.userName ?? ''
  return name ? name.slice(0, 1) : '无'
})

// 处理菜单点击
const handleMenuClick: MenuProps['onClick'] = (e) => {
  const key = e.key as string
  selectedKeys.value = [key]
  if (key.startsWith('/')) {
    router.push(key)
  }
}

// 去登录
const goLogin = () => {
  router.push('/user/login')
}

// 去个人中心
const goProfile = () => {
  router.push('/user/profile')
}

// 退出登录
const doLogout = async () => {
  const res = await userLogout()
  if (res.data.code === 0) {
    loginUserStore.setLoginUser({
      userName: '未登录',
    })
    message.success('退出登录成功')
    await router.push('/user/login')
  } else {
    message.error('退出登录失败，' + res.data.message)
  }
}
</script>

<style scoped>
.header {
  position: sticky;
  top: 0;
  z-index: 100;
  height: 64px;
  padding: 0 24px;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-border);
}

.header-inner {
  display: flex;
  align-items: center;
  gap: 24px;
  height: 100%;
  max-width: 1400px;
  margin: 0 auto;
}

/* 品牌 */
.brand {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
  text-decoration: none;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  font-size: 14px;
  font-weight: 800;
  /* antd Layout Header 默认 line-height: 64px，不重置会把文字顶出方块 */
  line-height: 1;
  color: var(--c-ink-contrast);
  background: var(--c-ink);
  border-radius: 8px;
}

.brand-name {
  font-size: 16px;
  font-weight: 700;
  line-height: 1.3;
  color: var(--c-text-1);
  letter-spacing: 0.2px;
  white-space: nowrap;
}

/* 导航 */
.nav {
  flex: 1;
  min-width: 0;
  background: transparent;
  border-bottom: none !important;
}

.nav :deep(.ant-menu-item) {
  color: var(--c-text-2);
}

.nav :deep(.ant-menu-title-content) {
  color: inherit;
}

.nav :deep(.ant-menu-item-selected) {
  color: var(--c-text-1);
  font-weight: 600;
}

/* 用户区 */
.user-area {
  display: flex;
  flex: none;
  align-items: center;
}

.user-button {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 4px 10px 4px 4px;
  font: inherit;
  color: var(--c-text-2);
  cursor: pointer;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 999px;
  transition:
    background 0.16s ease,
    border-color 0.16s ease;
}

.user-button:hover {
  background: var(--c-bg-soft);
  border-color: var(--c-border);
}

/* 默认头像的蓝色底与整体色调不搭，统一成中性灰 */
.user-button :deep(.ant-avatar) {
  background: var(--c-bg-soft);
  color: var(--c-text-2);
  font-size: 13px;
  font-weight: 600;
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.vip-tag {
  padding: 0 6px;
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
  color: var(--c-accent);
  background: var(--c-accent-soft);
  border-radius: 4px;
}

@media (max-width: 900px) {
  .header {
    padding: 0 12px;
  }

  .brand-name {
    font-size: 15px;
  }

  .nav :deep(.ant-menu-item) {
    padding-inline: 8px;
  }
}
</style>
