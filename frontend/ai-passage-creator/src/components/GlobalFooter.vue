<template>
  <a-layout-footer class="footer">
    <span class="footer-brand">智能图文编写</span>
    <span class="footer-sep">·</span>
    <span>多智能体协作的 AI 图文创作工具</span>
    <span class="footer-sep">·</span>
    <span>© {{ year }}</span>
  </a-layout-footer>
</template>

<script setup lang="ts">
const year = new Date().getFullYear()
</script>

<style scoped>
.footer {
  padding: 24px 16px;
  margin-top: 48px;
  font-size: 13px;
  color: var(--c-text-3);
  text-align: center;
  background: transparent;
  border-top: 1px solid var(--c-border);
}

.footer-brand {
  font-weight: 600;
  color: var(--c-text-2);
}

.footer-sep {
  margin: 0 8px;
  color: var(--c-border-strong);
}
</style>
