// 校验：app.js 引用的 DOM id 是否都存在于 index.html
// 用法：设置环境变量 HTML_FILE / JS_FILE 后执行 node check-dom-ids.js
const fs = require('fs');

const htmlPath = process.env.HTML_FILE;
const jsPath = process.env.JS_FILE;

if (!htmlPath || !jsPath) {
  console.error('需要设置 HTML_FILE 与 JS_FILE 环境变量');
  process.exit(2);
}

const html = fs.readFileSync(htmlPath, 'utf8');
const js = fs.readFileSync(jsPath, 'utf8');

const ids = new Set();
for (const m of html.matchAll(/id="([^"]+)"/g)) {
  ids.add(m[1]);
}

const used = new Set();
for (const m of js.matchAll(/\$\('([^']+)'\)/g)) {
  used.add(m[1]);
}

const missing = [...used].filter((id) => !ids.has(id));
const unused = [...ids].filter((id) => !used.has(id));

console.log('HTML id 数 = ' + ids.size);
console.log('JS 引用 id 数 = ' + used.size);
console.log('HTML 中缺失的 id: ' + (missing.length ? missing.join(', ') : '无'));
console.log('HTML 中未被 JS 使用的 id: ' + (unused.length ? unused.join(', ') : '无'));
process.exit(missing.length > 0 ? 1 : 0);
