<script setup>
import {
  computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  blockSpamWarning,
  deleteMail,
  downloadAttachment,
  getInboxMails,
  getMailDetail,
  getNotificationOnlineCount,
  getSpamIds,
  getSpamRecords,
  getSpamWarnings,
  getSentMails,
  getStarredMails,
  getTrashMails,
  getUnreadNotifications,
  markMailRead,
  markMailUnread,
  organizeMails,
  addContact,
  addStar,
  checkStar,
  getContactList,
  getDlAnalysis,
  ignoreSpamWarning,
  removeContact,
  removeStar,
  reportNotSpam,
  reportSpam,
  searchMails,
  sendMail,
  summarizeMail,
  translateMailToChinese,
  uploadAttachment,
  restoreMail,
  permanentlyDeleteMail
} from '../api/mail'
import ToastStack from '../components/ToastStack.vue'
import FolderSidebar from '../components/FolderSidebar.vue'
import {
  BASE_FOLDERS,
  COMPOSE_RECIPIENT_TARGETS,
  CONTACT_TYPE_OPTIONS,
  formatMailTime,
  normalizeContact,
  normalizeMailListItem,
  normalizeSpamRecord,
  htmlToPlainText,
  resolveReadState,
  splitEmails,
  toIdSet,
  useFeedbackToast,
  useNotificationStream
} from '../helpers/mail'
import { authState, clearAuth, loadCurrentUser, submitLogout, switchSavedAccount } from '../stores/auth'

const router = useRouter()
const { message, error, resetFeedback, clearFeedbackTimers } = useFeedbackToast()
const APP_VERSION = '智能邮件系统v1'
const DRAFT_STORAGE_PREFIX = 'email-system-drafts'

const activeFolderKey = ref('inbox')
const selectedMailId = ref(null)
const mails = ref([])
const selectedMail = ref(null)
const loadingList = ref(false)
const organized = ref(false)
const originalMailOrder = ref([])
const organizedPriorityOrder = ref([])
const loadingDetail = ref(false)
const searchKeyword = ref('')
const showFilters = ref(false)
const showUserPanel = ref(false)
const showAccountList = ref(false)
const passwordNoticeOpen = ref(false)
const composeOpen = ref(false)
const sending = ref(false)
const selectedFiles = ref([])
const composeFileInputRef = ref(null)
const composeZoomed = ref(false)
const showCc = ref(false)
const showBcc = ref(false)
const activeContactField = ref(null)
const formatBoldActive = ref(false)
const formatItalicActive = ref(false)
const formatUnderlineActive = ref(false)
const formatAlignment = ref("left")
const activeDraftId = ref(null)
const composeEditorRef = ref(null)
const showComposeColorPalette = ref(false)
const composeModalRef = ref(null)
const insightsPanelRef = ref(null)
const mailContentRef = ref(null)
const deleteConfirmOpen = ref(false)
const deleting = ref(false)
const pendingDeleteMail = ref(null)
const restoreConfirmOpen = ref(false)
const restoring = ref(false)
const pendingRestoreMail = ref(null)
const permanentDeleteConfirmOpen = ref(false)
const permanentDeleting = ref(false)
const pendingPermanentDeleteMail = ref(null)
const starredMailIds = ref(new Set())
const unstarConfirmOpen = ref(false)
const pendingUnstarMail = ref(null)
const insightsOpen = ref(false)
const loadingInsights = ref(false)
const submittingContact = ref(false)
const contactItems = ref([])
const loadingComposeContacts = ref(false)
const composeContactTarget = ref('to')
const notificationOverview = ref(null)
const onlineCount = ref(null)
const spamRecords = ref([])
const spamMailIds = ref(new Set())
const dlAnalysisCache = ref({})
const spamWarnings = ref([])
const contactsLoaded = ref(false)
const reportingSpam = ref(false)
const reportingNotSpam = ref(false)
const removingSenderFromBlacklist = ref(false)
const warningActionKey = ref('')
const mailAiLoadingAction = ref('')
const mailAiResult = reactive({
  title: '',
  content: ''
})
const folderCounts = ref({
  inbox: 0,
  starred: 0,
  drafts: 0,
  sent: 0,
  spam: 0,
  trash: 0
})
const mailListWidth = ref(38)
let folderCountsRequestId = 0
let listRequestId = 0
let detailRequestId = 0
let mailAiRequestId = 0
let savedComposeRange = null

const filterForm = reactive({
  isRead: '',
  hasAttachment: '',
  timeRange: ''
})

const composeForm = reactive({
  to: '',
  cc: '',
  bcc: '',
  subject: '',
  content: ''
})

const contactForm = reactive({
  contactEmail: '',
  listType: 'VIP'
})

const composeFormat = reactive({
  fontFamily: 'Microsoft YaHei',
  fontSize: '14px',
  color: '#111827'
})

const modalPosition = reactive({ x: 0, y: 0 })
const insightsPanelPosition = reactive({ x: 0, y: 0 })
const dragState = reactive({
  active: false,
  startX: 0,
  startY: 0,
  originX: 0,
  originY: 0
})
const insightsDragState = reactive({
  active: false,
  startX: 0,
  startY: 0,
  originX: 0,
  originY: 0
})
const paneResizeState = reactive({
  active: false,
  startX: 0,
  startWidth: 0
})

const activeFolder = computed(() => BASE_FOLDERS.find((folder) => folder.key === activeFolderKey.value) || BASE_FOLDERS[0])

const inboxUnreadCount = ref(0)
const spamUnreadCount = ref(0)
const userInitial = computed(() => {
  const source = authState.user?.username || authState.user?.email || '邮'
  return source.trim().slice(0, 1).toUpperCase()
})

const savedAccounts = computed(() => authState.accounts || [])

function getAccountInitial(account) {
  const source = account?.username || account?.email || '邮'
  return source.trim().slice(0, 1).toUpperCase()
}

function isCurrentAccount(account) {
  return Boolean(account?.id && String(account.id) === String(authState.user?.userId ?? authState.user?.email ?? ''))
}

function shouldShowMailStar(mail) {
  return starFeatureVisible.value && isMailStarred(mail)
}

const folders = computed(() =>
  BASE_FOLDERS.map((folder) => ({
    ...folder,
    count: folderCounts.value[folder.key] ?? 0
  }))
)

const folderAlertCounts = computed(() => ({
  inbox: inboxUnreadCount.value,
  spam: spamUnreadCount.value
}))

const {
  newMailToast,
  connectNotificationStream,
  disconnectNotificationStream
} = useNotificationStream({
  getToken: () => authState.token,
  getUserId: () => authState.user?.userId,
  onNewMail: (mail) => {
    const targetFolder = isMailSpam(mail) ? 'spam' : 'inbox'
    folderCounts.value = {
      ...folderCounts.value,
      [targetFolder]: folderCounts.value[targetFolder] + 1
    }
    if (targetFolder === 'spam') {
      spamUnreadCount.value++
    } else {
      inboxUnreadCount.value++
    }
    refreshFolderCounts()
  }
})

const composeModalStyle = computed(() => {
  if (composeZoomed.value) {
    return {
      left: '16px',
      top: '16px',
      width: 'calc(100vw - 32px)',
      height: 'calc(100vh - 32px)'
    }
  }
  return {
    left: `${modalPosition.x}px`,
    top: `${modalPosition.y}px`
  }
})

const insightsPanelStyle = computed(() => ({
  left: `${insightsPanelPosition.x}px`,
  top: `${insightsPanelPosition.y}px`
}))

const mailContentStyle = computed(() => ({
  "--mail-list-width": mailListWidth.value + "%"
}))

const selectedMailStarred = computed(() => (
  isMailStarred(selectedMail.value)
))

const starFeatureVisible = computed(() => !['spam', 'trash', 'drafts'].includes(activeFolderKey.value))

const contactTypeOptions = CONTACT_TYPE_OPTIONS

const contactTypeLabels = contactTypeOptions.reduce((labels, option) => {
  labels[option.value] = option.label
  return labels
}, {})

const composeRecipientTargets = COMPOSE_RECIPIENT_TARGETS

const composeFontOptions = [
  { label: '微软雅黑', value: 'Microsoft YaHei' },
  { label: '宋体', value: 'SimSun' },
  { label: '黑体', value: 'SimHei' },
  { label: 'Arial', value: 'Arial' },
  { label: 'Times', value: 'Times New Roman' }
]

const composeFontSizeOptions = ['12px', '14px', '16px', '18px', '20px', '24px']

const filterTimeOptions = [
  { value: '', label: '全部时间' },
  { value: 'today', label: '今天' },
  { value: 'last7', label: '近7天' },
  { value: 'last30', label: '近30天' },
  { value: 'thisYear', label: '今年' },
  { value: 'older30', label: '30天以前' }
]

const composeColorOptions = [
  '#111827', '#4b5563', '#ffffff', '#ef4444', '#f97316', '#f59e0b',
  '#eab308', '#84cc16', '#22c55e', '#06b6d4', '#3b82f6', '#6366f1',
  '#8b5cf6', '#d946ef', '#ec4899', '#7f1d1d', '#78350f', '#14532d'
]

const contactGroups = computed(() => contactTypeOptions.map((option) => ({
  ...option,
  items: contactItems.value.filter((item) => item.listType === option.value)
})))

const composeContactOptions = computed(() => {
  const seen = new Set()
  return contactItems.value.filter((item) => {
    if (!item.contactEmail) return false
    const email = item.contactEmail.toLowerCase()
    if (seen.has(email)) return false
    seen.add(email)
    return true
  })
})

function getComposeEditor() {
  return composeEditorRef.value
}

function updateComposeContentFromEditor() {
  const editor = getComposeEditor()
  if (!editor) return
  const text = editor.textContent || ''
  const html = editor.innerHTML || ''
  composeForm.content = text.trim() ? html : ''
}

function syncComposeEditorContent() {
  const editor = getComposeEditor()
  if (!editor) return
  if ((editor.innerHTML || '') !== (composeForm.content || '')) {
    editor.innerHTML = composeForm.content || ''
  }
}

function isSelectionInsideComposeEditor() {
  const editor = getComposeEditor()
  const selection = window.getSelection?.()
  if (!editor || !selection || selection.rangeCount === 0) return false
  const range = selection.getRangeAt(0)
  return editor.contains(range.commonAncestorContainer)
}

function saveComposeSelection() {
  if (!isSelectionInsideComposeEditor()) return
  const selection = window.getSelection()
  savedComposeRange = selection.getRangeAt(0).cloneRange()
}

function restoreComposeSelection() {
  const editor = getComposeEditor()
  const selection = window.getSelection?.()
  if (!editor || !selection || !savedComposeRange) return
  selection.removeAllRanges()
  selection.addRange(savedComposeRange)
}

function focusComposeEditor() {
  const editor = getComposeEditor()
  if (!editor) return
  editor.focus()
  restoreComposeSelection()
}

function normalizeFontSizeTags(size) {
  const editor = getComposeEditor()
  if (!editor) return
  editor.querySelectorAll('font[size="7"]').forEach((node) => {
    node.removeAttribute('size')
    node.style.fontSize = size
  })
}

function runComposeCommand(command, value = null) {
  focusComposeEditor()
  document.execCommand('styleWithCSS', false, true)
  document.execCommand(command, false, value)
  updateComposeContentFromEditor()
  saveComposeSelection()
}

function applyComposeFont() {
  runComposeCommand('fontName', composeFormat.fontFamily)
}

function applyComposeFontSize() {
  focusComposeEditor()
  document.execCommand('styleWithCSS', false, true)
  document.execCommand('fontSize', false, '7')
  normalizeFontSizeTags(composeFormat.fontSize)
  updateComposeContentFromEditor()
  saveComposeSelection()
}

function applyComposeColor(color) {
  composeFormat.color = color
  showComposeColorPalette.value = false
  runComposeCommand('foreColor', color)
}


function toggleComposeBold() {
  runComposeCommand('bold')
}

function toggleComposeItalic() {
  runComposeCommand('italic')
}

function toggleComposeUnderline() {
  runComposeCommand('underline')
}

function insertComposeUnorderedList() {
  focusComposeEditor()
  document.execCommand('styleWithCSS', false, true)
  document.execCommand('insertUnorderedList')
  updateComposeContentFromEditor()
  saveComposeSelection()
}

function insertComposeOrderedList() {
  focusComposeEditor()
  document.execCommand('styleWithCSS', false, true)
  document.execCommand('insertOrderedList')
  updateComposeContentFromEditor()
  saveComposeSelection()
}

function insertComposeBlockquote() {
  focusComposeEditor()
  document.execCommand('styleWithCSS', false, true)
  document.execCommand('formatBlock', false, 'blockquote')
  updateComposeContentFromEditor()
  saveComposeSelection()
}

function checkFormatStates() {
  const editor = getComposeEditor()
  if (!editor) return
  formatBoldActive.value = document.queryCommandState("bold")
  formatItalicActive.value = document.queryCommandState("italic")
  formatUnderlineActive.value = document.queryCommandState("underline")
  const align = document.queryCommandValue("justify")
  formatAlignment.value = align || "left"
}

function setComposeAlignment(value) {
  const cmd = "justify" + value.charAt(0).toUpperCase() + value.slice(1)
  runComposeCommand(cmd)
  checkFormatStates()
}

function getHtmlText(value) {
  if (!value) return ''
  const container = document.createElement('div')
  container.innerHTML = value
  return container.textContent || ''
}

function hasMeaningfulHtml(value) {
  return getHtmlText(value).trim().length > 0
}

function escapeHtml(value) {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function sanitizeStyleAttribute(styleText) {
  const allowed = new Set(['color', 'font-size', 'font-family', 'font-weight', 'font-style', 'text-decoration', 'background-color'])
  return String(styleText || '')
    .split(';')
    .map((rule) => rule.trim())
    .filter(Boolean)
    .map((rule) => {
      const index = rule.indexOf(':')
      if (index === -1) return ''
      const property = rule.slice(0, index).trim().toLowerCase()
      const value = rule.slice(index + 1).trim()
      if (!allowed.has(property) || /url\s*\(|expression\s*\(/i.test(value)) return ''
      return property + ': ' + value
    })
    .filter(Boolean)
    .join('; ')
}

function unwrapElement(element) {
  const parent = element.parentNode
  if (!parent) return
  while (element.firstChild) {
    parent.insertBefore(element.firstChild, element)
  }
  parent.removeChild(element)
}

function renderMailContent(content) {
  const source = String(content || '')
  if (!source.trim()) return ''
  if (!/<[a-z][\s\S]*>/i.test(source)) {
    return escapeHtml(source).replace(/\n/g, '<br>')
  }

  const template = document.createElement('template')
  template.innerHTML = source
  const allowedTags = new Set(['b', 'strong', 'i', 'em', 'u', 'span', 'font', 'p', 'div', 'br', 'ul', 'ol', 'li', 'blockquote'])
  const allowedAttrs = new Set(['style', 'color', 'face', 'size'])

  template.content.querySelectorAll('script, style, iframe, object, embed, link, meta').forEach((node) => node.remove())
  template.content.querySelectorAll('*').forEach((element) => {
    const tagName = element.tagName.toLowerCase()
    if (!allowedTags.has(tagName)) {
      unwrapElement(element)
      return
    }

    Array.from(element.attributes).forEach((attr) => {
      const name = attr.name.toLowerCase()
      if (name.startsWith('on') || !allowedAttrs.has(name)) {
        element.removeAttribute(attr.name)
        return
      }
      if (name === 'style') {
        const safeStyle = sanitizeStyleAttribute(attr.value)
        if (safeStyle) element.setAttribute('style', safeStyle)
        else element.removeAttribute('style')
      }
    })
  })

  return template.innerHTML
}


function getDraftStorageKey() {
  const userKey = authState.user?.userId || authState.user?.email || 'guest'
  return DRAFT_STORAGE_PREFIX + ':' + userKey
}

function readDrafts() {
  try {
    const raw = localStorage.getItem(getDraftStorageKey())
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed : []
  } catch (err) {
    console.warn('草稿读取失败:', err)
    return []
  }
}

function writeDrafts(drafts) {
  localStorage.setItem(getDraftStorageKey(), JSON.stringify(drafts))
}

function updateDraftCount() {
  folderCounts.value = {
    ...folderCounts.value,
    drafts: readDrafts().length
  }
}

function createDraftId() {
  return 'draft-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
}

function hasComposeDraftContent() {
  return ['to', 'cc', 'bcc', 'subject'].some((key) => composeForm[key].trim()) || hasMeaningfulHtml(composeForm.content)
}

function toDraftMail(draft) {
  return {
    mailId: draft.id,
    draftId: draft.id,
    senderName: '草稿',
    senderEmail: authState.user?.email || '',
    receiverEmails: splitEmails(draft.to || ''),
    subject: draft.subject || '(无主题)',
    preview: htmlToPlainText(draft.content) || draft.to || '未填写正文',
    content: draft.content || '',
    isRead: true,
    hasAttachment: false,
    spamFlag: false,
    receiveTime: draft.updatedAt,
    sendTime: draft.updatedAt,
    updatedAt: draft.updatedAt,
    raw: { draft }
  }
}

function toDraftDetail(draft) {
  return {
    mailId: draft.id,
    draftId: draft.id,
    subject: draft.subject || '(无主题)',
    content: draft.content || '',
    senderName: '草稿',
    senderEmail: authState.user?.email || '',
    to: splitEmails(draft.to || ''),
    cc: splitEmails(draft.cc || ''),
    bcc: splitEmails(draft.bcc || ''),
    sendTime: draft.updatedAt,
    updatedAt: draft.updatedAt,
    isRead: true,
    attachments: [],
    raw: { draft }
  }
}

function getDraftFolderMails() {
  return readDrafts()
    .sort((a, b) => new Date(b.updatedAt || 0) - new Date(a.updatedAt || 0))
    .map(toDraftMail)
}

function findDraftById(draftId) {
  if (!draftId) return null
  return readDrafts().find((draft) => String(draft.id) === String(draftId)) || null
}

function refreshDraftListIfNeeded(selectDraftId = selectedMailId.value) {
  updateDraftCount()
  if (activeFolderKey.value !== 'drafts') return

  const data = getDraftFolderMails()
  mails.value = data.map(normalizeListItem)
  applyFolderCount('drafts', data)

  if (!data.length) {
    selectedMailId.value = null
    selectedMail.value = null
    return
  }

  const nextSelected = data.find((draft) => String(draft.mailId) === String(selectDraftId))
  if (!nextSelected) {
    selectedMailId.value = null
    selectedMail.value = null
    return
  }
  selectedMailId.value = nextSelected.mailId
  selectedMail.value = toDraftDetail(nextSelected.raw.draft)
}

function removeDraftById(draftId) {
  if (!draftId) return
  const nextDrafts = readDrafts().filter((draft) => String(draft.id) !== String(draftId))
  writeDrafts(nextDrafts)
  if (String(activeDraftId.value) === String(draftId)) {
    activeDraftId.value = null
  }
  refreshDraftListIfNeeded(draftId)
}

function saveCurrentDraft({ silent = false } = {}) {
  updateComposeContentFromEditor()
  const draftId = activeDraftId.value
  if (!hasComposeDraftContent()) {
    if (draftId) {
      removeDraftById(draftId)
    }
    return null
  }

  const drafts = readDrafts()
  const existing = drafts.find((draft) => String(draft.id) === String(draftId))
  const now = new Date().toISOString()
  const draft = {
    id: draftId || createDraftId(),
    to: splitEmails(composeForm.to).join(', '),
    cc: splitEmails(composeForm.cc).join(', '),
    bcc: splitEmails(composeForm.bcc).join(', '),
    subject: composeForm.subject.trim(),
    content: composeForm.content,
    createdAt: existing?.createdAt || now,
    updatedAt: now
  }
  const nextDrafts = [draft, ...drafts.filter((item) => String(item.id) !== String(draft.id))]
  writeDrafts(nextDrafts)
  activeDraftId.value = draft.id
  refreshDraftListIfNeeded(draft.id)
  if (!silent) {
    message.value = '草稿已保存'
  }
  return draft
}

function handleSaveDraft() {
  resetFeedback()
  const draft = saveCurrentDraft()
  if (!draft) {
    error.value = '请先填写草稿内容'
  }
}

function openNewCompose() {
  resetCompose()
  composeOpen.value = true
}

function openDraftCompose(draftSource = selectedMail.value) {
  const draftId = draftSource?.draftId || draftSource?.mailId
  const draft = draftSource?.raw?.draft || findDraftById(draftId)
  if (!draft) {
    error.value = '草稿不存在或已删除'
    return
  }

  activeDraftId.value = draft.id
  composeForm.to = draft.to || ''
  composeForm.cc = draft.cc || ''
  composeForm.bcc = draft.bcc || ''
  composeForm.subject = draft.subject || ''
  composeForm.content = draft.content || ''
  selectedFiles.value = []
  resetComposeFileInput()
  composeOpen.value = true
  requestAnimationFrame(syncComposeEditorContent)
}

function closeComposeModal() {
  if (sending.value) return
  const draft = saveCurrentDraft({ silent: true })
  composeOpen.value = false
  resetCompose()
  if (draft) {
    message.value = '已保存到草稿箱'
  }
}

function handleDraftDelete() {
  const draftId = selectedMail.value?.draftId || selectedMail.value?.mailId
  removeDraftById(draftId)
  message.value = '草稿已删除'
}


const recentUnreadItems = computed(() => {
  const overview = notificationOverview.value || {}
  return overview.recentUnread || overview.mails || overview.notifications || []
})

const notificationUnreadCount = computed(() => {
  const overview = notificationOverview.value || {}
  return overview.unreadCount ?? overview.count ?? recentUnreadItems.value.length
})

const selectedSenderEmail = computed(() => normalizeEmailAddress(selectedMail.value?.senderEmail))

const currentSenderBlacklistContact = computed(() => findBlacklistContactByEmail(selectedSenderEmail.value))

function normalizeEmailAddress(value) {
  return (value || '').trim().toLowerCase()
}

function getWarningId(warning) {
  return warning?.id ?? warning?.warningId
}

function normalizeSpamWarning(warning) {
  return {
    id: getWarningId(warning),
    senderEmail: normalizeEmailAddress(warning?.senderEmail),
    mailId: warning?.mailId,
    status: warning?.status || 'PENDING',
    createdAt: warning?.createdAt || warning?.createTime || '',
    raw: warning
  }
}

function resetMailAiResult() {
  mailAiRequestId++
  mailAiLoadingAction.value = ''
  mailAiResult.title = ''
  mailAiResult.content = ''
}

function findBlacklistContactByEmail(email) {
  const normalizedEmail = normalizeEmailAddress(email)
  if (!normalizedEmail) return null
  return contactItems.value.find((item) => (
    item.listType === 'BLACKLIST' && normalizeEmailAddress(item.contactEmail) === normalizedEmail
  )) || null
}

function normalizeMailId(value) {
  if (value === null || value === undefined || value === '') return null
  const numericValue = Number(value)
  return Number.isFinite(numericValue) ? numericValue : value
}

function getMailId(value) {
  if (value && typeof value === 'object') {
    return normalizeMailId(value.mailId)
  }
  return normalizeMailId(value)
}

function hasUsableMailId(mailId) {
  return mailId !== null && mailId !== undefined && mailId !== ''
}

function isMailStarred(value) {
  const mailId = getMailId(value)
  return hasUsableMailId(mailId) && starredMailIds.value.has(mailId)
}

function buildStarredIdSet(list) {
  return new Set(
    (list || [])
      .map(getMailId)
      .filter(hasUsableMailId)
  )
}

function sortStarredFirst(list) {
  return [...(list || [])].sort((a, b) => {
    const aStarred = isMailStarred(a)
    const bStarred = isMailStarred(b)
    if (aStarred === bStarred) return 0
    return aStarred ? -1 : 1
  })
}

function refreshOrganizedStarPriority() {
  if (!organized.value) return
  const source = organizedPriorityOrder.value.length ? organizedPriorityOrder.value : mails.value
  mails.value = sortStarredFirst(source)
}

function setMailStarred(mailId, starred) {
  const normalizedMailId = normalizeMailId(mailId)
  if (!hasUsableMailId(normalizedMailId)) return

  const nextStarred = new Set(starredMailIds.value)
  if (starred) {
    nextStarred.add(normalizedMailId)
  } else {
    nextStarred.delete(normalizedMailId)
  }
  starredMailIds.value = nextStarred
  refreshOrganizedStarPriority()
}

function syncStarredIdsFromList(list) {
  starredMailIds.value = buildStarredIdSet(list)
  refreshOrganizedStarPriority()
}

function isMailSpam(mail, spamSet = spamMailIds.value) {
  if (!mail) return false
  const mailId = Number(mail.mailId)
  return Boolean(mail.spamFlag ?? mail.spam ?? mail.isSpam ?? mail.is_spam) ||
    (Number.isFinite(mailId) && spamSet.has(mailId))
}

async function fetchInboxAndSpamMails() {
  const [inboxResult, spamIdsResult] = await Promise.allSettled([getInboxMails(), getSpamIds()])
  if (inboxResult.status === 'rejected') {
    throw inboxResult.reason
  }
  if (spamIdsResult.status === 'fulfilled') {
    spamMailIds.value = toIdSet(spamIdsResult.value)
  }

  const cleanInbox = []
  const spamInbox = []
  for (const mail of inboxResult.value || []) {
    if (isMailSpam(mail)) {
      spamInbox.push({ ...mail, spamFlag: true })
    } else {
      cleanInbox.push(mail)
    }
  }

  return { inbox: cleanInbox, spam: spamInbox }
}

async function fetchFolderMails(folderKey) {
  if (folderKey === 'inbox') {
    const data = await fetchInboxAndSpamMails()
    return data.inbox
  }
  if (folderKey === 'sent') {
    return getSentMails()
  }
  if (folderKey === 'drafts') {
    return getDraftFolderMails()
  }
  if (folderKey === 'trash') {
    return getTrashMails()
  }
  if (folderKey === 'starred') {
    return getStarredMails()
  }
  if (folderKey === 'spam') {
    const data = await fetchInboxAndSpamMails()
    return data.spam
  }
  return []
}

function applyFolderCount(folderKey, list) {
  folderCounts.value = {
    ...folderCounts.value,
    [folderKey]: (list || []).length
  }
  if (folderKey === 'inbox') {
    inboxUnreadCount.value = (list || []).filter((mail) => !resolveReadState(mail)).length
  }
  if (folderKey === 'spam') {
    spamUnreadCount.value = (list || []).filter((mail) => !resolveReadState(mail)).length
  }
}

async function refreshFolderCounts(overrides = {}) {
  const requestId = ++folderCountsRequestId
  const nextCounts = { ...folderCounts.value }
  let nextInboxUnreadCount = inboxUnreadCount.value
  let nextSpamUnreadCount = spamUnreadCount.value
  const hasInboxOverride = Object.prototype.hasOwnProperty.call(overrides, 'inbox')
  const hasSpamOverride = Object.prototype.hasOwnProperty.call(overrides, 'spam')
  const inboxSpamRequest = hasInboxOverride && hasSpamOverride ? null : fetchInboxAndSpamMails()

  const tasks = BASE_FOLDERS.map(async (folder) => {
    const hasOverride = Object.prototype.hasOwnProperty.call(overrides, folder.key)
    let data
    if (hasOverride) {
      data = overrides[folder.key]
    } else if (folder.key === 'inbox' || folder.key === 'spam') {
      data = (await inboxSpamRequest)[folder.key]
    } else {
      data = await fetchFolderMails(folder.key)
    }
    nextCounts[folder.key] = (data || []).length
    if (folder.key === 'inbox') {
      nextInboxUnreadCount = (data || []).filter((mail) => !resolveReadState(mail)).length
    }
    if (folder.key === 'spam') {
      nextSpamUnreadCount = (data || []).filter((mail) => !resolveReadState(mail)).length
    }
  })

  const results = await Promise.allSettled(tasks)
  if (requestId !== folderCountsRequestId) return

  folderCounts.value = nextCounts
  inboxUnreadCount.value = nextInboxUnreadCount
  spamUnreadCount.value = nextSpamUnreadCount

  const failed = results.find((result) => result.status === 'rejected')
  if (failed) {
    console.warn('文件夹计数刷新失败:', failed.reason)
  }
}

const formatTime = formatMailTime

function normalizeListItem(mail) {
  return normalizeMailListItem(mail, {
    fallbackRead: ['sent', 'drafts'].includes(activeFolderKey.value)
  })
}

function mergeListItemMetadata(item, metadata) {
  if (!metadata) return item
  return {
    ...item,
    senderName: metadata.senderName || item.senderName,
    senderEmail: metadata.senderEmail || item.senderEmail,
    receiverEmails: metadata.receiverEmails?.length ? metadata.receiverEmails : item.receiverEmails,
    preview: metadata.preview || item.preview,
    isRead: metadata.isRead ?? item.isRead,
    hasAttachment: metadata.hasAttachment ?? item.hasAttachment,
    spamFlag: metadata.spamFlag ?? item.spamFlag,
    priority: metadata.priority ?? item.priority,
    time: metadata.time || item.time,
    raw: {
      ...item.raw,
      metadata: metadata.raw || metadata
    }
  }
}


function getMailDate(mail) {
  const value = mail?.time || mail?.receiveTime || mail?.sendTime || mail?.raw?.receiveTime || mail?.raw?.sendTime
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

function getStartOfToday() {
  const date = new Date()
  date.setHours(0, 0, 0, 0)
  return date
}

function isMailInTimeRange(mail, timeRange) {
  if (!timeRange) return true
  const date = getMailDate(mail)
  if (!date) return false

  const now = new Date()
  if (timeRange === 'today') {
    return date >= getStartOfToday()
  }
  if (timeRange === 'last7') {
    return date >= new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)
  }
  if (timeRange === 'last30') {
    return date >= new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000)
  }
  if (timeRange === 'thisYear') {
    return date >= new Date(now.getFullYear(), 0, 1)
  }
  if (timeRange === 'older30') {
    return date < new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000)
  }
  return true
}

function applyTimeFilterToList(list) {
  return (list || []).filter((mail) => isMailInTimeRange(mail, filterForm.timeRange))
}

function getFilterSourceFolderKey() {
  return BASE_FOLDERS.some((folder) => folder.key === activeFolderKey.value)
    ? activeFolderKey.value
    : BASE_FOLDERS[0].key
}

function applyMailFiltersToList(list, filters) {
  return applyTimeFilterToList(
    (list || []).filter((mail) => {
      if (filters.isRead !== null && mail.isRead !== filters.isRead) {
        return false
      }
      if (filters.hasAttachment !== null && mail.hasAttachment !== filters.hasAttachment) {
        return false
      }
      return true
    })
  )
}

async function enrichSearchLikeListItems(items) {
  if (!items.length) return items

  const metadataByMailId = new Map()
  const addMetadata = (list, options = {}) => {
    ;(list || []).forEach((mail) => {
      const normalized = normalizeMailListItem(mail, options)
      if (normalized.mailId) {
        metadataByMailId.set(normalized.mailId, normalized)
      }
    })
  }

  const [inboxSpamResult, sentResult] = await Promise.allSettled([
    fetchInboxAndSpamMails(),
    getSentMails()
  ])

  if (inboxSpamResult.status === 'fulfilled') {
    addMetadata(inboxSpamResult.value.inbox)
    addMetadata(inboxSpamResult.value.spam)
  } else {
    console.warn('搜索结果发件人信息补全失败:', inboxSpamResult.reason)
  }

  if (sentResult.status === 'fulfilled') {
    addMetadata(sentResult.value, { fallbackRead: true })
  } else {
    console.warn('搜索结果发件人信息补全失败:', sentResult.reason)
  }

  return items.map((item) => mergeListItemMetadata(item, metadataByMailId.get(item.mailId)))
}

async function loadFolder(folderKey = activeFolderKey.value) {
  const requestId = ++listRequestId
  resetFeedback()
  loadingList.value = true
  selectedMail.value = null
  selectedMailId.value = null
  detailRequestId++
  loadingDetail.value = false

  try {
    activeFolderKey.value = folderKey
    organized.value = false
    originalMailOrder.value = []
    organizedPriorityOrder.value = []
    const data = await fetchFolderMails(folderKey)
    if (requestId !== listRequestId) return
    if (folderKey === 'starred') {
      syncStarredIdsFromList(data)
    }
    mails.value = (data || []).map(normalizeListItem)
    applyFolderCount(folderKey, data)
    refreshFolderCounts({ [folderKey]: data })
  } catch (err) {
    if (requestId !== listRequestId) return
    error.value = err.message || '邮件列表加载失败'
    mails.value = []
  } finally {
    if (requestId === listRequestId) {
      loadingList.value = false
    }
  }
}

async function selectFolder(folder) {
  await loadFolder(folder.key)
}
async function selectMail(mail) {
  if (!mail?.mailId) return

  const requestId = ++detailRequestId
  resetMailAiResult()
  loadingDetail.value = true
  selectedMailId.value = mail.mailId

  if (activeFolderKey.value === 'drafts') {
    const draft = mail.raw?.draft || findDraftById(mail.draftId || mail.mailId)
    selectedMail.value = draft ? toDraftDetail(draft) : null
    loadingDetail.value = false
    return
  }

  try {
    const detail = await getMailDetail(mail.mailId)
    if (requestId !== detailRequestId) return
    if (!detail) { error.value = '邮件详情加载失败'; loadingDetail.value = false; return }
    selectedMail.value = detail
    if (activeFolderKey.value === 'spam') {
      ensureContactListLoaded().catch((contactErr) => {
        console.warn('黑名单状态同步失败:', contactErr)
      })
    }
    try {
      const starState = await checkStar(mail.mailId)
      if (requestId !== detailRequestId) return
      setMailStarred(mail.mailId, Boolean(starState?.starred))
    } catch (starErr) {
      console.warn('星标状态同步失败:', starErr)
    }
    if (detail.spamFlag) {
      getDlAnalysis(mail.mailId).then(function(a) { dlAnalysisCache.value = Object.assign({}, dlAnalysisCache.value, { ['' + mail.mailId]: a }) }).catch(function() {})
    }

    const current = mails.value.find((item) => item.mailId === mail.mailId)
    if (current && ['inbox', 'spam'].includes(activeFolderKey.value)) {
      if (!current.isRead) {
        current.isRead = true
      }
      if (activeFolderKey.value === 'spam') {
        spamUnreadCount.value = mails.value.filter(m => !m.isRead).length
      } else {
        inboxUnreadCount.value = mails.value.filter(m => !m.isRead).length
      }
    }
  } catch (err) {
    if (requestId !== detailRequestId) return
    error.value = err.message || '邮件详情加载失败'
  } finally {
    if (requestId === detailRequestId) {
      loadingDetail.value = false
    }
  }
}

function getMailAiPayload() {
  return {
    subject: selectedMail.value?.subject || '',
    content: htmlToPlainText(selectedMail.value?.content || '')
  }
}

async function handleMailAiAction(action) {
  if (!selectedMail.value || mailAiLoadingAction.value) return

  const requestId = ++mailAiRequestId
  resetFeedback()
  mailAiLoadingAction.value = action
  mailAiResult.title = ''
  mailAiResult.content = ''

  try {
    const request = action === 'translate' ? translateMailToChinese : summarizeMail
    const data = await request(getMailAiPayload())
    if (requestId !== mailAiRequestId) return
    mailAiResult.title = action === 'translate' ? '中文翻译' : '邮件总结'
    mailAiResult.content = data?.result || ''
  } catch (err) {
    if (requestId !== mailAiRequestId) return
    error.value = err.message || (action === 'translate' ? '翻译失败' : '总结失败')
  } finally {
    if (requestId === mailAiRequestId) {
      mailAiLoadingAction.value = ''
    }
  }
}

async function handleSearch() {
  const keyword = searchKeyword.value.trim()
  if (!keyword) {
    await loadFolder('inbox')
    return
  }

  const requestId = ++listRequestId
  resetFeedback()
  loadingList.value = true
  activeFolderKey.value = 'search'
  selectedMail.value = null
  selectedMailId.value = null
  detailRequestId++
  loadingDetail.value = false
  try {
    const data = await searchMails(authState.user.userId, keyword)
    if (requestId !== listRequestId) return
    const listItems = (data || []).map(normalizeListItem)
    mails.value = await enrichSearchLikeListItems(listItems)
    if (requestId !== listRequestId) return
    showFilters.value = false
  } catch (err) {
    if (requestId !== listRequestId) return
    error.value = err.message || '搜索失败'
    mails.value = []
  } finally {
    if (requestId === listRequestId) {
      loadingList.value = false
    }
  }
}

async function applyFilters() {
  const requestId = ++listRequestId
  const sourceFolderKey = getFilterSourceFolderKey()
  resetFeedback()
  loadingList.value = true
  activeFolderKey.value = sourceFolderKey
  selectedMail.value = null
  selectedMailId.value = null
  detailRequestId++
  loadingDetail.value = false

  const filters = {
    isRead: filterForm.isRead === '' ? null : filterForm.isRead === 'true',
    hasAttachment: filterForm.hasAttachment === '' ? null : filterForm.hasAttachment === 'true'
  }

  try {
    const data = await fetchFolderMails(sourceFolderKey)
    if (requestId !== listRequestId) return
    const listItems = (data || []).map(normalizeListItem)
    mails.value = applyMailFiltersToList(listItems, filters)
    showFilters.value = false
  } catch (err) {
    if (requestId !== listRequestId) return
    error.value = err.message || '筛选失败'
    mails.value = []
  } finally {
    if (requestId === listRequestId) {
      loadingList.value = false
    }
  }
}

async function handleReadToggle(read) {
  if (!selectedMail.value?.mailId) return

  resetFeedback()
  try {
    if (read) {
      await markMailRead(selectedMail.value.mailId)
    } else {
      await markMailUnread(selectedMail.value.mailId)
    }
    const current = mails.value.find((item) => item.mailId === selectedMail.value.mailId)
    if (current) current.isRead = read
    selectedMail.value.isRead = read
    if (activeFolderKey.value === 'inbox') {
      inboxUnreadCount.value = mails.value.filter(m => !m.isRead).length
    }
    message.value = read ? '已标记为已读' : '已标记为未读'
  } catch (err) {
    error.value = err.message || '状态更新失败'
  }
}

async function handleOrganize() {
  if (organized.value) {
    mails.value = [...originalMailOrder.value]
    organized.value = false
    organizedPriorityOrder.value = []
    return
  }

  resetFeedback()
  try {
    originalMailOrder.value = [...mails.value]

    const sortedMails = await organizeMails(authState.user.userId, activeFolderKey.value)
    const visibleMails = activeFolderKey.value === 'inbox'
      ? (sortedMails || []).filter((mail) => !isMailSpam(mail))
      : sortedMails || []
    const normalizedMails = visibleMails.map(normalizeListItem)
    organizedPriorityOrder.value = normalizedMails
    mails.value = sortStarredFirst(normalizedMails)

    organized.value = true
    message.value = '邮件已按优先级排序，星标邮件已置顶'
  } catch (err) {
    error.value = err.message || '整理失败'
  }
}

async function handleDelete() {
  if (!selectedMail.value?.mailId) return

  if (activeFolderKey.value === 'inbox') {
    pendingDeleteMail.value = selectedMail.value
    deleteConfirmOpen.value = true
    return
  }

  pendingDeleteMail.value = null
  await confirmDelete()
}

async function confirmDelete() {
  const mailToDelete = pendingDeleteMail.value || selectedMail.value
  if (!mailToDelete?.mailId) return

  resetFeedback()
  deleting.value = true
  try {
    await deleteMail(mailToDelete.mailId)
    deleteConfirmOpen.value = false
    pendingDeleteMail.value = null
    await loadFolder(activeFolderKey.value === 'sent' ? 'sent' : 'inbox')
    await refreshFolderCounts()
    message.value = '邮件已删除'
  } catch (err) {
    error.value = err.message || '删除失败'
  } finally {
    deleting.value = false
  }
}

function cancelDelete() {
  if (deleting.value) return
  pendingDeleteMail.value = null
  deleteConfirmOpen.value = false
}

async function handleStarToggle() {
  if (!selectedMail.value?.mailId) return

  if (selectedMailStarred.value) {
    pendingUnstarMail.value = selectedMail.value
    unstarConfirmOpen.value = true
    return
  }

  try {
    await addStar(selectedMail.value.mailId)
    setMailStarred(selectedMail.value.mailId, true)
    await refreshFolderCounts()
    message.value = '已标记为星标邮件'
  } catch (err) {
    error.value = err.message || '星标操作失败'
  }
}

async function confirmUnstar() {
  const mailToUnstar = pendingUnstarMail.value || selectedMail.value
  if (!mailToUnstar?.mailId) return

  try {
    await removeStar(mailToUnstar.mailId)
    setMailStarred(mailToUnstar.mailId, false)
    pendingUnstarMail.value = null
    unstarConfirmOpen.value = false
    if (activeFolderKey.value === 'starred') {
      await loadFolder('starred')
    } else {
      await refreshFolderCounts()
    }
    message.value = '已取消星标'
  } catch (err) {
    error.value = err.message || '取消星标失败'
  }
}

function cancelUnstar() {
  pendingUnstarMail.value = null
  unstarConfirmOpen.value = false
}

async function openInsights() {
  insightsOpen.value = true
  requestAnimationFrame(centerInsightsPanel)
  await loadInsights()
}

function closeInsights() {
  insightsOpen.value = false
  stopInsightsPanelDrag()
}

async function loadContactList() {
  const contacts = await getContactList()
  contactItems.value = (contacts || []).map(normalizeContact).filter((item) => item.id || item.contactEmail)
  contactsLoaded.value = true
}

async function ensureContactListLoaded() {
  if (!contactsLoaded.value) {
    await loadContactList()
  }
}

async function loadSpamWarnings() {
  const warnings = await getSpamWarnings()
  spamWarnings.value = (warnings || [])
    .map(normalizeSpamWarning)
    .filter((warning) => warning.id && warning.senderEmail)
}

async function loadComposeContacts() {
  loadingComposeContacts.value = true
  try {
    await loadContactList()
  } catch (err) {
    error.value = err.message || '联系人加载失败'
  } finally {
    loadingComposeContacts.value = false
  }
}

async function loadInsights() {
  if (!authState.user?.userId) return

  resetFeedback()
  loadingInsights.value = true
  const userId = authState.user.userId
  try {
    const [contacts, notifications, online, spamList, spamIds, warnings] = await Promise.allSettled([
      loadContactList(),
      getUnreadNotifications(userId),
      getNotificationOnlineCount(),
      getSpamRecords(),
      getSpamIds(),
      loadSpamWarnings()
    ])

    if (notifications.status === 'fulfilled') notificationOverview.value = notifications.value || null
    if (online.status === 'fulfilled') onlineCount.value = online.value?.onlineCount ?? online.value ?? null
    if (spamList.status === 'fulfilled') spamRecords.value = (spamList.value || []).map(normalizeSpamRecord)
    if (spamIds.status === 'fulfilled') spamMailIds.value = toIdSet(spamIds.value)

    const failed = [contacts, notifications, online, spamList, spamIds, warnings].find((item) => item.status === 'rejected')
    if (failed) {
      error.value = failed.reason?.message || '智能中心部分数据加载失败'
    }
  } finally {
    loadingInsights.value = false
  }
}

async function handleAddContact() {
  const email = contactForm.contactEmail.trim()
  if (!email) {
    error.value = '请输入联系人邮箱'
    return
  }

  resetFeedback()
  submittingContact.value = true
  try {
    await addContact(email, contactForm.listType)
    contactForm.contactEmail = ''
    await loadContactList()
    message.value = '联系人名单已更新'
  } catch (err) {
    error.value = err.message || '联系人添加失败'
  } finally {
    submittingContact.value = false
  }
}

async function handleRemoveContact(id) {
  if (!id) return

  resetFeedback()
  try {
    await removeContact(id)
    contactItems.value = contactItems.value.filter((item) => item.id !== id)
    message.value = '联系人已移除'
  } catch (err) {
    error.value = err.message || '联系人移除失败'
  }
}

async function handleReportSpam() {
  if (!selectedMail.value?.mailId) return

  resetFeedback()
  reportingSpam.value = true
  try {
    const result = await reportSpam(selectedMail.value.mailId)
    const nextSpamIds = new Set(spamMailIds.value)
    nextSpamIds.add(Number(selectedMail.value.mailId))
    spamMailIds.value = nextSpamIds
    await loadFolder('inbox')
    await refreshFolderCounts()
    // 显示DL学到的词（安全取值，容错处理）
    const dlProb = (result && typeof result === 'object' && result.dlProbability) || 0
    const extractedWords = (result && typeof result === 'object' && Array.isArray(result.llmExtractedWords)) ? result.llmExtractedWords : []
    if (extractedWords.length > 0) {
      const topWords = extractedWords.filter(w => w && w.text && w.text.length >= 2).sort((a,b) => b.probability - a.probability).slice(0,2)
      const wordsStr = topWords.map(w => w.text + '(' + Math.round(w.probability*100) + '%)').join(', ')
      message.value = '已举报 · DL概率' + dlProb + '% · 学到: ' + (wordsStr || '无')
      if (result && typeof result === 'object') {
        dlAnalysisCache.value = { ...dlAnalysisCache.value, [selectedMail.value.mailId]: result }
      }
    } else {
      message.value = '已标为垃圾邮件'
    }
  } catch (err) {
    error.value = err.message || '标为垃圾邮件失败'
  } finally {
    reportingSpam.value = false
  }
}

async function handleReportNotSpam() {
  if (!selectedMail.value?.mailId) return

  resetFeedback()
  reportingNotSpam.value = true
  try {
    const mailId = Number(selectedMail.value.mailId)
    await reportNotSpam(mailId)
    const nextSpamIds = new Set(spamMailIds.value)
    nextSpamIds.delete(mailId)
    spamMailIds.value = nextSpamIds
    await loadFolder(activeFolderKey.value === 'spam' ? 'spam' : activeFolderKey.value)
    await refreshFolderCounts()
    message.value = '已标记为正常邮件'
  } catch (err) {
    error.value = err.message || '标记为正常邮件失败'
  } finally {
    reportingNotSpam.value = false
  }
}

async function handleRemoveSenderFromBlacklist() {
  const email = selectedSenderEmail.value
  if (!email) return

  resetFeedback()
  removingSenderFromBlacklist.value = true
  try {
    await loadContactList()
    const contact = findBlacklistContactByEmail(email)
    if (!contact?.id) {
      message.value = '该发件人当前不在黑名单中'
      return
    }
    await removeContact(contact.id)
    contactItems.value = contactItems.value.filter((item) => item.id !== contact.id)
    message.value = '已移出黑名单，后续邮件不再因黑名单进入垃圾邮件'
  } catch (err) {
    error.value = err.message || '移出黑名单失败'
  } finally {
    removingSenderFromBlacklist.value = false
  }
}

const lastBlockDlInfo = ref(null)

async function handleBlockWarning(warning) {
  const warningId = getWarningId(warning)
  if (!warningId) return

  resetFeedback()
  warningActionKey.value = 'block-' + warningId
  try {
    const result = await blockSpamWarning(warningId)
    spamWarnings.value = spamWarnings.value.filter((item) => getWarningId(item) !== warningId)
    await loadContactList()
    if (result && result.dlProbability !== undefined) {
      lastBlockDlInfo.value = result
      const prob = result.dlProbability || 0
      // 优先展示LLM提取的关键词（含概率），与举报流程一致
      const extractedWords = (Array.isArray(result.llmExtractedWords) ? result.llmExtractedWords : [])
      if (extractedWords.length > 0) {
        const topWords = extractedWords
          .filter(w => w && w.text && w.text.length >= 2)
          .sort((a, b) => b.probability - a.probability)
          .slice(0, 3)
        const wordsStr = topWords.map(w => w.text + '(' + Math.round(w.probability * 100) + '%)').join(', ')
        message.value = '已拉黑 · DL概率' + prob + '% · 学到: ' + (wordsStr || '无')
      } else {
        // 兜底：展示DL分词特征词
        const features = (Array.isArray(result.dlTopFeatures) ? result.dlTopFeatures : [])
        const words = features.filter(f => f && f.weight > 0).map(f => f.text).slice(0, 5).join('、')
        message.value = '已拉黑 · DL概率' + prob + '% · 学到的词: ' + (words || '无')
      }
    } else {
      message.value = '已拉黑该发件人'
    }
  } catch (err) {
    error.value = err.message || '拉黑失败'
  } finally {
    warningActionKey.value = ''
  }
}

async function handleIgnoreWarning(warning) {
  const warningId = getWarningId(warning)
  if (!warningId) return

  resetFeedback()
  warningActionKey.value = 'ignore-' + warningId
  try {
    await ignoreSpamWarning(warningId)
    spamWarnings.value = spamWarnings.value.filter((item) => getWarningId(item) !== warningId)
    message.value = '已忽略该警告'
  } catch (err) {
    error.value = err.message || '忽略失败'
  } finally {
    warningActionKey.value = ''
  }
}

async function handleRestore() {
  if (!selectedMail.value?.mailId) return

  pendingRestoreMail.value = selectedMail.value
  restoreConfirmOpen.value = true
}

async function confirmRestore() {
  const mailToRestore = pendingRestoreMail.value || selectedMail.value
  if (!mailToRestore?.mailId) return

  resetFeedback()
  restoring.value = true
  try {
    await restoreMail(mailToRestore.mailId)
    restoreConfirmOpen.value = false
    pendingRestoreMail.value = null
    await loadFolder('trash')
    await refreshFolderCounts()
    message.value = '邮件已恢复'
  } catch (err) {
    error.value = err.message || '恢复失败'
  } finally {
    restoring.value = false
  }
}

function cancelRestore() {
  if (restoring.value) return
  pendingRestoreMail.value = null
  restoreConfirmOpen.value = false
}

function handlePermanentDelete() {
  if (!selectedMail.value?.mailId) return

  pendingPermanentDeleteMail.value = selectedMail.value
  permanentDeleteConfirmOpen.value = true
}

async function confirmPermanentDelete() {
  const mailToDelete = pendingPermanentDeleteMail.value || selectedMail.value
  if (!mailToDelete?.mailId) return

  resetFeedback()
  permanentDeleting.value = true
  try {
    await permanentlyDeleteMail(mailToDelete.mailId)
    permanentDeleteConfirmOpen.value = false
    pendingPermanentDeleteMail.value = null
    await loadFolder('trash')
    await refreshFolderCounts()
    message.value = '邮件已永久删除'
  } catch (err) {
    error.value = err.message || '永久删除失败'
  } finally {
    permanentDeleting.value = false
  }
}

function cancelPermanentDelete() {
  if (permanentDeleting.value) return
  pendingPermanentDeleteMail.value = null
  permanentDeleteConfirmOpen.value = false
}
function getComposeTargetLabel(targetKey = composeContactTarget.value) {
  return composeRecipientTargets.find((target) => target.key === targetKey)?.label || '收件人'
}

function addContactToCompose(email) {
  if (!email) return

  const target = composeContactTarget.value
  const currentEmails = splitEmails(composeForm[target])
  const exists = currentEmails.some((item) => item.toLowerCase() === email.toLowerCase())
  if (!exists) {
    currentEmails.push(email)
    composeForm[target] = currentEmails.join(', ')
    message.value = `已添加到${getComposeTargetLabel()}`
  } else {
    message.value = `该邮箱已在${getComposeTargetLabel()}中`
  }
}

function handleSelectContact(contact) {
  composeContactTarget.value = activeContactField.value || "to"
  addContactToCompose(contact.contactEmail)
  activeContactField.value = null
}

function toggleContactDropdown(field) {
  activeContactField.value = activeContactField.value === field ? null : field
}

function resetCompose() {
  composeForm.to = ''
  composeForm.cc = ''
  composeForm.bcc = ''
  composeForm.subject = ''
  composeForm.content = ''
  selectedFiles.value = []
  resetComposeFileInput()
  activeDraftId.value = null
  showComposeColorPalette.value = false
  showCc.value = false
  showBcc.value = false
  requestAnimationFrame(syncComposeEditorContent)
}

function formatFileSize(bytes = 0) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let size = bytes
  let unitIndex = 0
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex += 1
  }
  const precision = unitIndex === 0 || size >= 10 ? 0 : 1
  return size.toFixed(precision) + ' ' + units[unitIndex]
}

function resetComposeFileInput() {
  if (composeFileInputRef.value) {
    composeFileInputRef.value.value = ''
  }
}

function getSelectedFileKey(file) {
  return [file.name, file.size, file.lastModified].join(':')
}

function handleFileChange(event) {
  const nextFiles = Array.from(event.target.files || [])
  const existingKeys = new Set(selectedFiles.value.map(getSelectedFileKey))
  const addedFiles = nextFiles.filter((file) => !existingKeys.has(getSelectedFileKey(file)))
  selectedFiles.value = [...selectedFiles.value, ...addedFiles]
  resetComposeFileInput()
}

function removeSelectedFile(index) {
  selectedFiles.value = selectedFiles.value.filter((_, fileIndex) => fileIndex !== index)
  resetComposeFileInput()
}

function clearSelectedFiles() {
  selectedFiles.value = []
  resetComposeFileInput()
}

async function handleSend() {
  resetFeedback()
  updateComposeContentFromEditor()
  const to = splitEmails(composeForm.to)
  const cc = splitEmails(composeForm.cc)
  const bcc = splitEmails(composeForm.bcc)

  if (!to.length || !composeForm.subject.trim()) {
    error.value = '请填写收件人和主题'
    return
  }

  sending.value = true
  try {
    const result = await sendMail({
      to,
      cc,
      bcc,
      subject: composeForm.subject.trim(),
      content: composeForm.content.trim()
    })

    for (const file of selectedFiles.value) {
      await uploadAttachment(result.mailId, file)
    }

    if (activeDraftId.value) {
      removeDraftById(activeDraftId.value)
    }

    composeOpen.value = false
    resetCompose()
    message.value = '邮件发送成功'
    await loadFolder('sent')
    await refreshFolderCounts()
  } catch (err) {
    error.value = err.message || '发送失败'
  } finally {
    sending.value = false
  }
}

async function handleDownload(attachment) {
  resetFeedback()
  try {
    const blob = await downloadAttachment(attachment.attachmentId)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = attachment.fileName || 'attachment'
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (err) {
    error.value = err.message || '附件下载失败'
  }
}

function centerComposeModal() {
  const width = Math.min(680, window.innerWidth - 32)
  const height = Math.min(560, window.innerHeight - 32)
  modalPosition.x = Math.max(16, (window.innerWidth - width) / 2)
  modalPosition.y = Math.max(16, (window.innerHeight - height) / 2)
}

function clampModalPosition(x, y) {
  const modal = composeModalRef.value
  const width = modal?.offsetWidth || Math.min(680, window.innerWidth - 32)
  const height = modal?.offsetHeight || Math.min(560, window.innerHeight - 32)
  return {
    x: Math.min(Math.max(8, x), Math.max(8, window.innerWidth - width - 8)),
    y: Math.min(Math.max(8, y), Math.max(8, window.innerHeight - height - 8))
  }
}

function handleModalPointerMove(event) {
  if (!dragState.active) return
  const next = clampModalPosition(
    dragState.originX + event.clientX - dragState.startX,
    dragState.originY + event.clientY - dragState.startY
  )
  modalPosition.x = next.x
  modalPosition.y = next.y
}

function stopModalDrag() {
  dragState.active = false
  window.removeEventListener('pointermove', handleModalPointerMove)
  window.removeEventListener('pointerup', stopModalDrag)
}

function startModalDrag(event) {
  if (event.button !== 0) return
  dragState.active = true
  dragState.startX = event.clientX
  dragState.startY = event.clientY
  dragState.originX = modalPosition.x
  dragState.originY = modalPosition.y
  window.addEventListener('pointermove', handleModalPointerMove)
  window.addEventListener('pointerup', stopModalDrag)
}

function centerInsightsPanel() {
  const width = Math.min(860, window.innerWidth - 48)
  const height = Math.min(680, window.innerHeight - 48)
  insightsPanelPosition.x = Math.max(8, (window.innerWidth - width) / 2)
  insightsPanelPosition.y = Math.max(8, (window.innerHeight - height) / 2)
}

function clampInsightsPanelPosition(x, y) {
  const panel = insightsPanelRef.value
  const width = panel?.offsetWidth || Math.min(860, window.innerWidth - 48)
  const height = panel?.offsetHeight || Math.min(680, window.innerHeight - 48)
  return {
    x: Math.min(Math.max(8, x), Math.max(8, window.innerWidth - width - 8)),
    y: Math.min(Math.max(8, y), Math.max(8, window.innerHeight - height - 8))
  }
}

function handleInsightsPanelPointerMove(event) {
  if (!insightsDragState.active) return
  const next = clampInsightsPanelPosition(
    insightsDragState.originX + event.clientX - insightsDragState.startX,
    insightsDragState.originY + event.clientY - insightsDragState.startY
  )
  insightsPanelPosition.x = next.x
  insightsPanelPosition.y = next.y
}

function stopInsightsPanelDrag() {
  insightsDragState.active = false
  window.removeEventListener('pointermove', handleInsightsPanelPointerMove)
  window.removeEventListener('pointerup', stopInsightsPanelDrag)
}

function handlePaneResizeMove(event) {
  if (!paneResizeState.active) return
  const container = mailContentRef.value
  if (!container) return

  const rect = container.getBoundingClientRect()
  const minListWidth = 260
  const minDetailWidth = 360
  const maxListWidth = rect.width - minDetailWidth
  if (maxListWidth <= minListWidth) return

  const nextWidth = Math.min(
    Math.max(minListWidth, paneResizeState.startWidth + event.clientX - paneResizeState.startX),
    maxListWidth
  )
  mailListWidth.value = (nextWidth / rect.width) * 100
}

function stopPaneResize() {
  paneResizeState.active = false
  document.body.classList.remove("pane-resizing")
  window.removeEventListener("pointermove", handlePaneResizeMove)
  window.removeEventListener("pointerup", stopPaneResize)
}

function startPaneResize(event) {
  if (event.button !== 0 || window.matchMedia("(max-width: 920px)").matches) return
  const container = mailContentRef.value
  if (!container) return

  const rect = container.getBoundingClientRect()
  paneResizeState.active = true
  paneResizeState.startX = event.clientX
  paneResizeState.startWidth = (mailListWidth.value / 100) * rect.width
  document.body.classList.add("pane-resizing")
  window.addEventListener("pointermove", handlePaneResizeMove)
  window.addEventListener("pointerup", stopPaneResize)
  event.preventDefault()
}


function startInsightsPanelDrag(event) {
  if (event.button !== 0) return
  insightsDragState.active = true
  insightsDragState.startX = event.clientX
  insightsDragState.startY = event.clientY
  insightsDragState.originX = insightsPanelPosition.x
  insightsDragState.originY = insightsPanelPosition.y
  window.addEventListener('pointermove', handleInsightsPanelPointerMove)
  window.addEventListener('pointerup', stopInsightsPanelDrag)
}

function resetAccountScopedState() {
  activeFolderKey.value = 'inbox'
  selectedMailId.value = null
  selectedMail.value = null
  mails.value = []
  organized.value = false
  originalMailOrder.value = []
  organizedPriorityOrder.value = []
  searchKeyword.value = ''
  showFilters.value = false
  showUserPanel.value = false
  showAccountList.value = false
  composeOpen.value = false
  insightsOpen.value = false
  contactItems.value = []
  contactsLoaded.value = false
  notificationOverview.value = null
  onlineCount.value = null
  spamRecords.value = []
  spamMailIds.value = new Set()
  spamWarnings.value = []
  starredMailIds.value = new Set()
  folderCounts.value = {
    inbox: 0,
    starred: 0,
    drafts: 0,
    sent: 0,
    spam: 0,
    trash: 0
  }
  inboxUnreadCount.value = 0
  spamUnreadCount.value = 0
}

async function handleSwitchAccount(account) {
  if (!account?.id || isCurrentAccount(account)) return

  resetFeedback()
  try {
    disconnectNotificationStream()
    switchSavedAccount(account.id)
    await loadCurrentUser()
    resetAccountScopedState()
    await loadFolder('inbox')
    getStarredMails()
      .then(syncStarredIdsFromList)
      .catch(() => {})
    connectNotificationStream()
    message.value = '已切换到 ' + (authState.user?.email || account.email)
  } catch (err) {
    error.value = err.message || '账号切换失败，请重新登录'
    clearAuth()
    router.replace({ path: '/login', query: { redirect: '/dashboard' } })
  }
}

function handleAddAccountLogin() {
  resetFeedback()
  showUserPanel.value = false
  showAccountList.value = false
  disconnectNotificationStream()
  clearAuth()
  router.push({ path: '/login', query: { redirect: '/dashboard' } })
}

function closeUserPanel() {
  showUserPanel.value = false
  showAccountList.value = false
}

async function handleLogout() {
  await submitLogout()
  router.replace('/login')
}

function openPasswordNotice() {
  showUserPanel.value = false
  showAccountList.value = false
  passwordNoticeOpen.value = true
  message.value = '当前版本暂时不支持改密码'
}

function closePasswordNotice() {
  passwordNoticeOpen.value = false
}

watch(composeOpen, (open) => {
  if (open) {
    requestAnimationFrame(() => {
      centerComposeModal()
      syncComposeEditorContent()
    })
    loadComposeContacts()
  } else {
    stopModalDrag()
    composeContactTarget.value = 'to'
    showComposeColorPalette.value = false
  }
})

onMounted(() => {
  loadFolder('inbox')
  connectNotificationStream()
  getStarredMails()
    .then(syncStarredIdsFromList)
    .catch(() => {})
})

onBeforeUnmount(() => {
  stopModalDrag()
  stopInsightsPanelDrag()
  stopPaneResize()
  disconnectNotificationStream()
  clearFeedbackTimers()
  document.removeEventListener('click', closeUserPanelOnClickOutside)
})

// 点击 user area 之外关闭下拉
function closeUserPanelOnClickOutside(event) {
  if (showUserPanel.value) {
    const userArea = event.target.closest('.user-area')
    if (!userArea) {
      showUserPanel.value = false
    }
  }
}

watch(showUserPanel, (open) => {
  if (open) {
    setTimeout(() => document.addEventListener('click', closeUserPanelOnClickOutside), 0)
  } else {
    document.removeEventListener('click', closeUserPanelOnClickOutside)
  }
})
</script>

<template>
  <main class="dashboard-shell">
    <FolderSidebar
      :folders="folders"
      :active-folder-key="activeFolderKey"
      :inbox-unread-count="inboxUnreadCount"
      :folder-alert-counts="folderAlertCounts"
      @select-folder="selectFolder"
    />

    <section class="mail-area">
      <div class="topbar">
        <div class="topbar__search">
          <form class="toolbar" @submit.prevent="handleSearch">
            <div class="toolbar__searchbox">
              <svg class="toolbar__search-icon" viewBox="0 0 24 24" aria-hidden="true">
                <circle cx="11" cy="11" r="7"/>
                <path d="m16.5 16.5 4 4"/>
              </svg>
              <input v-model="searchKeyword" type="search" placeholder="搜索主题或正文" />
              <button
                class="toolbar__filter-icon"
                :class="{ active: showFilters }"
                type="button"
                aria-label="筛选"
                @click="showFilters = !showFilters"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M4 6h16"/>
                  <path d="M7 12h10"/>
                  <path d="M10 18h4"/>
                </svg>
              </button>
            </div>
            <button class="toolbar-button toolbar-button--search" type="submit">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <circle cx="11" cy="11" r="7"/>
                <path d="m16.5 16.5 4 4"/>
              </svg>
              搜索
            </button>
            <button
              class="toolbar-button"
              :class="{ active: insightsOpen }"
              type="button"
              @click="openInsights"
            >
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 3v3"/>
                <path d="M18.4 5.6 16.3 7.7"/>
                <path d="M21 12h-3"/>
                <path d="M5.6 5.6 7.7 7.7"/>
                <path d="M3 12h3"/>
                <path d="M9 16h6"/>
                <path d="M10 20h4"/>
                <path d="M8 12a4 4 0 1 1 8 0c0 1.5-.8 2.4-1.7 3H9.7C8.8 14.4 8 13.5 8 12Z"/>
              </svg>
              智能中心
            </button>
            <button v-if="activeFolderKey === 'inbox'" class="toolbar-button toolbar-button--organize" type="button" @click="handleOrganize">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="4" y1="9" x2="20" y2="9"/>
                <line x1="4" y1="15" x2="20" y2="15"/>
                <line x1="10" y1="3" x2="8" y2="21"/>
                <line x1="14" y1="3" x2="16" y2="21"/>
              </svg>
               {{ organized ? '恢复默认' : '整理' }}
            </button>
          </form>
          <form v-if="showFilters" class="filter-bar" @submit.prevent="applyFilters">
            <select v-model="filterForm.isRead">
              <option value="">全部状态</option>
              <option value="false">未读</option>
              <option value="true">已读</option>
            </select>
            <select v-model="filterForm.hasAttachment">
              <option value="">全部附件</option>
              <option value="true">有附件</option>
              <option value="false">无附件</option>
            </select>
            <select v-model="filterForm.timeRange">
              <option v-for="option in filterTimeOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
            <button class="primary-button" type="submit">应用</button>
          </form>
        </div>
        <div class="user-area">
          <button class="user-chip" type="button" @click="showUserPanel = !showUserPanel">
            <span class="user-avatar">{{ userInitial }}</span>
            <svg class="user-chip__chevron" viewBox="0 0 24 24" aria-hidden="true">
              <path d="m7 10 5 5 5-5" />
            </svg>
          </button>
          <div v-if="showUserPanel" class="user-dropdown">
            <div class="user-dropdown__topline">
              <span class="user-dropdown__account">{{ authState.user?.email || "未登录" }}</span>
              <button class="user-dropdown__close" type="button" aria-label="关闭账号面板" @click="closeUserPanel">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M6 6l12 12M18 6 6 18" />
                </svg>
              </button>
            </div>

            <div class="user-dropdown__hero">
              <span class="user-dropdown__avatar">{{ userInitial }}</span>
              <div class="user-dropdown__hero-copy">
                <strong>Hi, {{ authState.user?.username || authState.user?.email || '欢迎回来' }}</strong>
              </div>
            </div>

            <div class="user-dropdown__grid">
              <button class="user-dropdown__action user-dropdown__action--half" type="button" @click="handleAddAccountLogin">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M12 5v14M5 12h14" />
                </svg>
                <span>添加账号</span>
              </button>
              <button class="user-dropdown__action user-dropdown__action--half" type="button" @click="openPasswordNotice">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M7 10V8a5 5 0 0 1 10 0v2" />
                  <rect x="5" y="10" width="14" height="10" rx="2" />
                  <path d="M12 14v2" />
                </svg>
                <span>修改密码</span>
              </button>
            </div>

            <div class="user-dropdown__footer">
              <div class="user-dropdown__footer-row">
                <button class="user-dropdown__switch" type="button" @click="showAccountList = !showAccountList">
                  <span class="user-dropdown__switch-left">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M6 12h12" />
                      <path d="M13 5l7 7-7 7" />
                    </svg>
                    <span>切换账号</span>
                  </span>
                  <svg class="user-dropdown__chevron" :class="{ open: showAccountList }" viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M6 9l6 6 6-6" />
                  </svg>
                </button>

                <button class="user-dropdown__logout" type="button" @click="handleLogout">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M10 17H6a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h4" />
                    <path d="M15 7l5 5-5 5" />
                    <path d="M20 12H9" />
                  </svg>
                  <span>退出登录</span>
                </button>
              </div>

              <div v-if="showAccountList" class="user-account-switcher">
                <p v-if="!savedAccounts.length" class="user-account-switcher__empty">登录其他账号后会显示在这里</p>
                <button
                  v-for="account in savedAccounts"
                  :key="account.id"
                  class="user-account-option"
                  :class="{ active: isCurrentAccount(account) }"
                  type="button"
                  :disabled="isCurrentAccount(account)"
                  @click="handleSwitchAccount(account)"
                >
                  <span class="user-account-option__avatar">{{ getAccountInitial(account) }}</span>
                  <span class="user-account-option__meta">
                    <strong>{{ account.username || account.email }}</strong>
                    <span>{{ account.email }}</span>
                  </span>
                  <small v-if="isCurrentAccount(account)">当前</small>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <ToastStack :error="error" :message="message" :info="newMailToast" />

      <div class="mail-content" ref="mailContentRef" :style="mailContentStyle">
        <section class="mail-list" :aria-label="`${activeFolder.name}列表`">
          <div v-if="loadingList" class="empty-state">正在加载邮件</div>
          <div v-else-if="!mails.length" class="empty-state">暂无邮件</div>
          <template v-else>
            <button
              v-for="mail in mails"
              :key="mail.mailId"
              class="mail-row"
              :class="{ active: selectedMailId === mail.mailId, unread: !mail.isRead }"
              type="button"
              @click="selectMail(mail)"
            >
              <span class="sender-avatar">{{ (activeFolderKey === "sent" ? "我" : activeFolderKey === "drafts" ? "草" : (mail.senderName || "?"))[0] }}</span>
              <span class="mail-row-inner">
                <span class="mail-row-primary">
                  <span class="sender-name">{{ activeFolderKey === "sent" ? "我" : activeFolderKey === "drafts" ? "草稿" : mail.senderName }}</span>
                  <span class="mail-subject">{{ mail.subject }}</span>
                </span>
                <span class="mail-row-meta">
                  <span v-if="mail.hasAttachment" class="mail-tag">📎</span>
                  <span v-if="shouldShowMailStar(mail)" class="read-status-tag starred-tag"> ★ </span>
                  <span v-if="activeFolderKey === 'inbox'" class="read-status-tag" :class="mail.isRead ? 'read-tag' : 'unread-tag'">{{ mail.isRead ? '已读' : '未读' }}</span>
                  <span v-if="activeFolderKey === 'spam'" class="read-status-tag spam-tag">垃圾</span>
                  <span v-if="activeFolderKey === 'drafts'" class="read-status-tag draft-tag">草稿</span>
                  <small>{{ formatTime(mail.time) }}</small>
                </span>
              </span>
            </button>
          </template>
        </section>

        <div
          class="mail-pane-resizer"
          role="separator"
          aria-label="调整邮件列表和详情宽度"
          aria-orientation="vertical"
          @pointerdown="startPaneResize"
        ></div>

        <article class="mail-detail">
          <div v-if="loadingDetail" class="empty-state">正在加载详情</div>
          <div v-else-if="!selectedMail" class="mail-detail-placeholder" aria-label="未选择邮件">
            <svg viewBox="0 0 40 40" aria-hidden="true" focusable="false">
              <rect class="placeholder-icon-bg" width="40" height="40" />
              <rect class="placeholder-envelope-body" x="5" y="12" width="30" height="25" rx="3" />
              <path class="placeholder-envelope-fold" d="M10 17l10 6.5L30 17" />
            </svg>
          </div>
          <template v-else>
            <div class="detail-heading">
              <h2>{{ selectedMail.subject || '(无主题)' }}</h2>
              <div class="detail-actions">
                <button
                  v-if="activeFolderKey === 'drafts'"
                  class="ghost-button"
                  type="button"
                  @click="openDraftCompose(selectedMail)"
                >
                  继续编辑
                </button>
                <button
                  v-if="activeFolderKey === 'drafts'"
                  class="ghost-button danger"
                  type="button"
                  @click="handleDraftDelete"
                >
                  删除草稿
                </button>
                <button
                  v-if="activeFolderKey !== 'drafts'"
                  class="ghost-button"
                  type="button"
                  :disabled="Boolean(mailAiLoadingAction)"
                  @click="handleMailAiAction('translate')"
                >
                  {{ mailAiLoadingAction === 'translate' ? '翻译中...' : '翻译' }}
                </button>
                <button
                  v-if="activeFolderKey !== 'drafts'"
                  class="ghost-button"
                  type="button"
                  :disabled="Boolean(mailAiLoadingAction)"
                  @click="handleMailAiAction('summary')"
                >
                  {{ mailAiLoadingAction === 'summary' ? '总结中...' : '总结' }}
                </button>
                <button
                  v-if="activeFolderKey === 'inbox'"
                  class="ghost-button"
                  type="button"
                  @click="handleReadToggle(!(selectedMail.isRead ?? selectedMail.read))"
                >
                  {{ selectedMail.isRead ?? selectedMail.read ? '标为未读' : '标为已读' }}
                </button>
                <button
                  v-if="activeFolderKey === 'inbox'"
                  class="ghost-button danger"
                  type="button"
                  :disabled="reportingSpam"
                  @click="handleReportSpam"
                >
                  {{ reportingSpam ? '标记中...' : '标为垃圾邮件' }}
                </button>
                <button
                  v-if="activeFolderKey === 'spam'"
                  class="ghost-button"
                  type="button"
                  :disabled="reportingNotSpam"
                  @click="handleReportNotSpam"
                >
                  {{ reportingNotSpam ? '处理中...' : '不是垃圾邮件' }}
                </button>
                <button
                  v-if="activeFolderKey === 'spam' && currentSenderBlacklistContact"
                  class="ghost-button"
                  type="button"
                  :disabled="removingSenderFromBlacklist"
                  @click="handleRemoveSenderFromBlacklist"
                >
                  {{ removingSenderFromBlacklist ? '移出中...' : '移出黑名单' }}
                </button>
                <button
                  v-if="starFeatureVisible"
                  class="star-button"
                  :class="{ active: selectedMailStarred }"
                  type="button"
                  :title="selectedMailStarred ? '取消星标' : '标记为星标'"
                  @click="handleStarToggle"
                >
                  <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                    <path d="m12 2.8 2.8 5.7 6.3.9-4.6 4.5 1.1 6.3-5.6-3-5.6 3 1.1-6.3-4.6-4.5 6.3-.9L12 2.8Z" />
                  </svg>
                </button>
                <button v-if="!['trash', 'drafts'].includes(activeFolderKey)" class="ghost-button danger" type="button" @click="handleDelete">删除</button>
                <button v-if="activeFolderKey === 'trash'" class="ghost-button" type="button" @click="handleRestore">恢复</button>
                <button v-if="activeFolderKey === 'trash'" class="ghost-button danger" type="button" @click="handlePermanentDelete">永久删除</button>
              </div>
            </div>

            <div v-if="activeFolderKey === 'drafts'" class="detail-meta">
              <span>草稿保存于：{{ formatTime(selectedMail.updatedAt || selectedMail.sendTime) }}</span>
              <span>{{ selectedMail.to?.length ? '待发送' : '未填写收件人' }}</span>
            </div>
            <div v-else class="detail-meta">
              <span>来自：{{ selectedMail.senderName }} &lt;{{ selectedMail.senderEmail }}&gt;</span>
              <span>{{ formatTime(selectedMail.sendTime) }}</span>
            </div>

            <div v-if="selectedMail.spamFlag" class="spam-tag-banner">
              <strong>垃圾邮件</strong>
              <span v-if="selectedMail.spamType"> · 类型：{{ selectedMail.spamType }}</span>
              <span v-if="selectedMail.spamReason"> · 原因：{{ selectedMail.spamReason }}</span>
            </div>

            <div class="recipient-lines">
              <span>收件人：{{ (selectedMail.to || []).join(', ') || '-' }}</span>
              <span v-if="selectedMail.cc?.length">抄送：{{ selectedMail.cc.join(', ') }}</span>
            </div>

            <section v-if="mailAiResult.content" class="mail-ai-panel">
              <header>
                <strong>{{ mailAiResult.title }}</strong>
                <button type="button" @click="resetMailAiResult">关闭</button>
              </header>
              <p>{{ mailAiResult.content }}</p>
            </section>

            <div class="mail-body" v-html="renderMailContent(selectedMail.content)"></div>

            <section v-if="selectedMail.attachments?.length" class="attachment-list">
              <h3>附件</h3>
              <button
                v-for="attachment in selectedMail.attachments"
                :key="attachment.attachmentId"
                class="attachment-item"
                type="button"
                @click="handleDownload(attachment)"
              >
                {{ attachment.fileName }}
              </button>
            </section>
          </template>
        </article>
      </div>

    <div v-if="insightsOpen" class="modal-backdrop insights-backdrop" @click.self="closeInsights">
      <section
        ref="insightsPanelRef"
        class="insights-panel draggable-modal"
        :style="insightsPanelStyle"
        aria-label="智能中心"
      >
        <header class="insights-panel__header draggable-modal__header" @pointerdown="startInsightsPanelDrag">
          <div>
            <h2>智能中心</h2>
            <p>联系人名单、通知状态和智能识别结果</p>
          </div>
          <div class="insights-panel__actions" @pointerdown.stop>
            <button class="ghost-button" type="button" :disabled="loadingInsights" @click="loadInsights">刷新</button>
            <button class="ghost-button" type="button" @click="closeInsights">关闭</button>
          </div>
        </header>

        <div v-if="loadingInsights" class="empty-state insights-loading">正在加载智能数据</div>
        <div v-else class="insights-grid">
          <section class="insights-section insights-section--contacts">
            <header class="insights-section__header">
              <h3>联系人名单</h3>
              <span>{{ contactItems.length }} 条</span>
            </header>
            <form class="contact-form" @submit.prevent="handleAddContact">
              <input v-model="contactForm.contactEmail" type="email" placeholder="联系人邮箱" />
              <select v-model="contactForm.listType">
                <option v-for="option in contactTypeOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
              </select>
              <button class="primary-button" type="submit" :disabled="submittingContact">
                {{ submittingContact ? '添加中' : '添加' }}
              </button>
            </form>
            <div class="contact-groups">
              <div v-for="group in contactGroups" :key="group.value" class="contact-group">
                <div class="contact-group__title">
                  <strong>{{ group.label }}</strong>
                  <span>{{ group.items.length }}</span>
                </div>
                <p v-if="!group.items.length" class="inline-empty">暂无联系人</p>
                <ul v-else class="contact-list">
                  <li v-for="item in group.items" :key="item.id || item.contactEmail" class="contact-item">
                    <span>{{ item.contactEmail }}</span>
                    <button type="button" @click="handleRemoveContact(item.id)">移除</button>
                  </li>
                </ul>
              </div>
            </div>
          </section>

          <section class="insights-section">
            <header class="insights-section__header">
              <h3>通知状态</h3>
              <span>{{ onlineCount ?? '-' }} 在线</span>
            </header>
            <div class="insight-stats">
              <strong>{{ notificationUnreadCount }}</strong>
              <span>未读通知</span>
            </div>
            <div class="compact-list">
              <p v-if="!recentUnreadItems.length" class="inline-empty">暂无未读通知</p>
              <div v-for="item in recentUnreadItems.slice(0, 4)" :key="item.mailId || item.id || item.subject" class="compact-list__row">
                <strong>{{ item.subject || '(无主题)' }}</strong>
                <span>{{ item.senderName || item.senderEmail || '' }}</span>
              </div>
            </div>
          </section>

          <section class="insights-section">
            <header class="insights-section__header">
              <h3>垃圾识别</h3>
              <span>{{ spamMailIds.size }} 封</span>
            </header>
            <div class="compact-list compact-list--scroll">
              <p v-if="!spamRecords.length" class="inline-empty">暂无垃圾邮件记录</p>
              <div v-for="record in spamRecords.slice(0, 6)" :key="record.id" class="compact-list__row spam-record-row">
                <strong>邮件 #{{ record.mailId || '-' }}</strong>
                <span>{{ record.reason }}</span>
              </div>
            </div>
          </section>

          <section class="insights-section">
            <header class="insights-section__header">
              <h3>垃圾邮件警告</h3>
              <span>{{ spamWarnings.length }} 待处理</span>
            </header>
            <div class="compact-list compact-list--scroll spam-warning-list">
              <p v-if="!spamWarnings.length" class="inline-empty">暂无待处理警告</p>
              <div v-for="warning in spamWarnings" :key="warning.id" class="compact-list__row spam-warning-row">
                <div class="spam-warning-row__content">
                  <strong>{{ warning.senderEmail }}</strong>
                  <span>邮件 #{{ warning.mailId || '-' }} · {{ formatTime(warning.createdAt) || '待处理' }}</span>
                </div>
                <div class="spam-warning-row__actions">
                  <button
                    class="ghost-button danger"
                    type="button"
                    :disabled="Boolean(warningActionKey)"
                    @click="handleBlockWarning(warning)"
                  >
                    {{ warningActionKey === 'block-' + warning.id ? '拉黑中...' : '拉黑' }}
                  </button>
                  <button
                    class="ghost-button"
                    type="button"
                    :disabled="Boolean(warningActionKey)"
                    @click="handleIgnoreWarning(warning)"
                  >
                    {{ warningActionKey === 'ignore-' + warning.id ? '忽略中...' : '忽略' }}
                  </button>
                </div>
              </div>
            </div>
          </section>
        </div>
      </section>
    </div>

    <button class="compose-fab" type="button" title="写邮件" @click="openNewCompose">
      <span class="compose-fab__icon" aria-hidden="true">
        <svg viewBox="0 0 40 40" focusable="false">
          <path d="M8.8 29.2 11.6 21.4 27.1 5.9a3.6 3.6 0 0 1 5.1 5.1L16.6 26.4 8.8 29.2Z" />
          <path d="M24.7 8.3 29.7 13.3" />
          <path d="M11.6 21.4 16.6 26.4" />
        </svg>
      </span>
      <span class="compose-fab__label">写邮件</span>
    </button>

    <div v-if="composeOpen" class="modal-backdrop" @click.self="closeComposeModal">
      <section
        ref="composeModalRef"
        class="compose-modal draggable-modal"
        :class="{ 'compose-modal--zoomed': composeZoomed }"
        :style="composeModalStyle"
        aria-label="写邮件"
      >
        <header class="draggable-modal__header" @pointerdown="startModalDrag">
          <h2>{{ activeDraftId ? '编辑草稿' : '写邮件' }}</h2>
          <div class="compose-header-actions">
            <button
              class="icon-button compose-zoom-button"
              type="button"
              :title="composeZoomed ? '还原尺寸' : '最大化'"
              :aria-label="composeZoomed ? '还原尺寸' : '最大化'"
              @pointerdown.stop
              @click="composeZoomed = !composeZoomed"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path v-if="!composeZoomed" d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7" />
                <path v-else d="M8 3v5H3M21 8h-5V3M16 21v-5h5M3 16h5v5" />
              </svg>
            </button>
            <button
              class="icon-button compose-close-button"
              type="button"
              title="关闭"
              aria-label="关闭写邮件"
              @pointerdown.stop
              @click="closeComposeModal"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M18 6 6 18" />
                <path d="m6 6 12 12" />
              </svg>
            </button>
          </div>
        </header>

        <form class="compose-form" @submit.prevent="handleSend">
          <div class="compose-recipient-field">
            <div class="compose-input-wrapper">
            <input v-model="composeForm.to" type="text" placeholder="收件人邮箱，多个用逗号或空格分隔" />
              <div class="compose-input-addons">
                <button type="button" class="compose-inner-addon" :class="{ active: showCc }" @click="showCc = !showCc" title="添加抄送">CC</button>
                <button type="button" class="compose-inner-addon" :class="{ active: showBcc }" @click="showBcc = !showBcc" title="添加密送">BCC</button>
                <button type="button" class="compose-inner-addon compose-inner-addon--plus" @click="toggleContactDropdown('to')">+</button>
              </div>
            </div>
          </div>
          <div v-if="showCc" class="compose-input-wrapper">
            <input v-model="composeForm.cc" type="text" placeholder="抄送邮箱，可选" />
            <div class="compose-input-addons">
              <button type="button" class="compose-inner-addon compose-inner-addon--plus" @click="toggleContactDropdown('cc')">+</button>
            </div>
          </div>
          <div v-if="showBcc" class="compose-input-wrapper">
            <input v-model="composeForm.bcc" type="text" placeholder="密送邮箱，可选" />
            <div class="compose-input-addons">
              <button type="button" class="compose-inner-addon compose-inner-addon--plus" @click="toggleContactDropdown('bcc')">+</button>
            </div>
          </div>
          <div class="compose-contact-wrapper">
            <div v-if="activeContactField" class="compose-contact-dropdown">
              <p v-if="!composeContactOptions.length" class="compose-contact-empty">暂无联系人，可先在智能中心添加</p>
              <template v-else>
                <button
                  v-for="contact in composeContactOptions"
                  :key="contact.id || contact.contactEmail"
                  class="compose-contact-option"
                  type="button"
                  @click="handleSelectContact(contact)"
                >
                  <span class="compose-contact-avatar">{{ contact.contactEmail[0]?.toUpperCase() }}</span>
                  <span class="compose-contact-text">
                    <strong>{{ contact.contactEmail }}</strong>
                    <small>{{ contactTypeLabels[contact.listType] || contact.listType }}</small>
                  </span>
                </button>
              </template>
            </div>
          </div>
          <div class="compose-subject-wrapper">
          <input v-model="composeForm.subject" type="text" placeholder="主题" />
          </div>
          <div class="compose-editor-wrapper">
            <div
            ref="composeEditorRef"
            class="compose-rich-editor"
            contenteditable="true"
            data-placeholder="正文"
            role="textbox"
            aria-multiline="true"
            aria-label="正文"
            @input="updateComposeContentFromEditor"
            @keyup="saveComposeSelection(); checkFormatStates()"
            @mouseup="saveComposeSelection(); checkFormatStates()"
            @focus="saveComposeSelection"
          ></div>
            <section class="compose-format-toolbar" aria-label="正文格式工具栏">
            <select
              v-model="composeFormat.fontFamily"
              class="compose-format-select"
              aria-label="字体"
              @change="applyComposeFont"
              @mousedown="saveComposeSelection"
            >
              <option v-for="font in composeFontOptions" :key="font.value" :value="font.value">{{ font.label }}</option>
            </select>
            <select
              v-model="composeFormat.fontSize"
              class="compose-format-select compose-format-select--size"
              aria-label="字号"
              @change="applyComposeFontSize"
              @mousedown="saveComposeSelection"
            >
              <option v-for="size in composeFontSizeOptions" :key="size" :value="size">{{ size }}</option>
            </select>
            <button
            class="compose-format-button compose-format-button--bold"
              :class="{ active: formatBoldActive }"
              type="button"
              title="加粗"
              aria-label="加粗"
              @mousedown.prevent="saveComposeSelection"
              @click="toggleComposeBold"
            >
              B
            </button>
            <div class="compose-color-picker">
              <button
                class="compose-format-button compose-color-trigger"
                type="button"
                title="字体颜色"
                aria-label="字体颜色"
                @mousedown.prevent="saveComposeSelection"
                @click="showComposeColorPalette = !showComposeColorPalette"
              >
                <span>A</span>
                <i :style="{ backgroundColor: composeFormat.color }"></i>
              </button>
              <div v-if="showComposeColorPalette" class="compose-color-panel">
                <button
                  v-for="color in composeColorOptions"
                  :key="color"
                  class="compose-color-swatch"
                  :class="{ active: color === composeFormat.color }"
                  type="button"
                  :style="{ backgroundColor: color }"
                  :aria-label="'选择颜色 ' + color"
                 
                  @click="applyComposeColor(color)"
                ></button>

              </div>
            </div>
            <span class="compose-format-separator"></span>
            <button
            class="compose-format-button compose-format-button--icon"
            type="button"
            title="斜体"
            aria-label="斜体"
            @mousedown.prevent="saveComposeSelection"
              :class="{ active: formatItalicActive }"
            @click="toggleComposeItalic"
            >
            <svg viewBox="0 0 24 24" aria-hidden="true" class="compose-format-icon">
            <line x1="10" y1="4" x2="17" y2="4"/><line x1="6" y1="20" x2="13" y2="20"/><line x1="14" y1="4" x2="10" y2="20" stroke-width="1.5"/>
            </svg>
            </button>
            <button
            class="compose-format-button compose-format-button--icon"
            type="button"
            title="下划线"
            aria-label="下划线"
            @mousedown.prevent="saveComposeSelection"
              :class="{ active: formatUnderlineActive }"
            @click="toggleComposeUnderline"
            >
            <svg viewBox="0 0 24 24" aria-hidden="true" class="compose-format-icon">
            <path d="M6 3v7a6 6 0 0 0 12 0V3"/><line x1="4" y1="21" x2="20" y2="21"/>
            </svg>
            </button>
            <span class="compose-format-separator"></span>
            <button
            class="compose-format-button compose-format-button--icon"
            type="button"
            title="无序列表"
            aria-label="无序列表"
            @mousedown.prevent="saveComposeSelection"
            @click="insertComposeUnorderedList"
            >
            <svg viewBox="0 0 24 24" aria-hidden="true" class="compose-format-icon">
            <line x1="8" y1="6" x2="20" y2="6"/><line x1="8" y1="12" x2="20" y2="12"/><line x1="8" y1="18" x2="20" y2="18"/><circle cx="4" cy="6" r="1.5"/><circle cx="4" cy="12" r="1.5"/><circle cx="4" cy="18" r="1.5"/>
            </svg>
            </button>
            <button
            class="compose-format-button compose-format-button--icon"
            type="button"
            title="有序列表"
            aria-label="有序列表"
            @mousedown.prevent="saveComposeSelection"
            @click="insertComposeOrderedList"
            >
            <svg viewBox="0 0 24 24" aria-hidden="true" class="compose-format-icon">
            <line x1="12" y1="6" x2="20" y2="6"/><line x1="12" y1="12" x2="20" y2="12"/><line x1="12" y1="18" x2="20" y2="18"/><text x="4" y="9" font-size="9" font-weight="bold" fill="currentColor">1</text><text x="4" y="15" font-size="9" font-weight="bold" fill="currentColor">2</text><text x="4" y="21" font-size="9" font-weight="bold" fill="currentColor">3</text>
            </svg>
            </button>
            <button
            class="compose-format-button compose-format-button--icon"
            type="button"
            title="引用"
            aria-label="引用"
            @mousedown.prevent="saveComposeSelection"
            @click="insertComposeBlockquote"
            >
            <svg viewBox="0 0 24 24" aria-hidden="true" class="compose-format-icon">
            <path d="M6 4h13M6 8h13M6 12h10M8 16h10M6 20h13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" fill="none"/><line x1="3" y1="4" x2="3" y2="22" stroke="currentColor" stroke-width="2" stroke-linecap="round" opacity="0.4"/>
            </svg>
            </button>
            <span class="compose-format-separator"></span>
            <button
              class="compose-format-button compose-format-button--icon"
              type="button"
              title="左对齐"
              aria-label="左对齐"
              :class="{ active: formatAlignment === 'left' }"
              @mousedown.prevent="saveComposeSelection"
              @click="setComposeAlignment('left')"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" class="compose-format-icon">
                <line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="10" x2="15" y2="10"/><line x1="3" y1="14" x2="21" y2="14"/><line x1="3" y1="18" x2="12" y2="18"/>
              </svg>
            </button>
            <button
              class="compose-format-button compose-format-button--icon"
              type="button"
              title="居中"
              aria-label="居中"
              :class="{ active: formatAlignment === 'center' }"
              @mousedown.prevent="saveComposeSelection"
              @click="setComposeAlignment('center')"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" class="compose-format-icon">
                <line x1="6" y1="6" x2="18" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/><line x1="6" y1="14" x2="18" y2="14"/><line x1="3" y1="18" x2="21" y2="18"/>
              </svg>
            </button>
            <button
              class="compose-format-button compose-format-button--icon"
              type="button"
              title="右对齐"
              aria-label="右对齐"
              :class="{ active: formatAlignment === 'right' }"
              @mousedown.prevent="saveComposeSelection"
              @click="setComposeAlignment('right')"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" class="compose-format-icon">
                <line x1="3" y1="6" x2="21" y2="6"/><line x1="9" y1="10" x2="21" y2="10"/><line x1="3" y1="14" x2="21" y2="14"/><line x1="12" y1="18" x2="21" y2="18"/>
              </svg>
            </button>
            <button
              class="compose-format-button compose-format-button--icon"
              type="button"
              title="两端对齐"
              aria-label="两端对齐"
              :class="{ active: formatAlignment === 'full' }"
              @mousedown.prevent="saveComposeSelection"
              @click="setComposeAlignment('full')"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" class="compose-format-icon">
                <line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/><line x1="3" y1="14" x2="21" y2="14"/><line x1="3" y1="18" x2="21" y2="18"/>
              </svg>
            </button>
            </section>
          </div>

          <section v-if="selectedFiles.length" class="compose-attachment-preview" aria-label="已添加附件">
            <div class="compose-attachment-preview__header">
              <span>已添加 {{ selectedFiles.length }} 个附件</span>
              <button type="button" @click="clearSelectedFiles">清空</button>
            </div>
            <ul class="compose-attachment-list">
              <li v-for="(file, index) in selectedFiles" :key="index">
                <span class="compose-attachment-icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24">
                    <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/>
                  </svg>
                </span>
                <span class="compose-attachment-name" :title="file.name">{{ file.name }}</span>
                <span class="compose-attachment-size">{{ formatFileSize(file.size) }}</span>
                <button type="button" class="compose-attachment-remove" aria-label="移除附件" @click="removeSelectedFile(index)">移除</button>
              </li>
            </ul>
          </section>

          <div class="compose-actions">
            <button class="ghost-button" type="button" @click="handleSaveDraft">保存草稿</button>
            <label class="compose-attach-label" :class="{ active: selectedFiles.length }" title="添加附件">
              <svg viewBox="0 0 24 24" aria-hidden="true" width="18" height="18">
                <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <input ref="composeFileInputRef" type="file" multiple @change="handleFileChange" hidden />
            </label>
            <button class="primary-button" type="submit" :disabled="sending">
              {{ sending ? '发送中...' : '发送' }}
            </button>
          </div>
        </form>
      </section>
    </div>

    <div v-if="deleteConfirmOpen" class="modal-backdrop confirm-backdrop" @click.self="cancelDelete">
      <section class="confirm-dialog" aria-label="确认删除邮件">
        <div class="confirm-dialog__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 6h18"/>
            <path d="M8 6V4h8v2"/>
            <path d="M6 6l1 14h10l1-14"/>
            <path d="M10 11v5"/>
            <path d="M14 11v5"/>
          </svg>
        </div>
        <h2>确认删除这封邮件？</h2>
        <p>删除后邮件将移至回收站，确认前不会进行任何操作。</p>
        <div class="confirm-dialog__actions">
          <button class="ghost-button" type="button" :disabled="deleting" @click="cancelDelete">取消</button>
          <button class="danger-button" type="button" :disabled="deleting" @click="confirmDelete">
            {{ deleting ? '删除中...' : '确认删除' }}
          </button>
        </div>
      </section>
    </div>

    <div v-if="restoreConfirmOpen" class="modal-backdrop confirm-backdrop" @click.self="cancelRestore">
      <section class="confirm-dialog" aria-label="确认恢复邮件">
        <div class="confirm-dialog__icon confirm-dialog__icon--restore" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 12a9 9 0 1 0 3-6.7"/>
            <path d="M3 4v6h6"/>
            <path d="M12 8v5l3 2"/>
          </svg>
        </div>
        <h2>确认恢复这封邮件？</h2>
        <p>恢复后邮件将从回收站移回邮箱列表。</p>
        <div class="confirm-dialog__actions">
          <button class="ghost-button" type="button" :disabled="restoring" @click="cancelRestore">取消</button>
          <button class="restore-confirm-button" type="button" :disabled="restoring" @click="confirmRestore">
            {{ restoring ? '恢复中...' : '确认恢复' }}
          </button>
        </div>
      </section>
    </div>

    <div v-if="permanentDeleteConfirmOpen" class="modal-backdrop confirm-backdrop" @click.self="cancelPermanentDelete">
      <section class="confirm-dialog" aria-label="确认永久删除邮件">
        <div class="confirm-dialog__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 6h18"/>
            <path d="M8 6V4h8v2"/>
            <path d="M6 6l1 14h10l1-14"/>
            <path d="M10 11v5"/>
            <path d="M14 11v5"/>
          </svg>
        </div>
        <h2>确认永久删除？</h2>
        <p>该操作无法撤销，邮件和相关记录将从回收站中移除。</p>
        <div class="confirm-dialog__actions">
          <button class="ghost-button" type="button" :disabled="permanentDeleting" @click="cancelPermanentDelete">取消</button>
          <button class="danger-button" type="button" :disabled="permanentDeleting" @click="confirmPermanentDelete">
            {{ permanentDeleting ? '删除中...' : '永久删除' }}
          </button>
        </div>
      </section>
    </div>

    <div v-if="passwordNoticeOpen" class="modal-backdrop confirm-backdrop" @click.self="closePasswordNotice">
      <section class="confirm-dialog" aria-label="修改密码暂不支持">
        <div class="confirm-dialog__icon confirm-dialog__icon--info" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="9"/>
            <path d="M12 10v6"/>
            <path d="M12 7h.01"/>
          </svg>
        </div>
        <h2>暂不支持修改密码</h2>
        <p>当前版本暂时不支持改密码。</p>
        <div class="confirm-dialog__actions">
          <button class="primary-button" type="button" @click="closePasswordNotice">我知道了</button>
        </div>
      </section>
    </div>

    <div v-if="unstarConfirmOpen" class="modal-backdrop confirm-backdrop" @click.self="cancelUnstar">
      <section class="confirm-dialog confirm-dialog--star" aria-label="确认取消星标">
        <div class="confirm-dialog__icon confirm-dialog__icon--star" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor">
            <path d="m12 2.8 2.8 5.7 6.3.9-4.6 4.5 1.1 6.3-5.6-3-5.6 3 1.1-6.3-4.6-4.5 6.3-.9L12 2.8Z" />
          </svg>
        </div>
        <h2>确认取消星标？</h2>
        <p>确认后会从星标邮件列表中移除这封邮件。</p>
        <div class="confirm-dialog__actions">
          <button class="ghost-button" type="button" @click="cancelUnstar">取消</button>
          <button class="star-confirm-button" type="button" @click="confirmUnstar">确认取消</button>
        </div>
      </section>
    </div>
  </section>
  </main>
</template>
