# Frontend

The React/Mantine application lives in `src/`. It calls the backend through
relative `/api` URLs. During local development, Vite proxies those requests to
`API_PROXY_TARGET` (default: `http://127.0.0.1:12000`).

## Run locally

```sh
npm ci
npm run dev
```

To run the complete app with MongoDB and MinIO, follow the root
[README](../README.md#start-locally).

## Checks

```sh
npm run typecheck
npm run lint
npm run vitest
npm run build
```

Real-browser tests use Playwright and the isolated services described in
[testing](../docs/TESTING.md).
