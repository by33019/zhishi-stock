import { describe, expect, it } from 'vitest'

import baseCss from './base.css?raw'
import businessCss from './business.css?raw'
import pagesCss from './pages.css?raw'
import shellCss from './shell.css?raw'
import tokensCss from './tokens.css?raw'

describe('全局字体层级', () => {
  it('提供完整的语义化字体 Token', () => {
    expect(tokensCss).toContain('--text-caption: 12px')
    expect(tokensCss).toContain('--text-label: 13px')
    expect(tokensCss).toContain('--text-body: 14px')
    expect(tokensCss).toContain('--text-body-lg: 15px')
    expect(tokensCss).toContain('--text-card-title: 18px')
    expect(tokensCss).toContain('--text-section-title: 20px')
    expect(tokensCss).toContain('--text-page-title: clamp(36px, 2.6vw, 42px)')
  })

  it('业务样式不再使用低于 12px 的硬编码字号', () => {
    for (const css of [baseCss, shellCss, pagesCss, businessCss]) {
      expect(css).not.toMatch(/font-size:\s*(?:[6-9]|1[01])px/)
    }
  })
})
