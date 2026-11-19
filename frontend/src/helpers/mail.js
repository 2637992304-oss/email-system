import { ref, watch } from 'vue'

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || ''

export const BASE_FOLDERS = [
  { key: 'inbox', name: '收件箱' },
  { key: 'starred', name: '星标邮件' },
  { key: 'drafts', name: '草稿箱' },
  { key: 'sent', name: '已发送' },
  { key: 'spam', name: '垃圾邮件' },
  { key: 'trash', name: '回收站' }
]

export const CONTACT_TYPE_OPTIONS = [
  { value: 'VIP', label: 'VIP' },
  { value: 'WHITELIST', label: '白名单' },
  { value: 'BLACKLIST', label: '黑名单' }
]

export const COMPOSE_RECIPIENT_TARGETS = [
  { key: 'to', label: '收件人' },
  { key: 'cc', label: '抄送' },
  { key: 'bcc', label: '密送' }
]

export function buildApiUrl(path) {
  if (!API_BASE_URL) return path
  const base = API_BASE_URL.replace(/\/$/, '')
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  return `${base}${normalizedPath}`
}

export function toIdSet(value) {
  if (!value) return new Set()
  const source = Array.isArray(value) ? value : Object.values(value)
  return new Set(source.map(Number).filter(Number.isFinite))
}

export function resolveReadState(mail, fallbackRead = false) {
  const value = mail?.isRead ?? mail?.read
  return value === undefined || value === null ? fallbackRead : Boolean(value)
}

export function formatMailTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}


export function htmlToPlainText(value) {
  const source = String(value || '')
  if (!source || !/<[a-z][\s\S]*>/i.test(source)) return source
  if (typeof document === 'undefined') {
    return source.replace(/<[^>]+>/g, '')
  }
  const template = document.createElement('template')
  template.innerHTML = source
  return template.content.textContent || ''
}

export function normalizeMailListItem(mail, options = {}) {
  const fallbackRead = options.fallbackRead || false
  return {
    mailId: mail.mailId,
    senderName: mail.senderName || '未知发件人',
    senderEmail: mail.senderEmail || '',
    receiverEmails: mail.receiverEmails || [],
    subject: mail.subject || '(无主题)',
    preview: htmlToPlainText(mail.preview || mail.content || ''),
    isRead: resolveReadState(mail, fallbackRead),
    hasAttachment: Boolean(mail.hasAttachment),
    spamFlag: Boolean(mail.spamFlag ?? mail.spam),
    priority: mail.priority,
    time: mail.receiveTime || mail.sendTime,
    raw: mail
  }
}

export function normalizeContact(item) {
  return {
    id: item.id ?? item.contactId,
    contactEmail: item.contactEmail || item.email || '',
    listType: item.listType || item.type || 'VIP',
    createdAt: item.createdAt || item.createTime || ''
  }
}

export function normalizeSpamRecord(record, index) {
  return {
    id: record.id ?? record.recordId ?? record.mailId ?? index,
    mailId: record.mailId,
    reason: record.reason || record.ruleName || record.spamReason || record.label || '疑似垃圾邮件',
    createdAt: record.createdAt || record.detectedAt || record.createTime || ''
  }
}

export function splitEmails(value) {
  return value
    .split(/[,;，；\s]+/)
    .map((email) => email.trim())
    .filter(Boolean)
}

export function useFeedbackToast(timeout = 3000) {
  const message = ref('')
  const error = ref('')
  let messageTimer = null
  let errorTimer = null

  function clearMessageTimer() {
    if (messageTimer) {
      clearTimeout(messageTimer)
      messageTimer = null
    }
  }

  function clearErrorTimer() {
    if (errorTimer) {
      clearTimeout(errorTimer)
      errorTimer = null
    }
  }

  function resetFeedback() {
    error.value = ''
    message.value = ''
  }

  function clearFeedbackTimers() {
    clearMessageTimer()
    clearErrorTimer()
  }

  watch(message, (value) => {
    clearMessageTimer()
    if (value) {
      messageTimer = setTimeout(() => {
        message.value = ''
        messageTimer = null
      }, timeout)
    }
  })

  watch(error, (value) => {
    clearErrorTimer()
    if (value) {
      // 屏蔽无意义的JS引擎错误
      if (typeof value === 'string' && /null is not an object|null|Cannot read properties of null/i.test(value)) {
        error.value = ''
        return
      }
      errorTimer = setTimeout(() => {
        error.value = ''
        errorTimer = null
      }, timeout)
    }
  })

  return {
    message,
    error,
    resetFeedback,
    clearFeedbackTimers
  }
}

export function useNotificationStream(options) {
  const newMailToast = ref('')
  let eventSource = null
  let reconnectTimer = null
  let toastTimer = null
  let stopped = true

  function clearReconnectTimer() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  function clearToastTimer() {
    if (toastTimer) {
      clearTimeout(toastTimer)
      toastTimer = null
    }
  }

  function showNewMailToast(data) {
    clearToastTimer()
    newMailToast.value = `${data.senderName} 发来新邮件: ${data.subject}`
    toastTimer = setTimeout(() => {
      newMailToast.value = ''
      toastTimer = null
    }, 3000)
  }

  function closeEventSource() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  function connectNotificationStream() {
    const token = options.getToken()
    const userId = options.getUserId()
    if (!token || !userId || eventSource) return

    stopped = false
    clearReconnectTimer()

    const url = buildApiUrl(`/api/mail/notification/stream?token=${encodeURIComponent(token)}`)
    eventSource = new EventSource(url)

    eventSource.addEventListener('connected', (event) => {
      options.onNewMail?.({ reconnected: true })
      try {
        console.log('[SSE] 已连接:', JSON.parse(event.data))
      } catch {
        console.log('[SSE] 已连接')
      }
    })

    eventSource.addEventListener('new-mail', (event) => {
      try {
        const data = JSON.parse(event.data)
        showNewMailToast(data)
        options.onNewMail?.(data)
      } catch (streamError) {
        console.error('[SSE] 解析事件失败:', streamError)
      }
    })

    eventSource.onerror = () => {
      console.warn('[SSE] 连接断开，5秒后重连...')
      closeEventSource()
      if (!stopped) {
        reconnectTimer = setTimeout(connectNotificationStream, 5000)
      }
    }
  }

  function disconnectNotificationStream() {
    stopped = true
    clearReconnectTimer()
    clearToastTimer()
    closeEventSource()
    newMailToast.value = ''
  }

  return {
    newMailToast,
    connectNotificationStream,
    disconnectNotificationStream
  }
}
