// 星球演化 · 前端逻辑（第一阶段：真实 API 数据展示与手动推进）
const MAJOR_THRESHOLD = 60;
const MAX_EVENTS_SHOWN = 200;
const BATCH_YEARS = 200;
const AUTO_REFRESH_INTERVAL_MS = 1000;
// 推进结束后，用一轮密集刷新把异步生成的 AI 叙事尽快拉下来
const NARRATIVE_POLL_INTERVAL_MS = 1000;
const NARRATIVE_POLL_TIMES = 5;
const RARITY_LABELS = {
  CIVILIZATION_BIRTH: '文明诞生',
  DISASTER: '灾害',
  WAR: '战争',
  TECH_BREAKTHROUGH: '科技突破',
  EXTINCTION: '文明灭亡',
  AI_NARRATIVE: 'AI叙事',
  AI_EVENT: '世界事件',
};

const $ = (id) => document.getElementById(id);

function showError(message) {
  const box = $('error');
  box.textContent = message;
  box.hidden = !message;
}

// 轻量提示：复用底部提示条，2 秒后自动消失
let noticeTimer = null;

function showNotice(message, durationMs = 2000) {
  showError(message);
  if (noticeTimer !== null) {
    clearTimeout(noticeTimer);
  }
  noticeTimer = setTimeout(() => {
    noticeTimer = null;
    showError('');
  }, durationMs);
}

function num(value) {
  return Number(value).toLocaleString('zh-CN');
}

function decimal(value) {
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 1 });
}

async function api(path, options) {
  const response = await fetch(path, options);
  if (!response.ok) {
    throw new Error(`${path} 返回 ${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}

function post(path, body) {
  return api(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  });
}

function renderWorld(state) {
  const world = state.world;
  $('planetName').textContent = world.planet;
  $('year').textContent = num(world.year);
  $('population').textContent = num(world.population);
  $('techTotal').textContent = num(world.techTotal);
  $('food').textContent = num(Math.round(world.food));
  $('resources').textContent = num(Math.round(world.resources));
  $('temperature').textContent = decimal(world.temperature) + ' °C';
  $('habitability').textContent = decimal(world.habitability);
  $('era').textContent = world.era || '-';
  $('clock').textContent = (state.paused ? '暂停' : '运行中') + `（速度 ${state.speed}，仅状态）`;
  renderCivilizations(state.civilizations, world.aliveCivilizations, world.extinctCivilizations);
}

function renderCivilizations(civilizations, aliveCivilizations, extinctCivilizations) {
  const tbody = $('civilizations').querySelector('tbody');
  tbody.innerHTML = '';
  const list = Array.isArray(civilizations) ? civilizations.filter(Boolean) : [];
  // 只显示存活文明（population > 0），灭亡文明不出现在列表中
  const aliveList = list.filter((c) => c.population > 0);
  if (aliveList.length === 0) {
    const emptyRow = document.createElement('tr');
    emptyRow.innerHTML = '<td colspan="5">暂无存活文明</td>';
    tbody.appendChild(emptyRow);
  }
  for (const c of aliveList) {
    const row = document.createElement('tr');
    row.innerHTML =
      `<td>${c.name}</td><td>${c.foundedYear}</td>` +
      `<td>${num(c.population)}</td>` +
      `<td>${c.techLevel}</td>` +
      `<td>存活</td>`;
    tbody.appendChild(row);
  }
  // 优先使用后端计数，未提供时按列表回退统计
  const aliveFromList = aliveList.length;
  const alive = Number.isFinite(aliveCivilizations) ? aliveCivilizations : aliveFromList;
  const extinct = Number.isFinite(extinctCivilizations)
    ? extinctCivilizations
    : list.length - aliveFromList;
  $('civCount').textContent = `存活 ${alive} / 累计 ${alive + extinct}`;
}

function renderEvents(events) {
  const list = $('events');
  list.innerHTML = '';
  // 空值防御：非数组按空数组处理，过滤 null / undefined
  // 保留重大事件（稀有度 >= 60）与 AI 叙事事件
  const items = (Array.isArray(events) ? events : [])
    .filter(Boolean)
    .filter((e) => e.rarity >= MAJOR_THRESHOLD || e.type === 'AI_NARRATIVE');
  const majorCount = items.filter((e) => e.rarity >= MAJOR_THRESHOLD).length;
  $('eventCount').textContent = `共 ${items.length}，重大 ${majorCount}`;

  if (items.length === 0) {
    const li = document.createElement('li');
    li.className = 'event';
    li.innerHTML = '<span class="empty">暂无历史事件</span>';
    list.appendChild(li);
    return;
  }

  for (const event of items.slice(-MAX_EVENTS_SHOWN).reverse()) {
    const major = event.rarity >= MAJOR_THRESHOLD;
    const li = document.createElement('li');
    li.className = major ? 'event major' : 'event';
    li.innerHTML =
      `<span class="meta">Y${event.year} · ${RARITY_LABELS[event.type] || event.type}</span>` +
      `<span class="rarity">稀有度 ${event.rarity}${major ? ' · 重大' : ''}</span> ` +
      `<span class="desc">${event.description}</span>`;
    list.appendChild(li);
  }
}

// 7 大洲轮廓（纯手写 SVG 坐标，示意用，不精确）：points 为 "x,y x,y ..."
const MAP_CONTINENTS = [
  { name: '北美洲', points: '60,90 150,55 250,55 290,95 280,170 240,205 215,250 185,235 165,195 120,180 70,140' },
  { name: '南美洲', points: '195,265 290,258 320,300 300,360 275,420 240,450 210,420 190,360 178,310' },
  { name: '欧洲',   points: '445,160 455,100 495,70 550,58 605,70 640,100 630,150 595,182 535,192 480,185 460,172' },
  { name: '非洲',   points: '450,208 545,200 615,210 640,240 620,290 590,340 555,385 515,400 480,380 462,330 445,270' },
  { name: '亚洲',   points: '628,100 655,62 730,40 830,40 915,62 958,110 952,175 935,235 930,275 900,310 860,330 820,318 780,298 750,255 700,236 668,250 648,214 638,152' },
  { name: '大洋洲', points: '765,330 830,300 910,308 950,345 948,382 920,420 850,432 790,420 762,382' },
  { name: '南极洲', points: '200,455 700,455 720,478 660,492 300,492 210,478' }
];

// 30 个区域的色块坐标：摆放位置对应所属大洲（x/y 左上角，w/h 尺寸）
const MAP_REGIONS = [
  // 亚洲
  { id: 6,  name: '北亚',       x: 680, y: 50,  w: 170, h: 45 },
  { id: 1,  name: '东亚',       x: 868, y: 95,  w: 78,  h: 70 },
  { id: 2,  name: '东南亚',     x: 858, y: 240, w: 70,  h: 45 },
  { id: 3,  name: '南亚',       x: 788, y: 196, w: 64,  h: 60 },
  { id: 4,  name: '中亚',       x: 700, y: 132, w: 85,  h: 55 },
  { id: 5,  name: '西亚',       x: 660, y: 189, w: 60,  h: 48 },
  // 欧洲
  { id: 11, name: '北欧',       x: 535, y: 62,  w: 60,  h: 45 },
  { id: 7,  name: '西欧',       x: 452, y: 105, w: 62,  h: 45 },
  { id: 10, name: '中欧',       x: 535, y: 112, w: 60,  h: 42 },
  { id: 9,  name: '东欧',       x: 598, y: 108, w: 42,  h: 52 },
  { id: 8,  name: '南欧',       x: 505, y: 160, w: 70,  h: 28 },
  // 非洲
  { id: 12, name: '北非',       x: 458, y: 208, w: 150, h: 48 },
  { id: 13, name: '西非',       x: 455, y: 262, w: 72,  h: 58 },
  { id: 14, name: '中非',       x: 528, y: 262, w: 60,  h: 58 },
  { id: 15, name: '东非',       x: 590, y: 258, w: 45,  h: 72 },
  { id: 16, name: '南非',       x: 528, y: 328, w: 62,  h: 52 },
  // 南美洲
  { id: 21, name: '南美北部',   x: 205, y: 265, w: 85,  h: 55 },
  { id: 22, name: '南美西部',   x: 200, y: 325, w: 50,  h: 80 },
  { id: 23, name: '南美东部',   x: 258, y: 320, w: 55,  h: 75 },
  { id: 24, name: '南美南部',   x: 225, y: 405, w: 55,  h: 35 },
  // 北美洲
  { id: 18, name: '北美西部',   x: 70,  y: 95,  w: 90,  h: 80 },
  { id: 17, name: '北美东部',   x: 175, y: 85,  w: 100, h: 85 },
  { id: 19, name: '中美',       x: 165, y: 200, w: 65,  h: 45 },
  { id: 20, name: '加勒比',     x: 235, y: 205, w: 50,  h: 35 },
  // 大洋洲
  { id: 25, name: '澳大利亚',   x: 790, y: 345, w: 95,  h: 65 },
  { id: 26, name: '新西兰',     x: 890, y: 385, w: 40,  h: 30 },
  { id: 27, name: '太平洋岛屿', x: 840, y: 310, w: 70,  h: 34 },
  // 极地与岛屿
  { id: 29, name: '北极',       x: 300, y: 8,   w: 220, h: 32 },
  { id: 30, name: '大西洋岛屿', x: 330, y: 205, w: 75,  h: 55 },
  { id: 28, name: '南极洲',     x: 260, y: 458, w: 300, h: 30 }
];

// 玩家文明固定 id（与后端一致）；用最醒目的红色
const PLAYER_CIV_ID = 1;
const PLAYER_COLOR = '#ff2d2d';

// 文明配色：玩家红色，其余按 civId 取稳定色相
function colorForCiv(civId) {
  if (civId === PLAYER_CIV_ID) {
    return PLAYER_COLOR;
  }
  const hue = (civId * 137) % 360;
  return `hsl(${hue}, 60%, 55%)`;
}

const SVG_NS = 'http://www.w3.org/2000/svg';

/** 按区域归属绘制世界地图：先海洋底、再各大洲轮廓、最后区域色块。 */
function renderMap(regions) {
  const svg = $('worldMap');
  svg.innerHTML = '';
  const owners = new Map();
  for (const region of regions || []) {
    owners.set(region.id, region);
  }

  // 海洋底色
  const ocean = document.createElementNS(SVG_NS, 'rect');
  ocean.setAttribute('x', 0);
  ocean.setAttribute('y', 0);
  ocean.setAttribute('width', 1000);
  ocean.setAttribute('height', 500);
  ocean.setAttribute('fill', '#141a26');
  svg.appendChild(ocean);

  // 7 大洲轮廓（灰暗底，无边界色）
  for (const continent of MAP_CONTINENTS) {
    const polygon = document.createElementNS(SVG_NS, 'polygon');
    polygon.setAttribute('points', continent.points);
    polygon.setAttribute('fill', '#2a2d35');
    polygon.setAttribute('stroke', '#3a3f4b');
    polygon.setAttribute('stroke-width', '1.5');
    polygon.setAttribute('stroke-linejoin', 'round');
    const title = document.createElementNS(SVG_NS, 'title');
    title.textContent = continent.name;
    polygon.appendChild(title);
    svg.appendChild(polygon);
  }

  for (const shape of MAP_REGIONS) {
    const region = owners.get(shape.id);
    const ownerId = region ? region.ownerCivId : 0;
    const ownerName = region && region.ownerCivName ? region.ownerCivName : '无主';

    const rect = document.createElementNS(SVG_NS, 'rect');
    rect.setAttribute('x', shape.x);
    rect.setAttribute('y', shape.y);
    rect.setAttribute('width', shape.w);
    rect.setAttribute('height', shape.h);
    rect.setAttribute('rx', 4);
    rect.setAttribute('fill', ownerId === 0 ? '#444' : colorForCiv(ownerId));
    rect.setAttribute('stroke', '#0b1020');
    rect.setAttribute('stroke-width', '1.5');

    // 悬停提示：区域名 · 归属文明名
    const title = document.createElementNS(SVG_NS, 'title');
    title.textContent = `${shape.name} · ${ownerName}`;
    rect.appendChild(title);
    svg.appendChild(rect);

    const text = document.createElementNS(SVG_NS, 'text');
    text.setAttribute('x', shape.x + shape.w / 2);
    text.setAttribute('y', shape.y + shape.h / 2 + 4);
    text.setAttribute('text-anchor', 'middle');
    text.setAttribute('font-size', '11');
    text.setAttribute('fill', '#e8ecf7');
    text.setAttribute('pointer-events', 'none');
    text.textContent = shape.name;
    svg.appendChild(text);
  }
}

async function refresh() {
  const [state, events, regions] = await Promise.all([
    api('/api/world'),
    api('/api/world/events'),
    api('/api/world/regions'),
  ]);
  renderWorld(state);
  renderEvents(events);
  renderMap(regions);
}

async function run(action) {
  showError('');
  try {
    await action();
    await refresh();
  } catch (error) {
    showError('操作失败：' + error.message);
  }
}

async function advanceYears() {
  const total = Math.min(10000, Math.max(1, Number($('years').value) || 1));
  $('years').value = total;
  const btn = $('advance');
  let done = 0;
  btn.disabled = true;
  btn.textContent = `推进中 0/${total} 年`;
  try {
    while (done < total) {
      const chunk = Math.min(BATCH_YEARS, total - done);
      const result = await post('/api/world/advance', { years: chunk });
      done += chunk;
      if (result && result.year !== undefined) {
        $('year').textContent = num(result.year);
      }
      btn.textContent = `推进中 ${done}/${total} 年`;
      await refresh();
    }
  } finally {
    btn.disabled = false;
    btn.textContent = '推进';
  }
  // 全部批次推完后：每 1 秒刷新一次，共 5 次，不等下一轮自动刷新
  for (let i = 1; i <= NARRATIVE_POLL_TIMES; i++) {
    setTimeout(() => refresh().catch(() => {}), NARRATIVE_POLL_INTERVAL_MS * i);
  }
}

// 跳过时代：可选目标由后端算（当前时代之后最多 5 个），前端只负责让用户选一个
async function skipEra() {
  const options = await api('/api/world/eras');
  const targets = (options && options.targets) || [];
  if (targets.length === 0) {
    showError('已经是最后一个时代，没有可跳的目标。');
    return;
  }
  const list = targets.map((t, i) => `${i + 1}. ${t.name}`).join('\n');
  const answer = prompt(`当前时代：${options.currentEra}\n\n跳到哪个时代？输入序号：\n${list}`, '1');
  if (answer === null) {
    return;
  }
  const chosen = targets[Number(answer.trim()) - 1];
  if (!chosen) {
    showError('没有这个序号，已取消。');
    return;
  }
  if (!confirm(`确定跳到「${chosen.name}」？科技、人口、食物、资源都会按时代比例整体放大。`)) {
    return;
  }
  const result = await post('/api/world/skip-era', { targetEraIndex: chosen.index });
  if (result && result.result) {
    showNotice(result.result, 6000);
  } else {
    showError('跳过时代失败：' + ((result && result.error) || '未知错误'));
  }
}

function bind() {
  $('advance').addEventListener('click', () => run(advanceYears));

  $('skipEra').addEventListener('click', () => run(skipEra));

  $('runToMajor').addEventListener('click', () => run(async () => {
    const result = await post('/api/world/run-to-major', { maxYears: 5000 });
    if (result && !result.found) {
      showError(result.message || '达到最大搜索年限，未出现重大事件');
    }
  }));

  $('pause').addEventListener('click', () => run(() => post('/api/world/pause')));
  $('resume').addEventListener('click', () => run(() => post('/api/world/resume')));

  $('setSpeed').addEventListener('click', () => run(async () => {
    const speed = Math.min(100, Math.max(1, Number($('speed').value) || 1));
    $('speed').value = speed;
    await post('/api/world/speed', { speed });
  }));

  $('saveWorld').addEventListener('click', () => run(async () => {
    const result = await post('/api/world/save');
    if (result && result.saved) {
      showNotice('已保存');
    } else {
      showError('保存失败：' + ((result && result.error) || '未知错误'));
    }
  }));

  // 新游戏：清空世界从零开始（不可恢复，先确认）
  $('newGame').addEventListener('click', () => run(async () => {
    if (!confirm('确定要重开吗？当前世界会被清空，无法恢复。')) {
      return;
    }
    const result = await post('/api/world/new-game');
    if (result && result.error) {
      showError('世界已重置，但存档写入失败：' + result.error);
      return;
    }
    showNotice(result && result.result ? result.result : '世界已重置。', 6000);
  }));

  const sendCommand = () => run(async () => {
    const text = $('commandText').value.trim();
    if (!text) {
      $('commandResult').textContent = '请先输入一条指令。';
      return;
    }
    const button = $('sendCommand');
    button.disabled = true;
    $('commandResult').textContent = '正在解析指令……';
    try {
      const result = await post('/api/command', { text });
      if (result && result.result) {
        $('commandResult').textContent = result.result;
      } else {
        $('commandResult').textContent = '指令失败：' + ((result && result.error) || '未知错误');
      }
    } finally {
      button.disabled = false;
    }
  });

  $('sendCommand').addEventListener('click', sendCommand);
  $('commandText').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      sendCommand();
    }
  });
}

// 自动刷新：每 3 秒拉一次最新数据，用于看到异步生成的 AI 叙事
let autoRefreshTimer = null;

function startAutoRefresh() {
  if (autoRefreshTimer !== null) {
    return;
  }
  autoRefreshTimer = setInterval(() => {
    // 推进进行中（推进按钮被禁用）时跳过本次，避免与手动推进冲突
    if ($('advance').disabled) {
      return;
    }
    // 自动刷新失败不打扰用户，等下一次轮询重试
    refresh().catch(() => {});
  }, AUTO_REFRESH_INTERVAL_MS);
}

function stopAutoRefresh() {
  if (autoRefreshTimer !== null) {
    clearInterval(autoRefreshTimer);
    autoRefreshTimer = null;
  }
}

bind();
startAutoRefresh();
run(() => Promise.resolve());
