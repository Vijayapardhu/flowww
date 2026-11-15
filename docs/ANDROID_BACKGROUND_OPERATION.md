# Android Background Operation

Android sensor registration is currently scoped to the explicit visible
gesture session. The session stops and unregisters sensors when the screen is
left. Active Rust transfers have a declared `dataSync` foreground service, but
its process ownership and restart recovery are not yet complete.

An eventual background gesture session must be user-started, foreground,
notification-visible and permission-compliant. The product must not claim
uninterrupted hidden sensor monitoring.
