import DOMPurify from 'dompurify'
import { marked } from 'marked'

/**
 * 把 Markdown 渲染为安全的 HTML
 *
 * 正文与配图都来自 LLM 与外部图源，属于不可信内容：模型完全可能在正文里输出
 * <script>、<img onerror=...>、<a href="javascript:..."> 这类载荷。由于页面用 v-html
 * 插入渲染结果，必须在唯一的渲染出口做一次白名单净化——目前 4 处 v-html
 * （创作页的流式正文与完成态图文、详情页的正文与完整图文）全部经过这里。
 *
 * 沿用 DOMPurify 的默认白名单：正文需要的 h1-h6 / p / ul / ol / li / blockquote /
 * pre / code / table / img / a / strong / em 等标签，以及 class 等安全属性都会保留；
 * script、iframe、内联事件属性（on*）与 javascript: 协议会被剔除。
 */
export const markdownToHtml = (markdown: string): string => {
  const html = marked(markdown) as string
  return DOMPurify.sanitize(html)
}
