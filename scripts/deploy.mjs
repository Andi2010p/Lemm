#!/usr/bin/env node
// One-command deploy for Lemma's Firebase backend.
//
//   npm run deploy                 # test -> lint -> deploy database + storage + functions
//   npm run deploy -- --only functions
//   npm run deploy -- --skip-tests
//
// It refuses to ship if the rules tests or the functions lint fail, so a broken security rule or a
// syntax error never reaches production. Cross-platform (spawns through the shell).

import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';

const args = process.argv.slice(2);
const only = valueOf('--only') || 'database,storage,functions';
const skipTests = args.includes('--skip-tests');

function valueOf(flag) {
  const i = args.indexOf(flag);
  return i >= 0 && args[i + 1] ? args[i + 1] : null;
}

function step(title) { console.log(`\n\x1b[36m▶ ${title}\x1b[0m`); }

function run(cmd, cmdArgs = [], opts = {}) {
  const full = [cmd, ...cmdArgs].join(' ');
  const r = spawnSync(full, { stdio: 'inherit', shell: true, ...opts });
  if (r.status !== 0) {
    console.error(`\n\x1b[31m✖ Failed: ${full}\x1b[0m`);
    process.exit(r.status || 1);
  }
}

function has(cmd) {
  return spawnSync(`${cmd} --version`, { shell: true, stdio: 'ignore' }).status === 0;
}

// ---- preflight ----
if (!has('firebase')) {
  console.error('\x1b[31mfirebase CLI not found.\x1b[0m Install it once:  npm install -g firebase-tools');
  console.error('Then run  npm run setup  to log in and pick the project.');
  process.exit(1);
}

const targets = only.split(',').map((s) => s.trim()).filter(Boolean);
console.log(`Deploying: \x1b[1m${targets.join(', ')}\x1b[0m  (project: default → lemma-37061)`);

// ---- gates ----
if (!skipTests && targets.some((t) => t === 'database' || t === 'storage')) {
  step('Security-rules tests (tools/rules-tests)');
  if (!existsSync('tools/rules-tests/node_modules')) run('npm', ['ci'], { cwd: 'tools/rules-tests' });
  run('npm', ['test'], { cwd: 'tools/rules-tests' });
}

if (targets.includes('functions')) {
  step('Functions lint (functions)');
  if (!existsSync('functions/node_modules')) run('npm', ['ci'], { cwd: 'functions' });
  run('npm', ['run', 'lint'], { cwd: 'functions' });
}

// ---- deploy ----
step(`firebase deploy --only ${targets.join(',')}`);
run('firebase', ['deploy', '--only', targets.join(',')]);

console.log('\n\x1b[32m✔ Deploy complete.\x1b[0m  Run  npm run doctor  any time to check what else is set up.');
