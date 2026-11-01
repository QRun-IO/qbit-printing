#!/bin/sh
set -eu
# Only this disposable container's JRE trust store is changed. The mounted file
# is the public root certificate; the CA private key is never mounted here.
test -s /trust/root.crt
keytool -importcert -noprompt -alias printing-native-fixture -file /trust/root.crt -cacerts -storepass changeit >/dev/null 2>&1
exec java -Xmx768m -Dlog4j.configurationFile=/app/log4j2.xml -cp '/app/classes:/app/lib/*:/app/fixture' com.kingsrook.qbits.printing.NativeAuthHost
