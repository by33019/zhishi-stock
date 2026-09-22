<template>
  <RouterLink class="brand" to="/market" aria-label="知势首页">
    <img class="brand__mark" :src="logo" alt="" aria-hidden="true" width="38" height="38" />
    <span>
      <strong>知势</strong>
      <small>STOCK INTELLIGENCE</small>
    </span>
  </RouterLink>
</template>

<script setup lang="ts">
// 走 import 而不是引用 /public 下的绝对路径：@vitejs/plugin-vue 默认会转换
// 模板里 <img src> 的绝对路径（transformAssetUrls），把它当模块导入解析，
// 而 public/ 下的文件在 jsdom 测试环境里解析不到——表现为渲染 AppShell /
// LoginPage 的测试文件直接加载失败。import 让 Vite 在构建期就把 URL 定下来。
import logo from '@/assets/logo.png'
</script>
