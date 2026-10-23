<template>
  <div class="title-selecting-stage">
    <div class="stage-header">
      <h2 class="stage-title">选择标题方案</h2>
      <p class="stage-subtitle">AI 为您生成了以下标题，请选择一个或自定义</p>
    </div>

    <a-radio-group v-model:value="selectedIndex" class="title-options">
      <div v-for="(option, index) in titleOptions" :key="index" class="title-option">
        <a-radio :value="index">
          <div class="title-content">
            <div class="title-main">{{ option.mainTitle }}</div>
            <div class="title-sub">{{ option.subTitle }}</div>
          </div>
        </a-radio>
      </div>

      <!-- 自定义标题 -->
      <div class="title-option custom">
        <a-radio :value="-1">
          <div class="title-content">
            <div class="title-main">自定义标题</div>
          </div>
        </a-radio>
        <div v-if="selectedIndex === -1" class="custom-inputs">
          <a-input v-model:value="customMainTitle" placeholder="输入主标题" class="custom-input" />
          <a-input v-model:value="customSubTitle" placeholder="输入副标题" class="custom-input" />
        </div>
      </div>
    </a-radio-group>

    <div class="description-section">
      <label class="section-label">补充描述（可选）</label>
      <p class="section-tip">补充您对文章的期望、重点强调的内容等</p>
      <a-textarea
        v-model:value="userDescription"
        placeholder="例如：请重点强调技术原理，用通俗的语言讲解..."
        :rows="4"
        :maxlength="500"
        show-count
        class="description-textarea"
      />
    </div>

    <div class="actions">
      <a-button
        type="primary"
        size="large"
        :loading="loading"
        :disabled="!canConfirm"
        class="confirm-btn"
        @click="handleConfirm"
      >
        <template #icon>
          <CheckOutlined />
        </template>
        确认并生成大纲
      </a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 创作阶段之一：标题方案选择
 *
 * 纯展示 + 选择组件，不碰任何接口：用户选中（或自定义）标题、填写补充描述后，
 * 通过 confirm 事件把结果交给父页面，由父页面调用 confirm-title 接口。
 * 这样组件可以独立测试，也不会把接口调用散落在各个交互组件里。
 */
import { computed, ref } from 'vue'
import { CheckOutlined } from '@ant-design/icons-vue'

/** 标题方案，对应后端 ArticleState.TitleOption */
interface TitleOption {
  mainTitle: string
  subTitle: string
}

interface Props {
  /** AI 生成的标题方案列表 */
  titleOptions: TitleOption[]
  /** 确认请求进行中 */
  loading?: boolean
}

interface Emits {
  (
    e: 'confirm',
    data: { mainTitle: string; subTitle: string; userDescription: string },
  ): void
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
})

const emit = defineEmits<Emits>()

/** 选中的方案下标；-1 表示使用自定义标题 */
const selectedIndex = ref<number>(0)
const customMainTitle = ref('')
const customSubTitle = ref('')
const userDescription = ref('')

/** 自定义标题时主、副标题都必填（后端 confirm-title 同样校验） */
const canConfirm = computed(() => {
  if (selectedIndex.value === -1) {
    return Boolean(customMainTitle.value.trim() && customSubTitle.value.trim())
  }
  return selectedIndex.value >= 0 && selectedIndex.value < props.titleOptions.length
})

const handleConfirm = () => {
  let mainTitle = ''
  let subTitle = ''

  if (selectedIndex.value === -1) {
    mainTitle = customMainTitle.value.trim()
    subTitle = customSubTitle.value.trim()
  } else {
    const selected = props.titleOptions[selectedIndex.value]
    // 开启 noUncheckedIndexedAccess 后索引访问可能为 undefined，这里显式收窄
    if (!selected) {
      return
    }
    mainTitle = selected.mainTitle
    subTitle = selected.subTitle
  }

  emit('confirm', {
    mainTitle,
    subTitle,
    userDescription: userDescription.value.trim(),
  })
}
</script>

<style scoped lang="scss">
$primary: #667eea;
$primary-dark: #764ba2;

.title-selecting-stage {
  padding: 32px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.stage-header {
  margin-bottom: 24px;

  .stage-title {
    margin: 0 0 8px;
    font-size: 22px;
    font-weight: 700;
    color: #1a1a1a;
  }

  .stage-subtitle {
    margin: 0;
    font-size: 14px;
    color: #8c8c8c;
  }
}

/* 标题方案列表 */
.title-options {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}

.title-option {
  padding: 14px 16px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 10px;
  transition: all 0.2s;

  :deep(.ant-radio-wrapper) {
    align-items: flex-start;
    width: 100%;
    margin: 0;
  }

  &:hover {
    border-color: $primary;
    background: #f5f7ff;
  }

  .title-content {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding-left: 2px;
  }

  /* 选中态：靠 :deep 匹配 antd 内部类名 */
  :deep(.ant-radio-wrapper-checked) {
    .title-main {
      color: $primary;
    }
  }

  .title-main {
    font-size: 16px;
    font-weight: 600;
    line-height: 1.5;
    color: #1a1a1a;
    white-space: normal;
  }

  .title-sub {
    font-size: 13px;
    line-height: 1.6;
    color: #8c8c8c;
    white-space: normal;
  }

  /* 自定义标题选项 */
  &.custom {
    .custom-inputs {
      display: flex;
      flex-direction: column;
      gap: 10px;
      margin: 12px 0 0 26px;
    }

    .custom-input {
      border-radius: 8px;
    }
  }
}

/* 补充描述 */
.description-section {
  margin-top: 24px;

  .section-label {
    display: block;
    margin-bottom: 4px;
    font-size: 14px;
    font-weight: 600;
    color: #1a1a1a;
  }

  .section-tip {
    margin: 0 0 10px;
    font-size: 12px;
    color: #bfbfbf;
  }

  .description-textarea {
    font-size: 14px;
    border-radius: 8px;
  }
}

/* 操作区 */
.actions {
  margin-top: 28px;
  text-align: right;

  .confirm-btn {
    min-width: 200px;
    height: 44px;
    font-size: 15px;
    font-weight: 600;
    border: none;
    border-radius: 8px;
    background: linear-gradient(135deg, $primary 0%, $primary-dark 100%);

    &:not(:disabled):hover {
      transform: translateY(-1px);
      box-shadow: 0 6px 14px rgba(102, 126, 234, 0.35);
    }
  }
}

/* 响应式 */
@media (max-width: 768px) {
  .title-selecting-stage {
    padding: 20px;
  }

  .actions {
    text-align: center;

    .confirm-btn {
      width: 100%;
    }
  }
}
</style>
