import { AnimatePresence, motion } from "framer-motion";
import { QRCodeSVG } from "qrcode.react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { backend } from "./backend";
import type { DeviceSummary, LocalDevice, PairingView, TransferSummary } from "./types";

type Section = "overview" | "nearby" | "trusted" | "history" | "settings";

const nav: Array<{ id: Section; label: string; icon: string }> = [
  { id: "overview", label: "Overview", icon: "⌂" },
  { id: "nearby", label: "Nearby devices", icon: "◌" },
  { id: "trusted", label: "Trusted devices", icon: "✓" },
  { id: "history", label: "Transfer history", icon: "↗" },
  { id: "settings", label: "Settings", icon: "⋯" },
];

function App() {
  const [section, setSection] = useState<Section>("overview");
  const [device, setDevice] = useState<LocalDevice | null>(null);
  const [nearby, setNearby] = useState<DeviceSummary[]>([]);
  const [trusted, setTrusted] = useState<DeviceSummary[]>([]);
  const [history, setHistory] = useState<TransferSummary[]>([]);
  const [active, setActive] = useState<TransferSummary[]>([]);
  const [pairing, setPairing] = useState<PairingView | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [local, peers, trustedDevices, records, activeRecords] = await Promise.all([
        backend.getLocalDevice(),
        backend.getNearbyDevices(),
        backend.getTrustedDevices(),
        backend.getTransferHistory(),
        backend.getActiveTransfers(),
      ]);
      setDevice(local);
      setNearby(peers);
      setTrusted(trustedDevices);
      setHistory(records);
      setActive(activeRecords);
      setError(null);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    void backend.initializeNode().then((local) => {
      if (!cancelled) setDevice(local);
      return refresh();
    }).catch((reason) => {
      if (!cancelled) {
        setError(reason instanceof Error ? reason.message : String(reason));
        setBusy(false);
      }
    });
    let unlisten: (() => void) | undefined;
    void backend.listen((event) => {
      if (event.kind === "transfer-updated") {
        const terminal = (status: string) => /COMPLETED|CANCELLED|FAILED/.test(status);
        setActive((current) => {
          const previous = current.find((item) => item.transferId === event.transfer.transferId);
          if (previous && terminal(previous.status) && !terminal(event.transfer.status)) return current;
          return terminal(event.transfer.status) ? current.filter((item) => item.transferId !== event.transfer.transferId) : [event.transfer, ...current.filter((item) => item.transferId !== event.transfer.transferId)];
        });
        setHistory((current) => {
          const previous = current.find((item) => item.transferId === event.transfer.transferId);
          if (previous && terminal(previous.status) && !terminal(event.transfer.status)) return current;
          return [event.transfer, ...current.filter((item) => item.transferId !== event.transfer.transferId)];
        });
      }
      if (event.kind === "peer-found") setNearby((current) => [event.device, ...current.filter((item) => item.deviceId !== event.device.deviceId)]);
      if (event.kind === "peer-lost") setNearby((current) => current.map((item) => item.deviceId === event.deviceId ? { ...item, online: false } : item));
      if (event.kind === "resync-required") void refresh();
      if (event.kind === "error") setError(event.message);
      if (event.kind === "pairing-completed") { setPairing(null); void refresh(); }
      if (event.kind === "pairing-failed") setError(event.reason);
    }).then((stop) => { unlisten = stop; });
    return () => { cancelled = true; unlisten?.(); };
  }, [refresh]);

  const startPairing = async () => {
    try { setPairing(await backend.startPairing()); } catch (reason) { setError(String(reason)); }
  };

  const cancel = async (transferId: string) => {
    try {
      const result = await backend.cancelTransfer(transferId);
      if (!result.accepted) setError(result.message);
      await refresh();
    } catch (reason) { setError(String(reason)); }
  };

  const content = useMemo(() => {
    if (busy) return <Loading />;
    if (section === "nearby") return <DeviceSection title="Nearby devices" subtitle="Devices discovered on your local network." devices={nearby} empty="No nearby devices yet." />;
    if (section === "trusted") return <DeviceSection title="Trusted devices" subtitle="Devices paired with this installation." devices={trusted} empty="Pair a device to build your trusted circle." />;
    if (section === "history") return <TransferSection records={history} />;
    if (section === "settings") return <Settings device={device} />;
    return <Overview device={device} nearby={nearby} active={active} onNavigate={setSection} onCancel={cancel} />;
  }, [active, busy, device, history, nearby, section, trusted]);

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><span className="brand-mark">f</span><span>flowww</span></div>
        <div className="sidebar-caption">LOCAL TRANSFER</div>
        <nav>{nav.map((item) => <button key={item.id} className={section === item.id ? "nav-item active" : "nav-item"} onClick={() => setSection(item.id)}><span>{item.icon}</span>{item.label}</button>)}</nav>
        <div className="sidebar-bottom"><div className="connection"><span className="status-dot" /> Local engine <span className="connection-state">ready</span></div><button className="pair-button" onClick={() => void startPairing()}>+ Pair a device</button></div>
      </aside>
      <main className="main-content">
        <header className="topbar"><div><div className="eyebrow">WORKSPACE</div><h1>{section === "overview" ? "Good to see you." : nav.find((item) => item.id === section)?.label}</h1></div><div className="device-chip"><span className="avatar">{device?.name.slice(0, 1).toUpperCase() ?? "?"}</span><div><strong>{device?.name ?? "Starting flowww"}</strong><small>{device?.platform ?? "offline"}</small></div></div></header>
        {error && <div className="error-banner" role="alert">{error}<button onClick={() => setError(null)}>Dismiss</button></div>}
        <AnimatePresence mode="wait"><motion.div key={section} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18 }}>{content}</motion.div></AnimatePresence>
      </main>
      {pairing && <PairingDialog pairing={pairing} onJoin={async (uri) => {
        try { await backend.approvePairing(uri); setPairing(null); await refresh(); }
        catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)); }
      }} onClose={() => { setPairing(null); void backend.rejectPairing(); }} />}
    </div>
  );
}

function Loading() { return <div className="loading"><span className="spinner" /> Loading local engine…</div>; }

function Overview({ device, nearby, active, onNavigate, onCancel }: { device: LocalDevice | null; nearby: DeviceSummary[]; active: TransferSummary[]; onNavigate: (section: Section) => void; onCancel: (id: string) => void }) {
  return <section className="page"><div className="hero"><div><span className="eyebrow orange">PRIVATE BY DEFAULT</span><h2>Move things<br /><em>without the cloud.</em></h2><p>Send files and links to the devices around you. Encrypted, local, and under your control.</p><button className="primary-button" onClick={() => onNavigate("nearby")}>Choose a device <span>→</span></button></div><div className="hero-orbit"><div className="orbit orbit-one" /><div className="orbit orbit-two" /><div className="orbit-center">{device?.name.slice(0, 1).toUpperCase() ?? "f"}</div><span className="orbit-node node-a">{nearby.filter((item) => item.online).length}</span><span className="orbit-node node-b">TLS</span></div></div><div className="section-heading"><div><span className="eyebrow">LIVE</span><h3>Active transfers</h3></div><button className="text-button" onClick={() => onNavigate("history")}>View history →</button></div><TransferSection records={active} empty="Nothing moving right now." onCancel={onCancel} compact /><div className="section-heading nearby-heading"><div><span className="eyebrow">LOCAL NETWORK</span><h3>Nearby now</h3></div><button className="text-button" onClick={() => onNavigate("nearby")}>See all →</button></div><DeviceSection devices={nearby.slice(0, 3)} empty="No devices discovered." compact /></section>;
}

function DeviceSection({ title, subtitle, devices, empty, compact = false }: { title?: string; subtitle?: string; devices: DeviceSummary[]; empty: string; compact?: boolean }) {
  return <section className={compact ? "device-section compact" : "device-section"}>{title && <div className="section-heading"><div><span className="eyebrow">DEVICES</span><h2>{title}</h2><p>{subtitle}</p></div></div>}{devices.length === 0 ? <EmptyState text={empty} /> : <div className="device-list">{devices.map((device) => <DeviceCard key={device.deviceId} device={device} />)}</div>}</section>;
}

function DeviceCard({ device }: { device: DeviceSummary }) { return <div className="device-card"><div className={device.online ? "device-status online" : "device-status"} /><div className="device-initial">{device.name.slice(0, 1).toUpperCase()}</div><div className="device-copy"><strong>{device.name}</strong><span>{device.platform} · {device.online ? "Online" : "Offline"}</span></div>{device.paired && <span className="trusted-pill">Trusted</span>}<button className="more-button" aria-label={`More actions for ${device.name}`}>···</button></div>; }

function TransferSection({ records, empty = "No transfers recorded yet.", onCancel, compact = false }: { records: TransferSummary[]; empty?: string; onCancel?: (id: string) => void; compact?: boolean }) { return <div className={compact ? "transfer-list compact" : "transfer-list"}>{records.length === 0 ? <EmptyState text={empty} /> : records.slice(0, 12).map((record) => <TransferRow key={record.transferId} record={record} onCancel={onCancel} />)}</div>; }

function TransferRow({ record, onCancel }: { record: TransferSummary; onCancel?: (id: string) => void }) { const percent = record.size > 0 ? Math.min(100, Math.round(record.bytesTransferred / record.size * 100)) : 0; const active = !/COMPLETED|CANCELLED|FAILED/.test(record.status); return <div className="transfer-row"><div className="transfer-type">{record.direction === "incoming" ? "↓" : "↑"}</div><div className="transfer-copy"><strong>{record.filename ?? "Inline content"}</strong><span>{record.direction} · {record.route.replace("_", " ")}</span>{active && <div className="progress-track"><i style={{ width: `${percent}%` }} /></div>}</div><div className="transfer-metric">{active ? `${percent}%` : record.status.toLowerCase()}</div>{active && onCancel && <button className="cancel-button" onClick={() => onCancel(record.transferId)}>Cancel</button>}</div>; }

function Settings({ device }: { device: LocalDevice | null }) { return <section className="page"><div className="section-heading"><div><span className="eyebrow">PREFERENCES</span><h2>Settings</h2><p>Local engine configuration and identity.</p></div></div><div className="settings-list"><div><span>Device name</span><strong>{device?.name ?? "—"}</strong></div><div><span>Platform</span><strong>{device?.platform ?? "—"}</strong></div><div><span>Protocol</span><strong>{device?.protocolVersion ?? "FLW/1.0"}</strong></div><div><span>Identity fingerprint</span><strong className="mono">{device?.fingerprint ?? "—"}</strong></div></div></section>; }

function PairingDialog({ pairing, onJoin, onClose }: { pairing: PairingView; onJoin: (uri: string) => Promise<void>; onClose: () => void }) {
  const [uri, setUri] = useState("");
  const [joining, setJoining] = useState(false);
  const [remaining, setRemaining] = useState(Math.max(0, pairing.expiresAt - Date.now()));
  useEffect(() => { const timer = window.setInterval(() => setRemaining(Math.max(0, pairing.expiresAt - Date.now())), 1000); return () => window.clearInterval(timer); }, [pairing.expiresAt]);
  const join = async () => { if (!uri.trim()) return; setJoining(true); try { await onJoin(uri.trim()); } finally { setJoining(false); } };
  return <div className="dialog-backdrop"><motion.div className="pairing-dialog" initial={{ opacity: 0, scale: 0.97 }} animate={{ opacity: 1, scale: 1 }}><button className="dialog-close" onClick={onClose}>×</button><span className="eyebrow orange">ONE-TIME PAIRING</span><h2>Connect a device.</h2><p>Scan this code from another device, or paste a pairing URI below to join an existing session.</p><div className="qr-real"><QRCodeSVG value={pairing.uri} size={172} bgColor="#f9f8f5" fgColor="#191817" /></div><div className="pairing-expiry">{remaining > 0 ? `Expires in ${Math.ceil(remaining / 1000)}s` : "Pairing expired"}</div><code>{pairing.uri}</code><div className="join-row"><input value={uri} onChange={(event) => setUri(event.target.value)} placeholder="flowww://pair/v1/…" aria-label="Remote pairing URI" /><button className="secondary-button join-button" disabled={joining || !uri.trim()} onClick={() => void join()}>{joining ? "Joining…" : "Join"}</button></div><button className="secondary-button" onClick={onClose}>Close</button></motion.div></div>;
}

function EmptyState({ text }: { text: string }) { return <div className="empty-state"><span>—</span><p>{text}</p></div>; }

export default App;
