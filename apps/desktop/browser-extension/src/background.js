// Flowww browser extension background service worker.
// Handles Native Messaging communication with the Windows agent.

const NATIVE_MESSAGING_APP = "com.flowww.desktop";
const MAX_MESSAGE_SIZE = 1_048_576;
const REQUEST_TIMEOUT_MS = 30_000;

let nativePort = null;
let pendingRequests = new Map();
let requestIdCounter = 0;

function nextRequestId() {
  return (++requestIdCounter).toString();
}

function connectNativeMessaging() {
  if (nativePort) {
    return nativePort;
  }
  try {
    nativePort = chrome.runtime.connectNative(NATIVE_MESSAGING_APP);
    nativePort.onMessage.addListener(handleNativeMessage);
    nativePort.onDisconnect.addListener(() => {
      nativePort = null;
      // Reject all pending requests
      for (const [id, { reject }] of pendingRequests.entries()) {
        reject(new Error("Native Messaging host disconnected"));
      }
      pendingRequests.clear();
    });
  } catch (e) {
    console.error("Flowww: Failed to connect to Native Messaging host:", e);
    nativePort = null;
  }
  return nativePort;
}

function handleNativeMessage(message) {
  const { id, type, ...payload } = message;
  const pending = pendingRequests.get(id);
  if (!pending) {
    return;
  }
  pendingRequests.delete(id);
  clearTimeout(pending.timeout);

  if (type === "error") {
    pending.reject(new Error(payload.message || "Native Messaging error"));
  } else {
    pending.resolve(payload);
  }
}

function sendNativeMessage(message) {
  return new Promise((resolve, reject) => {
    const port = connectNativeMessaging();
    if (!port) {
      reject(new Error("Native Messaging host not available"));
      return;
    }

    const id = nextRequestId();
    const timeout = setTimeout(() => {
      pendingRequests.delete(id);
      reject(new Error("Native Messaging request timed out"));
    }, REQUEST_TIMEOUT_MS);

    pendingRequests.set(id, { resolve, reject, timeout });

    try {
      port.postMessage({ id, ...message });
    } catch (e) {
      pendingRequests.delete(id);
      clearTimeout(timeout);
      reject(e);
    }
  });
}

// Listen for messages from content scripts and popup
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  (async () => {
    try {
      let result;
      switch (request.type) {
        case "getActiveTab": {
          const tab = await getActiveTabInfo();
          result = { type: "activeTab", ...tab };
          break;
        }
        case "stageActiveTab": {
          const tab = await getActiveTabInfo();
          const nmResponse = await sendNativeMessage({
            type: "stage-active-tab",
            url: tab.url,
            title: tab.title,
            favicon_url: tab.faviconUrl,
          });
          result = { type: "stagedContent", ...nmResponse };
          break;
        }
        case "stageSelectedText": {
          const nmResponse = await sendNativeMessage({
            type: "stage-selected-text",
            text: request.text,
          });
          result = { type: "stagedContent", ...nmResponse };
          break;
        }
        case "ping": {
          const nmResponse = await sendNativeMessage({ type: "ping" });
          result = { type: "pong", ...nmResponse };
          break;
        }
        default:
          throw new Error("Unknown request type: " + request.type);
      }
      sendResponse({ ok: true, result });
    } catch (e) {
      sendResponse({ ok: false, error: e.message });
    }
  })();
  return true; // Keep channel open for async response
});

async function getActiveTabInfo() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tabs || tabs.length === 0) {
    throw new Error("No active tab found");
  }
  const tab = tabs[0];
  return {
    url: tab.url || "",
    title: tab.title || "",
    faviconUrl: tab.favIconUrl || undefined,
  };
}

// Handle extension install/update
chrome.runtime.onInstalled.addListener((details) => {
  if (details.reason === "install") {
    console.log("Flowww extension installed");
  }
});
