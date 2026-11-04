<template>
  <div class="shell">
    <header class="top">
      <div class="brand">
        <span class="logo">AI</span>
        <span class="name">在线客服</span>
        <span class="tag">智能受理 · 复杂问题转人工</span>
      </div>
      <!-- 演示用：真实产品里不会把后台入口放在顾客这边 -->
      <a class="agent-link" href="/agent/" target="_blank" rel="noopener">
        人工客服入口 →
      </a>
    </header>

    <main class="body">
      <ChatView />
    </main>

    <footer class="foot">
      对话内容会用于整理工单，请不要发送与问题无关的敏感信息。
    </footer>
  </div>
</template>

<script setup>
import ChatView from './views/ChatView.vue'
</script>

<style scoped>
.shell {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-page);
}

.top {
  height: 60px;
  flex: 0 0 60px;
  background: #fff;
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  padding: 0 24px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo {
  width: 28px;
  height: 28px;
  border-radius: 7px;
  background: var(--brand);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.name {
  font-size: 17px;
  font-weight: 700;
  color: var(--text-main);
}

.tag {
  font-size: 12px;
  color: var(--text-muted);
  border-left: 1px solid var(--line);
  padding-left: 10px;
  margin-left: 2px;
}

.agent-link {
  margin-left: auto;
  font-size: 13px;
  color: var(--text-muted);
  text-decoration: none;
}
.agent-link:hover {
  color: var(--brand);
}

.body {
  flex: 1;
  min-height: 0;
  display: flex;
  justify-content: center;
  padding: 20px 16px;
}

.foot {
  flex: 0 0 auto;
  text-align: center;
  font-size: 12px;
  color: #a8b3bf;
  padding: 0 16px 14px;
}

@media (max-width: 720px) {
  .tag { display: none; }
}
</style>
