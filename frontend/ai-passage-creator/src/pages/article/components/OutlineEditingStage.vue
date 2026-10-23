<template>
  <div class="outline-editing-stage">
    <div class="stage-header">
      <h2 class="stage-title">编辑文章大纲</h2>
      <p class="stage-subtitle">您可以编辑、调整章节顺序，或添加新章节</p>
    </div>

    <!-- 章节列表：拖拽排序由 sortablejs 接管，handle 限定为左侧把手 -->
    <div ref="outlineListRef" class="outline-list">
      <div
        v-for="(section, index) in outlineSections"
        :key="section.section"
        class="outline-section"
        :data-section-id="section.section"
      >
        <div class="section-header">
          <span class="drag-handle" title="拖动排序">⋮⋮</span>
          <span class="section-number">{{ index + 1 }}</span>
          <a-input
            v-model:value="section.title"
            placeholder="章节标题"
            class="section-title-input"
          />
          <a-button type="text" danger class="delete-btn" @click="deleteSection(index)">
            <template #icon>
              <DeleteOutlined />
            </template>
          </a-button>
        </div>

        <div class="section-points">
          <div v-for="(point, pointIdx) in section.points" :key="pointIdx" class="point-item">
            <span class="point-bullet">•</span>
            <a-input
              v-model:value="section.points[pointIdx]"
              placeholder="要点内容"
              class="point-input"
            />
            <a-button
              type="text"
              size="small"
              class="delete-point-btn"
              @click="deletePoint(index, pointIdx)"
            >
              ×
            </a-button>
          </div>

          <a-button type="dashed" class="add-point-btn" @click="addPoint(index)">
            <template #icon>
              <PlusOutlined />
            </template>
            添加要点
          </a-button>
        </div>
      </div>
    </div>

    <!-- AI 辅助修改大纲 -->
    <div class="ai-chat-section">
      <div class="chat-header">
        <RobotOutlined />
        <span>AI 助手修改大纲</span>
      </div>
      <div class="chat-input-wrapper">
        <a-textarea
          v-model:value="modifySuggestion"
          placeholder="告诉 AI 如何修改大纲，例如：请在第二章节后增加一个关于实践案例的章节"
          :rows="3"
          :maxlength="500"
          show-count
          class="chat-textarea"
        />
        <a-button
          type="primary"
          :loading="aiModifying"
          :disabled="!modifySuggestion.trim()"
          class="ai-modify-btn"
          @click="handleAiModify"
        >
          <template #icon>
            <RobotOutlined />
          </template>
          AI 修改大纲
        </a-button>
      </div>
    </div>

    <div class="actions">
      <a-button size="large" class="add-section-btn" @click="addSection">
        <template #icon>
          <PlusOutlined />
        </template>
        添加章节
      </a-button>
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
        确认并生成正文
      </a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 创作阶段之一：大纲编辑
 *
 * 支持：章节标题/要点就地编辑、拖拽调整章节顺序、增删章节与要点、以及让 AI 按自然语言建议改写大纲。
 * 除「AI 修改大纲」需要调接口外，其余都是纯前端编辑；最终通过 confirm 事件把整份大纲交给父页面确认。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { CheckOutlined, DeleteOutlined, PlusOutlined, RobotOutlined } from '@ant-design/icons-vue'
import Sortable, { type SortableEvent } from 'sortablejs'
import { aiModifyOutline } from '@/api/articleController'

/** 内部使用的可编辑大纲结构（字段齐全，避免编辑时空值判断） */
interface OutlineSection {
  section: number
  title: string
  points: string[]
}

interface Props {
  /** 后端返回的大纲（字段都可选，组件内部会归一化） */
  outline: API.OutlineSection[]
  /** 当前任务 ID，AI 修改大纲需要 */
  taskId: string
  /** 确认请求进行中 */
  loading?: boolean
}

interface Emits {
  (e: 'confirm', outline: OutlineSection[]): void
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
})

const emit = defineEmits<Emits>()

/** 把 API 的可选字段归一化成可编辑结构 */
const normalizeOutline = (source: API.OutlineSection[]): OutlineSection[] =>
  source.map((item, index) => ({
    section: item.section ?? index + 1,
    title: item.title ?? '',
    points: [...(item.points ?? [])],
  }))

const outlineSections = ref<OutlineSection[]>(normalizeOutline(props.outline))

const outlineListRef = ref<HTMLElement | null>(null)
const modifySuggestion = ref('')
const aiModifying = ref(false)

/** sortablejs 实例，需要在卸载时销毁，否则会残留对 DOM 的监听 */
let sortableInstance: Sortable | null = null

/** 每个章节都要有标题和非空的要点，且要点内容不能为空 */
const canConfirm = computed(
  () =>
    outlineSections.value.length > 0 &&
    outlineSections.value.every(
      (section) =>
        Boolean(section.title.trim()) &&
        section.points.length > 0 &&
        section.points.every((point) => Boolean(point.trim())),
    ),
)

/** 章节序号重新编号，保证与界面顺序一致 */
const renumberSections = () => {
  outlineSections.value.forEach((section, index) => {
    section.section = index + 1
  })
}

onMounted(() => {
  nextTick(() => {
    if (!outlineListRef.value) {
      return
    }

    sortableInstance = Sortable.create(outlineListRef.value, {
      animation: 150,
      handle: '.drag-handle',
      onEnd: (evt: SortableEvent) => {
        const { oldIndex, newIndex } = evt
        if (oldIndex === undefined || newIndex === undefined || oldIndex === newIndex) {
          return
        }

        // 拖拽结束时按新位置重排数据，交给 Vue 重新渲染，避免 DOM 与数据顺序不一致
        const [moved] = outlineSections.value.splice(oldIndex, 1)
        if (!moved) {
          return
        }
        outlineSections.value.splice(newIndex, 0, moved)
        renumberSections()
      },
    })
  })
})

onBeforeUnmount(() => {
  sortableInstance?.destroy()
  sortableInstance = null
})

/** 添加章节 */
const addSection = () => {
  outlineSections.value.push({
    section: outlineSections.value.length + 1,
    title: '',
    points: [''],
  })
}

/** 删除章节 */
const deleteSection = (index: number) => {
  outlineSections.value.splice(index, 1)
  renumberSections()
}

/** 添加要点 */
const addPoint = (sectionIndex: number) => {
  const section = outlineSections.value[sectionIndex]
  if (!section) {
    return
  }
  section.points.push('')
}

/** 删除要点（每个章节至少保留一个，避免整章空掉） */
const deletePoint = (sectionIndex: number, pointIndex: number) => {
  const section = outlineSections.value[sectionIndex]
  if (!section || section.points.length <= 1) {
    return
  }
  section.points.splice(pointIndex, 1)
}

const handleConfirm = () => {
  emit('confirm', outlineSections.value)
}

/** 让 AI 按用户建议改写大纲，成功后直接替换本地编辑内容 */
const handleAiModify = async () => {
  if (!modifySuggestion.value.trim()) {
    message.warning('请输入修改建议')
    return
  }

  aiModifying.value = true
  try {
    const res = await aiModifyOutline({
      taskId: props.taskId,
      modifySuggestion: modifySuggestion.value.trim(),
    })

    const modified = res.data?.data
    if (modified && modified.length > 0) {
      outlineSections.value = normalizeOutline(modified)
      modifySuggestion.value = ''
      message.success('AI 已根据您的建议修改大纲')
    } else {
      message.warning(res.data?.message || 'AI 未返回有效大纲')
    }
  } catch (error) {
    const err = error as Error
    message.error(err.message || 'AI 修改失败')
  } finally {
    aiModifying.value = false
  }
}
</script>

<style scoped lang="scss">
$primary: #667eea;
$primary-dark: #764ba2;

.outline-editing-stage {
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

/* 章节列表 */
.outline-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.outline-section {
  padding: 16px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 10px;

  .section-header {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  /* 拖拽把手 */
  .drag-handle {
    flex: 0 0 auto;
    padding: 0 2px;
    font-size: 14px;
    line-height: 1;
    color: #bfbfbf;
    letter-spacing: -2px;
    cursor: grab;
    user-select: none;

    &:active {
      cursor: grabbing;
    }
  }

  .section-number {
    flex: 0 0 22px;
    width: 22px;
    height: 22px;
    font-size: 12px;
    font-weight: 600;
    line-height: 22px;
    color: #fff;
    text-align: center;
    background: $primary;
    border-radius: 50%;
  }

  .section-title-input {
    flex: 1;
    min-width: 0;
    font-size: 15px;
    font-weight: 600;

    :deep(.ant-input) {
      border-radius: 8px;
    }
  }

  .delete-btn {
    flex: 0 0 auto;
  }

  .section-points {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin: 12px 0 0 32px;
  }

  .point-item {
    display: flex;
    align-items: center;
    gap: 8px;

    .point-bullet {
      flex: 0 0 auto;
      color: $primary;
    }

    .point-input {
      flex: 1;
      min-width: 0;

      :deep(.ant-input) {
        font-size: 13px;
        border-radius: 6px;
      }
    }

    .delete-point-btn {
      flex: 0 0 auto;
      color: #bfbfbf;

      &:hover {
        color: #cf1322;
      }
    }
  }

  .add-point-btn {
    align-self: flex-start;
    margin-top: 2px;
    font-size: 13px;
  }
}

/* AI 辅助修改 */
.ai-chat-section {
  margin-top: 24px;
  padding: 16px;
  background: #f5f7ff;
  border: 1px solid #e4e9f2;
  border-radius: 10px;

  .chat-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
    font-size: 14px;
    font-weight: 600;
    color: $primary;
  }

  .chat-input-wrapper {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .chat-textarea {
    font-size: 14px;
    border-radius: 8px;
  }

  .ai-modify-btn {
    align-self: flex-end;
  }
}

/* 操作区 */
.actions {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 28px;

  .add-section-btn {
    height: 44px;
    border-radius: 8px;
  }

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
  .outline-editing-stage {
    padding: 20px;
  }

  .outline-section .section-points {
    margin-left: 0;
  }

  .actions {
    flex-direction: column;

    .confirm-btn {
      width: 100%;
    }
  }
}
</style>
