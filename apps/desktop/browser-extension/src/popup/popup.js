// Flowww extension popup UI.

const STATUS_TEXT = document.getElementById('status-text');
const STATUS_EL = document.getElementById('status');
const STAGE_PAGE_BTN = document.getElementById('stagePage');
const STAGE_SELECTION_BTN = document.getElementById('stageSelection');
const RECENT_STAGED = document.getElementById('recentStaged');

let connected = false;

function updateStatus(isConnected) {
  connected = isConnected;
  STATUS_TEXT.textContent = isConnected ? 'Connected' : 'Disconnected';
  STATUS_EL.className = 'status ' + (isConnected ? 'connected' : 'disconnected');
  STAGE_PAGE_BTN.disabled = !isConnected;
  STAGE_SELECTION_BTN.disabled = !isConnected;
}

async function sendToBackground(message) {
  return new Promise((resolve, reject) => {
    chrome.runtime.sendMessage(message, (response) => {
      if (chrome.runtime.lastError) {
        reject(new Error(chrome.runtime.lastError.message));
      } else {
        resolve(response);
      }
    });
  });
}

async function checkConnection() {
  try {
    const res = await sendToBackground({ type: 'ping' });
    updateStatus(res.ok);
  } catch (e) {
    updateStatus(false);
  }
}

async function stageCurrentPage() {
  STAGE_PAGE_BTN.disabled = true;
  STAGE_PAGE_BTN.textContent = 'Staging...';
  try {
    const res = await sendToBackground({ type: 'stageActiveTab' });
    if (res.ok) {
      addRecentStaged(res.result.content_id || 'unknown', res.result.expires_at || 0);
    } else {
      alert('Failed to stage page: ' + (res.error || 'unknown error'));
    }
  } catch (e) {
    alert('Error: ' + e.message);
  } finally {
    STAGE_PAGE_BTN.disabled = false;
    STAGE_PAGE_BTN.textContent = 'Stage Current Page';
  }
}

async function stageSelectedText() {
  // First get selected text from content script
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tabs || tabs.length === 0) {
    alert('No active tab');
    return;
  }

  const tabId = tabs[0].id;
  try {
    const selectionRes = await new Promise((resolve, reject) => {
      chrome.tabs.sendMessage(tabId, { type: 'getSelectedText' }, (response) => {
        if (chrome.runtime.lastError) {
          reject(new Error(chrome.runtime.lastError.message));
        } else {
          resolve(response);
        }
      });
    });

    if (!selectionRes || !selectionRes.text || selectionRes.length === 0) {
      alert('No text selected');
      return;
    }

    STAGE_SELECTION_BTN.disabled = true;
    STAGE_SELECTION_BTN.textContent = 'Staging...';

    const res = await sendToBackground({
      type: 'stageSelectedText',
      text: selectionRes.text,
    });

    if (res.ok) {
      addRecentStaged(res.result.content_id || 'unknown', res.result.expires_at || 0);
    } else {
      alert('Failed to stage text: ' + (res.error || 'unknown error'));
    }
  } catch (e) {
    alert('Error: ' + e.message);
  } finally {
    STAGE_SELECTION_BTN.disabled = false;
    STAGE_SELECTION_BTN.textContent = 'Stage Selected Text';
  }
}

function addRecentStaged(contentId, expiresAt) {
  const item = document.createElement('div');
  item.className = 'recent-item';
  item.textContent = contentId + (expiresAt ? ' (expires soon)' : '');
  RECENT_STAGED.insertBefore(item, RECENT_STAGED.firstChild);
  
  // Limit to 5 items
  while (RECENT_STAGED.children.length > 5) {
    RECENT_STAGED.removeChild(RECENT_STAGED.lastChild);
  }
}

// Initialize
checkConnection();
setInterval(checkConnection, 5000);

STAGE_PAGE_BTN.addEventListener('click', stageCurrentPage);
STAGE_SELECTION_BTN.addEventListener('click', stageSelectedText);
