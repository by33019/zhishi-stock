import { describe, expect, it, vi } from 'vitest'

import { useRemoteData } from './useRemoteData'

describe('useRemoteData', () => {
  it('成功后写入数据并结束加载态', async () => {
    const { data, loading, error, reload } = useRemoteData(() => Promise.resolve({ value: 1 }))

    const pending = reload()
    expect(loading.value).toBe(true)

    await pending
    expect(data.value).toEqual({ value: 1 })
    expect(loading.value).toBe(false)
    expect(error.value).toBeUndefined()
  })

  it('失败时保留 traceId 供用户报障时对上服务端日志', async () => {
    const { data, loading, error, reload } = useRemoteData(() =>
      Promise.reject({ code: 'SECTOR_NOT_FOUND', message: '板块不存在', traceId: 'trace-404' }))

    await reload()

    expect(data.value).toBeUndefined()
    expect(loading.value).toBe(false)
    expect(error.value).toEqual({ message: '板块不存在', traceId: 'trace-404' })
  })

  it('重试成功后清掉上一次的错误，而不是一直显示旧错误', async () => {
    const load = vi.fn()
      .mockRejectedValueOnce({ message: '网络异常' })
      .mockResolvedValueOnce({ value: 2 })
    const { data, error, reload } = useRemoteData(load)

    await reload()
    expect(error.value?.message).toBe('网络异常')

    await reload()
    expect(error.value).toBeUndefined()
    expect(data.value).toEqual({ value: 2 })
  })

  it('丢弃过期响应：先发的慢请求不得覆盖后发的快请求', async () => {
    // 快速切换榜单口径时请求会并发，而返回顺序不保证与发出顺序一致。
    // 没有这个守卫的话，页面显示"跌幅榜"却列着涨幅榜的内容，且不会有任何异常。
    let resolveSlow: ((value: { tag: string }) => void) | undefined
    const slow = new Promise<{ tag: string }>((resolve) => {
      resolveSlow = resolve
    })
    const load = vi.fn()
      .mockReturnValueOnce(slow)
      .mockResolvedValueOnce({ tag: 'fast' })

    const { data, reload } = useRemoteData(load)

    const slowCall = reload()
    await reload()
    expect(data.value).toEqual({ tag: 'fast' })

    resolveSlow?.({ tag: 'slow' })
    await slowCall
    expect(data.value).toEqual({ tag: 'fast' })
  })
})
