<script setup lang="ts">
import { Search } from '@lucide/vue'
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const keyword = ref('')
const focused = ref(false)
const router = useRouter()

const suggestions = [
  { code: '600000', name: '浦发银行', exchange: 'SH' },
  { code: '300750', name: '宁德时代', exchange: 'SZ' },
  { code: '688981', name: '中芯国际', exchange: 'SH' },
]

function selectStock() {
  focused.value = false
  keyword.value = ''
  void router.push('/stocks/19876543210001')
}

function scheduleBlur() {
  window.setTimeout(() => {
    focused.value = false
  }, 120)
}
</script>

<template>
  <div class="global-search" :class="{ 'is-focused': focused }">
    <Search :size="17" />
    <input
      v-model="keyword"
      aria-label="全局证券搜索"
      placeholder="搜索股票、代码或板块"
      @focus="focused = true"
      @blur="scheduleBlur"
      @keyup.enter="selectStock"
    />
    <kbd>⌘ K</kbd>
    <div v-if="focused" class="global-search__results">
      <p>快速检索</p>
      <button v-for="item in suggestions" :key="item.code" type="button" @mousedown.prevent="selectStock">
        <span><strong>{{ item.name }}</strong><small>{{ item.exchange }} · {{ item.code }}</small></span>
        <span class="trend-up mono">+2.74%</span>
      </button>
    </div>
  </div>
</template>
