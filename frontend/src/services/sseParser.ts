/**
 * SSE 帧解析（WHATWG "event stream parsing" 的最小子集）。
 *
 * 只实现本项目用到的部分：`event` / `id` / `data` 字段与注释行。
 * `retry` 字段刻意忽略——重连的节奏由前端自己控制（见 {@link ../apiClient.ts}），
 * 服务端没有需要传达的 重试间隔。
 *
 * 独立成纯函数的原因：解析器的一切错误都表现为"前端安静地收不到事件"，
 * 不抛异常、无法在联调时被发现。只有把它做成字符串进、字符串出的纯函数，
 * 才能在单测里把分帧边界（一帧拆两次到达、CRLF、多行 data）逐个钉住。
 */
export interface SseMessage {
  /** 帧的 `event:` 字段值；未写时按规范是 `"message"`。 */
  event: string
  /** 帧的 `id:` 字段值；未写时为 `null`，调用方据此推进续传游标。 */
  id: string | null
  /** `data:` 行按规范用 `\n` 拼接（本项目的载荷都是单行 JSON）。 */
  data: string
}

/**
 * 从累积缓冲里取出**完整**的消息，返回剩余的不完整部分。
 *
 * 半帧留在 `rest` 里等下一块数据——按"凑齐一块就解析"的节奏调用即可，
 * 不需要调用方关心帧边界。
 */
export function extractSseMessages(buffer: string): { messages: SseMessage[]; rest: string } {
  // 规范允许 LF / CRLF / CR 三种行结束符。先归一成 LF 再按空行分帧，
  // 否则分帧逻辑要同时认三种边界。结尾若悬着半个 CRLF，把它留在 rest 里。
  const normalized = buffer.replace(/\r\n|\r/g, '\n')
  const endsWithCr = buffer.endsWith('\r') && !buffer.endsWith('\r\n')

  const messages: SseMessage[] = []
  let start = 0
  for (;;) {
    const boundary = normalized.indexOf('\n\n', start)
    if (boundary === -1) break
    const block = normalized.slice(start, boundary)
    start = boundary + 2
    const message = parseBlock(block)
    if (message) messages.push(message)
  }

  let rest = normalized.slice(start)
  if (endsWithCr) rest = rest.slice(0, -1) + '\r'
  return { messages, rest }
}

/** 解析一帧。没有 `data` 行的帧不产生消息（规范如此），返回 `null`。 */
function parseBlock(block: string): SseMessage | null {
  let event = 'message'
  let id: string | null = null
  const data: string[] = []

  for (const line of block.split('\n')) {
    // 冒号开头的行是注释（nginx 等代理用 ": keep-alive" 保活），跳过。
    if (line === '' || line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    // 规范：字段值前恰好一个空格属于分隔符，要去掉；后续空格是内容的一部分。
    if (value.startsWith(' ')) value = value.slice(1)

    if (field === 'event') event = value
    else if (field === 'data') data.push(value)
    else if (field === 'id' && !value.includes('\u0000')) id = value
  }

  if (data.length === 0) return null
  return { event, id, data: data.join('\n') }
}
