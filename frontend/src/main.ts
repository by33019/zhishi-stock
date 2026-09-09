import '@fontsource-variable/manrope'
import './styles/tokens.css'
import './styles/base.css'
import './styles/shell.css'
import './styles/pages.css'
import './styles/business.css'

import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import { router } from './router'

createApp(App).use(createPinia()).use(router).mount('#app')
