<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <span class="brand-mark">智</span>
        <span class="brand-name">智能图文编写</span>
      </div>

      <h1 class="auth-title">注册</h1>
      <p class="auth-sub">创建账号，开始你的第一篇图文</p>

      <a-form :model="formState" name="basic" autocomplete="off" @finish="handleSubmit">
        <a-form-item name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
          <a-input v-model:value="formState.userAccount" size="large" placeholder="账号" />
        </a-form-item>
        <a-form-item
          name="userPassword"
          :rules="[
            { required: true, message: '请输入密码' },
            { min: 8, message: '密码不能小于 8 位' },
          ]"
        >
          <a-input-password
            v-model:value="formState.userPassword"
            size="large"
            placeholder="密码（至少 8 位）"
          />
        </a-form-item>
        <a-form-item
          name="checkPassword"
          :rules="[
            { required: true, message: '请确认密码' },
            { min: 8, message: '密码不能小于 8 位' },
            { validator: validateCheckPassword },
          ]"
        >
          <a-input-password
            v-model:value="formState.checkPassword"
            size="large"
            placeholder="确认密码"
          />
        </a-form-item>

        <a-button type="primary" html-type="submit" size="large" block>注册</a-button>
      </a-form>

      <div class="auth-tips">
        已有账号？
        <RouterLink to="/user/login">去登录</RouterLink>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { userRegister } from '@/api/userController.ts'

const router = useRouter()

const formState = reactive<API.UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})

/**
 * 验证确认密码
 */
const validateCheckPassword = (
  rule: unknown,
  value: string,
  callback: (error?: Error) => void,
) => {
  if (value && value !== formState.userPassword) {
    callback(new Error('两次输入密码不一致'))
  } else {
    callback()
  }
}

/**
 * 提交表单
 */
const handleSubmit = async (values: API.UserRegisterRequest) => {
  const res = await userRegister(values)
  if (res.data.code === 0) {
    message.success('注册成功')
    router.replace('/user/login')
  } else {
    message.error('注册失败，' + res.data.message)
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
