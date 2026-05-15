<template>
  <div class="captcha-image" @click="refresh" :class="{ loading }">
    <img v-if="src" :src="src" alt="验证码,点击刷新" />
    <span v-else class="placeholder">{{ loading ? '加载中…' : '点击刷新' }}</span>
  </div>
</template>

<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import { getCaptcha } from '/@/api/captcha'

const emit = defineEmits<{ (e: 'update:token', token: string): void }>()

const src = ref<string>('')
const loading = ref(false)

async function refresh () {
  if (loading.value) return
  loading.value = true
  try {
    const res = await getCaptcha()
    if (res.code === 0 && res.data) {
      src.value = `data:image/png;base64,${res.data.image_base64}`
      emit('update:token', res.data.token)
    } else {
      src.value = ''
      emit('update:token', '')
    }
  } catch {
    src.value = ''
    emit('update:token', '')
  } finally {
    loading.value = false
  }
}

defineExpose({ refresh })
onMounted(refresh)
</script>

<style lang="scss" scoped>
.captcha-image {
  width: 120px;
  height: 40px;
  border: 1px solid rgba(64, 158, 255, 0.3);
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.04);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  overflow: hidden;
  user-select: none;
  transition: box-shadow 150ms ease;
  &:hover { box-shadow: 0 0 8px rgba(24, 144, 255, 0.4); }
  img { width: 100%; height: 100%; display: block; }
  .placeholder {
    color: rgba(255, 255, 255, 0.5);
    font-size: 12px;
  }
}
</style>
