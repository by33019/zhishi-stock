<script setup lang="ts">
import { ArrowRight, Check, Eye, EyeOff, LockKeyhole, ShieldCheck, UserRound } from '@lucide/vue'
import { ref } from 'vue'

import BrandMark from '@/components/BrandMark.vue'

const showPassword = ref(false)
</script>

<template>
  <main class="login-page login-canvas">
    <div class="login-atmosphere" aria-hidden="true">
      <svg class="login-market-line" viewBox="0 0 1440 620" preserveAspectRatio="none">
        <path d="M-60 462 C 160 430, 238 502, 420 414 S 720 272, 900 326 S 1160 452, 1500 236" />
        <circle cx="420" cy="414" r="5" />
        <circle cx="900" cy="326" r="5" />
      </svg>
      <span class="login-orbit login-orbit--large" />
      <span class="login-orbit login-orbit--small" />
    </div>

    <header class="login-topbar">
      <BrandMark />
      <RouterLink class="login-back" to="/market">
        先浏览市场
        <ArrowRight :size="15" />
      </RouterLink>
    </header>

    <section class="login-stage">
      <article class="login-story">
        <div class="login-story__content">
          <span class="eyebrow">AI STOCK RESEARCH</span>
          <h1>让每个判断，<em>都有证据。</em></h1>
          <p>从行情、板块到事件线索，知势帮助你更快理解市场，而不是替你做决定。</p>
        </div>
        <ul class="login-capabilities" aria-label="平台能力">
          <li><Check :size="14" />持续跟踪研究标的</li>
          <li><Check :size="14" />生成带引用的 AI 分析</li>
          <li><Check :size="14" />沉淀个人研究历史</li>
        </ul>
      </article>

      <form class="login-form login-card" @submit.prevent>
        <header class="login-form__header">
          <span class="eyebrow">WELCOME BACK</span>
          <h2>登录研究工作台</h2>
          <p>还没有账号？<a href="#">免费注册</a></p>
        </header>

        <div class="login-field">
          <label for="login-username">手机号或用户名</label>
          <div class="login-input">
            <UserRound :size="17" />
            <input id="login-username" type="text" placeholder="请输入手机号或用户名" autocomplete="username" />
          </div>
        </div>

        <div class="login-field">
          <label for="login-password">登录密码</label>
          <div class="login-input">
            <LockKeyhole :size="17" />
            <input id="login-password" :type="showPassword ? 'text' : 'password'" placeholder="请输入密码" autocomplete="current-password" />
            <button
              data-testid="password-visibility"
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <EyeOff v-if="showPassword" :size="17" />
              <Eye v-else :size="17" />
            </button>
          </div>
        </div>

        <div class="form-meta">
          <label><input type="checkbox" /> 保持登录</label>
          <a href="#">忘记密码？</a>
        </div>

        <button class="login-submit" type="submit">
          登录
          <ArrowRight :size="17" />
        </button>
        <p class="login-agreement">登录即表示你同意《用户协议》和《隐私政策》</p>
      </form>
    </section>

    <footer class="login-trust">
      <span><ShieldCheck :size="16" /> 数据传输加密</span>
      <span>AI 仅提供研究辅助，不构成投资建议</span>
    </footer>
  </main>
</template>
