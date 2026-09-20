import { ref, shallowRef } from 'vue'

/** 失败态：`traceId` 直接来自后端统一响应壳，用户报障时能对上服务端日志。 */
export interface RemoteError {
  message: string
  traceId?: string
}

/**
 * 一次远程请求的三态：`data` / `loading` / `error`，外加 `reload`。
 *
 * 抽出来是因为四个接入页都需要同一套样板，各写一遍就是四份同样的
 * `try / catch / finally`——而它们的分支很容易各自演化（例如某处忘了清 `error`，
 * 于是"重试成功后仍显示上一次的错误"）。
 *
 * **刻意不做的事**：不做缓存、不做并发去重、不做轮询、不做重试退避。
 * 这些都没有需求支撑；`apiClient` 已经处理了 401 刷新。
 *
 * `data` 用 `shallowRef`：响应对象是整体替换的，深层响应式只会浪费遍历开销。
 */
export function useRemoteData<T>(load: () => Promise<T>) {
  const data = shallowRef<T>()
  const loading = ref(false)
  const error = ref<RemoteError>()

  /**
   * 请求序号：只有"最新一次"的响应才允许写入状态。
   *
   * 快速切换榜单口径 / K 线周期时会并发多个请求，而它们的返回顺序**不保证**与发出顺序一致。
   * 没有这个守卫的话，先发的慢请求后到达，会把新口径的数据覆盖成旧口径的——
   * 页面显示"跌幅榜"却列着涨幅榜的内容，且不会有任何异常。
   */
  let latestRequest = 0

  async function reload(): Promise<void> {
    const request = ++latestRequest
    loading.value = true
    error.value = undefined
    try {
      const result = await load()
      if (request !== latestRequest) return
      data.value = result
    } catch (cause) {
      if (request !== latestRequest) return
      const failure = cause as { message?: string; traceId?: string }
      data.value = undefined
      error.value = {
        message: failure?.message ?? '数据暂时无法加载',
        traceId: failure?.traceId,
      }
    } finally {
      if (request === latestRequest) loading.value = false
    }
  }

  return { data, loading, error, reload }
}
