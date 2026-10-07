#!/usr/bin/env bash
# Downloads the server and plugins and writes a 2b2t-like config. Safe to re-run; the world is kept.
# --update: list the newest Paper/Grim/ViaVersion builds instead (edit versions.env to move to them).
set -euo pipefail
cd "$(dirname "$0")"
source versions.env

if [[ "${1:-}" == "--update" ]]; then
	curl -s "https://fill.papermc.io/v3/projects/paper/versions/$PAPER_VERSION/builds/latest" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("paper     ", d["downloads"]["server:default"]["url"])'
	curl -s "https://api.modrinth.com/v2/project/grimac/version?loaders=%5B%22paper%22%5D&game_versions=%5B%22$PAPER_VERSION%22%5D" | python3 -c 'import json,sys; v=json.load(sys.stdin)[0]; print("grim      ", v["files"][0]["url"])'
	curl -s "https://api.modrinth.com/v2/project/viaversion/version?loaders=%5B%22paper%22%5D" | python3 -c 'import json,sys; v=[v for v in json.load(sys.stdin) if v["version_type"]=="release"][0]; print("viaversion", v["files"][0]["url"], v["game_versions"][-1])'
	exit 0
fi

mkdir -p server/plugins
fetch() { [[ -f "$2" ]] || { echo "downloading $(basename "$2")"; curl -sSfL -o "$2" "$1"; }; }
fetch "$PAPER_URL" server/paper.jar
rm -f server/plugins/grimac-*.jar.old
fetch "$GRIM_URL" "server/plugins/$(basename "$GRIM_URL")"
fetch "$VIAVERSION_URL" "server/plugins/$(basename "$VIAVERSION_URL")"

echo "eula=true" > server/eula.txt

# 2b2t-like where it's public knowledge: 1.21.4 behind ViaVersion, Grim, hard survival, the 2b2t seed, no spawn
# protection, Nether and End on. Offline mode so the dev client (no Microsoft login) can join.
cat > server/server.properties <<PROPS
online-mode=false
enforce-secure-profile=false
server-port=$PORT
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
broadcast-rcon-to-ops=false
level-name=world
level-seed=-4172144997902289642
gamemode=survival
force-gamemode=false
difficulty=hard
pvp=true
spawn-protection=0
allow-nether=true
allow-flight=false
view-distance=8
simulation-distance=6
max-players=20
motd=Grim test server (2b2t-like)
network-compression-threshold=256
PROPS
echo "set up in $(pwd)/server"
