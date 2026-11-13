import { reactive } from 'vue'
import { getCurrentUser, loginUser, logoutUser, registerUser } from '../api/auth'

const TOKEN_KEY = 'email-system-token'
const ACCOUNTS_KEY = 'email-system-accounts'
const MAX_SAVED_ACCOUNTS = 6

function readSavedAccounts() {
  try {
    const value = JSON.parse(localStorage.getItem(ACCOUNTS_KEY) || '[]')
    if (!Array.isArray(value)) return []
    return value
      .filter((account) => account?.token && account?.email)
      .map((account) => ({
        id: String(account.id ?? account.userId ?? account.email),
        userId: account.userId,
        username: account.username || '',
        email: account.email || '',
        token: account.token,
        lastUsedAt: account.lastUsedAt || 0
      }))
      .sort((a, b) => b.lastUsedAt - a.lastUsedAt)
      .slice(0, MAX_SAVED_ACCOUNTS)
  } catch {
    return []
  }
}

export const authState = reactive({
  token: localStorage.getItem(TOKEN_KEY),
  user: null,
  loadingUser: false,
  accounts: readSavedAccounts()
})

function getAccountId(account) {
  return String(account?.userId ?? account?.email ?? '')
}

function normalizeAccount(data) {
  if (!data?.token || !data?.email) return null
  const id = getAccountId(data)
  if (!id) return null
  return {
    id,
    userId: data.userId,
    username: data.username || data.name || '',
    email: data.email || '',
    token: data.token,
    lastUsedAt: Date.now()
  }
}

function persistSavedAccounts() {
  localStorage.setItem(ACCOUNTS_KEY, JSON.stringify(authState.accounts))
}

function setActiveAccount(account) {
  authState.token = account.token
  authState.user = {
    userId: account.userId,
    username: account.username,
    email: account.email
  }
  localStorage.setItem(TOKEN_KEY, account.token)
}

function upsertSavedAccount(data) {
  const account = normalizeAccount(data)
  if (!account) return null
  const rest = authState.accounts.filter((item) => item.id !== account.id)
  authState.accounts.splice(0, authState.accounts.length, account, ...rest.slice(0, MAX_SAVED_ACCOUNTS - 1))
  persistSavedAccounts()
  return account
}

export function getToken() {
  return authState.token
}

export function setAuth(loginData) {
  const account = upsertSavedAccount(loginData)
  if (!account) return
  setActiveAccount(account)
}

export function clearAuth() {
  authState.token = null
  authState.user = null
  localStorage.removeItem(TOKEN_KEY)
}

export function removeSavedAccount(accountId) {
  const id = String(accountId || '')
  const index = authState.accounts.findIndex((account) => account.id === id)
  if (index >= 0) {
    authState.accounts.splice(index, 1)
    persistSavedAccounts()
  }
}

export function switchSavedAccount(accountId) {
  const id = String(accountId || '')
  const account = authState.accounts.find((item) => item.id === id)
  if (!account) {
    throw new Error('未找到该账号，请重新登录')
  }
  const nextAccount = { ...account, lastUsedAt: Date.now() }
  setActiveAccount(nextAccount)
  upsertSavedAccount(nextAccount)
  return authState.user
}

export async function submitLogin(form) {
  const data = await loginUser(form)
  setAuth(data)
  return data
}

export async function submitRegister(form) {
  return registerUser(form)
}

export async function loadCurrentUser() {
  if (!authState.token) {
    return null
  }

  authState.loadingUser = true
  try {
    const userData = await getCurrentUser()
    authState.user = {
      userId: userData.userId,
      username: userData.username || userData.name || '',
      email: userData.email || ''
    }
    upsertSavedAccount({
      ...authState.user,
      token: authState.token
    })
    return authState.user
  } catch (error) {
    clearAuth()
    throw error
  } finally {
    authState.loadingUser = false
  }
}

export async function submitLogout() {
  const accountId = getAccountId(authState.user)
  try {
    if (authState.token) {
      await logoutUser()
    }
  } finally {
    if (accountId) {
      removeSavedAccount(accountId)
    }
    clearAuth()
  }
}
