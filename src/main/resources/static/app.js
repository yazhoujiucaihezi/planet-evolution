// 星球演化 · 前端逻辑（重构版：主信息区 + 小地图 + 弹出全屏地图）
const MAJOR_THRESHOLD = 60;
const MAX_EVENTS_SHOWN = 200;
const BATCH_YEARS = 200;
const AUTO_REFRESH_INTERVAL_MS = 1000;
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
  $('era').textContent = world.era || '-';
  // 四柱简述
  const pillars = state.pillars;
  if (pillars) {
    $('pillars-summary').textContent =
      `科技${Math.round(pillars.science)} 民生${Math.round(pillars.welfare)} 资源${Math.round(pillars.resource)} 军力${Math.round(pillars.military)}`;
  } else {
    $('pillars-summary').textContent = '-';
  }
  renderPillars(state.pillars);
}

// 王国四柱
const PILLAR_KEYS = ['science', 'welfare', 'resource', 'military'];

function pillarLevel(value) {
  if (value <= 20 || value >= 80) return 'danger';
  if (value < 40 || value > 60) return 'warn';
  return 'ok';
}

function renderPillars(pillars) {
  for (const key of PILLAR_KEYS) {
    const cap = key.charAt(0).toUpperCase() + key.slice(1);
    const fill = $('pillar' + cap);
    const valueBox = $('pillar' + cap + 'Value');
    if (!fill || !valueBox) {
      continue;
    }
    if (!pillars || typeof pillars[key] !== 'number') {
      fill.className = 'pillar-fill';
      fill.style.width = '0%';
      valueBox.textContent = '-';
      continue;
    }
    const value = Math.max(0, Math.min(100, Math.round(pillars[key])));
    fill.className = 'pillar-fill ' + pillarLevel(value);
    fill.style.width = value + '%';
    valueBox.textContent = String(value);
  }
}

// ==================== 科技树面板 ====================

const TECH_BRANCH_IDS = {
  military: 'techBranchMilitary',
  economy: 'techBranchEconomy',
  society: 'techBranchSociety',
  space: 'techBranchSpace',
};
const TECH_STATE_LABELS = {
  unlocked: '已解锁',
  researching: '正在研究',
  available: '可研究',
  locked: '锁住（需要先解锁前置）',
};

let techTreeCache = null;

function techNodeState(node, unlockedIds) {
  if (node.unlocked) return 'unlocked';
  if (node.researching) return 'researching';
  const prerequisite = node.prerequisite;
  return !prerequisite || unlockedIds.has(prerequisite) ? 'available' : 'locked';
}

function techNodeNames(branches) {
  const names = new Map();
  for (const nodes of Object.values(branches || {})) {
    for (const node of nodes || []) {
      names.set(node.id, node.name);
    }
  }
  return names;
}

function techTooltip(node, unlockedIds, names) {
  const state = techNodeState(node, unlockedIds);
  const prerequisiteName = node.prerequisite
    ? (names.get(node.prerequisite) || node.prerequisite)
    : '无（分支起点）';
  return [
    node.name,
    `成本：${node.cost}`,
    `前置：${prerequisiteName}`,
    `状态：${TECH_STATE_LABELS[state] || state}`,
  ].join('\n');
}

function renderTechTree(tree) {
  const branches = (tree && tree.branches) || {};
  const unlockedIds = new Set((tree && tree.playerUnlocked) || []);
  const names = techNodeNames(branches);

  for (const [branchKey, containerId] of Object.entries(TECH_BRANCH_IDS)) {
    const container = $(containerId);
    if (!container) continue;
    container.innerHTML = '';
    const nodes = branches[branchKey] || [];
    if (nodes.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'note';
      empty.textContent = '暂无数据';
      container.appendChild(empty);
      continue;
    }
    nodes.forEach((node, index) => {
      const state = techNodeState(node, unlockedIds);
      if (index > 0) {
        const link = document.createElement('div');
        link.className = 'tech-link ' + state;
        container.appendChild(link);
      }
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'tech-node ' + state;
      el.dataset.techId = node.id;
      el.title = techTooltip(node, unlockedIds, names);
      const lock = state === 'locked' ? '<span class="tech-lock">🔒</span>' : '';
      el.innerHTML = `${lock}<span class="tech-name">${node.name}</span>` +
        `<span class="tech-cost">${node.cost}</span>`;
      el.addEventListener('click', () => onTechNodeClick(node, unlockedIds, names));
      container.appendChild(el);
    });
  }

  const unlockedCount = (tree && tree.playerUnlocked ? tree.playerUnlocked.length : 0);
  const totalCount = Object.values(branches).reduce((sum, nodes) => sum + (nodes ? nodes.length : 0), 0);
  const researchingId = tree && tree.playerResearching;
  const researchingName = researchingId ? (names.get(researchingId) || researchingId) : '无';
  $('techSummary').textContent =
    `已解锁 ${unlockedCount}/${totalCount} · 正在研究：${researchingName}`;
}

async function openTechTree() {
  $('techPanel').hidden = false;
  await loadTechTree();
}

function closeTechTree() {
  $('techPanel').hidden = true;
}

async function loadTechTree() {
  try {
    const tree = await api('/api/world/tech-tree');
    techTreeCache = tree;
    renderTechTree(tree);
  } catch (error) {
    showError('科技树加载失败：' + error.message);
  }
}

async function onTechNodeClick(node, unlockedIds, names) {
  const state = techNodeState(node, unlockedIds);
  if (state === 'unlocked') {
    showNotice(`【${node.name}】已解锁`);
    return;
  }
  if (state === 'locked') {
    const prerequisiteName = node.prerequisite
      ? (names.get(node.prerequisite) || node.prerequisite)
      : '前置';
    showNotice(`需要先解锁【${prerequisiteName}】`, 4000);
    return;
  }
  if (state === 'researching') {
    showNotice(`正在研究【${node.name}】`, 3000);
    return;
  }
  if (!confirm(`开始研究【${node.name}】？`)) return;
  try {
    const result = await post('/api/command', { text: `研究${node.name}` });
    const message = result && result.result ? result.result : '指令失败：' + ((result && result.error) || '未知错误');
    $('commandResult').textContent = message;
    showNotice(message, 6000);
  } catch (error) {
    showError('研究指令发送失败：' + error.message);
  }
  await loadTechTree();
  await refresh().catch(() => {});
}

function renderEvents(events) {
  const list = $('events');
  list.innerHTML = '';
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

// ==================== 世界地图（重构版：小地图 + 弹出大地图） ====================

// 30 个区域 → 国家代码映射
const REGION_COUNTRIES = {
  1: ['cn', 'jp', 'kr', 'kp', 'mn', 'tw'],
  2: ['vn', 'th', 'ph', 'id', 'my', 'sg', 'mm', 'kh', 'la', 'bn', 'tl'],
  3: ['in', 'pk', 'bd', 'lk', 'np', 'bt', 'mv', 'af'],
  4: ['kz', 'uz', 'tm', 'kg', 'tj'],
  5: ['sa', 'ir', 'iq', 'tr', 'sy', 'jo', 'lb', 'il', 'ye', 'om', 'ae', 'qa', 'kw', 'bh', 'am', 'az', 'ge'],
  6: ['ru'],
  7: ['gb', 'ie', 'fr', 'nl', 'be', 'lu', 'pt', 'es'],
  8: ['it', 'gr', 'hr', 'rs', 'ba', 'me', 'mk', 'al', 'bg', 'ro', 'si', 'mt', 'cy'],
  9: ['ua', 'by', 'md', 'lt', 'lv', 'ee', 'pl', 'sk', 'hu', 'cz'],
  10: ['de', 'at', 'ch', 'li'],
  11: ['se', 'no', 'fi', 'dk', 'is'],
  12: ['eg', 'ly', 'tn', 'dz', 'ma', 'sd'],
  13: ['ng', 'gh', 'ci', 'sn', 'ml', 'ne', 'bf', 'gn', 'gw', 'sl', 'lr', 'tg', 'bj', 'mr'],
  14: ['cd', 'cf', 'td', 'cm', 'ga', 'cg', 'gq', 'ao'],
  15: ['et', 'ke', 'tz', 'ug', 'rw', 'bi', 'so', 'ss', 'er', 'dj', 'mg'],
  16: ['za', 'na', 'bw', 'zw', 'mz', 'zm', 'mw', 'ls', 'sz'],
  17: ['us'],
  18: ['ca'],
  19: ['mx', 'gt', 'bz', 'sv', 'hn', 'ni', 'cr', 'pa'],
  20: ['cu', 'jm', 'ht', 'do', 'pr', 'tt', 'bb', 'bs'],
  21: ['co', 've', 'gy', 'sr', 'gf'],
  22: ['pe', 'ec', 'bo'],
  23: ['br'],
  24: ['ar', 'cl', 'uy', 'py', 'fk'],
  25: ['au'],
  26: ['nz'],
  27: ['pg', 'fj', 'sb', 'vu', 'ws', 'to', 'nc', 'pf'],
};

const COUNTRY_REGION = new Map();
for (const [regionId, codes] of Object.entries(REGION_COUNTRIES)) {
  for (const code of codes) {
    COUNTRY_REGION.set(code, Number(regionId));
  }
}

// 玩家文明固定 id
const PLAYER_CIV_ID = 1;
const PLAYER_COLOR = '#ff2d2d';

// 颜色收敛方案
const FOG_COLOR = '#0f1319';         // 未探索
const UNOWNED_FILL = '#3a3f4b';      // 无主
const UNMAPPED_FILL = '#2f333d';     // 不属于任何区域的国家

// 文明配色：玩家红色，其他文明蓝色系
function colorForCiv(civId) {
  if (civId === PLAYER_CIV_ID) {
    return PLAYER_COLOR;
  }
  // 蓝色系：hsl(210, 55%, 38% + (civId % 6) * 5%)
  const lightness = 38 + (civId % 6) * 5;
  return `hsl(210, 55%, ${lightness}%)`;
}

const SVG_NS = 'http://www.w3.org/2000/svg';

// 底图缓存
let mapSvgPromise = null;
let mapErrorShown = false;
let lastRegions = [];
let mapSvgSource = null; // 保存底图内容，用于渲染小地图和大地图

/** 拉 world-map.svg 并缓存内容 */
function loadMapSvg() {
  if (mapSvgPromise === null) {
    mapSvgPromise = (async () => {
      const response = await fetch('/world-map.svg');
      if (!response.ok) throw new Error(`/world-map.svg 返回 ${response.status}`);
      const text = await response.text();
      const parsed = new DOMParser().parseFromString(text, 'image/svg+xml');
      const source = parsed.documentElement;
      if (!source || source.nodeName.toLowerCase() !== 'svg' || parsed.querySelector('parsererror')) {
        throw new Error('world-map.svg 解析失败');
      }
      mapSvgSource = source;
      return true;
    })().catch((error) => {
      mapSvgPromise = null;
      throw error;
    });
  }
  return mapSvgPromise;
}

/** 渲染地图到指定 SVG 容器 */
function renderMapToSvg(svg, regions) {
  if (!mapSvgSource) return;
  const viewBox = mapSvgSource.getAttribute('viewBox') || '0 0 1000 500';
  svg.setAttribute('viewBox', viewBox);
  svg.removeAttribute('width');
  svg.removeAttribute('height');
  svg.innerHTML = mapSvgSource.innerHTML;

  const byId = new Map((Array.isArray(regions) ? regions : []).filter(Boolean).map((r) => [r.id, r]));

  for (const element of svg.querySelectorAll('[id]')) {
    const regionId = COUNTRY_REGION.get(element.id);
    if (regionId === undefined) {
      element.setAttribute('fill', UNMAPPED_FILL);
      continue;
    }
    const region = byId.get(regionId);
    const fill = regionFill(region);
    element.setAttribute('fill', fill);
    ensureTitle(element).textContent = regionHoverText(region, regionId);
  }
}

/** 确保元素有 <title> 子元素 */
function ensureTitle(element) {
  for (const child of element.children) {
    if (child.tagName.toLowerCase() === 'title') return child;
  }
  const title = document.createElementNS(SVG_NS, 'title');
  element.insertBefore(title, element.firstChild);
  return title;
}

/** 区域填充色 */
function regionFill(region) {
  if (region && region.explored === false) return FOG_COLOR;
  const ownerId = region ? region.ownerCivId : 0;
  return ownerId ? colorForCiv(ownerId) : UNOWNED_FILL;
}

/** 区域悬停提示 */
function regionHoverText(region, regionId) {
  if (region && region.explored === false) return '未探索';
  const regionName = region && region.name ? region.name : `区域 ${regionId}`;
  const ownerName = region && region.ownerCivName ? region.ownerCivName : '无主';
  return `${regionName} · ${ownerName}`;
}

/** 渲染小地图（右下角，200x120，不响应交互） */
function renderMiniMap(regions) {
  const miniMap = $('miniMap');
  renderMapToSvg(miniMap, regions);
}

/** 渲染大地图（弹出地图） */
function renderBigMap(regions) {
  const bigMap = $('bigMap');
  renderMapToSvg(bigMap, regions);
  // 给可点击区域添加标记
  for (const element of bigMap.querySelectorAll('[id]')) {
    const regionId = COUNTRY_REGION.get(element.id);
    if (regionId !== undefined) {
      element.classList.add('clickable');
      element.dataset.regionId = regionId;
    }
  }
}

// ==================== 弹出地图交互 ====================

// 地图视图状态（用于滚轮缩放和拖拽平移）
let mapViewState = {
  scale: 1,
  translateX: 0,
  translateY: 0,
  isDragging: false,
  lastX: 0,
  lastY: 0,
};

const DEFAULT_VIEW = { scale: 1, translateX: 0, translateY: 0 };

/** 更新大地图的 viewBox 实现缩放平移 */
function updateBigMapView() {
  const bigMap = $('bigMap');
  const baseViewBox = mapSvgSource ? mapSvgSource.getAttribute('viewBox') : '0 0 1000 500';
  const [baseX, baseY, baseW, baseH] = baseViewBox.split(' ').map(Number);

  const w = baseW / mapViewState.scale;
  const h = baseH / mapViewState.scale;
  const x = baseX + mapViewState.translateX;
  const y = baseY + mapViewState.translateY;

  bigMap.setAttribute('viewBox', `${x} ${y} ${w} ${h}`);
}

/** 打开弹出地图 */
async function openMapOverlay() {
  $('mapOverlay').hidden = false;
  closeRegionDetail();
  try {
    await loadMapSvg();
    const regions = await api('/api/world/regions');
    renderBigMap(regions);
  } catch (error) {
    showError('地图加载失败：' + error.message);
  }
}

/** 关闭弹出地图 */
function closeMapOverlay() {
  $('mapOverlay').hidden = true;
  resetMapView();
  closeRegionDetail();
}

/** 重置视图 */
function resetMapView() {
  mapViewState = { ...DEFAULT_VIEW, isDragging: false, lastX: 0, lastY: 0 };
  updateBigMapView();
}

/** 高亮玩家领地 */
function highlightPlayerTerritory() {
  const bigMap = $('bigMap');
  const byId = new Map(lastRegions.map((r) => [r.id, r]));

  // 清除之前的高亮
  bigMap.querySelectorAll('.highlight-pulse').forEach((el) => {
    el.classList.remove('highlight-pulse');
  });

  // 高亮玩家所有领土
  for (const [regionId, region] of byId) {
    if (region && region.ownerCivId === PLAYER_CIV_ID) {
      const codes = REGION_COUNTRIES[regionId] || [];
      for (const code of codes) {
        const el = bigMap.querySelector(`[id="${code}"]`);
        if (el) el.classList.add('highlight-pulse');
      }
    }
  }
}

/** 关闭区域详情面板 */
function closeRegionDetail() {
  $('regionDetail').hidden = true;
}

/** 点击区域查看详情 */
async function onRegionClick(regionId) {
  const byId = new Map(lastRegions.map((r) => [r.id, r]));
  const region = byId.get(Number(regionId));

  if (!region || region.explored === false) {
    showNotice('未探索', 2000);
    return;
  }

  try {
    const detail = await api(`/api/world/regions/${regionId}/detail`);
    showRegionDetail(detail);
  } catch (error) {
    showError('获取区域详情失败：' + error.message);
  }
}

/** 显示区域详情面板 */
function showRegionDetail(detail) {
  const panel = $('regionDetail');
  panel.hidden = false;

  $('regionName').textContent = detail.name || '-';

  if (detail.ownerCivId && detail.ownerCivId !== 0) {
    const civColor = colorForCiv(detail.ownerCivId);
    $('civColor').style.backgroundColor = civColor;
    $('civName').textContent = detail.ownerCivName || '未知文明';
    $('civStats').hidden = false;
    if (detail.ownerStats) {
      $('civPop').textContent = num(detail.ownerStats.population);
      $('civTech').textContent = num(detail.ownerStats.techLevel);
      $('civTerr').textContent = detail.ownerStats.territoryCount;
    }
  } else {
    $('civColor').style.backgroundColor = UNOWNED_FILL;
    $('civName').textContent = '无主';
    $('civStats').hidden = true;
  }

  // 邻接区域
  const neighborsList = $('neighborsList');
  neighborsList.innerHTML = '';
  const neighbors = detail.neighbors || [];
  if (neighbors.length === 0) {
    neighborsList.innerHTML = '<li class="note">无邻接区域</li>';
  } else {
    for (const neighborName of neighbors) {
      const li = document.createElement('li');
      li.textContent = neighborName;
      li.addEventListener('click', () => {
        // 查找邻居对应的 regionId
        const neighborRegion = lastRegions.find((r) => r.name === neighborName);
        if (neighborRegion) {
          closeRegionDetail();
          onRegionClick(neighborRegion.id);
        }
      });
      neighborsList.appendChild(li);
    }
  }

  // 最近事件
  const eventsList = $('regionEvents');
  eventsList.innerHTML = '';
  const events = detail.recentEvents || [];
  if (events.length === 0) {
    eventsList.innerHTML = '<li class="note">暂无相关事件</li>';
  } else {
    for (const event of events.slice(-10)) {
      const li = document.createElement('li');
      li.innerHTML = `<span class="year">Y${event.year}</span> <span class="type">${RARITY_LABELS[event.type] || event.type}</span><span class="desc">${event.description}</span>`;
      eventsList.appendChild(li);
    }
  }
}

/** 初始化大地图交互事件 */
function initBigMapEvents() {
  const bigMap = $('bigMap');

  // 点击区域
  bigMap.addEventListener('click', (e) => {
    if (mapViewState.isDragging) return;
    const target = e.target.closest('[data-region-id]');
    if (target && target.dataset.regionId) {
      onRegionClick(target.dataset.regionId);
    }
  });

  // 拖拽平移
  bigMap.addEventListener('mousedown', (e) => {
    if (e.target.closest('[data-region-id]')) return;
    mapViewState.isDragging = true;
    mapViewState.lastX = e.clientX;
    mapViewState.lastY = e.clientY;
    e.preventDefault();
  });

  document.addEventListener('mousemove', (e) => {
    if (!mapViewState.isDragging) return;
    const dx = (e.clientX - mapViewState.lastX) * 2;
    const dy = (e.clientY - mapViewState.lastY) * 2;
    mapViewState.translateX -= dx / mapViewState.scale;
    mapViewState.translateY -= dy / mapViewState.scale;
    mapViewState.lastX = e.clientX;
    mapViewState.lastY = e.clientY;
    updateBigMapView();
  });

  document.addEventListener('mouseup', () => {
    mapViewState.isDragging = false;
  });

  // 滚轮缩放
  bigMap.addEventListener('wheel', (e) => {
    e.preventDefault();
    const zoomFactor = e.deltaY > 0 ? 0.9 : 1.1;
    const newScale = Math.max(0.5, Math.min(10, mapViewState.scale * zoomFactor));

    // 以鼠标位置为中心缩放
    const rect = bigMap.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;
    const ratioX = mouseX / rect.width;
    const ratioY = mouseY / rect.height;

    const baseViewBox = mapSvgSource ? mapSvgSource.getAttribute('viewBox') : '0 0 1000 500';
    const [, , baseW, baseH] = baseViewBox.split(' ').map(Number);

    const currentW = baseW / mapViewState.scale;
    const currentH = baseH / mapViewState.scale;
    const currentX = parseFloat(mapSvgSource ? mapSvgSource.getAttribute('viewBox').split(' ')[0] : '0') + mapViewState.translateX;
    const currentY = parseFloat(mapSvgSource ? mapSvgSource.getAttribute('viewBox').split(' ')[1] : '0') + mapViewState.translateY;

    mapViewState.translateX = currentX + currentW * ratioX - (currentW * ratioX / newScale * mapViewState.scale);
    mapViewState.translateY = currentY + currentH * ratioY - (currentH * ratioY / newScale * mapViewState.scale);
    mapViewState.scale = newScale;

    updateBigMapView();
  });
}

// ==================== 主刷新逻辑 ====================

async function refresh() {
  const [state, events, regions] = await Promise.all([
    api('/api/world'),
    api('/api/world/events'),
    api('/api/world/regions'),
  ]);
  renderWorld(state);
  renderEvents(events);
  lastRegions = Array.isArray(regions) ? regions : [];

  try {
    await loadMapSvg();
    mapErrorShown = false;
    renderMiniMap(regions);
  } catch (error) {
    if (!mapErrorShown) {
      mapErrorShown = true;
      showError('世界地图加载失败：' + error.message);
    }
  }
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
  for (let i = 1; i <= NARRATIVE_POLL_TIMES; i++) {
    setTimeout(() => refresh().catch(() => {}), NARRATIVE_POLL_INTERVAL_MS * i);
  }
}

async function skipEra() {
  const options = await api('/api/world/eras');
  const targets = (options && options.targets) || [];
  if (targets.length === 0) {
    showError('已经是最后一个时代，没有可跳的目标。');
    return;
  }
  const list = targets.map((t, i) => `${i + 1}. ${t.name}`).join('\n');
  const answer = prompt(`当前时代：${options.currentEra}\n\n跳到哪个时代？输入序号：\n${list}`, '1');
  if (answer === null) return;
  const chosen = targets[Number(answer.trim()) - 1];
  if (!chosen) {
    showError('没有这个序号，已取消。');
    return;
  }
  if (!confirm(`确定跳到「${chosen.name}」？科技、人口、食物、资源都会按时代比例整体放大。`)) return;
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

  $('newGame').addEventListener('click', () => run(async () => {
    if (!confirm('确定要重开吗？当前世界会被清空，无法恢复。')) return;
    const result = await post('/api/world/new-game');
    if (result && result.error) {
      showError('世界已重置，但存档写入失败：' + result.error);
      return;
    }
    showNotice(result && result.result ? result.result : '世界已重置。', 6000);
  }));

  // 科技树
  $('openTechTree').addEventListener('click', () => run(openTechTree));
  $('closeTechTree').addEventListener('click', closeTechTree);
  $('techPanel').addEventListener('click', (event) => {
    if (event.target === $('techPanel')) closeTechTree();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !$('techPanel').hidden) closeTechTree();
  });

  // 小地图点击打开大地图
  $('miniMapContainer').addEventListener('click', () => openMapOverlay());

  // 大地图控制按钮
  $('closeMapOverlay').addEventListener('click', () => closeMapOverlay());
  $('highlightPlayer').addEventListener('click', () => highlightPlayerTerritory());
  $('resetMapView').addEventListener('click', () => resetMapView());

  // 大地图键盘快捷键
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !$('mapOverlay').hidden) {
      closeMapOverlay();
    }
  });

  // 玩家指挥
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
    if (event.key === 'Enter') sendCommand();
  });

  // 初始化大地图交互
  initBigMapEvents();
}

let autoRefreshTimer = null;

function startAutoRefresh() {
  if (autoRefreshTimer !== null) return;
  autoRefreshTimer = setInterval(() => {
    if ($('advance').disabled) return;
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
