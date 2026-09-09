<template><nav v-if="items.length" class="cc-section-nav" aria-label="功能导航"><router-link v-for="item in items" :key="item.path" :to="item.path" :class="{active:activeSubnav(item.path,route.path,route.query)}">{{ item.label }}</router-link></nav></template>
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { subnav, sectionFor, activeSubnav } from './navigation.mjs'
const route = useRoute()
const items = computed(() => subnav[sectionFor(route.path, route.query)] || [])
</script>
