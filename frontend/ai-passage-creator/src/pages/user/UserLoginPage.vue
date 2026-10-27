<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <span class="brand-mark">智</span>
        <span class="brand-name">智能图文编写</span>
      </div>

      <h1 class="auth-title">登录</h1>
      <p class="auth-sub">登录后即可保存创作记录</p>

      <a-form :model="formState" name="basic" autocomplete="off" @finish="handleSubmit">
        <a-form-item name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
          <a-input v-model:value="formState.userAccount" size="large" placeholder="账号" />
        </a-form-item>
        <a-form-item
          name="userPassword"
          :rules="[
            { required: true, message: '请输入密码' },
            { min: 8, message: '密码长度不能小于 8 位' },
          ]"
        >
          <a-input-password
            v-model:value="formState.userPassword"
            size="large"
            placeholder="密码"
          />
        </a-form-item>

        <a-button type="primary" html-type="submit" size="large" block>登录</a-button>
      </a-form>

      <div class="auth-tips">
        没有账号
        <RouterLink to="/user/register">去注册</RouterLink>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { userLogin } from '@/api/userController.ts'
import { useLoginUserStore } from '@/stores/loginUser.ts'

const formState = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()

/**
 * 提交表单
 */
const handleSubmit = async (values: API.UserLoginRequest) => {
  const res = await userLogin(values)
  if (res.data.code === 0 && res.data.data) {
    await loginUserStore.fetchLoginUser()
    message.success('登录成功')
    // 支持访问受保护页面时被重定向过来的场景
    const redirect = route.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/')
  } else {
    message.error('登录失败，' + res.data.message)
  }
}
</script>

<style scoped>
.auth-page {
  display: grid;
  place-items: center;
  min-height: calc(100vh - 64px - 160px);
  padding: 48px 16px;
}

.auth-card {
  width: 100%;
  max-width: 400px;
  padding: 32px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-1);
}

.auth-brand {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 28px;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  font-size: 12px;
  font-weight: 800;
  color: var(--c-ink-contrast);
  background: var(--c-ink);
  border-radius: 7px;
}

.brand-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text-2);
}

.auth-title {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 700;
  color: var(--c-text-1);
}

.auth-sub {
  margin: 0 0 24px;
  font-size: 13px;
  color: var(--c-text-3);
}

.auth-tips {
  margin-top: 20px;
  font-size: 13px;
  color: var(--c-text-3);
  text-align: center;
}
</style>
