import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import {
  apiRequest,
  clearAccessToken,
  onAuthenticationFailure,
  refreshAccessToken,
  setAccessToken,
  type TokenResponse,
  type UserSummary,
} from '@/services/apiClient'

interface UserProfile extends UserSummary {
  status: string
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserSummary>()
  const permissions = ref<string[]>([])
  const ready = ref(false)
  let restoring: Promise<void> | undefined

  const authenticated = computed(() => Boolean(user.value))
  const isAdmin = computed(() =>
    permissions.value.includes('admin:access') || permissions.value.includes('ROLE_ADMIN'),
  )

  function applyTokens(tokens: TokenResponse) {
    setAccessToken(tokens.accessToken)
    if (tokens.user) user.value = tokens.user
    permissions.value = [...tokens.permissions]
  }

  function clearSession() {
    clearAccessToken()
    user.value = undefined
    permissions.value = []
  }

  async function login(account: string, password: string) {
    const tokens = await apiRequest<TokenResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ account, password, deviceName: 'Web' }),
    })
    applyTokens(tokens)
    ready.value = true
  }

  async function restore() {
    if (ready.value) return
    if (!restoring) {
      restoring = (async () => {
        try {
          const tokens = await refreshAccessToken()
          applyTokens(tokens)
          if (!tokens.user) {
            user.value = await apiRequest<UserProfile>('/users/me')
          }
        } catch {
          clearSession()
        } finally {
          ready.value = true
          restoring = undefined
        }
      })()
    }
    return restoring
  }

  async function logout() {
    try {
      await apiRequest('/auth/logout', { method: 'POST' })
    } finally {
      clearSession()
      ready.value = true
    }
  }

  onAuthenticationFailure(clearSession)

  return {
    user,
    permissions,
    ready,
    authenticated,
    isAdmin,
    login,
    restore,
    logout,
    clearSession,
  }
})
