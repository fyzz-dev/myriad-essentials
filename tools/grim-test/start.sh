#!/usr/bin/env bash
# Starts the server in the background. --experimental turns on Grim's experimental checks (2b2t's setting is unknown).
set -euo pipefail
cd "$(dirname "$0")"
source versions.env
[[ -f server/paper.jar ]] || ./setup.sh
if ./cmd list >/dev/null 2>&1; then echo "already running"; exit 0; fi

grim_config=server/plugins/GrimAC/config.yml
experimental=false; [[ "${1:-}" == "--experimental" ]] && experimental=true
if [[ -f "$grim_config" ]]; then
	# Every flag, with its details, in the console log (where ./flags reads them).
	python3 - "$grim_config" "$experimental" <<'PY'
import re, sys
path, experimental = sys.argv[1], sys.argv[2]
s = open(path).read()
s = re.sub(r'(?m)^(alerts:\n(?:[ \t]+#.*\n)*[ \t]+print-to-console:) \w+', r'\1 true', s)
s = re.sub(r'(?m)^(verbose:\n(?:[ \t]+#.*\n)*[ \t]+print-to-console:) \w+', r'\1 true', s)
s = re.sub(r'(?m)^experimental-checks: \w+', 'experimental-checks: ' + experimental, s)
s = re.sub(r'(?m)^check-for-updates: \w+', 'check-for-updates: false', s)
open(path, 'w').write(s)
PY
	# Alert on every violation (Grim normally waits for several). Alerts are only messages: what Grim cancels or sets
	# back doesn't change.
	sed -i -E 's/"[0-9]+:[0-9]+ \[alert\]"/"1:1 [alert]"/' server/plugins/GrimAC/punishments.yml
fi

cd server
nohup "$JAVA" -Xms2G -Xmx3G -jar paper.jar nogui > console.log 2>&1 < /dev/null &
echo $! > server.pid
cd ..
echo -n "starting"
for _ in $(seq 1 120); do
	if grep -q 'Done (' server/console.log 2>/dev/null; then echo; echo "up on localhost:$PORT (lagged: localhost:$LAG_PORT via ./lag.py)"; exit 0; fi
	if ! kill -0 "$(cat server/server.pid)" 2>/dev/null; then echo; echo "server exited:"; tail -20 server/console.log; exit 1; fi
	echo -n "."; sleep 1
done
echo; echo "still starting; see server/console.log"
