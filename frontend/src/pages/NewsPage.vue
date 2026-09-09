<script setup lang="ts">
import { ExternalLink, Filter, Search } from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import { getNews } from '@/services/mockApi'
import type { NewsItem } from '@/types/domain'
import { formatDateTime } from '@/utils/format'

const news = ref<NewsItem[]>([])
const activeType = ref('全部')
const keyword = ref('')

onMounted(async () => {
  news.value = (await getNews()).data
})

const filteredNews = computed(() => news.value.filter((item) => {
  const typeMatch = activeType.value === '全部' || (activeType.value === '公告' ? item.newsType === 'ANNOUNCEMENT' : activeType.value === '研报' ? item.newsType === 'RESEARCH' : item.newsType === 'NEWS')
  return typeMatch && `${item.title}${item.summary}`.includes(keyword.value)
}))
</script>

<template>
  <div class="business-page page-enter">
    <PageHeader eyebrow="EVENT INTELLIGENCE" title="资讯中心" description="将授权新闻、交易所公告和研究摘要关联到具体标的，降低信息噪声。" data-time="最近更新 14:31" />
    <section class="news-layout">
      <div class="news-main">
        <header class="news-toolbar"><div class="segmented-tabs"><button v-for="type in ['全部', '快讯', '公告', '研报']" :key="type" :class="{ active: activeType === type }" type="button" @click="activeType = type">{{ type }}</button></div><div class="toolbar-actions"><label class="inline-search"><Search :size="14" /><input v-model="keyword" placeholder="搜索事件" /></label><button class="filter-button" type="button"><Filter :size="14" /> 时间筛选</button></div></header>
        <div class="news-feed">
          <article v-for="(item, index) in filteredNews" :key="item.newsId">
            <div class="news-feed__time"><strong>{{ formatDateTime(item.publishedAt).split(' ')[1] }}</strong><span>{{ formatDateTime(item.publishedAt).split(' ')[0] }}</span></div>
            <div class="news-feed__content"><div><span class="content-type">{{ item.newsType === 'ANNOUNCEMENT' ? '公告' : item.newsType === 'RESEARCH' ? '研报摘要' : '市场快讯' }}</span><span v-for="symbol in item.relatedSymbols" :key="symbol" class="symbol-tag">{{ symbol }}</span></div><h2>{{ item.title }}</h2><p>{{ item.summary }}</p><footer><span>{{ item.sourceName }} · 已通过来源校验</span><a href="#">查看原文 <ExternalLink :size="13" /></a></footer></div>
            <span class="news-feed__number">0{{ index + 1 }}</span>
          </article>
        </div>
      </div>
      <aside class="news-sidebar">
        <section><span class="eyebrow">TODAY</span><h2>今日事件密度</h2><div class="event-density"><strong>286</strong><span>条有效事件</span></div><div class="density-bar"><i style="width: 72%" /></div><p>较近 20 个交易日均值高 18%，金融与半导体相关事件最集中。</p></section>
        <section><span class="eyebrow">TOPICS</span><h2>高频主题</h2><ol><li><b>01</b><span>金融政策</span><em>42</em></li><li><b>02</b><span>半导体设备</span><em>36</em></li><li><b>03</b><span>算力基础设施</span><em>29</em></li><li><b>04</b><span>消费复苏</span><em>18</em></li></ol></section>
      </aside>
    </section>
  </div>
</template>
