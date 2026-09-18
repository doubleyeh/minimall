import js from '@eslint/js'
import prettierConfig from 'eslint-config-prettier'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

/**
 * ESLint flat config(前端文档 1 要求 flat config)。
 *
 * 两点说明:
 * 1. **关掉 `no-undef`**:`unplugin-auto-import` 注入的 `useMessage`/`ref` 这类标识符在源码里没有 import,
 *    ESLint 会当成未定义变量。TS 的 strict 已经能查出真正的拼写错误,这里不再重复一份白名单。
 * 2. `eslint-config-prettier` 放最后,负责关掉与 Prettier 冲突的格式类规则 —— 格式只由 Prettier 管。
 */
export default tseslint.config(
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      // 这两个文件由 unplugin 生成,不参与 lint
      'src/types/auto-imports.d.ts',
      'src/types/components.d.ts',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: ['.vue'],
        sourceType: 'module',
      },
    },
  },
  {
    rules: {
      // 视图层大量使用 index.vue 这类约定文件名,这条规则在本项目里是误报
      'vue/multi-word-component-names': 'off',
      // 类型检查已经覆盖,避免与 TS 的检查重复
      'no-undef': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    },
  },
  prettierConfig,
)
