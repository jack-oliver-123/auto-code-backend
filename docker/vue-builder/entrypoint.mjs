import { copyFile } from 'node:fs/promises'
import { build } from 'vite'

const runtimeConfig = '/tmp/auto-code-vite.config.mjs'

await copyFile('/opt/vue-builder/vite.config.mjs', runtimeConfig)
await build({
  root: '/workspace',
  configFile: runtimeConfig
})
