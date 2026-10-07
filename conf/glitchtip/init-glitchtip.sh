#!/bin/sh
# Bootstrap GlitchTip (superuser, org, one Android project, DSN files).
# Idempotent: safe to re-run; will not create duplicate projects.
#
# Set FORCE_GLITCHTIP_INIT=1 to ignore the lock (still idempotent).
# Omit compose.init.yml / this service in prod once provisioned.
set -e

LOCK_FILE="/out/.setup.lock"

if [ -f "$LOCK_FILE" ] && [ "${FORCE_GLITCHTIP_INIT:-0}" != "1" ]; then
  echo "=== GlitchTip init lock present — refreshing DSN files only ==="
  # Still refresh DSN paths if public host env changed; no new projects
  ./manage.py shell < /out/bootstrap.py
  echo "=== Done (lock kept) ==="
  echo "    Host DSN:    $(cat /out/public-dsn.host.txt 2>/dev/null || true)"
  echo "    Android DSN: $(cat /out/public-dsn.android.txt 2>/dev/null || true)"
  exit 0
fi

echo "=== GlitchTip bootstrap (superuser, org, project, DSN files) ==="
./manage.py shell < /out/bootstrap.py

touch "$LOCK_FILE"
echo "=== Done ==="
echo "    Host DSN:    $(cat /out/public-dsn.host.txt 2>/dev/null || true)"
echo "    Android DSN: $(cat /out/public-dsn.android.txt 2>/dev/null || true)"
