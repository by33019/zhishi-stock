import { describe, expect, it } from 'vitest'

import { extractSseMessages } from './sseParser'

describe('SSE 帧解析（AI-05）', () => {
  it('按空行分帧，取出 event 与 data', () => {
    const { messages, rest } = extractSseMessages(
      'event: snapshot\ndata: {"task":1}\n\nevent: chunk\ndata: {"delta":"你好"}\n\n',
    )

    expect(messages).toEqual([
      { event: 'snapshot', id: null, data: '{"task":1}' },
      { event: 'chunk', id: null, data: '{"delta":"你好"}' },
    ])
    expect(rest).toBe('')
  })

  it('半帧留在 rest 里等下一块，凑齐后再解析', () => {
    const first = extractSseMessages('event: chunk\ndata: {"del')
    expect(first.messages).toEqual([])
    expect(first.rest).toBe('event: chunk\ndata: {"del')

    const second = extractSseMessages(first.rest + 'ta":"你好"}\n\n')
    expect(second.messages).toEqual([{ event: 'chunk', id: null, data: '{"delta":"你好"}' }])
  })

  it('id 字段被取出，用于推进 Last-Event-ID 游标', () => {
    const { messages } = extractSseMessages('id: 42\nevent: status\ndata: {"status":"RUNNING"}\n\n')

    expect(messages).toEqual([
      { event: 'status', id: '42', data: '{"status":"RUNNING"}' },
    ])
  })

  it('CRLF 与 LF 混用都能分帧（服务端与代理的行结束符不保证一致）', () => {
    const { messages } = extractSseMessages(
      'event: chunk\r\ndata: {"a":1}\r\n\r\nevent: done\ndata: {"b":2}\n\n',
    )

    expect(messages.map((message) => message.event)).toEqual(['chunk', 'done'])
  })

  it('多行 data 按规范用 \\n 拼接', () => {
    const { messages } = extractSseMessages('data: 第一行\ndata: 第二行\n\n')

    expect(messages).toEqual([{ event: 'message', id: null, data: '第一行\n第二行' }])
  })

  it('注释行与没有 data 的帧不产生消息（规范如此）', () => {
    const { messages } = extractSseMessages(
      ': keep-alive\n\nid: 7\n\n: nginx 保活\nevent: chunk\ndata: {}\n\n',
    )

    expect(messages).toEqual([{ event: 'chunk', id: null, data: '{}' }])
  })

  it('字段值前的一个空格是分隔符，内容里的空格保留', () => {
    const { messages } = extractSseMessages('data:  两个空格开头 \n\n')

    expect(messages).toEqual([{ event: 'message', id: null, data: ' 两个空格开头 ' }])
  })
})
