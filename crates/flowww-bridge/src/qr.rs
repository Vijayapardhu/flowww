use qrcode::render::svg;
use qrcode::QrCode;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum QrError {
    #[error("the pairing link is too large to encode")]
    Encode(#[from] qrcode::types::QrError),
}

/// Render a pairing URI as a standalone SVG.
///
/// The UI presents it on a white tile in both themes so scanners keep contrast.
pub fn pairing_svg(uri: &str) -> Result<String, QrError> {
    let code = QrCode::new(uri.as_bytes())?;
    Ok(code
        .render::<svg::Color>()
        .min_dimensions(240, 240)
        .quiet_zone(true)
        .build())
}
