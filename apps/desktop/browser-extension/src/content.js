// Flowww content script - runs in the context of web pages.
// Provides selected text capture and page status reporting.

(function() {
  'use strict';

  const EXTENSION_ID = chrome.runtime.id;

  // Report page status to the background service worker
  function reportPageStatus() {
    const selection = window.getSelection();
    const selectedText = selection.toString().trim();
    
    chrome.runtime.sendMessage({
      type: 'pageStatus',
      url: window.location.href,
      title: document.title,
      hasSelection: selectedText.length > 0,
      selectionLength: selectedText.length,
    }).catch(() => {
      // Background script may not be ready yet
    });
  }

  // Expose selected text to the extension on request
  chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.type === 'getSelectedText') {
      const selection = window.getSelection();
      const text = selection.toString().trim();
      sendResponse({ text, length: text.length });
      return true;
    }
    return false;
  });

  // Report status on load and selection change
  document.addEventListener('DOMContentLoaded', reportPageStatus);
  document.addEventListener('selectionchange', () => {
    // Debounce selection reporting
    clearTimeout(window.__flowwwSelectionTimeout);
    window.__flowwwSelectionTimeout = setTimeout(reportPageStatus, 200);
  });

  // Initial report
  reportPageStatus();

})();
