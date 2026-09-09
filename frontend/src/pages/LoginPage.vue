<script setup lang="ts">
import { ArrowRight, Check, Eye, LockKeyhole, ShieldCheck } from '@lucide/vue'
import { ref } from 'vue'

import BrandMark from '@/components/BrandMark.vue'

const showPassword = ref(false)
</script>

<template>
  <main class="login-page">
    <section class="login-story">
      <BrandMark />
      <div class="login-story__content"><span class="eyebrow">AI STOCK RESEARCH</span><h1>让每个判断，<br><em>都有证据。</em></h1><p>从行情、板块到事件线索，知势帮助你更快理解市场，而不是替你做决定。</p><ul><li><Check :size="14" />收藏并持续跟踪研究标的</li><li><Check :size="14" />生成带来源引用的 AI 分析</li><li><Check :size="14" />沉淀个人研究历史</li></ul></div>
      <footer><ShieldCheck :size="15" /> 数据传输加密 · AI 仅提供研究辅助，不构成投资建议</footer>
    </section>
    <section class="login-form-wrap">
      <RouterLink class="login-back" to="/market">先浏览市场 <ArrowRight :size="14" /></RouterLink>
      <form class="login-form" @submit.prevent>
        <span class="eyebrow">WELCOME BACK</span><h2>登录研究工作台</h2><p>还没有账号？<a href="#">免费注册</a></p>
        <label>手机号或用户名<div><input type="text" placeholder="请输入手机号或用户名" autocomplete="username" /></div></label>
        <label>登录密码<div><LockKeyhole :size="16" /><input :type="showPassword ? 'text' : 'password'" placeholder="请输入密码" autocomplete="current-password" /><button type="button" aria-label="显示密码" @click="showPassword = !showPassword"><Eye :size="16" /></button></div></label>
        <div class="form-meta"><label><input type="checkbox" /> 保持登录</label><a href="#">忘记密码？</a></div>
        <button class="login-submit" type="submit">登录 <ArrowRight :size="16" /></button>
        <p class="login-agreement">登录即表示你同意《用户协议》和《隐私政策》</p>
      </form>
    </section>
  </main>
</template>
