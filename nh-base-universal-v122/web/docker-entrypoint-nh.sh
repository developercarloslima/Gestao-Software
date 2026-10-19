#!/bin/sh
set -eu
escape_js() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
SGA_ESCAPED="$(escape_js "${SGA_URL:-}")"
TEAM_ESCAPED="$(escape_js "${TEAM_WHATSAPP_NUMBER:-}")"
BACKEND_ESCAPED="$(escape_js "${BACKEND_API_URL:-}")"
cat > /usr/share/nginx/html/shared/config.js <<CONFIG
window.NH_CONFIG = {
  sgaUrl: "${SGA_ESCAPED}",
  teamWhatsapp: "${TEAM_ESCAPED}",
  backendBaseUrl: "${BACKEND_ESCAPED}"
};
window.NH_API = {
  backend: function (path) {
    var normalized = String(path || '').charAt(0) === '/' ? String(path) : '/' + path;
    var base = String(window.NH_CONFIG.backendBaseUrl || '').replace(/\\/+$/, '');
    return base ? base + normalized : normalized;
  }
};
CONFIG
