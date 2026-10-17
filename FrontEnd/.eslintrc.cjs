module.exports = {
  root: true,
  env: { browser: true, node: true, es2022: true },
  parser: '@typescript-eslint/parser',
  parserOptions: { ecmaVersion: 'latest', sourceType: 'module', ecmaFeatures: { jsx: true } },
  plugins: ['@typescript-eslint', 'react-hooks'],
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react-hooks/recommended',
  ],
  rules: { 'no-undef': 'off', '@typescript-eslint/no-explicit-any': 'error' },
  overrides: [
    { files: ['**/*.test.ts', '**/*.test.tsx'], env: { jest: true } },
    { files: ['e2e/**/*.ts'], rules: { 'no-empty-pattern': 'off' } },
  ],
  ignorePatterns: ['dist', 'node_modules', 'playwright-report', 'test-results'],
};
