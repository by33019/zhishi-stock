import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import WatchlistPage from './WatchlistPage.vue'

describe('我的自选页', () => {
  it('将卡片次要操作收拢为语义清晰的统一操作组', () => {
    const wrapper = mount(WatchlistPage, {
      global: {
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })

    const cards = wrapper.findAll('.watch-cards article')

    expect(cards.length).toBeGreaterThan(0)
    cards.forEach((card) => {
      const actionGroup = card.get('[data-testid="watch-card-actions"]')
      const actions = actionGroup.findAll('button')

      expect(card.get('.watch-sparkline').element.tagName).toBe('svg')
      expect(actionGroup.attributes('role')).toBe('group')
      expect(actionGroup.attributes('aria-label')).toBe('股票快捷操作')
      expect(actions).toHaveLength(2)
      expect(actionGroup.get('[data-action="ai"] span').text()).toBe('AI 解读')
      expect(actionGroup.get('[data-action="ai"] svg').attributes('width')).toBe('15')
      expect(actionGroup.get('[data-action="remove"]').attributes('aria-label')).toBe('移出自选')
      expect(actionGroup.get('[data-action="remove"] svg').attributes('width')).toBe('15')
    })
  })
})
