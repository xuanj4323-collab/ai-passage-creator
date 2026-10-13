/**
 * SSE 工具函数
 *
 * 后端通过 SseEmitter 推送创作进度，前端用 EventSource 接收。
 * 这里只负责「连接生命周期」，业务语义（收到消息做什么、异常怎么提示）全部交给调用方，
 * 因此不对请求路径做任何假设——完整 URL 由调用方拼好传进来。
 *
 * 集中处理三件容易踩坑的事：
 * 1）收到终态消息后主动关闭连接，否则浏览器会按服务端下发的 reconnectTime 反复重连一个已经结束的流；
 * 2）吞掉终态关闭后浏览器补发的那次 error——服务端 complete() 会触发它，它并不是真的异常，
 *    若不加区分就提示「连接中断」，明明创作已经成功却弹了错误；
 * 3）单条消息解析失败只跳过这一条，不断开整个连接。
 */

/** 所有推送消息都带 type 字段，与后端 SseMessageTypeEnum 对应 */
export interface SseMessage {
  type: string
}

/** 连接结束的原因 */
export type SseCloseReason =
  /** 收到终态消息（默认 ALL_COMPLETE / ERROR）后由前端主动关闭 */
  | 'done'
  /** 连接异常断开 */
  | 'network'
  /** 调用方主动关闭，例如组件卸载或用户重新开始创作 */
  | 'aborted'

export interface SseOptions<T extends SseMessage = SseMessage> {
  /** 完整请求地址（含 baseURL 与查询参数），由调用方拼接 */
  url: string
  /** 收到业务消息；进入这里时 JSON 解析与终态判定都已由工具处理 */
  onMessage: (payload: T) => void
  /** 判断某条消息是否代表流已结束，默认识别 type 为 ALL_COMPLETE / ERROR */
  isTerminal?: (payload: T) => boolean
  /** 连接异常断开；终态关闭后补发的伪 error 不会触发这里 */
  onError?: (error: Event) => void
  /** 连接已关闭，reason 说明原因 */
  onClose?: (reason: SseCloseReason) => void
  /** 是否携带 Cookie，默认 true（后端 CorsConfig 已 allowCredentials(true)） */
  withCredentials?: boolean
}

/** 连接句柄 */
export interface SseConnection {
  /** 主动关闭连接（幂等，重复调用不会重复触发 onClose） */
  close: () => void
  /** 底层 EventSource，一般无需直接使用 */
  readonly source: EventSource
}

/** 默认的终态消息类型 */
const TERMINAL_TYPES = ['ALL_COMPLETE', 'ERROR']

/**
 * 建立 SSE 连接
 *
 * @param options 连接参数与回调集合
 * @returns 连接句柄，调用方通过 close() 主动断开
 */
export const openSse = <T extends SseMessage = SseMessage>(options: SseOptions<T>): SseConnection => {
  const { url, onMessage, isTerminal, onError, onClose, withCredentials = true } = options

  const source = new EventSource(url, { withCredentials })

  /** 是否已经关闭；它同时充当「终态后伪 error」的判定依据，属于工具内部状态 */
  let closed = false

  /** 统一收口，保证 close 幂等且 onClose 只回调一次 */
  const finish = (reason: SseCloseReason) => {
    if (closed) {
      return
    }
    closed = true
    source.close()
    onClose?.(reason)
  }

  const isTerminalMessage = isTerminal ?? ((payload: T) => TERMINAL_TYPES.includes(payload.type))

  source.onmessage = (event) => {
    if (closed) {
      return
    }

    let payload: T
    try {
      payload = JSON.parse(event.data) as T
    } catch (error) {
      console.error('SSE 消息解析失败:', error)
      return
    }

    onMessage(payload)

    if (isTerminalMessage(payload)) {
      finish('done')
    }
  }

  source.onerror = (event) => {
    // 已经因终态关闭过连接，这次 error 是服务端 complete() 之后的正常余波，静默忽略
    if (closed) {
      return
    }

    console.error('SSE 连接错误:', event)
    onError?.(event)
    finish('network')
  }

  return {
    close: () => finish('aborted'),
    get source() {
      return source
    },
  }
}
