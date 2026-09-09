<script setup lang="ts">
withDefaults(defineProps<{ eyebrow: string; title: string; description: string; dataTime?: string }>(), {
  dataTime: '',
})
</script>

<template>
  <header class="page-header">
    <div>
      <span class="eyebrow">{{ eyebrow }}</span>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>
    <div class="page-header__actions">
      <span v-if="dataTime" class="page-header__time">数据截止 {{ dataTime }}</span>
      <slot />
    </div>
  </header>
</template>
