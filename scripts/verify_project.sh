#!/usr/bin/env bash
set -euo pipefail

required_protocols=(srt hls rtmp rtp udp)
for protocol in "${required_protocols[@]}"; do
  grep -Rqi -- "$protocol" README.md app/src/main || {
    echo "Protocolo ausente: $protocol" >&2
    exit 1
  }
done

test -f app/src/main/AndroidManifest.xml
test -f app/src/main/java/com/isaque/signalplay/MainActivity.kt
test -f app/src/test/java/com/isaque/signalplay/ProtocolContractTest.kt

if grep -En 'CAMERA|RECORD_AUDIO|READ_MEDIA|READ_EXTERNAL|WRITE_EXTERNAL' app/src/main/AndroidManifest.xml; then
  echo "Permissão invasiva encontrada no manifesto" >&2
  exit 1
fi

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

files = list(Path("app/src/main/res").rglob("*.xml")) + [Path("app/src/main/AndroidManifest.xml")]
for file in files:
    ET.parse(file)
print(f"XML válido: {len(files)} arquivos")
PY

echo "Estrutura, protocolos e permissões: OK"
