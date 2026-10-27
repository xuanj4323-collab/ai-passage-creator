<template>
  <div class="profile-page">
    <div class="profile-inner">
      <!-- 身份区 -->
      <section class="identity">
        <a-upload
          class="avatar-upload"
          :show-upload-list="false"
          :before-upload="beforeAvatarUpload"
          accept="image/png,image/jpeg,image/webp,image/gif"
          :disabled="uploading"
        >
          <div class="avatar-wrap" :class="{ 'is-uploading': uploading }">
            <a-avatar :size="88" :src="user.userAvatar || undefined" class="avatar">
              {{ avatarInitial }}
            </a-avatar>
            <span class="avatar-mask">{{ uploading ? '上传中…' : '更换头像' }}</span>
          </div>
        </a-upload>

        <div class="identity-main">
          <h1 class="identity-name">{{ user.userName || '未设置昵称' }}</h1>
          <p class="identity-account">@{{ user.userAccount }}</p>
          <div class="identity-tags">
            <!-- 角色标签本身按会员着色，避免再叠一个重复的「会员」标签 -->
            <span class="tag" :class="{ 'tag-vip': isVip }">{{ roleLabel }}</span>
          </div>
          <p class="identity-bio">{{ user.userProfile || '还没有填写个人简介' }}</p>
        </div>
      </section>

      <div class="profile-grid">
        <!-- 左栏：用量与账户 -->
        <aside class="side">
          <section class="card">
            <h2 class="card-title">用量余额</h2>
            <div class="quota-row">
              <span class="quota-value">{{ quotaText }}</span>
              <span class="quota-unit">{{ quotaUnit }}</span>
            </div>
            <p class="quota-hint">{{ quotaHint }}</p>
            <div v-if="!unlimited" class="quota-bar">
              <span class="quota-bar-fill" :style="{ width: quotaPercent + '%' }" />
            </div>
          </section>

          <section class="card">
            <h2 class="card-title">账户信息</h2>
            <dl class="meta">
              <div class="meta-row"><dt>账号</dt><dd>{{ user.userAccount || '—' }}</dd></div>
              <div class="meta-row"><dt>角色</dt><dd>{{ roleLabel }}</dd></div>
              <div class="meta-row"><dt>注册时间</dt><dd>{{ formatDate(user.createTime) }}</dd></div>
              <div v-if="isVip" class="meta-row">
                <!-- vipTime 记录的是开通时间；会员开通后永久有效，不存在到期日 -->
                <dt>会员开通</dt>
                <dd>
                  {{ formatDate(user.vipTime) }}
                  <span class="meta-note">永久有效</span>
                </dd>
              </div>
            </dl>
          </section>
        </aside>

        <!-- 右栏：资料与安全 -->
        <main class="main">
          <section class="card">
            <h2 class="card-title">个人资料</h2>
            <p class="card-desc">昵称与简介会展示在这里，也让生成内容的署名更自然。</p>
            <a-form layout="vertical" :model="profileForm" @finish="saveProfile">
              <a-form-item label="昵称">
                <a-input
                  v-model:value="profileForm.userName"
                  :maxlength="50"
                  show-count
                  placeholder="给自己起一个称呼"
                />
              </a-form-item>
              <a-form-item label="个人简介">
                <a-textarea
                  v-model:value="profileForm.userProfile"
                  :rows="4"
                  :maxlength="500"
                  show-count
                  placeholder="介绍一下你自己，或者你常写的方向"
                />
              </a-form-item>
              <a-button type="primary" html-type="submit" :loading="savingProfile">
                保存资料
              </a-button>
            </a-form>
          </section>

          <section class="card">
            <h2 class="card-title">修改密码</h2>
            <p class="card-desc">修改后当前会话仍然有效，下次登录请使用新密码。</p>
            <a-form
              layout="vertical"
              :model="passwordForm"
              :rules="passwordRules"
              @finish="savePassword"
            >
              <a-form-item label="原密码" name="oldPassword">
                <a-input-password
                  v-model:value="passwordForm.oldPassword"
                  placeholder="当前密码"
                  autocomplete="current-password"
                />
              </a-form-item>
              <a-form-item label="新密码" name="newPassword">
                <a-input-password
                  v-model:value="passwordForm.newPassword"
                  placeholder="8-20 位"
                  autocomplete="new-password"
                />
              </a-form-item>
              <a-form-item label="确认新密码" name="checkPassword">
                <a-input-password
                  v-model:value="passwordForm.checkPassword"
                  placeholder="再输入一次"
                  autocomplete="new-password"
                />
              </a-form-item>
              <a-button type="primary" html-type="submit" :loading="savingPassword">
                更新密码
              </a-button>
            </a-form>
          </section>
        </main>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { updateMyUser, updatePassword, uploadAvatar } from '@/api/userProfile'

const router = useRouter()
const loginUserStore = useLoginUserStore()

const user = computed(() => loginUserStore.loginUser)

const uploading = ref(false)
const savingProfile = ref(false)
const savingPassword = ref(false)

const profileForm = reactive({
  userName: '',
  userProfile: '',
})

const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  checkPassword: '',
})

const roleLabel = computed(() => {
  const role = user.value.userRole
  if (role === 'admin') return '管理员'
  if (role === 'vip') return '会员'
  return '普通用户'
})

const isVip = computed(() => user.value.userRole === 'vip')

/** 管理员与会员不消耗额度 */
const unlimited = computed(() => user.value.userRole === 'admin' || user.value.userRole === 'vip')

const quotaText = computed(() => (unlimited.value ? '不限' : String(user.value.quota ?? 0)))
const quotaUnit = computed(() => (unlimited.value ? '次数' : '次剩余'))
const quotaHint = computed(() =>
  unlimited.value
    ? '管理员与会员创作不消耗额度'
    : '每创作一篇消耗 1 次；额度用完后可在会员中心开通',
)

/** 免费额度默认 5 次，用于画进度条 */
const quotaPercent = computed(() => {
  const quota = Number(user.value.quota ?? 0)
  return Math.max(0, Math.min(100, Math.round((quota / 5) * 100)))
})

const avatarInitial = computed(() => {
  const name = user.value.userName ?? ''
  return name ? name.slice(0, 1) : '无'
})

const formatDate = (value?: string) => (value ? value.replace('T', ' ').slice(0, 16) : '—')

/** 把 store 里的最新资料同步到表单 */
const syncProfileForm = () => {
  profileForm.userName = user.value.userName ?? ''
  profileForm.userProfile = user.value.userProfile ?? ''
}

const passwordRules = {
  oldPassword: [{ required: true, message: '请输入原密码' }],
  newPassword: [
    { required: true, message: '请输入新密码' },
    { min: 8, max: 20, message: '新密码长度需为 8-20 位' },
  ],
  checkPassword: [
    { required: true, message: '请再次输入新密码' },
    {
      validator: (_rule: any, value: string) =>
        value === passwordForm.newPassword
          ? Promise.resolve()
          : Promise.reject(new Error('两次输入的新密码不一致')),
    },
  ],
}

/**
 * 头像上传
 *
 * 返回 false 阻止 a-upload 自己发请求，改为手动走 userProfile 接口。
 */
const beforeAvatarUpload = async (file: any) => {
  if (!file.type?.startsWith('image/')) {
    message.error('请选择图片文件')
    return false
  }
  if (file.size > 2 * 1024 * 1024) {
    message.error('图片不能超过 2MB')
    return false
  }

  uploading.value = true
  try {
    const res = await uploadAvatar(file)
    if (res.data.code === 0 && res.data.data) {
      message.success('头像已更新')
      await loginUserStore.fetchLoginUser()
    } else {
      message.error(res.data.message || '头像上传失败')
    }
  } catch (error: any) {
    message.error(error?.message || '头像上传失败')
  } finally {
    uploading.value = false
  }
  return false
}

/** 保存昵称与简介 */
const saveProfile = async () => {
  savingProfile.value = true
  try {
    const res = await updateMyUser({
      userName: profileForm.userName.trim(),
      userProfile: profileForm.userProfile.trim(),
    })
    if (res.data.code === 0) {
      message.success('资料已保存')
      await loginUserStore.fetchLoginUser()
      syncProfileForm()
    } else {
      message.error(res.data.message || '保存失败')
    }
  } catch (error: any) {
    message.error(error?.message || '保存失败')
  } finally {
    savingProfile.value = false
  }
}

/** 修改密码 */
const savePassword = async () => {
  savingPassword.value = true
  try {
    const res = await updatePassword({ ...passwordForm })
    if (res.data.code === 0) {
      message.success('密码已更新')
      passwordForm.oldPassword = ''
      passwordForm.newPassword = ''
      passwordForm.checkPassword = ''
    } else {
      message.error(res.data.message || '修改失败')
    }
  } catch (error: any) {
    message.error(error?.message || '修改失败')
  } finally {
    savingPassword.value = false
  }
}

onMounted(async () => {
  if (!user.value.id) {
    message.warning('请先登录')
    router.replace('/user/login?redirect=/user/profile')
    return
  }
  await loginUserStore.fetchLoginUser()
  syncProfileForm()
})
</script>

<style scoped>
.profile-page {
  min-height: 100vh;
  background: var(--c-bg);
}

.profile-inner {
  max-width: 1120px;
  margin: 0 auto;
  padding: 32px 24px 64px;
}

/* 身份区 */
.identity {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  align-items: center;
  padding: 32px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
}

.avatar-upload {
  display: block;
}

.avatar-wrap {
  position: relative;
  width: 88px;
  height: 88px;
  overflow: hidden;
  cursor: pointer;
  border-radius: 50%;
}

.avatar-wrap :deep(.ant-avatar) {
  color: var(--c-text-2);
  background: var(--c-bg-soft);
  font-size: 32px;
  font-weight: 600;
}

.avatar-mask {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-size: 12px;
  color: #fff;
  background: rgba(26, 26, 25, 0.55);
  border-radius: 50%;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.avatar-wrap:hover .avatar-mask,
.avatar-wrap.is-uploading .avatar-mask {
  opacity: 1;
}

.identity-main {
  flex: 1;
  min-width: 240px;
}

.identity-name {
  margin: 0 0 4px;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.4px;
  color: var(--c-text-1);
}

.identity-account {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--c-text-3);
}

.identity-tags {
  display: flex;
  gap: 8px;
}

.tag {
  padding: 2px 10px;
  font-size: 12px;
  color: var(--c-text-2);
  background: var(--c-bg-soft);
  border-radius: 999px;
}

.tag-vip {
  font-weight: 600;
  color: var(--c-accent);
  background: var(--c-accent-soft);
}

.identity-bio {
  max-width: 620px;
  margin: 12px 0 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--c-text-2);
}

/* 两栏 */
.profile-grid {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  margin-top: 16px;
}

.side,
.main {
  display: grid;
  gap: 16px;
  align-content: start;
}

.card {
  padding: 24px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
}

.card-title {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text-1);
}

.card-desc {
  margin: 0 0 20px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--c-text-3);
}

/* 额度 */
.quota-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
  margin-top: 12px;
}

.quota-value {
  font-size: 40px;
  font-weight: 700;
  letter-spacing: -1px;
  color: var(--c-text-1);
}

.quota-unit {
  font-size: 13px;
  color: var(--c-text-3);
}

.quota-hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-text-3);
}

.quota-bar {
  height: 6px;
  margin-top: 16px;
  overflow: hidden;
  background: var(--c-bg-soft);
  border-radius: 999px;
}

.quota-bar-fill {
  display: block;
  height: 100%;
  background: var(--c-accent);
  border-radius: 999px;
  transition: width 0.3s ease;
}

/* 账户信息 */
.meta {
  display: grid;
  gap: 10px;
  margin: 12px 0 0;
}

.meta-row {
  display: flex;
  gap: 12px;
  justify-content: space-between;
  font-size: 13px;
}

.meta-row dt {
  color: var(--c-text-3);
}

.meta-row dd {
  margin: 0;
  color: var(--c-text-1);
  text-align: right;
}

.meta-note {
  margin-left: 6px;
  font-size: 12px;
  color: var(--c-accent);
}

/* 响应式 */
@media (max-width: 900px) {
  .profile-inner {
    padding: 20px 16px 48px;
  }

  .identity {
    padding: 24px 20px;
  }

  .profile-grid {
    grid-template-columns: 1fr;
  }
}
</style>
