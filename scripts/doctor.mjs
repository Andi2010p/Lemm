#!/usr/bin/env node
// Readiness check — tells you, at a glance, what is set up for hosting and what is still on you.
// Read-only: it never changes anything. Run it any time.
//
//   npm run doctor

import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';

const rows = [];
function check(label, pass, hint) { rows.push({ label, pass, hint }); }
function out(cmd, args = []) {
  const r = spawnSync([cmd, ...args].join(' '), { shell: true, encoding: 'utf8' });
  return { code: r.status, text: `${r.stdout || ''}${r.stderr || ''}` };
}
function has(cmd) { return spawnSync(`${cmd} --version`, { shell: true, stdio: 'ignore' }).status === 0; }

const firebase = has('firebase');
check('firebase CLI installed', firebase, 'npm install -g firebase-tools');

let loggedIn = false;
if (firebase) {
  const r = out('firebase', ['login:list']);
  loggedIn = /\[.+\]|Logged in as|@/.test(r.text) && !/No authorized/i.test(r.text);
}
check('logged in to Firebase', loggedIn, 'firebase login   (or: npm run setup)');

check('project linked (.firebaserc)', existsSync('.firebaserc'), 'already committed — should be present');
check('functions deps installed', existsSync('functions/node_modules'), 'npm ci --prefix functions');
check('rules-test deps installed', existsSync('tools/rules-tests/node_modules'), 'npm ci --prefix tools/rules-tests');
check('storage rules present', existsSync('docs/storage.rules'), 'docs/storage.rules should exist');
check('database rules present', existsSync('docs/database.rules.json'), 'docs/database.rules.json should exist');

// Can't reliably probe these without a project round-trip; surface them as manual reminders.
const manual = [
  'Blaze billing ON (Cloud Functions + Storage require it)      → Firebase console → Usage & billing',
  'Budget alert set (e.g. $10/mo)                               → Google Cloud → Billing → Budgets',
  'Secrets set: GEMINI_API_KEY, MAIL_USER, MAIL_APP_PASSWORD    → npm run setup',
  'Auth providers ON: Email/Password + Google                   → console → Authentication → Sign-in method',
  'App Check debug token registered before enforcing            → console → App Check → Manage debug tokens',
];

console.log('\n\x1b[1mLemma hosting readiness\x1b[0m\n');
let ready = 0;
for (const r of rows) {
  const mark = r.pass ? '\x1b[32m✔\x1b[0m' : '\x1b[33m•\x1b[0m';
  console.log(` ${mark} ${r.label}${r.pass ? '' : `  \x1b[2m→ ${r.hint}\x1b[0m`}`);
  if (r.pass) ready++;
}
console.log(`\n \x1b[1m${ready}/${rows.length}\x1b[0m automated checks pass.`);

console.log('\n\x1b[1mManual, console-only steps (can’t be probed from here):\x1b[0m');
for (const m of manual) console.log(`   • ${m}`);

console.log('\nWhen the checks above are green:  \x1b[36mnpm run deploy\x1b[0m');
console.log('Full context:  RELEASE_CHECKLIST.md  and  docs/HOSTING.md');
