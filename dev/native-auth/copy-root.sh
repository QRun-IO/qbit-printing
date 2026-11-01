#!/bin/sh
set -eu
attempt=0
while [ ! -s /data/caddy/pki/authorities/local/root.crt ]; do
   attempt=$((attempt + 1))
   if [ "$attempt" -ge 60 ]; then
      echo 'The fixture public CA certificate did not become ready.' >&2
      exit 1
   fi
   sleep 1
done
# Never copy the key or mount the private CA volume into a client/server.
cp /data/caddy/pki/authorities/local/root.crt /trust/root.crt
chmod 0644 /trust/root.crt
