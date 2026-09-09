<script setup lang="ts">
import { Bot, Send, X } from '@lucide/vue'
import { ref } from 'vue'

defineEmits<{ close: [] }>()

const question = ref('')
const prompts = ['解读今日市场强弱', '银行板块异动原因', '对比浦发银行与招商银行']
</script>

<template>
  <aside class="ai-panel" data-testid="ai-side-panel">
    <header class="ai-panel__head">
      <div class="ai-panel__identity">
        <span class="ai-panel__icon"><Bot :size="18" /></span>
        <span><strong>研究助手</strong><small>证据驱动 · 数据截止 14:32</small></span>
      </div>
      <button class="icon-button" type="button" aria-label="关闭 AI 研究侧栏" @click="$emit('close')">
        <X :size="18" />
      </button>
    </header>

    <div class="ai-panel__body">
      <section class="ai-welcome">
        <span class="eyebrow">CONTEXTUAL RESEARCH</span>
        <h2>从当前盘面开始研究</h2>
        <p>我会结合行情、板块与授权资讯形成可复核结论，并明确标注风险与不确定性。</p>
      </section>
      <div class="ai-context">
        <span>当前上下文</span>
        <strong>中国 A 股 · 市场总览</strong>
        <small>指数 4 · 板块 4 · 事件 3</small>
      </div>
      <div class="ai-prompts">
        <button v-for="prompt in prompts" :key="prompt" type="button" @click="question = prompt">
          {{ prompt }}
        </button>
      </div>
    </div>

    <footer class="ai-composer">
      <textarea v-model="question" aria-label="向 AI 提问" rows="3" placeholder="输入你想验证的市场判断……" />
      <div>
        <small>AI 内容仅供研究参考</small>
        <button type="button" aria-label="发送问题"><Send :size="16" /></button>
      </div>
    </footer>
  </aside>
</template>
