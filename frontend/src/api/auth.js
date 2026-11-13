import { request } from './http'

export function registerUser(payload) {
  return request('/api/auth/register', {
    method: 'POST',
    body: payload
  })
}

export function loginUser(payload) {
  return request('/api/auth/login', {
    method: 'POST',
    body: payload
  })
}

export function getCurrentUser() {
  return request('/api/user/me')
}

export function logoutUser() {
  return request('/api/auth/logout', {
    method: 'POST'
  })
}
