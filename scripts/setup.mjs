#!/usr/bin/env node
// First-time hosting setup — walks you through the one-off manual bits so `npm run deploy` just works
// afterwards. Safe to re-run; each step is idempotent.
//
//   npm run setup

import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';

function step(t) { console.log(`\n\x1b[36m▶ ${t}\x1b[0m`); }
function ok(t) { console.log(`\x1b[32m  ✔ ${t}\x1b[0m`); }
function run(cmd, args = [], opts = {}) {
  return spawnSync([cmd, ...args].join(' '), { stdio: 'inherit', shell: true, ...opts }).status === 0;
}
function has(cmd) {
  return spawnSync(`${cmd} --version`, { shell: true, stdio: 'ignore' }).status === 0;
}

console.log('\x1b[1mLemma hosting setup\x1b[0m — Blaze billing must be on first (see RELEASE_CHECKLIST.md → B1).');

if (!has('firebase')) {
  step('Installing firebase-tools globally');
  if (!run('npm', ['install', '-g', 'firebase-tools'])) process.exit(1);
}
ok('firebase CLI present');

step('Logging in (skips if already logged in)');
run('firebase', ['login']);

step('Installing dependencies');
if (!existsSync('functions/node_modules')) run('npm', ['ci'], { cwd: 'functions' });
if (!existsSync('tools/rules-tests/node_modules')) run('npm', ['ci'], { cwd: 'tools/rules-tests' });
ok('dependencies installed');

step('Setting the server secrets (values are stored by Google, never in the repo or the APK)');
console.log('  You will be prompted for each value. Press Ctrl+C to skip any you have already set.');
for (const secret of ['GEMINI_API_KEY', 'MAIL_USER', 'MAIL_APP_PASSWORD']) {
  console.log(`\n  → ${secret}`);
  run('firebase', ['functions:secrets:set', secret]);
}

console.log('\n\x1b[32m✔ Setup done.\x1b[0m  Next:  npm run doctor   then   npm run deploy');
