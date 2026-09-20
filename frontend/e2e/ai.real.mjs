import assert from 'node:assert/strict'

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8080'
const account = process.env.E2E_USERNAME ?? 'demo'
const password = process.env.E2E_PASSWORD ?? 'Stock@123'

async function api(method, path, { body, token } = {}) {
  const response = await fetch(`${baseURL}${path}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const text = await response.text()
  let parsed = null
  try {
    parsed = JSON.parse(text)
  } catch {
    parsed = text
  }
  return { status: response.status, body: parsed }
}

// ---------- 1. 未登录必须被挡住 ----------
assert.equal((await api('GET', '/api/v1/ai/scenes')).status, 401)
assert.equal(
  (await api('POST', '/api/v1/ai/context-previews', { body: { scene: 'MARKET', targets: [] } })).status,
  401,
)
console.log('✓ 未登录访问 AI-01 / AI-02 均 401')

// ---------- 2. 登录 ----------
const login = await api('POST', '/api/v1/auth/login', { body: { account, password } })
assert.equal(login.status, 200)
const token = login.body.data.accessToken
assert.ok(token, '登录必须返回 accessToken')
console.log('✓ 登录成功')

// ---------- 3. AI-01 场景目录 ----------
const scenes = await api('GET', '/api/v1/ai/scenes', { token })
assert.equal(scenes.status, 200)
assert.deepEqual(
  scenes.body.data.map((scene) => scene.scene),
  ['MARKET', 'SECTOR', 'STOCK', 'STOCK_RISK', 'COMPARE'],
)
for (const scene of scenes.body.data) {
  assert.ok(scene.name && scene.description, `${scene.scene} 缺名称或说明`)
  assert.equal(scene.questionMaxLength, 500)
  assert.equal(scene.defaultRange.presets.length, 3)
  assert.equal(scene.defaultRange.defaultPreset, 'LAST_5_TRADING_DAYS')
  assert.equal(scene.defaultRange.maxCustomDays, 365)
}
assert.equal(scenes.body.data[4].minTargets, 2)
assert.equal(scenes.body.data[4].maxTargets, 3)
const scenesRaw = JSON.stringify(scenes.body)
for (const forbidden of ['systemPrompt', 'userPrompt', 'promptTemplate']) {
  assert.ok(!scenesRaw.includes(forbidden), `AI-01 不应出现 ${forbidden}`)
}
console.log('✓ AI-01：5 个场景，字段齐全，无内部 Prompt')

// ---------- 4. AI-02 合法请求 ----------
const preview = await api('POST', '/api/v1/ai/context-previews', {
  token,
  body: {
    scene: 'STOCK',
    targets: [{ targetType: 'SECURITY', targetId: 'sim-600519', targetRole: 'PRIMARY' }],
  },
})
assert.equal(preview.status, 200)
const data = preview.body.data
assert.equal(data.targets.length, 1)
assert.equal(data.targets[0].targetId, 'sim-600519')
assert.equal(data.targets[0].targetRole, 'PRIMARY')
assert.ok(data.targets[0].targetCode && data.targets[0].targetName, '目标摘要必须来自主数据')
assert.ok(!('storageId' in data.targets[0]), '不得返回内部代理键')
const categories = data.dataCategories.map((cutoff) => cutoff.category)
assert.ok(categories.includes('QUOTE'), `缺少 QUOTE：${categories.join(',')}`)
for (const notYet of ['KLINE', 'BUSINESS', 'CALENDAR', 'RULE']) {
  assert.ok(!categories.includes(notYet), `未接入的类别不得出现：${notYet}`)
}
const previewRaw = JSON.stringify(preview.body)
for (const forbidden of ['systemPrompt', 'userPrompt', 'storageId', 'contentHash']) {
  assert.ok(!previewRaw.includes(forbidden), `AI-02 不应出现 ${forbidden}`)
}
console.log(`✓ AI-02：canGenerate=${data.canGenerate} newsCount=${data.newsCount}`)
console.log(`  目标：${data.targets[0].targetCode} ${data.targets[0].targetName}`)
for (const cutoff of data.dataCategories) {
  console.log(`  数据类别：${cutoff.category} 截止 ${cutoff.dataCutoffAt}`)
}
if (data.limitations.length > 0) {
  console.log(`  降级说明：${data.limitations.join(' / ')}`)
}

// ---------- 5. AI-02 对比场景（两个标的同一快照版本） ----------
const compare = await api('POST', '/api/v1/ai/context-previews', {
  token,
  body: {
    scene: 'COMPARE',
    targets: [
      { targetType: 'SECURITY', targetId: 'sim-600519', targetRole: 'PRIMARY' },
      { targetType: 'SECURITY', targetId: 'sim-000001', targetRole: 'COMPARISON' },
    ],
  },
})
assert.equal(compare.status, 200)
assert.equal(compare.body.data.targets.length, 2)
assert.deepEqual(
  compare.body.data.targets.map((target) => target.targetRole),
  ['PRIMARY', 'COMPARISON'],
)
const quoteCutoff = compare.body.data.dataCategories.find((c) => c.category === 'QUOTE')
const stockQuoteCutoff = data.dataCategories.find((c) => c.category === 'QUOTE')
assert.equal(
  quoteCutoff.dataCutoffAt,
  stockQuoteCutoff.dataCutoffAt,
  '两次预览的行情截止时间应当一致（同一批次）',
)
console.log(`✓ AI-02 对比场景：2 个目标，行情截止 ${quoteCutoff.dataCutoffAt}（与单标的场景一致）`)

// ---------- 6. 市场场景 ----------
const market = await api('POST', '/api/v1/ai/context-previews', {
  token,
  body: { scene: 'MARKET', targets: [{ targetType: 'MARKET', targetId: 'CN', targetRole: 'PRIMARY' }] },
})
assert.equal(market.status, 200)
assert.equal(market.body.data.targets[0].targetId, 'CN')
console.log(`✓ AI-02 市场场景：canGenerate=${market.body.data.canGenerate}`)

// ---------- 7. 非法请求 ----------
const cases = [
  {
    name: '未知场景',
    body: { scene: 'NOT_A_SCENE', targets: [{ targetType: 'MARKET', targetId: 'CN', targetRole: 'PRIMARY' }] },
    code: 'INVALID_REQUEST',
  },
  {
    name: '目标类型与场景不匹配',
    body: { scene: 'MARKET', targets: [{ targetType: 'SECURITY', targetId: 'sim-600519', targetRole: 'PRIMARY' }] },
    code: 'AI_TARGET_INVALID',
  },
  {
    name: 'COMPARE 只有一个目标',
    body: { scene: 'COMPARE', targets: [{ targetType: 'SECURITY', targetId: 'sim-600519', targetRole: 'PRIMARY' }] },
    code: 'AI_TARGET_INVALID',
  },
  {
    name: '客户端传 CONTEXT 角色',
    body: { scene: 'STOCK', targets: [{ targetType: 'SECURITY', targetId: 'sim-600519', targetRole: 'CONTEXT' }] },
    code: 'AI_TARGET_INVALID',
  },
  {
    name: '证券标识不存在',
    body: { scene: 'STOCK', targets: [{ targetType: 'SECURITY', targetId: 'sim-999999', targetRole: 'PRIMARY' }] },
    code: 'AI_TARGET_INVALID',
  },
  {
    name: '只给区间起点',
    body: {
      scene: 'STOCK',
      targets: [{ targetType: 'SECURITY', targetId: 'sim-600519', targetRole: 'PRIMARY' }],
      analysisStartAt: '2026-09-01T00:00:00+08:00',
    },
    code: 'INVALID_REQUEST',
  },
  {
    name: '区间跨度超过 365 天',
    body: {
      scene: 'STOCK',
      targets: [{ targetType: 'SECURITY', targetId: 'sim-600519', targetRole: 'PRIMARY' }],
      analysisStartAt: '2024-01-01T00:00:00+08:00',
      analysisEndAt: '2026-09-18T15:00:00+08:00',
    },
    code: 'INVALID_REQUEST',
  },
]

for (const item of cases) {
  const result = await api('POST', '/api/v1/ai/context-previews', { token, body: item.body })
  assert.equal(result.status, 400, `${item.name} 应当 400，实际 ${result.status}`)
  assert.equal(result.body.code, item.code, `${item.name} 业务码应当是 ${item.code}`)
  console.log(`✓ ${item.name} → 400 ${result.body.code}`)
}

console.log('\nAI-01 / AI-02 真实端到端核对全部通过')
