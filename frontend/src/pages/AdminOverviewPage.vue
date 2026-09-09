<script setup lang="ts">
import { Activity, Bot, CheckCircle2, Clock3, Database, Server, ShieldCheck, Users } from '@lucide/vue'

import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div class="business-page page-enter">
    <PageHeader eyebrow="SYSTEM OPERATIONS" title="系统运营" description="统一查看用户、数据采集、缓存、AI 服务和安全审计状态。" data-time="系统时间 14:34:06">
      <button class="secondary-button" type="button"><ShieldCheck :size="15" /> 审计日志</button>
    </PageHeader>
    <section class="ops-metrics"><article><span><Users :size="18" /></span><div><small>注册用户</small><strong>12,680</strong><b class="trend-up">本周 +4.8%</b></div></article><article><span><Activity :size="18" /></span><div><small>在线会话</small><strong>486</strong><b>峰值 612</b></div></article><article><span><Bot :size="18" /></span><div><small>今日 AI 分析</small><strong>3,842</strong><b>成功率 98.7%</b></div></article><article><span><Database :size="18" /></span><div><small>行情延迟</small><strong>26s</strong><b class="trend-up">目标内</b></div></article></section>
    <section class="ops-grid"><article class="service-status"><header><div><span class="eyebrow">SERVICE HEALTH</span><h2>核心服务状态</h2></div><span class="healthy"><CheckCircle2 :size="14" />全部正常</span></header><div><p><span><Server :size="15" />Spring Boot API</span><b>正常</b><small>32 ms</small></p><p><span><Database :size="15" />MySQL 主库</span><b>正常</b><small>18 ms</small></p><p><span><Activity :size="15" />Redis 缓存</span><b>正常</b><small>4 ms</small></p><p><span><Bot :size="15" />LLM Provider</span><b>正常</b><small>2.8 s</small></p></div></article><article class="job-status"><header><span class="eyebrow">SCHEDULED JOBS</span><h2>采集任务</h2></header><div><p><i class="success" /><span><strong>市场行情快照</strong><small>每 30 秒 · 最近 14:33:30</small></span><b>成功</b></p><p><i class="success" /><span><strong>新闻增量采集</strong><small>每 2 分钟 · 最近 14:32:00</small></span><b>成功</b></p><p><i class="running" /><span><strong>日 K 聚合</strong><small>交易日 15:10 · 等待执行</small></span><b>等待</b></p><p><i class="success" /><span><strong>缓存预热</strong><small>每交易日 09:10 · 最近 09:10:03</small></span><b>成功</b></p></div></article></section>
    <section class="ops-bottom"><article><header><div><span class="eyebrow">AI USAGE</span><h2>模型调用概览</h2></div><Clock3 :size="17" /></header><div class="usage-number"><strong>1.82M</strong><span>今日 Tokens</span></div><div class="usage-track"><i style="width: 61%" /></div><p><span>预算用量 61%</span><span>预计剩余 1.18M</span></p></article><article><header><div><span class="eyebrow">RECENT AUDIT</span><h2>近期操作</h2></div></header><ul><li><span>admin</span><p>更新用户角色权限</p><time>14:28</time></li><li><span>system</span><p>行情数据源自动切换</p><time>13:52</time></li><li><span>operator</span><p>重新执行新闻采集任务</p><time>11:36</time></li></ul></article></section>
  </div>
</template>
