<template>
  <div class="project-media-wrapper">
    <div class="media-header">
      <a-row>
        <a-col :span="1"></a-col>
        <a-col :span="22">媒体文件</a-col>
        <a-col :span="1"></a-col>
      </a-row>
    </div>
    <div class="media-hint">
      媒体列表和下载内容显示在右侧面板。
    </div>
  </div>
</template>

<script lang="ts" setup>
</script>

<style lang="scss" scoped>
.project-media-wrapper {
  height: 100%;
  color: #ffffff;

  .media-header {
    height: 50px;
    line-height: 50px;
    border-bottom: 1px solid #4f4f4f;
    font-weight: 450;
  }

  .media-hint {
    padding: 16px;
    color: #9aa0a6;
    font-size: 13px;
    line-height: 1.6;
  }
}
</style>
