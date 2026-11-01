#!/bin/sh
# Copyright 2026 Kingsrook, LLC
# SPDX-License-Identifier: Apache-2.0

set -eu
# This first request must fail with the ordinary TLS validator, before trust.
dotnet /app/NativeAuth.dll assert-untrusted
test -s /trust/root.crt
cp /trust/root.crt /usr/local/share/ca-certificates/printing-native-fixture.crt
update-ca-certificates >/dev/null 2>&1
exec dotnet /app/NativeAuth.dll test
