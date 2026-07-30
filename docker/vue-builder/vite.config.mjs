import path from 'node:path'
import vue from '/opt/vue-builder/node_modules/@vitejs/plugin-vue/dist/index.mjs'

const projectRoot = '/workspace'
const dependenciesRoot = '/opt/vue-builder/node_modules'

export default {
  root: projectRoot,
  base: './',
  publicDir: path.join(projectRoot, 'public'),
  plugins: [vue()],
  resolve: {
    alias: [
      { find: '@', replacement: path.join(projectRoot, 'src') },
      {
        find: /^vue$/,
        replacement: path.join(dependenciesRoot, 'vue/dist/vue.runtime.esm-bundler.js')
      },
      {
        find: /^vue-router$/,
        replacement: path.join(dependenciesRoot, 'vue-router/dist/vue-router.mjs')
      }
    ]
  },
  build: {
    outDir: path.join(projectRoot, 'dist'),
    emptyOutDir: true,
    sourcemap: false
  }
}
