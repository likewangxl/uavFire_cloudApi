<template>
  <a-modal
    :visible="open"
    title="忘记密码"
    :footer="null"
    :width="360"
    @cancel="onClose"
    class="forgot-password-modal"
  >
    <p>忘记密码请联系系统管理员。</p>
    <div class="actions">
      <a-button type="primary" @click="onClose">我知道了</a-button>
    </div>
  </a-modal>
</template>

<script lang="ts" setup>
import { defineProps, defineEmits } from 'vue'

defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>()

function onClose () {
  emit('update:open', false)
}
</script>

<style lang="scss" scoped>
.forgot-password-modal :deep(.ant-modal-content) {
  background: rgba(11, 28, 58, 0.95);
  color: #fff;
}
.actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
