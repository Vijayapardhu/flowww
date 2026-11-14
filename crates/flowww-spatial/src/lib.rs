//! Spatial relay model.
//!
//! The phone is a controller: it picks content up, aims at a physical target and
//! releases. This crate keeps that gesture state machine and the marker registry
//! deterministic. It performs no I/O; the node crate decides who actually sends
//! bytes and over which route.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::Duration;
use thiserror::Error;

use flowww_core::{unix_millis, Content, ContentHandleId, DeviceId, TransferRoute};

/// How long content may stay held before it must be picked up again.
pub const DEFAULT_HOLD_TTL: Duration = Duration::from_secs(90);
/// How long a discovered marker stays usable without a refresh.
pub const DEFAULT_MARKER_TTL: Duration = Duration::from_secs(30);

#[derive(Debug, Error)]
pub enum SpatialError {
    #[error("a gesture is already in progress")]
    AlreadyHolding,
    #[error("there is no held content")]
    NothingHeld,
    #[error("the hold has expired")]
    Expired,
    #[error("invalid spatial coordinate")]
    InvalidCoordinate,
    #[error("the marker does not describe a usable target")]
    InvalidTarget,
    #[error("the content handle does not exist")]
    UnknownHandle,
    #[error("the content handle has expired")]
    HandleExpired,
    #[error("the content handle was already used")]
    HandleConsumed,
    #[error("staged content must not carry a local source path")]
    UnsafeContent,
    #[error("the staged content has no local bytes to share")]
    NoStagedBytes,
}

pub type SpatialResult<T> = Result<T, SpatialError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SpatialState {
    Idle,
    Holding,
    Completed,
    Cancelled,
    Expired,
}

impl SpatialState {
    pub fn is_active(self) -> bool {
        matches!(self, Self::Holding)
    }
}

/// Content held by the controller, with a hard expiry.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HeldContent {
    pub content: Content,
    pub picked_at: u64,
    pub expires_at: u64,
    #[serde(skip)]
    pub controller: Option<DeviceId>,
}

/// One pick-aim-release gesture.
#[derive(Debug, Clone)]
pub struct SpatialSession {
    state: SpatialState,
    held: Option<HeldContent>,
    ttl_ms: u64,
}

impl SpatialSession {
    pub fn new(ttl: Duration) -> Self {
        Self {
            state: SpatialState::Idle,
            held: None,
            ttl_ms: ttl.as_millis() as u64,
        }
    }

    pub fn default_ttl() -> Self {
        Self::new(DEFAULT_HOLD_TTL)
    }

    pub fn state(&self) -> SpatialState {
        self.state
    }

    pub fn held(&self) -> Option<&HeldContent> {
        self.held.as_ref()
    }

    /// Begin holding content. The controller is optional because a desktop can
    /// also drop onto a phone during testing and web use.
    pub fn pick(
        &mut self,
        content: Content,
        controller: Option<DeviceId>,
    ) -> SpatialResult<&HeldContent> {
        self.expire_if_needed(unix_millis());
        if self.state.is_active() || self.held.is_some() {
            return Err(SpatialError::AlreadyHolding);
        }
        let now = unix_millis();
        self.state = SpatialState::Holding;
        self.held = Some(HeldContent {
            content,
            picked_at: now,
            expires_at: now.saturating_add(self.ttl_ms),
            controller,
        });
        Ok(self.held.as_ref().expect("just inserted"))
    }

    /// Lazily expire an old hold. Returns true if the state changed.
    pub fn expire_if_needed(&mut self, now: u64) -> bool {
        if self.state == SpatialState::Holding {
            if let Some(held) = &self.held {
                if now >= held.expires_at {
                    self.state = SpatialState::Expired;
                    self.held = None;
                    return true;
                }
            }
        }
        false
    }

    pub fn is_expired(&self, now: u64) -> bool {
        self.held
            .as_ref()
            .map(|held| now >= held.expires_at)
            .unwrap_or(false)
    }

    /// Finish the gesture and hand the content to the caller.
    pub fn drop_content(&mut self) -> SpatialResult<HeldContent> {
        self.expire_if_needed(unix_millis());
        if self.state == SpatialState::Expired {
            return Err(SpatialError::Expired);
        }
        if self.state != SpatialState::Holding {
            return Err(SpatialError::NothingHeld);
        }
        let held = self.held.take().ok_or(SpatialError::NothingHeld)?;
        self.state = SpatialState::Completed;
        Ok(held)
    }

    pub fn cancel(&mut self) {
        self.held = None;
        self.state = SpatialState::Cancelled;
    }

    /// Return to idle after a completed, cancelled or expired gesture.
    pub fn reset(&mut self) {
        self.held = None;
        self.state = SpatialState::Idle;
    }
}

impl Default for SpatialSession {
    fn default() -> Self {
        Self::default_ttl()
    }
}

/// A physical or virtual drop target.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SpatialTarget {
    pub device_id: DeviceId,
    pub name: String,
    /// Normalized position on the controller's plane, 0.0..=1.0.
    pub x: f32,
    pub y: f32,
    pub last_seen_ms: u64,
}

impl SpatialTarget {
    pub fn new(
        device_id: DeviceId,
        name: impl Into<String>,
        x: f32,
        y: f32,
        last_seen_ms: u64,
    ) -> SpatialResult<Self> {
        if !x.is_finite() || !y.is_finite() {
            return Err(SpatialError::InvalidCoordinate);
        }
        let name = name.into();
        if name.trim().is_empty() || name.len() > 64 {
            return Err(SpatialError::InvalidTarget);
        }
        Ok(Self {
            device_id,
            name,
            x: x.clamp(-1.0, 2.0),
            y: y.clamp(-1.0, 2.0),
            last_seen_ms,
        })
    }

    pub fn distance_to(&self, x: f32, y: f32) -> f32 {
        ((self.x - x).powi(2) + (self.y - y).powi(2)).sqrt()
    }
}

/// The markers the controller currently believes are around it.
#[derive(Debug, Clone)]
pub struct SpatialRegistry {
    targets: HashMap<DeviceId, SpatialTarget>,
    marker_ttl_ms: u64,
    radius: f32,
}

impl SpatialRegistry {
    pub fn new(marker_ttl: Duration, radius: f32) -> Self {
        Self {
            targets: HashMap::new(),
            marker_ttl_ms: marker_ttl.as_millis() as u64,
            radius: if radius.is_finite() && radius > 0.0 {
                radius
            } else {
                0.15
            },
        }
    }

    pub fn default_config() -> Self {
        Self::new(DEFAULT_MARKER_TTL, 0.15)
    }

    pub fn upsert(&mut self, target: SpatialTarget) -> SpatialResult<()> {
        if target.name.trim().is_empty() {
            return Err(SpatialError::InvalidTarget);
        }
        self.targets.insert(target.device_id.clone(), target);
        Ok(())
    }

    pub fn remove(&mut self, device_id: &DeviceId) -> Option<SpatialTarget> {
        self.targets.remove(device_id)
    }

    pub fn len(&self) -> usize {
        self.targets.len()
    }

    pub fn is_empty(&self) -> bool {
        self.targets.is_empty()
    }

    /// Drop markers that have not been refreshed.
    pub fn retain_fresh(&mut self, now: u64) -> usize {
        let before = self.targets.len();
        let ttl = self.marker_ttl_ms;
        self.targets
            .retain(|_, target| now.saturating_sub(target.last_seen_ms) <= ttl);
        before - self.targets.len()
    }

    pub fn fresh_targets(&self, now: u64) -> Vec<&SpatialTarget> {
        let ttl = self.marker_ttl_ms;
        self.targets
            .values()
            .filter(|target| now.saturating_sub(target.last_seen_ms) <= ttl)
            .collect()
    }

    /// Nearest fresh marker within the configured drop radius.
    pub fn nearest(&self, x: f32, y: f32, now: u64) -> Option<&SpatialTarget> {
        if !x.is_finite() || !y.is_finite() {
            return None;
        }
        let ttl = self.marker_ttl_ms;
        self.targets
            .values()
            .filter(|target| now.saturating_sub(target.last_seen_ms) <= ttl)
            .map(|target| (target.distance_to(x, y), target))
            .filter(|(distance, _)| *distance <= self.radius)
            .min_by(|left, right| left.0.total_cmp(&right.0))
            .map(|(_, target)| target)
    }
}

impl Default for SpatialRegistry {
    fn default() -> Self {
        Self::default_config()
    }
}

/// A resolved release: who receives the content and which route is used.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DropDecision {
    pub destination: DeviceId,
    pub route: TransferRoute,
    pub distance_milli: u32,
}

/// Resolve a release gesture against the marker registry.
///
/// * The phone itself as source becomes `PHONE_TO_DESKTOP`.
/// * A third-party controller relaying between two desktops becomes
///   `PHONE_RELAY`.
/// * Everything else is a direct connection.
pub fn resolve_drop(
    source: &DeviceId,
    controller: Option<&DeviceId>,
    destination: &DeviceId,
    distance: f32,
) -> DropDecision {
    let route = if controller.is_some_and(|controller| controller == source) {
        TransferRoute::PhoneToDesktop
    } else if controller.is_some() && destination != controller.unwrap() {
        TransferRoute::PhoneRelay
    } else {
        TransferRoute::Direct
    };
    DropDecision {
        destination: destination.clone(),
        route,
        distance_milli: (distance.max(0.0) * 1000.0).round() as u32,
    }
}

/// Content staged by the source behind an opaque handle.
///
/// The descriptor is always wire-safe; the optional local path never leaves
/// this process and is never serialized into a capability.
#[derive(Debug, Clone)]
pub struct StagedContent {
    pub handle: ContentHandleId,
    pub content: Content,
    pub local_path: Option<PathBuf>,
    pub staged_at: u64,
    pub expires_at: u64,
    consumed: bool,
}

impl StagedContent {
    pub fn is_expired(&self, now: u64) -> bool {
        now >= self.expires_at
    }

    pub fn is_consumed(&self) -> bool {
        self.consumed
    }

    pub fn local_path(&self) -> Option<&Path> {
        self.local_path.as_deref()
    }
}

/// Source-owned handles for content that may be shared spatially.
///
/// A handle is short-lived, single-use and meaningful only on the source. The
/// vault is the only place a local path is kept; capabilities carry just the
/// handle and a wire-safe descriptor.
#[derive(Debug)]
pub struct ContentVault {
    entries: HashMap<ContentHandleId, StagedContent>,
    ttl_ms: u64,
}

impl ContentVault {
    pub fn new(ttl: Duration) -> Self {
        Self {
            entries: HashMap::new(),
            ttl_ms: ttl.as_millis().max(1) as u64,
        }
    }

    pub fn default_ttl() -> Self {
        Self::new(DEFAULT_HOLD_TTL)
    }

    /// Stage wire-safe content and mint a fresh handle.
    pub fn stage(
        &mut self,
        content: Content,
        local_path: Option<PathBuf>,
    ) -> SpatialResult<StagedContent> {
        if !content.is_wire_safe() {
            return Err(SpatialError::UnsafeContent);
        }
        content
            .validate()
            .map_err(|_| SpatialError::UnsafeContent)?;
        let now = unix_millis();
        let staged = StagedContent {
            handle: ContentHandleId::generate(),
            content,
            local_path,
            staged_at: now,
            expires_at: now.saturating_add(self.ttl_ms),
            consumed: false,
        };
        self.entries.insert(staged.handle.clone(), staged.clone());
        Ok(staged)
    }

    /// Stage content with an explicit local path, stripping the path from the
    /// wire descriptor.
    pub fn stage_with_reference(
        &mut self,
        mut content: Content,
        local_path: impl Into<PathBuf>,
    ) -> SpatialResult<StagedContent> {
        content.strip_source_reference();
        self.stage(content, Some(local_path.into()))
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Look up a live, unused handle.
    pub fn live(&self, handle: &ContentHandleId, now: u64) -> SpatialResult<&StagedContent> {
        let entry = self
            .entries
            .get(handle)
            .ok_or(SpatialError::UnknownHandle)?;
        if entry.consumed {
            return Err(SpatialError::HandleConsumed);
        }
        if entry.is_expired(now) {
            return Err(SpatialError::HandleExpired);
        }
        Ok(entry)
    }

    /// Consume a live handle. The entry stays in the vault so an in-flight
    /// delivery can still read its local path, but no second command can use it.
    pub fn consume(&mut self, handle: &ContentHandleId, now: u64) -> SpatialResult<StagedContent> {
        let entry = self
            .entries
            .get_mut(handle)
            .ok_or(SpatialError::UnknownHandle)?;
        if entry.consumed {
            return Err(SpatialError::HandleConsumed);
        }
        if entry.is_expired(now) {
            return Err(SpatialError::HandleExpired);
        }
        entry.consumed = true;
        Ok(entry.clone())
    }

    pub fn get(&self, handle: &ContentHandleId) -> Option<&StagedContent> {
        self.entries.get(handle)
    }

    /// Drop entries whose capability window has closed.
    pub fn retain_live(&mut self, now: u64) -> usize {
        let before = self.entries.len();
        self.entries.retain(|_, entry| !entry.is_expired(now));
        before - self.entries.len()
    }

    pub fn clear(&mut self) {
        self.entries.clear();
    }
}

impl Default for ContentVault {
    fn default() -> Self {
        Self::default_ttl()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flowww_core::Capability;

    fn device(id: &str) -> DeviceId {
        DeviceId::new(id).unwrap()
    }

    fn content() -> Content {
        Content::text(device("fw_source_01"), "spatial payload").unwrap()
    }

    fn target(id: &str, x: f32, y: f32, seen: u64) -> SpatialTarget {
        SpatialTarget::new(device(id), "Marker", x, y, seen).unwrap()
    }

    #[test]
    fn hold_drop_and_reset_cycle() {
        let mut session = SpatialSession::default_ttl();
        assert_eq!(session.state(), SpatialState::Idle);
        session
            .pick(content(), Some(device("fw_phone_001")))
            .unwrap();
        assert_eq!(session.state(), SpatialState::Holding);
        let held = session.drop_content().unwrap();
        assert_eq!(held.content.name, "Text");
        assert_eq!(session.state(), SpatialState::Completed);
        session.reset();
        assert_eq!(session.state(), SpatialState::Idle);
        assert!(session.pick(content(), None).is_ok());
    }

    #[test]
    fn a_second_pick_is_rejected() {
        let mut session = SpatialSession::default_ttl();
        session.pick(content(), None).unwrap();
        assert!(matches!(
            session.pick(content(), None),
            Err(SpatialError::AlreadyHolding)
        ));
    }

    #[test]
    fn expired_holds_cannot_be_dropped() {
        let mut session = SpatialSession::new(Duration::from_millis(1));
        session.pick(content(), None).unwrap();
        std::thread::sleep(Duration::from_millis(5));
        assert!(session.expire_if_needed(unix_millis()));
        assert_eq!(session.state(), SpatialState::Expired);
        assert!(matches!(session.drop_content(), Err(SpatialError::Expired)));
    }

    #[test]
    fn cancelling_clears_content() {
        let mut session = SpatialSession::default_ttl();
        session.pick(content(), None).unwrap();
        session.cancel();
        assert_eq!(session.state(), SpatialState::Cancelled);
        assert!(session.held().is_none());
    }

    #[test]
    fn nearest_respects_radius_and_freshness() {
        let mut registry = SpatialRegistry::new(Duration::from_secs(10), 0.2);
        let now = unix_millis();
        registry
            .upsert(target("fw_target_01", 0.5, 0.5, now))
            .unwrap();
        registry
            .upsert(target("fw_target_02", 0.9, 0.9, now))
            .unwrap();
        registry
            .upsert(target("fw_target_03", 0.52, 0.5, now - 20_000))
            .unwrap();
        let hit = registry.nearest(0.51, 0.5, now).unwrap();
        assert_eq!(hit.device_id.as_str(), "fw_target_01");
        assert!(registry.nearest(0.1, 0.1, now).is_none());
    }

    #[test]
    fn stale_markers_are_removed() {
        let mut registry = SpatialRegistry::new(Duration::from_secs(5), 0.3);
        let now = unix_millis();
        registry
            .upsert(target("fw_target_01", 0.4, 0.4, now - 9_000))
            .unwrap();
        assert_eq!(registry.retain_fresh(now), 1);
        assert!(registry.is_empty());
    }

    #[test]
    fn route_rules_cover_all_three_paths() {
        let source = device("fw_desktop_01");
        let controller = device("fw_phone_001");
        let destination = device("fw_desktop_02");
        assert_eq!(
            resolve_drop(&source, Some(&controller), &destination, 0.02).route,
            TransferRoute::PhoneRelay
        );
        assert_eq!(
            resolve_drop(&controller, Some(&controller), &destination, 0.02).route,
            TransferRoute::PhoneToDesktop
        );
        assert_eq!(
            resolve_drop(&source, Some(&controller), &controller, 0.02).route,
            TransferRoute::Direct
        );
        assert_eq!(
            resolve_drop(&source, None, &destination, 0.02).route,
            TransferRoute::Direct
        );
    }

    #[test]
    fn coordinates_are_validated() {
        assert!(SpatialTarget::new(device("fw_target_01"), "Marker", f32::NAN, 0.5, 0).is_err());
        assert!(SpatialTarget::new(device("fw_target_01"), " ", 0.5, 0.5, 0).is_err());
        assert!(SpatialTarget::new(device("fw_target_01"), "Marker", 2.0, 2.0, 0).is_ok());
    }

    #[test]
    fn capability_set_includes_spatial_targets() {
        assert!(Capability::standard().contains(&Capability::SpatialTarget));
    }

    #[test]
    fn vault_mints_opaque_single_use_handles() {
        let mut vault = ContentVault::new(Duration::from_secs(30));
        let staged = vault
            .stage(content(), Some(PathBuf::from("C:/private/payload.txt")))
            .unwrap();
        assert!(staged.handle.as_str().starts_with("fh_"));
        assert!(staged.content.is_wire_safe());
        assert_eq!(
            staged.local_path.as_deref(),
            Some(Path::new("C:/private/payload.txt"))
        );
        let now = unix_millis();
        assert!(vault.live(&staged.handle, now).is_ok());
        let consumed = vault.consume(&staged.handle, now).unwrap();
        assert_eq!(consumed.handle, staged.handle);
        assert!(matches!(
            vault.consume(&staged.handle, now),
            Err(SpatialError::HandleConsumed)
        ));
    }

    #[test]
    fn vault_rejects_handles_with_local_paths_in_the_descriptor() {
        let mut vault = ContentVault::default_ttl();
        let source = device("fw_source_01");
        let content = Content::file(
            source,
            "report.pdf",
            "application/pdf",
            10,
            flowww_core::ContentHash([1u8; 32]),
            "C:/private/report.pdf",
        )
        .unwrap();
        assert!(matches!(
            vault.stage(content, None),
            Err(SpatialError::UnsafeContent)
        ));
    }

    #[test]
    fn vault_handles_expire() {
        let mut vault = ContentVault::new(Duration::from_millis(1));
        let staged = vault.stage(content(), None).unwrap();
        std::thread::sleep(Duration::from_millis(5));
        assert!(matches!(
            vault.live(&staged.handle, unix_millis()),
            Err(SpatialError::HandleExpired)
        ));
    }
}
