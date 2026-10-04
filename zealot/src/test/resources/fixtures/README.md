# Zealot latest API fixtures

Captured on 2026-10-02 from a real Android channel using the same versioned
`GET /api/apps/latest` request as the SDK. `latest-available.json` is the
response for the preceding installed version; `latest-current.json` is the
response for the latest installed version. `latest-invalid-channel.json`
contains the HTTP status and JSON body for an unknown channel key.

Only fields consumed by the SDK are retained in successful responses. Package
names, versions, install URLs, release notes, and error text are replaced with
example values. Field types, release count, changelog entry count, and HTTP
status are preserved. Credentials and the original server address are omitted.

These fixtures keep contract checks independent of network access and changing
server data. `ZealotLiveApiTest` remains the opt-in check against a real channel.
