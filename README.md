# Alleria Filmotheque
Netflix-style Android client for the Alleria Filmotheque Jellyfin server, with an MX Player-style player.
Server is built in (Api.kt, `SERVER`) and hidden from the login screen.

Player: swipe left/right half for brightness/volume, swipe sideways to seek, double-tap sides for ±10s,
pinch to zoom, long-press for 2x, lock button, PiP with Home, resume + progress sync.

## Settings
- App settings (⚙ on home and login screens): edit the server URL, test the connection, reset to default.
- Player settings (⚙ in the player): quality Original/480p/720p/1080p/2K/4K and audio Original/Stereo/Mono/5.1/7.1.
  Any non-Original choice uses Jellyfin HLS transcoding; choices are remembered.
