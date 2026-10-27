<script setup lang="ts">
import BasicLayout from '@/layouts/BasicLayout.vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'

/**
 * Ant Design 主题映射
 *
 * 与 src/assets/design-tokens.css 的 --c-* 同源，只在这里定义一次。
 * colorPrimary 故意用墨黑：主按钮不要彩色，这是「去 AI 味」的核心约定。
 */
const themeConfig = {
  token: {
    colorPrimary: '#1f1f1d',
    colorInfo: '#635c4e',
    colorSuccess: '#40714f',
    colorWarning: '#a6763a',
    colorError: '#a33f38',
    colorTextBase: '#1a1a19',
    colorLink: '#8a6a4b',
    colorLinkHover: '#6f5339',
    colorBorder: '#dededa',
    colorBorderSecondary: '#e9e9e4',
    borderRadius: 10,
    borderRadiusLG: 14,
    borderRadiusSM: 6,
    fontSize: 15,
    fontFamily:
      "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
    boxShadow: '0 1px 2px rgba(26, 26, 25, 0.06), 0 1px 3px rgba(26, 26, 25, 0.04)',
    boxShadowSecondary: '0 8px 24px rgba(26, 26, 25, 0.08), 0 2px 6px rgba(26, 26, 25, 0.04)',
  },
  components: {
    Button: { primaryShadow: 'none', defaultShadow: 'none' },
    Card: { borderRadiusLG: 14 },
    Layout: { headerBg: '#ffffff', bodyBg: 'transparent', footerBg: 'transparent' },
  },
}
</script>

<template>
  <a-config-provider :theme="themeConfig" :locale="zhCN">
    <BasicLayout />
  </a-config-provider>
</template>

<style>
* {
  box-sizing: border-box;
}

body {
  margin: 0;
  padding: 0;
  background: var(--c-bg);
  color: var(--c-text-1);
  font-family: var(--font-sans);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  overflow-x: hidden;
}

html {
  overflow-x: hidden;
}

a {
  color: var(--c-primary);
}
</style>
