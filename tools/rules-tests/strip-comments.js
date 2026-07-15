'use strict';
const fs = require('fs');

/**
 * Firebase's rules parser accepts // comments; JSON.parse does not. The emulator is happy either
 * way, but rules-unit-testing hands the string to the emulator verbatim, so we keep this here to
 * also let the tests JSON.parse the rules when they want to inspect them.
 */
function stripJsonComments(src) {
  const out = [];
  let i = 0;
  const n = src.length;
  let inStr = false;
  while (i < n) {
    const ch = src[i];
    if (inStr) {
      if (ch === '\\') { out.push(src.slice(i, i + 2)); i += 2; continue; }
      if (ch === '"') inStr = false;
      out.push(ch); i++; continue;
    }
    if (ch === '"') { inStr = true; out.push(ch); i++; continue; }
    if (src.startsWith('//', i)) { while (i < n && src[i] !== '\n') i++; continue; }
    out.push(ch); i++;
  }
  return out.join('');
}

function loadRules(path) {
  const raw = fs.readFileSync(path, 'utf8');
  const stripped = stripJsonComments(raw);
  JSON.parse(stripped); // fail loudly here rather than inside the emulator
  return stripped;
}

module.exports = { stripJsonComments, loadRules };
