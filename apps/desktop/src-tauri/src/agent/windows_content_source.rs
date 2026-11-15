//! Windows implementation of [`flowww_node::ContentSource`].
//!
//! Answers content requests from peers by asking the registered platform
//! providers (clipboard, Explorer) what content they currently hold. The
//! local node's device id is supplied after the node starts, since it is
//! generated inside `Node::start`.

use std::sync::{Arc, Mutex};

use flowww_core::{Content, DeviceId};
use flowww_node::{ContentSource, NodeError, NodeResult, ProviderContent};

use crate::agent::providers::ProviderRegistry;

/// Windows content source: bridges platform providers to the flowww content
/// request path.
///
/// `source_device` starts as `None` and is populated once the node has
/// started and its device id is known.
pub struct WindowsContentSource {
    source_device: Arc<Mutex<Option<DeviceId>>>,
    providers: Arc<ProviderRegistry>,
}

impl WindowsContentSource {
    pub fn new(
        source_device: Arc<Mutex<Option<DeviceId>>>,
        providers: Arc<ProviderRegistry>,
    ) -> Self {
        Self {
            source_device,
            providers,
        }
    }

    /// Publish the local node's device id once the node is running.
    pub fn set_source_device(&self, device_id: DeviceId) {
        *self.source_device.lock().unwrap() = Some(device_id);
    }
}

#[async_trait::async_trait]
impl ContentSource for WindowsContentSource {
    async fn current_content(&self, provider: &str) -> NodeResult<Option<ProviderContent>> {
        let Some(platform) = self.providers.get(provider) else {
            return Ok(None);
        };

        let Some(dto) = platform
            .get_current_content()
            .await
            .map_err(|e| NodeError::Config(e.to_string()))?
        else {
            return Ok(None);
        };

        let guard = self.source_device.lock().unwrap();
        let Some(source_device) = guard.as_ref() else {
            return Err(NodeError::Config(
                "content source not yet initialised".into(),
            ));
        };

        let content = match dto.content_type {
            crate::agent::providers::ContentType::Text => {
                Content::text(source_device.clone(), dto.content_reference)
            }
            crate::agent::providers::ContentType::Url => {
                Content::url(source_device.clone(), dto.content_reference)
            }
            // File, image, video and document content is staged through the
            // provider's stage_content flow; current_content only reports
            // inline clipboard/text content for now.
            _ => return Ok(None),
        }
        .map_err(|e| NodeError::Config(e.to_string()))?;

        Ok(Some(ProviderContent::inline(content)))
    }

    fn providers(&self) -> Vec<String> {
        self.providers.names()
    }
}
