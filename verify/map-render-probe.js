// 用 DOM stub 在 Node 里执行 app.js 的 renderMap，验证 30 个色块的生成结果。
// 用法：node map-render-probe.js <app.js 路径> <regions json 路径>
const fs = require('fs');
const vm = require('vm');

const appJsPath = process.argv[2];
const regionsPath = process.argv[3];

function makeNode(tag) {
  const node = {
    tagName: tag,
    attributes: {},
    children: [],
    textContent: '',
    value: '',
    disabled: false,
    hidden: false,
    style: {},
    setAttribute(k, v) { this.attributes[k] = String(v); },
    getAttribute(k) { return this.attributes[k]; },
    appendChild(child) { this.children.push(child); return child; },
    addEventListener() {},
    querySelector() { return makeNode('div'); },
    querySelectorAll() { return []; },
    get innerHTML() { return ''; },
    set innerHTML(_) { this.children = []; },
  };
  return node;
}

const elements = new Map();
const document = {
  getElementById(id) {
    if (!elements.has(id)) { elements.set(id, makeNode('div')); }
    return elements.get(id);
  },
  createElement(tag) { return makeNode(tag); },
  createElementNS(_ns, tag) { return makeNode(tag); },
};

const sandbox = {
  document,
  console,
  setTimeout: () => 0,
  clearTimeout: () => {},
  setInterval: () => 0,
  clearInterval: () => {},
  fetch: () => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) }),
};
sandbox.window = sandbox;

const code = fs.readFileSync(appJsPath, 'utf8');
vm.createContext(sandbox);
vm.runInContext(code, sandbox, { filename: 'app.js' });

const regions = JSON.parse(fs.readFileSync(regionsPath, 'utf8'));
const mapNode = document.getElementById('worldMap');
mapNode.children = [];
sandbox.renderMap(regions);

const svg = mapNode;
const polygons = svg.children.filter((c) => c.tagName === 'polygon');
const rects = svg.children.filter((c) => c.tagName === 'rect' && c.getAttribute('width') !== '1000');
const texts = svg.children.filter((c) => c.tagName === 'text');
const titles = rects.flatMap((r) => r.children.filter((c) => c.tagName === 'title'));

console.log('pol 大洲轮廓数量 = ' + polygons.length + '（期望 7）');
console.log('大洲轮廓 points 抽样: ' + polygons.slice(0, 2).map((p) => (p.getAttribute('points') || '').slice(0, 28) + '...').join(' | '));
console.log('rect 区域色块数量 = ' + rects.length + '（期望 30）');
console.log('text 数量 = ' + texts.length + '（期望 30）');
console.log('title 数量 = ' + titles.length + '（期望 30，用于悬停提示）');
console.log('title 格式示例: ' + titles.slice(0, 3).map((t) => t.textContent).join(' | '));

const owned = regions.filter((r) => r.ownerCivId !== 0);
const unowned = regions.filter((r) => r.ownerCivId === 0);
console.log('有主区域 = ' + owned.length + '，无主区域 = ' + unowned.length);

const grayRects = rects.filter((r) => r.getAttribute('fill') === '#444');
console.log('灰色(#444) 色块数 = ' + grayRects.length + '（期望等于无主数 ' + unowned.length + '）');

const playerRects = rects.filter((r) => r.getAttribute('fill') === '#ff2d2d');
const playerRegions = regions.filter((r) => r.ownerCivId === 1);
console.log('玩家红色(#ff2d2d) 色块数 = ' + playerRects.length + '（期望等于玩家区域数 ' + playerRegions.length + '）');
console.log('玩家区域: ' + (playerRegions.map((r) => r.name).join('、') || '（无）'));

const colored = rects.filter((r) => (r.getAttribute('fill') || '').startsWith('hsl('));
const otherOwned = owned.filter((r) => r.ownerCivId !== 1);
console.log('HSL 色块数 = ' + colored.length + '（期望等于其他文明区域数 ' + otherOwned.length + '）');

// 区域块是否落在某块大洲的包围盒内
function bbox(points) {
  const nums = points.split(/\s+/).map((p) => p.split(',').map(Number));
  return {
    x1: Math.min(...nums.map((n) => n[0])), y1: Math.min(...nums.map((n) => n[1])),
    x2: Math.max(...nums.map((n) => n[0])), y2: Math.max(...nums.map((n) => n[1])),
  };
}
const boxes = polygons.map((p) => ({ name: p.children[0] ? p.children[0].textContent : '?', box: bbox(p.getAttribute('points')) }));
let outside = 0;
const outsideNames = [];
for (const r of rects) {
  const x = Number(r.getAttribute('x')), y = Number(r.getAttribute('y'));
  const w = Number(r.getAttribute('width')), h = Number(r.getAttribute('height'));
  const inside = boxes.some((b) => x >= b.box.x1 - 4 && y >= b.box.y1 - 4 && x + w <= b.box.x2 + 4 && y + h <= b.box.y2 + 4);
  if (!inside) { outside++; outsideNames.push(r.children[0].textContent); }
}
console.log('落在所有大洲包围盒之外的色块数 = ' + outside + '（含北极与大西洋岛屿属正常）');
if (outside > 0) { console.log('  外部色块: ' + outsideNames.join('、')); }

console.log('有主区域归属与配色抽样:');
for (const r of regions.filter((x) => x.ownerCivId !== 0).slice(0, 6)) {
  const rect = rects.find((x) => x.children.some((c) => c.tagName === 'title'
    && c.textContent.startsWith(regionName(regions, r.id))));
  console.log('  ' + r.name + ' -> civ#' + r.ownerCivId + ' [' + r.ownerCivName + '] fill='
    + (rect ? rect.getAttribute('fill') : '未找到') + ' 期望 hue=' + ((r.ownerCivId * 137) % 360));
}

function regionName(list, id) {
  const found = list.find((x) => x.id === id);
  return found ? found.name : '';
}

// 同一文明多次出现应得到同一颜色
const byCiv = new Map();
for (const r of owned) {
  const rect = rects.find((x) => x.children.some((c) => c.tagName === 'title'
    && c.textContent.startsWith(r.name + ' ·')));
  if (!rect) { continue; }
  const fill = rect.getAttribute('fill');
  if (!byCiv.has(r.ownerCivId)) { byCiv.set(r.ownerCivId, new Set()); }
  byCiv.get(r.ownerCivId).add(fill);
}
let unstable = 0;
for (const [, set] of byCiv) { if (set.size > 1) { unstable++; } }
console.log('同一文明颜色不稳定的数量 = ' + unstable + '（期望 0）');
