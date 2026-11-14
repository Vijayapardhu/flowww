(() => {
  "use strict";

  const hash = new URLSearchParams(location.hash.slice(1));
  const hashToken = hash.get("token") || "";
  const tokenKey = "flowww.bridge.token";
  let storedToken = "";
  try {
    if (hashToken) {
      sessionStorage.setItem(tokenKey, hashToken);
    }
    storedToken = sessionStorage.getItem(tokenKey) || "";
  } catch {
    storedToken = "";
  }
  const token = hashToken || storedToken;
  if (location.hash) {
    history.replaceState(null, "", `${location.pathname}${location.search}`);
  }

  const $ = (id) => document.getElementById(id);
  const ui = {
    deviceName: $("device-name"),
    deviceMeta: $("device-meta"),
    fingerprint: $("fingerprint"),
    peers: $("peers"),
    peersEmpty: $("peers-empty"),
    targetLabel: $("target-label"),
    payload: $("payload"),
    sendText: $("send-text"),
    sendLink: $("send-link"),
    hold: $("hold"),
    dropzone: $("dropzone"),
    file: $("file"),
    uploadProgress: $("upload-progress"),
    uploadBar: $("upload-bar"),
    offers: $("offers"),
    transfers: $("transfers"),
    transfersEmpty: $("transfers-empty"),
    autoAccept: $("auto-accept"),
    pairingActive: $("pairing-active"),
    showPairing: $("show-pairing"),
    pairOpen: $("pair-open"),
    dialog: $("pair-dialog"),
    pairClose: $("pair-close"),
    pairStatus: $("pair-status"),
    pairError: $("pair-error"),
    qr: $("qr"),
    copyPair: $("copy-pair"),
    pairUri: $("pair-uri"),
    pairJoin: $("pair-join"),
    space: $("space"),
    spaceHint: $("space-hint"),
    spacePlane: $("space-plane"),
    spaceCancel: $("space-cancel"),
    toasts: $("toasts"),
  };

  let snapshot = null;
  let selected = null;
  let pairingSession = null;
  let qrObjectUrl = null;
  let actionBusy = false;
  let hasDraft = false;
  let refreshTimer = null;
  let lastError = "";

  if (!token) {
    showFatal();
    return;
  }

  bind();
  refresh();
  connect();
  window.setInterval(refresh, 8000);

  // ---------------------------------------------------------------- helpers

  function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs)) {
      if (value === undefined || value === null || value === false) continue;
      if (key === "class") node.className = value;
      else if (key === "text") node.textContent = String(value);
      else if (key === "dataset") Object.assign(node.dataset, value);
      else if (key.startsWith("on") && typeof value === "function") {
        node.addEventListener(key.slice(2).toLowerCase(), value);
      } else {
        node.setAttribute(key, value === true ? "" : String(value));
      }
    }
    for (const child of children.flat()) {
      if (child === null || child === undefined || child === false) continue;
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  function toast(message) {
    if (!message) return;
    const node = el("div", { class: "toast", text: message });
    ui.toasts.append(node);
    window.setTimeout(() => node.remove(), 4200);
  }

  function showFatal() {
    const notice = el(
      "section",
      { class: "notice" },
      el("span", { text: "This page is missing its access token." }),
      el("span", { class: "target-label", text: "Reopen the URL printed by the daemon." })
    );
    document.querySelector("main").prepend(notice);
    document.querySelectorAll("button, input, textarea").forEach((node) => {
      node.disabled = true;
    });
    document.title = "flowww — access token required";
  }

  function platformLabel(platform) {
    switch (platform) {
      case "windows":
        return "Windows";
      case "android":
        return "Android";
      case "web":
        return "Web";
      case "macos":
        return "macOS";
      case "linux":
        return "Linux";
      default:
        return "Device";
    }
  }

  function shortHex(value) {
    if (!value) return "";
    if (value.includes("…")) return value;
    return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;
  }

  function snippet(value, max = 90) {
    const text = String(value).replace(/\s+/g, " ").trim();
    return text.length > max ? `${text.slice(0, max - 1)}…` : text;
  }

  function sizeLabel(bytes) {
    if (!bytes) return "0 B";
    const units = ["B", "KB", "MB", "GB", "TB"];
    let value = Number(bytes);
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    const digits = unit === 0 || value >= 10 ? 0 : 1;
    return `${value.toFixed(digits)} ${units[unit]}`;
  }

  function timeLabel(ms) {
    if (!ms) return "";
    const date = new Date(ms);
    const today = new Date();
    const sameDay = date.toDateString() === today.toDateString();
    try {
      return new Intl.DateTimeFormat(undefined, sameDay
        ? { hour: "2-digit", minute: "2-digit" }
        : { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }
      ).format(date);
    } catch {
      return date.toLocaleTimeString();
    }
  }

  function isHttpUrl(value) {
    try {
      const url = new URL(value);
      return url.protocol === "http:" || url.protocol === "https:";
    } catch {
      return false;
    }
  }

  function deviceName(id) {
    if (!snapshot) return id;
    const peer = (snapshot.peers || []).find((item) => item.device_id === id);
    if (peer) return peer.name;
    const device = (snapshot.devices || []).find((item) => item.info.device_id === id);
    if (device) return device.info.device_name;
    return id ? id.slice(0, 14) : "a device";
  }

  function mergedDevices() {
    if (!snapshot) return [];
    const byId = new Map();
    for (const device of snapshot.devices || []) {
      byId.set(device.info.device_id, {
        id: device.info.device_id,
        name: device.info.device_name,
        platform: device.info.platform,
        paired: Boolean(device.trusted),
        online: false,
        fingerprint: shortHex(device.public_key_fingerprint),
        detail: "offline",
      });
    }
    for (const peer of snapshot.peers || []) {
      const existing = byId.get(peer.device_id) || {};
      byId.set(peer.device_id, {
        ...existing,
        id: peer.device_id,
        name: peer.name,
        platform: peer.platform,
        paired: Boolean(peer.paired),
        online: true,
        fingerprint: peer.fingerprint,
        detail: peer.address ? `${peer.address}:${peer.port}` : "online",
      });
    }
    return [...byId.values()].sort(
      (left, right) =>
        Number(right.online) - Number(left.online) || left.name.localeCompare(right.name)
    );
  }

  function selectedDevice() {
    return mergedDevices().find((device) => device.id === selected) || null;
  }

  // ---------------------------------------------------------------- network

  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set("Authorization", `Bearer ${token}`);
    let body = options.body;
    if (body !== undefined && !(body instanceof FormData)) {
      headers.set("Content-Type", "application/json");
      body = JSON.stringify(body);
    }
    const response = await fetch(path, {
      ...options,
      headers,
      body,
      cache: "no-store",
    });
    const type = response.headers.get("content-type") || "";
    const data = type.includes("application/json")
      ? await response.json().catch(() => null)
      : await response.text();
    if (!response.ok) {
      throw new Error((data && data.error) || `Request failed (${response.status})`);
    }
    return data;
  }

  async function apiBlob(path) {
    const response = await fetch(path, {
      headers: { Authorization: `Bearer ${token}` },
      cache: "no-store",
    });
    if (!response.ok) {
      let message = `Request failed (${response.status})`;
      try {
        const data = await response.json();
        message = data.error || message;
      } catch {
        // The body was not JSON; keep the generic message.
      }
      throw new Error(message);
    }
    return response.blob();
  }

  async function refresh() {
    try {
      snapshot = await api("/api/snapshot");
      lastError = "";
      const available = (snapshot.peers || []).filter((peer) => peer.paired);
      if (!available.some((peer) => peer.device_id === selected)) {
        selected = available[0]?.device_id || null;
      }
      render();
    } catch (error) {
      if (error.message !== lastError) {
        lastError = error.message;
        toast(error.message);
      }
    }
  }

  function scheduleRefresh() {
    window.clearTimeout(refreshTimer);
    refreshTimer = window.setTimeout(refresh, 140);
  }

  function connect() {
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    const socket = new WebSocket(
      `${protocol}//${location.host}/api/events?token=${encodeURIComponent(token)}`
    );
    socket.addEventListener("open", () => {
      scheduleRefresh();
    });
    socket.addEventListener("message", (event) => {
      let payload;
      try {
        payload = JSON.parse(event.data);
      } catch {
        return;
      }
      handleEvent(payload);
    });
    socket.addEventListener("close", () => {
      window.setTimeout(connect, 1200);
    });
  }

  function handleEvent(event) {
    if (!event || typeof event !== "object") return;
    switch (event.type) {
      case "PAIRING_COMPLETED": {
        pairingSession = null;
        if (ui.dialog.open) ui.dialog.close();
        toast(`Paired with ${event.data.device_name}`);
        break;
      }
      case "PAIRING_FAILED": {
        if (pairingSession && ui.dialog.open) {
          pairingSession = null;
          setPairStatus("Pairing did not complete.");
          showPairError(event.data.reason);
        }
        break;
      }
      case "OFFER_RECEIVED": {
        toast(`Incoming from ${event.data.source.device_name}`);
        break;
      }
      case "ERROR": {
        toast(event.data.message);
        break;
      }
      default:
        break;
    }
    scheduleRefresh();
  }

  async function act(action) {
    if (actionBusy) return undefined;
    actionBusy = true;
    renderComposer();
    try {
      return await action();
    } catch (error) {
      toast(error.message);
      return undefined;
    } finally {
      actionBusy = false;
      renderComposer();
      scheduleRefresh();
    }
  }

  // ----------------------------------------------------------------- render

  function render() {
    if (!snapshot) return;
    renderHeader();
    renderPeers();
    renderComposer();
    renderOffers();
    renderTransfers();
    renderPairingNotice();
    renderSpace();
    renderSettings();
  }

  function renderHeader() {
    ui.deviceName.textContent = snapshot.device.device_name;
    ui.deviceMeta.textContent = `${platformLabel(snapshot.device.platform)} · port ${snapshot.listen_port}`;
    ui.fingerprint.textContent = `${shortHex(snapshot.public_key_fingerprint)} · ${snapshot.device.device_id}`;
  }

  function renderPeers() {
    const devices = mergedDevices();
    ui.peers.replaceChildren(...devices.map(peerRow));
    ui.peersEmpty.hidden = devices.length > 0;
  }

  function peerRow(device) {
    const selectable = device.online && device.paired;
    const pill = !device.paired
      ? el("span", { class: "pill" }, "pair to send")
      : device.online
        ? el("span", { class: "pill paired" }, "paired")
        : el("span", { class: "pill offline" }, "offline");

    const row = el(
      "button",
      {
        class: "peer",
        type: "button",
        "data-selectable": String(selectable),
        "aria-pressed": String(selected === device.id),
        "aria-disabled": String(!selectable),
        title: selectable
          ? `Send to ${device.name}`
          : device.paired
            ? "This device is paired but not online."
            : "Pair this device before sending.",
        onclick: () => {
          if (!selectable) return;
          selected = device.id;
          render();
        },
      },
      el("span", { class: `dot${device.online ? " online" : ""}` }),
      el(
        "span",
        { class: "peer-main" },
        el("span", { class: "peer-name" }, device.name, pill),
        el("span", {
          class: "peer-sub",
          text: `${platformLabel(device.platform)} · ${device.online ? device.detail : "offline"}${
            device.fingerprint ? ` · ${device.fingerprint}` : ""
          }`,
        })
      )
    );
    return el("li", {}, row);
  }

  function renderComposer() {
    const device = selectedDevice();
    const ready = Boolean(device && device.online && device.paired && !actionBusy);
    ui.sendText.disabled = !ready || !hasDraft;
    ui.sendLink.disabled = !ready || !hasDraft;
    ui.hold.disabled = !hasDraft || actionBusy || Boolean(snapshot?.spatial?.held);
    ui.targetLabel.textContent = device
      ? device.name
      : mergedDevices().some((item) => item.paired)
        ? "Choose a device"
        : "Pair a device first";
    ui.dropzone.dataset.enabled = String(Boolean(ready));
    ui.dropzone.setAttribute("aria-disabled", String(!ready));
  }

  function renderOffers() {
    const offers = snapshot.pending_offers || [];
    ui.offers.hidden = offers.length === 0;
    ui.offers.replaceChildren(...offers.map(offerCard));
  }

  function offerCard(offer) {
    const source = offer.source?.device_name || deviceName(offer.source?.device_id);
    const title = offer.content.inline
      ? `“${snippet(offer.content.inline, 90)}”`
      : offer.content.name || "An item";
    const actionable = !actionBusy;

    const accept = el("button", {
      class: "button primary",
      type: "button",
      disabled: !actionable,
      onclick: () =>
        act(async () => {
          await api(`/api/offers/${encodeURIComponent(offer.transfer_id)}/accept`, {
            method: "POST",
          });
          toast("Accepted");
        }),
    }, "Accept");

    const decline = el("button", {
      class: "button quiet",
      type: "button",
      disabled: !actionable,
      onclick: () =>
        act(async () => {
          await api(`/api/offers/${encodeURIComponent(offer.transfer_id)}/decline`, {
            method: "POST",
          });
          toast("Declined");
        }),
    }, "Decline");

    return el(
      "article",
      { class: "offer-card" },
      el(
        "div",
        { class: "offer-top" },
        el(
          "div",
          {},
          el("div", { class: "offer-source", text: `Incoming from ${source}` }),
          el("p", { class: "offer-title", text: title }),
          el("div", { class: "offer-meta", text: metaForContent(offer.content) })
        )
      ),
      el("div", { class: "offer-actions" }, accept, decline)
    );
  }

  function metaForContent(content) {
    const parts = [];
    if (content.kind) parts.push(content.kind === "url" ? "link" : content.kind);
    if (content.size) parts.push(sizeLabel(content.size));
    return parts.join(" · ");
  }

  function renderTransfers() {
    const records = (snapshot.transfers || []).slice(0, 12);
    ui.transfers.replaceChildren(...records.map(transferRow));
    ui.transfersEmpty.hidden = records.length > 0;
  }

  function transferRow(record) {
    const incoming = record.direction === "incoming";
    const other = incoming ? record.source_device : record.destination_device;
    const inline = record.inline_content ? snippet(record.inline_content, 72) : "";
    const label = inline
      ? `“${inline}”`
      : record.filename || (record.kind === "url" ? "Link" : record.kind === "text" ? "Text" : "File");

    const name = el("div", { class: "transfer-name" });
    if (inline) {
      name.append(el("span", { class: "snippet", text: label }));
    } else {
      name.textContent = label;
    }

    const meta = el(
      "div",
      { class: "transfer-meta" },
      el("span", { text: incoming ? `from ${deviceName(other)}` : `to ${deviceName(other)}` }),
      record.size ? el("span", { text: sizeLabel(record.size) }) : null,
      record.created_at ? el("span", { text: timeLabel(record.created_at) }) : null
    );

    const main = el("div", { class: "transfer-main" }, name, meta);

    const active = ["CREATED", "OFFERED", "ACCEPTED", "CONNECTING", "TRANSFERRING", "PAUSED", "VERIFYING"].includes(record.status);
    if (active && record.size > 0) {
      const percent = Math.min(100, Math.round((record.bytes_transferred / record.size) * 100));
      const fill = el("span");
      fill.style.width = `${percent}%`;
      main.append(el("div", { class: "progress" }, fill));
    }

    const statusClass = (record.status || "").toLowerCase();
    return el(
      "li",
      { class: "transfer", title: record.error || "" },
      el("span", { class: "transfer-icon", text: incoming ? "↓" : "↑" }),
      main,
      el("span", { class: `transfer-status ${statusClass}`, text: statusLabel(record.status) })
    );
  }

  function statusLabel(status) {
    switch (status) {
      case "CREATED":
        return "queued";
      case "OFFERED":
        return "waiting";
      case "ACCEPTED":
        return "accepted";
      case "CONNECTING":
        return "connecting";
      case "TRANSFERRING":
        return "transferring";
      case "PAUSED":
        return "paused";
      case "VERIFYING":
        return "verifying";
      case "COMPLETED":
        return "done";
      case "CANCELLED":
        return "cancelled";
      case "FAILED":
        return "failed";
      default:
        return (status || "").toLowerCase();
    }
  }

  function renderPairingNotice() {
    ui.pairingActive.hidden = !snapshot.pairing;
    if (snapshot.pairing) {
      ui.showPairing.textContent = "Show code";
    }
  }

  function renderSpace() {
    const spatial = snapshot.spatial;
    const holding = spatial && spatial.state === "HOLDING" && spatial.held;
    ui.space.hidden = !holding;
    if (!holding) return;

    const preview = spatial.held.inline
      ? snippet(spatial.held.inline, 70)
      : spatial.held.name || "An item";
    ui.spaceHint.textContent = `Holding “${preview}”. Choose a device.`;
    const targets = (spatial.targets || []).map((target) => {
      const button = el(
        "button",
        {
          class: "space-target",
          type: "button",
          title: `Drop at ${target.name}`,
          onclick: () => dropAt(target),
        },
        el("span", { class: "space-dot" }),
        el("span", { text: target.name })
      );
      const x = Math.min(0.94, Math.max(0.06, Number(target.x) || 0.5));
      const y = Math.min(0.9, Math.max(0.1, Number(target.y) || 0.5));
      button.style.left = `${x * 100}%`;
      button.style.top = `${y * 100}%`;
      return button;
    });
    ui.spacePlane.replaceChildren(...targets);
    if (targets.length === 0) {
      ui.spacePlane.append(
        el("p", { class: "empty", text: "No spatial targets yet." })
      );
    }
  }

  function renderSettings() {
    ui.autoAccept.checked = Boolean(snapshot.auto_accept);
  }

  // ---------------------------------------------------------------- actions

  function bind() {
    ui.payload.addEventListener("input", () => {
      hasDraft = ui.payload.value.trim().length > 0;
      renderComposer();
    });

    ui.sendText.addEventListener("click", () => sendPayload("text"));
    ui.sendLink.addEventListener("click", () => sendPayload("url"));
    ui.hold.addEventListener("click", holdPayload);

    ui.dropzone.addEventListener("click", () => {
      if (!ui.dropzone.dataset.enabled || ui.dropzone.dataset.enabled === "false") return;
      if (!actionBusy) ui.file.click();
    });
    ui.dropzone.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        ui.dropzone.click();
      }
    });
    ui.file.addEventListener("change", () => {
      const file = ui.file.files?.[0];
      ui.file.value = "";
      if (file) uploadFile(file);
    });

    for (const type of ["dragenter", "dragover"]) {
      ui.dropzone.addEventListener(type, (event) => {
        event.preventDefault();
        if (ui.dropzone.dataset.enabled === "true") ui.dropzone.classList.add("dragging");
      });
    }
    for (const type of ["dragleave", "dragend"]) {
      ui.dropzone.addEventListener(type, () => ui.dropzone.classList.remove("dragging"));
    }
    ui.dropzone.addEventListener("drop", (event) => {
      event.preventDefault();
      ui.dropzone.classList.remove("dragging");
      const file = event.dataTransfer?.files?.[0];
      if (file) uploadFile(file);
    });

    ui.autoAccept.addEventListener("change", async () => {
      const value = ui.autoAccept.checked;
      try {
        await api("/api/settings", { method: "POST", body: { auto_accept: value } });
        toast(value ? "Auto-accept is on" : "Auto-accept is off");
      } catch (error) {
        ui.autoAccept.checked = !value;
        toast(error.message);
      }
    });

    ui.spaceCancel.addEventListener("click", () =>
      act(async () => {
        await api("/api/spatial/cancel", { method: "POST" });
        toast("Cancelled");
      })
    );

    ui.pairOpen.addEventListener("click", openPairing);
    ui.showPairing.addEventListener("click", openPairing);
    ui.pairClose.addEventListener("click", () => ui.dialog.close());
    ui.dialog.addEventListener("close", () => {
      if (pairingSession) {
        pairingSession = null;
        api("/api/pairing/cancel", { method: "POST" }).catch(() => {});
        scheduleRefresh();
      }
      clearQr();
    });
    ui.dialog.addEventListener("click", (event) => {
      if (event.target === ui.dialog) ui.dialog.close();
    });
    ui.copyPair.addEventListener("click", copyPairingCode);
    ui.pairJoin.addEventListener("click", joinPairing);
  }

  async function sendPayload(kind) {
    const device = selectedDevice();
    if (!device || !device.online || !device.paired) {
      toast("Choose a paired device first");
      return;
    }
    const value = ui.payload.value.trim();
    if (!value) {
      toast("Write something first");
      return;
    }
    if (kind === "url" && !isHttpUrl(value)) {
      toast("Enter an http or https link");
      return;
    }
    await act(async () => {
      await api(`/api/send/${kind}`, {
        method: "POST",
        body:
          kind === "text"
            ? { device_id: device.id, text: value }
            : { device_id: device.id, url: value },
      });
      ui.payload.value = "";
      hasDraft = false;
      toast(kind === "text" ? "Text sent" : "Link sent");
    });
  }

  async function holdPayload() {
    const value = ui.payload.value.trim();
    if (!value) {
      toast("Write something to hold");
      return;
    }
    if (snapshot?.spatial?.held) {
      toast("Something is already held. Cancel it first.");
      return;
    }
    await act(async () => {
      await api("/api/spatial/hold", {
        method: "POST",
        body: { kind: isHttpUrl(value) ? "url" : "text", value },
      });
      toast("Held. Drop it in Space.");
      ui.space.scrollIntoView({ behavior: "smooth", block: "nearest" });
    });
  }

  async function dropAt(target) {
    await act(async () => {
      const record = await api("/api/spatial/drop", {
        method: "POST",
        body: { x: target.x, y: target.y },
      });
      toast(`Dropped to ${deviceName(record.destination_device)}`);
    });
  }

  function uploadFile(file) {
    const device = selectedDevice();
    if (!device || !device.online || !device.paired) {
      toast("Choose a paired device first");
      return;
    }
    if (actionBusy) return;

    actionBusy = true;
    renderComposer();
    ui.uploadProgress.hidden = false;
    ui.uploadBar.style.width = "0%";
    let settled = false;

    const finish = (message) => {
      if (settled) return;
      settled = true;
      actionBusy = false;
      ui.uploadProgress.hidden = true;
      renderComposer();
      toast(message);
      scheduleRefresh();
    };

    const form = new FormData();
    form.append("file", file, file.name);
    const request = new XMLHttpRequest();
    request.open("POST", `/api/send/file/${encodeURIComponent(device.id)}`);
    request.setRequestHeader("Authorization", `Bearer ${token}`);
    request.upload.addEventListener("progress", (event) => {
      if (!event.lengthComputable) return;
      const percent = Math.round((event.loaded / event.total) * 100);
      ui.uploadBar.style.width = `${percent}%`;
    });
    request.addEventListener("load", () => {
      if (request.status >= 200 && request.status < 300) {
        finish(`Sent ${file.name}`);
        return;
      }
      let message = `Upload failed (${request.status})`;
      try {
        message = JSON.parse(request.responseText).error || message;
      } catch {
        // Keep the status-based fallback.
      }
      finish(message);
    });
    request.addEventListener("error", () => finish("Upload failed"));
    request.addEventListener("abort", () => finish("Upload cancelled"));
    request.send(form);
  }

  async function openPairing() {
    showPairError("");
    if (!ui.dialog.open) ui.dialog.showModal();
    if (snapshot?.pairing) {
      pairingSession = snapshot.pairing;
      await showPairingCode();
      return;
    }
    if (pairingSession) {
      await showPairingCode();
      return;
    }
    setPairStatus("Preparing a code…");
    ui.copyPair.disabled = true;
    try {
      pairingSession = await api("/api/pairing/start", { method: "POST" });
      setPairStatus("Scan this code with the other device.");
      await showPairingCode();
      scheduleRefresh();
    } catch (error) {
      pairingSession = null;
      setPairStatus("Could not start pairing.");
      showPairError(error.message);
    }
  }

  async function showPairingCode() {
    if (!pairingSession) return;
    ui.copyPair.disabled = false;
    setPairStatus("Scan this code with the other device. It expires in two minutes.");
    try {
      const blob = await apiBlob("/api/pairing/qr.svg");
      clearQr();
      qrObjectUrl = URL.createObjectURL(blob);
      ui.qr.src = qrObjectUrl;
    } catch (error) {
      showPairError(error.message);
    }
  }

  async function copyPairingCode() {
    if (!pairingSession) return;
    try {
      await navigator.clipboard.writeText(pairingSession.uri);
      toast("Pairing code copied");
    } catch {
      ui.pairUri.value = pairingSession.uri;
      ui.pairUri.focus();
      ui.pairUri.select();
      toast("Select the code and copy it");
    }
  }

  async function joinPairing() {
    const uri = ui.pairUri.value.trim();
    if (!uri) return;
    showPairError("");
    ui.pairJoin.disabled = true;
    try {
      const device = await api("/api/pairing/join", {
        method: "POST",
        body: { uri },
      });
      pairingSession = null;
      ui.pairUri.value = "";
      ui.dialog.close();
      toast(`Paired with ${device.device_name}`);
      scheduleRefresh();
    } catch (error) {
      showPairError(error.message);
    } finally {
      ui.pairJoin.disabled = false;
    }
  }

  function setPairStatus(message) {
    ui.pairStatus.textContent = message;
  }

  function showPairError(message) {
    if (!message) {
      ui.pairError.hidden = true;
      ui.pairError.textContent = "";
      return;
    }
    ui.pairError.hidden = false;
    ui.pairError.textContent = message;
  }

  function clearQr() {
    if (qrObjectUrl) {
      URL.revokeObjectURL(qrObjectUrl);
      qrObjectUrl = null;
    }
    ui.qr.removeAttribute("src");
  }
})();
