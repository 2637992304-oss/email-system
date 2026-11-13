<script setup>
defineProps({
  error: {
    type: String,
    default: ''
  },
  message: {
    type: String,
    default: ''
  },
  info: {
    type: String,
    default: ''
  }
})
</script>

<template>
  <div v-if="error || message || info" class="toast-stack" aria-live="polite">
    <p v-if="error" class="page-toast page-toast--error">{{ error }}</p>
    <p v-if="message" class="page-toast page-toast--success">{{ message }}</p>
    <p v-if="info" class="page-toast page-toast--info">{{ info }}</p>
  </div>
</template>
