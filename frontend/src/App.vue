<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'

import AppShell from '@/layouts/AppShell.vue'

const route = useRoute()
const usesShell = computed(() => route.meta.shell !== false)
</script>

<template>
  <AppShell v-if="usesShell" />
  <RouterView v-else />
</template>
