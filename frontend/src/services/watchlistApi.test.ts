import { describe, expect, it, vi } from 'vitest'

const client = vi.hoisted(() => ({ apiRequest: vi.fn(), toQueryString: vi.fn() }))
vi.mock('./apiClient', async () => {
  // `toQueryString` 用真实实现：它丢弃 null/undefined/空串的行为正是这里要守的契约。
  const actual = await vi.importActual<typeof import('./apiClient')>('./apiClient')
  return { ...client, toQueryString: actual.toQueryString }
})

import {
  addWatchlistItem,
  createWatchlistGroup,
  deleteWatchlistGroup,
  getWatchlistOverview,
  moveWatchlistItem,
  removeWatchlistItem,
  renameWatchlistGroup,
  reorderWatchlistGroups,
  reorderWatchlistItems,
} from './watchlistApi'

describe('自选接口', () => {
  it('WAT-11 不传 groupId 时 query 里没有该参数，不传空串', async () => {
    client.apiRequest.mockResolvedValue({})

    await getWatchlistOverview()
    expect(client.apiRequest).toHaveBeenCalledWith('/watchlists/overview?')

    await getWatchlistOverview('7001')
    expect(client.apiRequest).toHaveBeenLastCalledWith('/watchlists/overview?groupId=7001')
  })

  it('WAT-02 用 POST 并把幂等键放进请求头', async () => {
    client.apiRequest.mockResolvedValue({})

    await createWatchlistGroup('核心持仓', 'key-1')

    expect(client.apiRequest).toHaveBeenCalledWith('/watchlist-groups', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'key-1' },
      body: JSON.stringify({ groupName: '核心持仓' }),
    })
  })

  it('WAT-03 把版本号放进 If-Match', async () => {
    client.apiRequest.mockResolvedValue({})

    await renameWatchlistGroup('7001', '核心持仓', 3)

    expect(client.apiRequest).toHaveBeenCalledWith('/watchlist-groups/7001', {
      method: 'PATCH',
      headers: { 'If-Match': '3' },
      body: JSON.stringify({ groupName: '核心持仓' }),
    })
  })

  it('WAT-04 删除非空分组时带 moveItemsToGroupId；不传时 query 里没有它', async () => {
    client.apiRequest.mockResolvedValue({})

    await deleteWatchlistGroup('7001', 0, '7002')
    expect(client.apiRequest).toHaveBeenLastCalledWith(
      '/watchlist-groups/7001?moveItemsToGroupId=7002',
      { method: 'DELETE', headers: { 'If-Match': '0' } },
    )

    await deleteWatchlistGroup('7001', 0)
    expect(client.apiRequest).toHaveBeenLastCalledWith('/watchlist-groups/7001?', {
      method: 'DELETE',
      headers: { 'If-Match': '0' },
    })
  })

  it('WAT-05 提交完整的 groupIds 数组', async () => {
    client.apiRequest.mockResolvedValue({})

    await reorderWatchlistGroups(['7002', '7001'])

    expect(client.apiRequest).toHaveBeenCalledWith('/watchlist-groups/order', {
      method: 'PUT',
      body: JSON.stringify({ groupIds: ['7002', '7001'] }),
    })
  })

  it('WAT-07 把 securityId 放进请求体、幂等键放进请求头', async () => {
    client.apiRequest.mockResolvedValue({})

    await addWatchlistItem('7001', 'sim-600519', 'key-2')

    expect(client.apiRequest).toHaveBeenCalledWith('/watchlist-groups/7001/items', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'key-2' },
      body: JSON.stringify({ securityId: 'sim-600519' }),
    })
  })

  it('WAT-08 用 DELETE 且不带请求体', async () => {
    client.apiRequest.mockResolvedValue({})

    await removeWatchlistItem('7001', '8001')

    expect(client.apiRequest).toHaveBeenCalledWith('/watchlist-groups/7001/items/8001', {
      method: 'DELETE',
    })
  })

  it('WAT-09 的 targetGroupId 与 If-Match 都按契约传', async () => {
    client.apiRequest.mockResolvedValue({})

    await moveWatchlistItem('7001', '8001', '7002', 5)

    expect(client.apiRequest).toHaveBeenCalledWith('/watchlist-groups/7001/items/8001', {
      method: 'PATCH',
      headers: { 'If-Match': '5' },
      body: JSON.stringify({ targetGroupId: '7002' }),
    })
  })

  it('WAT-10 提交完整的 itemIds 数组', async () => {
    client.apiRequest.mockResolvedValue({})

    await reorderWatchlistItems('7001', ['8002', '8001'])

    expect(client.apiRequest).toHaveBeenCalledWith('/watchlist-groups/7001/items/order', {
      method: 'PUT',
      body: JSON.stringify({ itemIds: ['8002', '8001'] }),
    })
  })
})
