import { request } from './http'

export function getInboxMails() {
  return request('/api/mail/inbox')
}

export function getSentMails() {
  return request('/api/mail/sent')
}

export function getTrashMails() {
  return request('/api/mail/trash')
}

export function getMailDetail(mailId) {
  return request(`/api/mail/detail/${mailId}`)
}

export function sendMail(payload) {
  return request('/api/mail/send', {
    method: 'POST',
    body: payload
  })
}

export function deleteMail(mailId) {
  return request(`/api/mail/${mailId}`, {
    method: 'DELETE'
  })
}

export function markMailRead(mailId) {
  return request(`/api/mail/read/${mailId}`, {
    method: 'PUT'
  })
}

export function markMailUnread(mailId) {
  return request(`/api/mail/unread/${mailId}`, {
    method: 'PUT'
  })
}

export function searchMails(userId, keyword) {
  const params = new URLSearchParams({
    userId: String(userId),
    keyword: keyword || ''
  })
  return request(`/api/mail/search?${params.toString()}`)
}

export function filterMails(userId, filters = {}) {
  const params = new URLSearchParams({ userId: String(userId) })
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') {
      params.set(key, String(value))
    }
  })
  return request(`/api/mail/filter?${params.toString()}`)
}

export function restoreMail(mailId) {
  return request(`/api/mail/restore/${mailId}`, {
    method: 'PUT'
  })
}

export function permanentlyDeleteMail(mailId) {
  return request(`/api/mail/${mailId}/permanent`, {
    method: 'DELETE'
  })
}

export function uploadAttachment(mailId, file) {
  const formData = new FormData()
  formData.set('mailId', mailId)
  formData.set('file', file)

  return request('/api/attachment/upload', {
    method: 'POST',
    body: formData
  })
}

export function downloadAttachment(attachmentId) {
  return request(`/api/attachment/download/${attachmentId}`, {
    responseType: 'blob'
  })
}

export function getPriorityList(userId) {
  const params = new URLSearchParams({ userId: String(userId) })
  return request(`/api/mail/priority?${params.toString()}`)
}

export function organizeMails(userId, folderKey = 'inbox') {
  return request('/api/mail/organize', {
    method: 'POST',
    body: { userId: String(userId), folderKey }
  })
}

export function translateMailToChinese(payload) {
  return request('/api/mail/ai/translate', {
    method: 'POST',
    body: payload
  })
}

export function summarizeMail(payload) {
  return request('/api/mail/ai/summary', {
    method: 'POST',
    body: payload
  })
}

export function getUnreadNotifications(userId) {
  const params = new URLSearchParams({ userId: String(userId) })
  return request('/api/mail/notification?' + params.toString())
}

export function getNotificationOnlineCount() {
  return request('/api/mail/notification/online-count')
}

export function getSpamRecords() {
  return request('/api/mail/spam-records')
}

export function getSpamIds() {
  return request('/api/mail/spam-ids')
}

export function reportSpam(mailId) {
  return request('/api/mail/' + mailId + '/report-spam', {
    method: 'POST'
  })
}

export function reportNotSpam(mailId) {
  return request('/api/mail/' + mailId + '/report-not-spam', {
    method: 'POST'
  })
}

export function getSpamWarnings() {
  return request('/api/mail/spam-warnings')
}

export function blockSpamWarning(warningId) {
  return request('/api/mail/spam-warnings/' + warningId + '/block', {
    method: 'POST'
  })
}

export function ignoreSpamWarning(warningId) {
  return request('/api/mail/spam-warnings/' + warningId + '/ignore', {
    method: 'POST'
  })
}

export function addStar(mailId) {
  return request(`/api/mail/star/${mailId}`, { method: 'POST' })
}

export function removeStar(mailId) {
  return request(`/api/mail/star/${mailId}`, { method: 'DELETE' })
}

export function getStarredMails() {
  return request('/api/mail/starred')
}

export function checkStar(mailId) {
  return request(`/api/mail/star/${mailId}/check`)
}

export function addContact(contactEmail, listType) {
  return request('/api/mail/contact-list', {
    method: 'POST',
    body: { contactEmail, listType }
  })
}

export function removeContact(id) {
  return request(`/api/mail/contact-list/${id}`, { method: 'DELETE' })
}

export function getContactList() {
  return request('/api/mail/contact-list')
}

export function getDlAnalysis(mailId) {
  return request('/api/mail/' + mailId + '/dl-analysis')
}
