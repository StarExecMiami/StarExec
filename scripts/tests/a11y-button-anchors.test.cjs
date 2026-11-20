const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '../..');
const webapp = path.join(root, 'starexec-app/src/main/webapp');
const masterSrc = fs.readFileSync(path.join(webapp, 'js/master.js'), 'utf8');

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (/\.(jsp|tag)$/.test(entry.name)) out.push(full);
  }
  return out;
}

test('all interactive anchor elements without href have role="button" and tabindex="0"', () => {
  const missing = [];
  const aRe = /<a\b([^>]*)>/g;
  for (const file of walk(webapp)) {
    const src = fs.readFileSync(file, 'utf8');
    let m;
    while ((m = aRe.exec(src)) !== null) {
      const attrs = m[1];
      if (/href\s*=/.test(attrs) || /name\s*=/.test(attrs)) continue;
      if (!/role\s*=\s*["']button["']/.test(attrs) || !/tabindex\s*=\s*["']0["']/.test(attrs)) {
        const line = src.slice(0, m.index).split('\n').length;
        missing.push(`${path.relative(webapp, file)}:${line}`);
      }
    }
  }
  assert.deepEqual(missing, []);
});

test('master.js defines Enter and Space keyboard activation for non-href role="button" anchors', () => {
  assert.match(masterSrc, /\$\(document\)\.on\("keydown",\s*'a\[role="button"\]:not\(\[href\]\)'/);
  assert.match(masterSrc, /e\.key === "Enter"/);
  assert.match(masterSrc, /e\.key === " "/);
  assert.match(masterSrc, /!\$\(this\)\.hasClass\("ui-button"\)/);
});
