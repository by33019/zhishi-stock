<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { useRemoteData } from '@/composables/useRemoteData'
import {
  changeAdminUserStatus,
  deleteAdminUser,
  getAdminUser,
  getAdminUsers,
  revokeAdminUserSessions,
} from '@/services/adminApi'
import type { AdminUserDetail, AdminUserSummary } from '@/types/admin'

/**
 * 用户管理分区（ADM-USR-01/02/05/08/09 的页面覆盖）。
 *
 * 联系方式在详情里就是脱敏值（`138****1234`），后端不给明文，页面也不该要。
 * 每个写操作一个独立的幂等键（`randomUUID()`）："这次点下去的强制下线"与
 * "上一次"是两个意图，重试由操作者自己决定。
 */
const keyword = ref('')
const submittedKeyword = ref('')
const page = ref(1)
const notice = ref('')
const actionError = ref('')

const users = useRemoteData(() =>
  getAdminUsers({ keyword: submittedKeyword.value || undefined, page: page.value, size: 20 }),
)

onMounted(() => users.reload())

const detail = ref<AdminUserDetail>()
const detailLoading = ref(false)

async function openDetail(user: AdminUserSummary) {
  detailLoading.value = true
  actionError.value = ''
  try {
    detail.value = await getAdminUser(user.userId)
  } catch (cause) {
    actionError.value = (cause as { message?: string }).message ?? '加载详情失败'
  } finally {
    detailLoading.value = false
  }
}

async function runAction(action: () => Promise<unknown>, successMessage: string) {
  notice.value = ''
  actionError.value = ''
  try {
    await action()
    notice.value = successMessage
    await users.reload()
    if (detail.value) await openDetail({ userId: detail.value.userId } as AdminUserSummary)
  } catch (cause) {
    actionError.value = (cause as { message?: string }).message ?? '操作失败'
  }
}

function lock(user: AdminUserSummary | AdminUserDetail) {
  void runAction(
    () =>
      changeAdminUserStatus(
        user.userId,
        (user as AdminUserDetail).version ?? 0,
        'LOCKED',
        '后台锁定',
      ),
    `已锁定 ${user.username}`,
  )
}

function activate(user: AdminUserSummary | AdminUserDetail) {
  void runAction(
    () => changeAdminUserStatus(user.userId, (user as AdminUserDetail).version ?? 0, 'ACTIVE'),
    `已启用 ${user.username}`,
  )
}

function revoke(user: AdminUserSummary) {
  void runAction(
    () => revokeAdminUserSessions(user.userId, crypto.randomUUID(), '管理员强制下线'),
    `已强制下线 ${user.username}`,
  )
}

function remove(user: AdminUserDetail) {
  void runAction(
    () => deleteAdminUser(user.userId, user.version, '后台删除'),
    `已删除 ${user.username}`,
  )
}

function search() {
  submittedKeyword.value = keyword.value.trim()
  page.value = 1
  void users.reload()
}

function goToPage(next: number) {
  page.value = next
  void users.reload()
}

const totalPages = computed(() => users.data.value?.totalPages ?? 0)
</script>

<template>
  <div>
    <section class="research-panel">
      <div class="section-heading">
        <div><span class="eyebrow">USERS</span><h2>用户列表</h2></div>
        <form class="admin-search" @submit.prevent="search">
          <input v-model="keyword" type="search" placeholder="账号 / 昵称" aria-label="搜索用户" />
          <button class="secondary-button" type="submit">搜索</button>
        </form>
      </div>

      <p v-if="notice" class="admin-notice">{{ notice }}</p>
      <p v-if="actionError" class="admin-error">{{ actionError }}</p>
      <p v-if="users.error.value" class="admin-error">{{ users.error.value.message }}</p>

      <table v-else-if="users.data.value && users.data.value.items.length" class="quote-table">
        <thead><tr><th>账号</th><th>昵称</th><th>状态</th><th>角色数</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="user in users.data.value.items" :key="user.userId">
            <td><strong>{{ user.username }}</strong><small>{{ user.isSuperAdmin ? '超级管理员' : '' }}</small></td>
            <td>{{ user.nickName || '—' }}</td>
            <td><span class="status-label" :class="user.status === 'ACTIVE' ? 'up' : 'flat'">{{ user.status }}</span></td>
            <td>{{ user.roleCount }}</td>
            <td class="admin-actions">
              <button type="button" @click="openDetail(user)">详情</button>
              <button v-if="user.status === 'ACTIVE'" type="button" @click="lock(user)">锁定</button>
              <button v-else type="button" @click="activate(user)">启用</button>
              <button type="button" @click="revoke(user)">强制下线</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="users.data.value" class="admin-empty">没有符合条件的用户。</p>
      <div v-else class="page-loading"><span /><span /><span /></div>

      <nav v-if="totalPages > 1" class="admin-pager">
        <button type="button" :disabled="page <= 1" @click="goToPage(page - 1)">上一页</button>
        <span>{{ page }} / {{ totalPages }}</span>
        <button type="button" :disabled="page >= totalPages" @click="goToPage(page + 1)">下一页</button>
      </nav>
    </section>

    <section v-if="detailLoading" class="research-panel"><div class="page-loading"><span /><span /><span /></div></section>
    <section v-else-if="detail" class="research-panel">
      <div class="section-heading">
        <div><span class="eyebrow">USER DETAIL</span><h2>{{ detail.username }}</h2></div>
        <button class="secondary-button" type="button" @click="remove(detail)">删除用户</button>
      </div>
      <dl class="admin-facts">
        <div><dt>用户 ID</dt><dd>{{ detail.userId }}</dd></div>
        <div><dt>邮箱（脱敏）</dt><dd>{{ detail.maskedEmail || '—' }}</dd></div>
        <div><dt>手机（脱敏）</dt><dd>{{ detail.maskedPhone || '—' }}</dd></div>
        <div><dt>令牌版本</dt><dd>{{ detail.tokenVersion }}</dd></div>
        <div><dt>版本（If-Match）</dt><dd>{{ detail.version }}</dd></div>
        <div>
          <dt>角色</dt>
          <dd>{{ detail.roles.map((role) => role.roleName).join('、') || '—' }}</dd>
        </div>
      </dl>
    </section>
  </div>
</template>
