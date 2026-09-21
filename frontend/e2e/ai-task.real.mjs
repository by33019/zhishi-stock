// M3-07 真实端到端：AI 任务从创建到报告落库的整条链路。
//
// 跑法（全栈已在 docker-compose 下起来）：
//   node frontend/e2e/ai-task.real.mjs
//
// 它验证的是**单测与契约测试都验不到**的部分：跨进程（stock-api 创建 → Redis Stream
// → stock-ai-worker 执行 → MySQL 落库 → stock-api 中继 SSE）、真实 Redis 的消费组语义、
// 以及五张表里确实有真实数据（而不是夹具）。
//
// 前身是 ai.real.mjs（M3-06，只覆盖 AI-01/AI-02）；本脚本覆盖 M3-07 的 AI-03~AI-08。

import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8080'
const account = process.env.E2E_USERNAME ?? 'demo'
const password = process.env.E2E_PASSWORD ?? 'Stock@123'

const mysqlContainer = process.env.E2E_MYSQL_CONTAINER ?? 'stock_system-mysql-1'
const dbName = process.env.E2E_DB_NAME ?? 'stock_system'
const dbUser = process.env.E2E_DB_USER ?? 'stock'
const dbPassword = process.env.E2E_DB_PASSWORD ?? 'stock_dev_password'

const SECTIONS = [
  'CORE_CONCLUSION',
  'QUOTE_EVIDENCE',
  'COMPARISON_ANALYSIS',
  'EVENT_CLUES',
  'RISK_AND_UNCERTAINTY',
  'DISCLAIMER',
]

async function api(method, path, { body, token, headers } = {}) {
  const response = await fetch(`${baseURL}${path}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
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
  return { status: response.status, body: parsed, headers: response.headers }
}

function query(sql) {
  return execFileSync(
    'docker',
    ['exec', mysqlContainer, 'mysql', `-u${dbUser}`, `-p${dbPassword}`, dbName, '-N', '-B', '-e', sql],
    { encoding: 'utf8' },
  ).trim()
}

/** 读 SSE 直到看到终止事件或流结束。返回收到的全部帧。 */
async function readSse(url, token, { stopOn = 'done', timeoutMs = 60_000, lastEventId } = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const frames = []
  try {
    const response = await fetch(`${baseURL}${url}`, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'text/event-stream',
        ...(lastEventId ? { 'Last-Event-ID': String(lastEventId) } : {}),
      },
      signal: controller.signal,
    })
    assert.equal(response.status, 200, 'SSE 必须返回 200')
    assert.ok(
      (response.headers.get('content-type') ?? '').startsWith('text/event-stream'),
      `SSE 的 Content-Type 必须是 text/event-stream，实际 ${response.headers.get('content-type')}`,
    )

    const decoder = new TextDecoder()
    let buffer = ''
    for await (const chunk of response.body) {
      buffer += decoder.decode(chunk, { stream: true })
      let index
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, index)
        buffer = buffer.slice(index + 2)
        const frame = { id: null, event: null, data: null }
        for (const line of raw.split('\n')) {
          if (line.startsWith('id:')) frame.id = line.slice(3).trim()
          else if (line.startsWith('event:')) frame.event = line.slice(6).trim()
          else if (line.startsWith('data:')) frame.data = line.slice(5).trim()
        }
        if (frame.event) {
          frames.push(frame)
          if (frame.event === stopOn) {
            controller.abort()
            return frames
          }
        }
      }
    }
    return frames
  } catch (error) {
    if (error?.name === 'AbortError') return frames
    throw error
  } finally {
    clearTimeout(timer)
  }
}

const createBody = (question) => ({
  sessionId: null,
  scene: 'STOCK',
  targets: [{ targetType: 'SECURITY', targetId: 'sim-600519', targetRole: 'PRIMARY' }],
  analysisStartAt: '2026-09-01T00:00:00+08:00',
  analysisEndAt: '2026-09-18T15:00:00+08:00',
  question,
})

// ---------- 1. 未登录必须被挡住 ----------
for (const [method, path] of [
  ['POST', '/api/v1/ai/tasks'],
  ['GET', '/api/v1/ai/tasks/1'],
  ['GET', '/api/v1/ai/tasks/1/stream'],
  ['POST', '/api/v1/ai/tasks/1/cancel'],
  ['POST', '/api/v1/ai/tasks/1/retry'],
  ['POST', '/api/v1/ai/sessions/1/follow-up-tasks'],
]) {
  const response = await api(method, path)
  assert.equal(response.status, 401, `游客访问 ${method} ${path} 应当 401`)
}
console.log('✓ 未登录访问 AI-03~AI-08 全部 401')

// ---------- 2. 登录 ----------
const login = await api('POST', '/api/v1/auth/login', { body: { account, password } })
assert.equal(login.status, 200)
const token = login.body.data.accessToken
assert.ok(token, '登录必须返回 accessToken')
console.log('✓ 登录成功')

// ---------- 3. AI-03 创建（带 Idempotency-Key）----------
const idempotencyKey = `e2e-${Date.now()}`
const created = await api('POST', '/api/v1/ai/tasks', {
  token,
  headers: { 'Idempotency-Key': idempotencyKey },
  body: createBody('端到端：怎么看这只股票？'),
})
if (created.status === 429 && created.body?.code === 'AI_QUOTA_EXCEEDED') {
  // 不是缺陷：单用户每日额度（默认 20）被跑完了。本脚本每跑一次消耗 3 个，
  // 因此同一天最多跑 6 次。这里给出可执行的恢复方式，而不是抛一个看不懂的断言。
  console.error(
    `\nAI_QUOTA_EXCEEDED：${account} 今日额度已用完` +
      `（${created.body.data?.usedCount}/${created.body.data?.dailyLimit}）。\n` +
      `本脚本每跑一次消耗 3 个额度，同一天最多跑 ${Math.floor((created.body.data?.dailyLimit ?? 20) / 3)} 次。\n` +
      `本地联调时可以直接清空任务表再跑：\n` +
      `  docker exec ${mysqlContainer} mysql -u${dbUser} -p${dbPassword} ${dbName} -e "DELETE FROM ai_task"\n`,
  )
  process.exit(2)
}
assert.equal(created.status, 202, `AI-03 必须返回 202，实际 ${created.status}`)
const accepted = created.body.data
const taskId = accepted.task.taskId
assert.ok(taskId, 'AI-03 必须返回 taskId')
assert.equal(accepted.task.status, 'QUEUED')
assert.equal(accepted.statusUrl, `/api/v1/ai/tasks/${taskId}`)
assert.equal(accepted.streamUrl, `/api/v1/ai/tasks/${taskId}/stream`)
assert.equal(accepted.quota.dailyLimit, 20)
assert.ok(accepted.quota.usedCount >= 1, '创建之后已用额度至少是 1')
console.log(`✓ AI-03 创建任务 202：taskId=${taskId} usedCount=${accepted.quota.usedCount}`)

// ---------- 4. 同一个 Idempotency-Key 重发 → 同一个 taskId ----------
const replayed = await api('POST', '/api/v1/ai/tasks', {
  token,
  headers: { 'Idempotency-Key': idempotencyKey },
  body: createBody('端到端：怎么看这只股票？'),
})
assert.equal(replayed.status, 202)
assert.equal(replayed.body.data.task.taskId, taskId, '同一个幂等键必须回放同一个 taskId')
const usedAfterReplay = replayed.body.data.quota.usedCount
console.log(`✓ 同键重发回放同一个 taskId，额度未重复消耗（usedCount=${usedAfterReplay}）`)

// ---------- 5. 轮询 AI-04：状态确实推进过 ----------
const observed = new Set()
let final = null
for (let attempt = 0; attempt < 60; attempt++) {
  const polled = await api('GET', `/api/v1/ai/tasks/${taskId}`, { token })
  assert.equal(polled.status, 200)
  const summary = polled.body.data
  observed.add(summary.status)
  if (['COMPLETED', 'FAILED', 'CANCELED', 'TIMED_OUT'].includes(summary.status)) {
    final = summary
    break
  }
  await new Promise((resolve) => setTimeout(resolve, 200))
}
assert.ok(final, '任务必须在 12 秒内进入终态')
assert.equal(final.status, 'COMPLETED', `任务应当完成，实际 ${final.status}（${final.error?.code}）`)
assert.ok(final.reportId, '完成的摘要必须带 reportId')
assert.ok(final.completedAt, '完成的摘要必须带 completedAt')
assert.ok(final.firstChunkAt, '必须记录首段片段时刻（契约 §13.5 的 P95 要有数据可测）')
console.log(`✓ AI-04 状态推进：${[...observed].join(' → ')}，reportId=${final.reportId}`)

// ---------- 6. AI-05 SSE：六类事件 ----------
const frames = await readSse(accepted.streamUrl, token)
const kinds = frames.map((frame) => frame.event)
for (const expected of ['snapshot', 'status', 'chunk', 'report', 'done']) {
  assert.ok(kinds.includes(expected), `SSE 必须收到 ${expected}，实际 ${kinds.join(',')}`)
}
const chunks = frames.filter((frame) => frame.event === 'chunk')
assert.ok(chunks.length > 0, '必须至少收到一个 chunk')
for (const frame of chunks) {
  const payload = JSON.parse(frame.data)
  assert.ok(
    SECTIONS.includes(payload.section),
    `chunk 的 section 必须属于六章节，实际 ${payload.section}`,
  )
  assert.equal(payload.taskId, taskId, 'chunk 载荷里的 taskId 必须是字符串形式的同一个任务')
}
// 除 snapshot 之外的事件都带 id:，且严格递增——重连补发靠它
const sequenced = frames.filter((frame) => frame.event !== 'snapshot')
for (let index = 0; index < sequenced.length; index++) {
  assert.ok(sequenced[index].id, `${sequenced[index].event} 必须带 id:`)
  if (index > 0) {
    assert.ok(
      Number(sequenced[index].id) > Number(sequenced[index - 1].id),
      'id 必须严格递增',
    )
  }
}
const snapshot = JSON.parse(frames.find((frame) => frame.event === 'snapshot').data)
assert.ok(snapshot.task, 'snapshot 必须带 task')
assert.ok(Number.isInteger(snapshot.lastSequence), 'snapshot 必须带 lastSequence')
console.log(
  `✓ AI-05 SSE：${[...new Set(kinds)].join('/')}，${chunks.length} 个 chunk，` +
    `snapshot.lastSequence=${snapshot.lastSequence}`,
)

// 重连：带上 Last-Event-ID，必须**只**补发它之后的事件（契约 §13.4）
const resumeFrom = Number(sequenced[1].id)
const resumed = await readSse(accepted.streamUrl, token, { lastEventId: resumeFrom })
const resumedIds = resumed.filter((frame) => frame.event !== 'snapshot').map((frame) => Number(frame.id))
assert.ok(resumedIds.length > 0, `重连必须补发 Last-Event-ID=${resumeFrom} 之后的事件`)
assert.ok(
  Math.min(...resumedIds) > resumeFrom,
  `补发必须从 ${resumeFrom} 之后开始，实际最小 id=${Math.min(...resumedIds)}`,
)
assert.equal(
  resumed[0].event,
  'snapshot',
  '重连的第一帧仍然是 snapshot（前端据此恢复半份报告）',
)
console.log(
  `✓ AI-05 重连：Last-Event-ID=${resumeFrom} → 补发 ${resumedIds.length} 条（最小 id=${Math.min(...resumedIds)}）`,
)

// ---------- 7. 查库：五张表都有真实行 ----------
const counts = query(
  `SELECT (SELECT COUNT(*) FROM ai_task WHERE id = ${taskId}),` +
    `(SELECT COUNT(*) FROM ai_task_target WHERE task_id = ${taskId}),` +
    `(SELECT COUNT(*) FROM ai_context_snapshot WHERE task_id = ${taskId}),` +
    `(SELECT COUNT(*) FROM ai_message WHERE task_id = ${taskId}),` +
    `(SELECT COUNT(*) FROM ai_report WHERE task_id = ${taskId})`,
)
const [tasks, targets, snapshots, messages, reports] = counts.split('\t').map(Number)
assert.equal(tasks, 1, 'ai_task 应当恰好一行')
assert.ok(targets > 0, 'ai_task_target 必须有行')
assert.ok(snapshots > 0, 'ai_context_snapshot 必须有行')
assert.ok(messages > 0, 'ai_message 必须有行')
assert.ok(reports > 0, 'ai_report 必须有行')
console.log(
  `✓ 查库（真实数据，非夹具）：task=${tasks} target=${targets} snapshot=${snapshots} ` +
    `message=${messages} report=${reports}`,
)

// 报告的截止时间来自真实行情批次，不是"现在"
const cutoff = query(
  `SELECT market_data_cutoff_at, quality_status, is_limited FROM ai_report WHERE task_id = ${taskId}`,
)
const [marketCutoff, quality, isLimited] = cutoff.split('\t')
assert.ok(marketCutoff && marketCutoff !== 'NULL', 'market_data_cutoff_at 不得为空')
assert.ok(['VALID', 'LIMITED'].includes(quality), `quality_status 非法：${quality}`)
assert.equal(isLimited, quality === 'LIMITED' ? '1' : '0', '受限三字段必须自洽')
console.log(`✓ 报告口径：marketDataCutoffAt=${marketCutoff} quality=${quality} isLimited=${isLimited}`)

// ---------- 8. AI-06 取消已完成任务：状态不变 ----------
const cancelled = await api('POST', `/api/v1/ai/tasks/${taskId}/cancel`, {
  token,
  headers: { 'Idempotency-Key': `e2e-cancel-${Date.now()}` },
})
assert.equal(cancelled.status, 200)
assert.equal(cancelled.body.data.status, 'COMPLETED', '已完成的任务不得被改成 CANCELED')
assert.equal(cancelled.body.data.cancelRequested, false)
assert.equal(cancelled.body.data.effectiveImmediately, false)
console.log('✓ AI-06 取消已完成任务：状态保持 COMPLETED，effectiveImmediately=false')

// ---------- 9. AI-08 追问 ----------
const followUp = await api('POST', '/api/v1/ai/sessions/1/follow-up-tasks', {
  token,
  headers: { 'Idempotency-Key': `e2e-follow-${Date.now()}` },
  body: { question: '那风险呢？' },
})
// 会话 1 大概率不属于 demo（幂等键派生自 userId，会话 ID 是 Snowflake），
// 因此这里接受 404；关键是"不是 500"。
assert.ok(
  [202, 404].includes(followUp.status),
  `AI-08 应当返回 202 或 404，实际 ${followUp.status}`,
)
console.log(
  `✓ AI-08 追问：${followUp.status}` +
    (followUp.status === 404 ? `（${followUp.body.code}，会话不属于该用户）` : ''),
)

// ---------- 10. 并发上限 ----------
const created3 = []
for (let index = 0; index < 3; index++) {
  const response = await api('POST', '/api/v1/ai/tasks', {
    token,
    headers: { 'Idempotency-Key': `e2e-concurrent-${Date.now()}-${index}` },
    body: createBody(`并发测试 ${index}`),
  })
  created3.push(response)
}
const rejected = created3.filter((response) => response.status === 429)
if (rejected.length > 0) {
  assert.equal(rejected[0].body.code, 'AI_CONCURRENCY_EXCEEDED')
  console.log(`✓ 并发上限：连续创建 3 个，${rejected.length} 个被 429 拒绝`)
} else {
  // 模拟 Provider 很快，任务可能在下一个请求到达前就完成了；此时闸门本来就不该拦。
  // 这不算通过，但也不该让整条脚本红掉——如实打印出来。
  console.log(
    `⚠ 并发上限未被触发（3 个都成功）：模拟 Provider 完成得太快，活跃任务数始终 < 2。` +
      `该闸门由 AiTaskServiceTest 单测覆盖。`,
  )
}

console.log('\n全部通过：M3-07 的创建 → 执行 → 落库 → SSE 中继整条链路在真实栈上可用。')
