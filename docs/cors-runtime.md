# Credentialed CORS Configuration

The backend treats `CORS_ALLOWED_ORIGINS` as the complete list of browser origins
that may make credentialed API requests. The development default contains
exactly:

```text
http://localhost:5173,http://localhost:5174
```

Supplying the environment variable replaces that default rather than extending
it. Separate multiple origins with commas. Surrounding whitespace is trimmed and
exact duplicates are removed while preserving the configured order.

Each entry must be an exact absolute `http` or `https` origin with a host and an
optional valid port. Configuration fails at startup for blank entries, `null`,
wildcards or origin patterns, user information, non-root paths, queries,
fragments, missing hosts, unsupported schemes, and invalid ports. Credentialed
CORS never reflects an unconfigured request origin.

For production, set exact trusted origins, for example:

```text
CORS_ALLOWED_ORIGINS=https://app.example.com,https://admin.example.com
```

Do not configure `*`, `https://*.example.com`, opaque sandbox origins, or broad
localhost port patterns. Allowed preflight and actual responses echo the exact
request origin and include `Access-Control-Allow-Credentials: true`. A readable
Redis dependency 503 retains these headers only when MVC already authorized the
origin.
