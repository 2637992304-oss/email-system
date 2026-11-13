<script setup>
defineProps({
  folders: {
    type: Array,
    required: true
  },
  activeFolderKey: {
    type: String,
    required: true
  },
  inboxUnreadCount: {
    type: Number,
    default: 0
  },
  folderAlertCounts: {
    type: Object,
    default: () => ({})
  }
})

defineEmits(['select-folder'])

const folderIcons = {
  inbox: [
    'M4.5 6.5h15l1.5 7.5v3.5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V14l1.5-7.5Z',
    'M3.5 14h4l1.5 2h6l1.5-2h4'
  ],
  starred: [
    'm12 3 2.7 5.5 6 .9-4.3 4.2 1 6-5.4-2.9-5.4 2.9 1-6-4.3-4.2 6-.9L12 3Z'
  ],
  drafts: [
    'M6 3.5h8l4 4V20a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 5 20V5A1.5 1.5 0 0 1 6.5 3.5Z',
    'M14 3.5V8h4',
    'M8 12h8',
    'M8 16h5'
  ],
  sent: [
    'M3.5 11.5 20.5 4l-7.2 16-2.7-6.6-7.1-1.9Z',
    'M10.6 13.4 20.5 4'
  ],
  spam: [
    'M8 3.5h8L20.5 8v8L16 20.5H8L3.5 16V8L8 3.5Z',
    'M12 7.5v5.5',
    'M12 16.5h.01'
  ],
  trash: [
    'M4 6.5h16',
    'M9 6.5V4.5h6v2',
    'M6.5 6.5 7.5 20h9l1-13.5',
    'M10 10.5v5.5',
    'M14 10.5v5.5'
  ]
}

function getFolderIcon(folderKey) {
  return folderIcons[folderKey] || folderIcons.inbox
}
</script>

<template>
  <aside class="sidebar">
    <div class="mail-product">
      <div class="mail-product__icon" aria-hidden="true">
        <svg viewBox="0 0 32 32" width="24" height="24" fill="none">
          <path d="M5.5 9.5h21v13h-21z" />
          <path d="M6.5 10.5 16 17l9.5-6.5" />
          <path d="M10 7h12" />
          <path d="M12 25h8" />
          <circle cx="25" cy="8" r="2.5" />
        </svg>
      </div>
      <div>
        <strong>Mail Studio</strong>
        <small>Workspace</small>
      </div>
    </div>

    <nav class="folder-nav" aria-label="邮件文件夹">
      <button
        v-for="folder in folders"
        :key="folder.key"
        class="folder-item"
        :class="{ active: folder.key === activeFolderKey }"
        type="button"
        @click="$emit('select-folder', folder)"
      >
        <span class="folder-label">
          <span class="folder-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path v-for="path in getFolderIcon(folder.key)" :key="path" :d="path" />
            </svg>
          </span>
          <span class="folder-name">{{ folder.name }}</span>
          <span v-if="(folderAlertCounts[folder.key] ?? (folder.key === 'inbox' ? inboxUnreadCount : 0)) > 0" class="unread-dot"></span>
        </span>
        <span v-if="folder.count !== null" class="folder-count">{{ folder.count }}</span>
      </button>
    </nav>
  </aside>
</template>
