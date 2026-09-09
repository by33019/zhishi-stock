import { defineStore } from 'pinia'

export const useUiStore = defineStore('ui', {
  state: () => ({
    aiPanelOpen: false,
    mobileNavOpen: false,
  }),
  actions: {
    toggleAiPanel() {
      this.aiPanelOpen = !this.aiPanelOpen
    },
    closeAiPanel() {
      this.aiPanelOpen = false
    },
    toggleMobileNav() {
      this.mobileNavOpen = !this.mobileNavOpen
    },
    closeMobileNav() {
      this.mobileNavOpen = false
    },
  },
})
