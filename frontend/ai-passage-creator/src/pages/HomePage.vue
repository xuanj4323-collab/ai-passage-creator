<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/loginUser'

const router = useRouter()
const loginUserStore = useLoginUserStore()

const isLogin = computed(() => Boolean(loginUserStore.loginUser.id))

/** 三步流程 */
const steps = [
  {
    title: '输入选题',
    desc: '一句话说明你想写什么，也可以从热门选题里直接挑一个。',
  },
  {
    title: '智能体协作',
    desc: '选题分析、标题打磨、大纲规划、正文撰写、配图排版依次执行，过程可见。',
  },
  {
    title: '导出成稿',
    desc: '生成结果可直接预览，一键导出 Markdown，粘贴到任何编辑器。',
  },
]

/** 能力卡片 */
const features = [
  {
    title: '多智能体协作',
    desc: '标题、大纲、正文、配图各由一个智能体负责，把长文写作拆成可检查的步骤。',
  },
  {
    title: '多模态配图',
    desc: '支持 Unsplash 图库、Mermaid 图表、Iconify 图标与 AI 生图，按段落自动配图。',
  },
  {
    title: '过程可追踪',
    desc: '每一步都有实时日志，生成卡在哪一步、失败原因是什么，都看得见。',
  },
  {
    title: '一键导出',
    desc: '完整图文导出为 Markdown，标题层级与图片引用一并保留。',
  },
]

const goCreate = () =>
  router.push(isLogin.value ? '/create' : '/user/login?redirect=/create')
const goList = () =>
  router.push(isLogin.value ? '/article/list' : '/user/login?redirect=/article/list')
</script>

<template>
  <div class="home">
    <!-- 首屏 -->
    <section class="hero">
      <span class="eyebrow">AI 图文创作</span>
      <h1 class="title">
        输入一个选题，<br />
        写成一篇完整图文
      </h1>
      <p class="subtitle">
        从标题、大纲到正文与配图，由多个智能体分工完成。你只需要给出方向，剩下的交给流程。
      </p>

      <div class="actions">
        <a-button type="primary" size="large" @click="goCreate">开始创作</a-button>
        <a-button size="large" @click="goList">我的文章</a-button>
      </div>

      <p v-if="isLogin" class="login-hint">
        欢迎回来，{{ loginUserStore.loginUser.userName ?? '创作者' }}。
      </p>
      <p v-else class="login-hint">
        已有账号？<router-link to="/user/login">登录</router-link>后会保存你的创作记录
      </p>
    </section>

    <!-- 流程 -->
    <section class="steps">
      <div v-for="(step, index) in steps" :key="step.title" class="step">
        <span class="step-no">{{ String(index + 1).padStart(2, '0') }}</span>
        <h2 class="step-title">{{ step.title }}</h2>
        <p class="step-desc">{{ step.desc }}</p>
      </div>
    </section>

    <!-- 能力 -->
    <section class="features">
      <h2 class="section-title">它能做到什么</h2>
      <div class="feature-grid">
        <article v-for="feature in features" :key="feature.title" class="feature-card">
          <h3>{{ feature.title }}</h3>
          <p>{{ feature.desc }}</p>
        </article>
      </div>
    </section>

    <!-- 底部行动 -->
    <section class="cta">
      <h2>现在写第一篇</h2>
      <p>不用准备提纲，给一个你最近在想的话题就好。</p>
      <a-button type="primary" size="large" @click="goCreate">开始创作</a-button>
    </section>
  </div>
</template>

<style scoped>
.home {
  max-width: 1120px;
  margin: 0 auto;
  padding: 0 24px 64px;
}

/* 首屏 */
.hero {
  max-width: 760px;
  padding: 88px 0 64px;
}

.eyebrow {
  display: inline-block;
  padding: 4px 12px;
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--c-primary);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 999px;
}

.title {
  margin: 20px 0 18px;
  font-size: 48px;
  font-weight: 700;
  line-height: 1.22;
  letter-spacing: -1px;
  color: var(--c-text-1);
}

.subtitle {
  max-width: 600px;
  margin: 0;
  font-size: 16px;
  line-height: 1.75;
  color: var(--c-text-2);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 28px;
}

.login-hint {
  margin: 16px 0 0;
  font-size: 13px;
  color: var(--c-text-3);
}

/* 流程 */
.steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  overflow: hidden;
  background: var(--c-border);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
}

.step {
  padding: 24px;
  background: var(--c-surface);
}

.step-no {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--c-text-3);
}

.step-title {
  margin: 10px 0 6px;
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text-1);
}

.step-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--c-text-2);
}

/* 能力 */
.features {
  margin-top: 64px;
}

.section-title {
  margin: 0 0 20px;
  font-size: 22px;
  font-weight: 700;
  color: var(--c-text-1);
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.feature-card {
  padding: 24px;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  transition:
    box-shadow 0.2s ease,
    transform 0.2s ease;
}

.feature-card:hover {
  box-shadow: var(--shadow-2);
  transform: translateY(-2px);
}

.feature-card h3 {
  margin: 0 0 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text-1);
}

.feature-card p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--c-text-2);
}

/* 底部行动 */
.cta {
  padding: 48px 24px;
  margin-top: 64px;
  text-align: center;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-xl);
}

.cta h2 {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: 700;
  color: var(--c-text-1);
}

.cta p {
  margin: 0 0 24px;
  font-size: 14px;
  color: var(--c-text-2);
}

/* 响应式 */
@media (max-width: 900px) {
  .hero {
    padding: 56px 0 40px;
  }

  .title {
    font-size: 32px;
    letter-spacing: -0.5px;
  }

  .steps {
    grid-template-columns: 1fr;
  }

  .feature-grid {
    grid-template-columns: 1fr;
  }

  .features,
  .cta {
    margin-top: 40px;
  }
}
</style>
