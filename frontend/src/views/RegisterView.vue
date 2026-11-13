<script setup>
import { reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { submitRegister } from '../stores/auth'

const router = useRouter()

const form = reactive({
  email: '',
  username: '',
  password: ''
})

const error = ref('')
const success = ref('')
const loading = ref(false)
const showPassword = ref(false)

async function handleSubmit() {
  error.value = ''
  success.value = ''

  if (!form.email || !form.username || !form.password) {
    error.value = '请完整填写邮箱、用户名和密码'
    return
  }

  if (form.password.length < 6 || form.password.length > 32) {
    error.value = '密码长度需要在 6 到 32 位之间'
    return
  }

  loading.value = true
  try {
    await submitRegister({
      email: form.email.trim(),
      username: form.username.trim(),
      password: form.password
    })
    success.value = '注册成功，正在前往登录页'
    setTimeout(() => router.replace('/login'), 700)
  } catch (err) {
    error.value = err.message || '注册失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page auth-page--simple">
    <section class="auth-panel">
      <div class="brand-block brand-block--center">
        <h1>注册</h1>
      </div>

      <form class="auth-form" @submit.prevent="handleSubmit">
        <label>
          邮箱
          <input v-model="form.email" type="email" autocomplete="email" placeholder="请输入邮箱" />
        </label>

        <label>
          用户名
          <input v-model="form.username" type="text" autocomplete="username" placeholder="请输入用户名" />
        </label>

        <label>
          密码
          <span class="password-field">
            <input
              v-model="form.password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="new-password"
              placeholder="6 到 32 位"
            />
            <button
              class="password-toggle"
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              :title="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <svg v-if="showPassword" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6Z" />
                <circle cx="12" cy="12" r="3" />
              </svg>
              <svg v-else viewBox="0 0 24 24" aria-hidden="true">
                <path d="M3 3l18 18" />
                <path d="M10.6 10.6A3 3 0 0 0 12 15a3 3 0 0 0 2.2-.9" />
                <path d="M7.5 7.8C4.3 9.5 2.5 12 2.5 12s3.5 6 9.5 6c1.5 0 2.8-.4 4-.9" />
                <path d="M13.8 6.2C18.6 7 21.5 12 21.5 12a17 17 0 0 1-2.1 2.7" />
              </svg>
            </button>
          </span>
        </label>

        <p v-if="error" class="form-error">{{ error }}</p>
        <p v-if="success" class="form-success">{{ success }}</p>

        <button class="primary-button" type="submit" :disabled="loading">
          {{ loading ? '注册中...' : '注册' }}
        </button>
      </form>

      <p class="auth-switch">
        已有账号？
        <RouterLink to="/login">去登录</RouterLink>
      </p>
    </section>
  </main>
</template>
