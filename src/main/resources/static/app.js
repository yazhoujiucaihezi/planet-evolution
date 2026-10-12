// 星球演化 · 前端逻辑（地图主导航版：左栏信息 + 主地图 + 缩略图取景框 + 底部指令）
const MAJOR_THRESHOLD = 60;
const MAX_GAME_YEAR = 1000;
const MAX_EVENTS_SHOWN = 200;
const BATCH_YEARS = 200;
const AUTO_REFRESH_INTERVAL_MS = 3000;
const NARRATIVE_POLL_INTERVAL_MS = 1000;
const NARRATIVE_POLL_TIMES = 5;
const SYNC_INTERVAL_MS = 1000;  // 同步轮询间隔
const RARITY_LABELS = {
  CIVILIZATION_BIRTH: '文明诞生',
  DISASTER: '灾害',
  WAR: '战争',
  TECH_BREAKTHROUGH: '科技突破',
  EXTINCTION: '文明灭亡',
  AI_NARRATIVE: 'AI叙事',
  AI_EVENT: '世界事件',
};

// 玩家会话状态
let sessionId = localStorage.getItem('planet_session_id');
/**
 * 当前玩家控制的文明 id —— 地图上「红色 = 自己的文明」全看它。
 * 来源是 /api/world 返回的 player 字段（后端按 sessionId 解析出的「我控制哪个文明」），
 * 加入 / 切换文明后由 refresh() 重新同步；没登录或还没分到文明时是 null（则没有文明显示红色）。
 * 不再写死 1。
 */
let playerCivId = null;
let playerName = localStorage.getItem('planet_player_name') || '';
// 最近一次拿到的世界年份（混乱度提示 toast 用）
let lastWorldYear = 0;

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

// ==================== 顶部 / 左栏信息 ====================

function renderWorld(state) {
  const world = state.world;
  lastWorldYear = world.year;
  $('planetName').textContent = world.planet;
  $('year').textContent = num(world.year);
  $('era').textContent = world.era || '-';
  $('population').textContent = num(world.population);
  $('techTotal').textContent = num(world.techTotal);
  renderPillars(state.pillars);

  // 我的国家：科技值 / 资源点总量 / 个人时代；世界平均时代另算
  const player = state.player;
  $('myTech').textContent = player ? num(player.techLevel) : '-';
  renderTotalPoints(player);
  renderPoints(player);
  $('myEra').textContent = player && player.era ? player.era : '-';
  $('worldEra').textContent = world.era || '-';

  // 速度输入框与后端状态保持同步（世界默认自己在走，速度由后端说了算）；
  // 玩家正在改这个框时不要覆盖他输入的值
  const speedInput = $('speed');
  if (speedInput && typeof state.speed === 'number' && document.activeElement !== speedInput) {
    speedInput.value = state.speed;
  }

  // 剩余年数：1000 - 当前年，剩 100 年以内数字变红
  const left = Math.max(0, MAX_GAME_YEAR - world.year);
  const leftBox = $('yearsLeft');
  leftBox.textContent = num(left);
  leftBox.style.color = left <= 100 ? '#ff4d4f' : '';

  // 玩家信息
  if (playerName) {
    const playerInfo = $('playerInfo');
    if (playerInfo) {
      playerInfo.hidden = false;
      $('playerName').textContent = playerName;
      $('playerCiv').textContent = playerCivId ? '文明-' + playerCivId : '旁观';
    }
  }

  renderGameOver(state);
}

// ==================== 结算画面 ====================

const GAME_OVER_DISABLED_IDS = ['advance', 'resume', 'skipEra', 'commandText', 'sendCommand'];

function setPlayDisabled(disabled) {
  for (const id of GAME_OVER_DISABLED_IDS) {
    const el = $(id);
    if (el) el.disabled = disabled;
  }
}

function renderGameOver(state) {
  const overlay = $('gameOverOverlay');
  if (!state || !state.gameOver) {
    overlay.hidden = true;
    setPlayDisabled(false);
    return;
  }
  const player = state.player;
  $('gameOverReason').textContent = state.gameOverReason || '这一局结束了。';
  $('overName').textContent = player ? player.name : '-';
  $('overYears').textContent = player
    ? num(Math.max(0, state.world.year - player.foundedYear)) + ' 年'
    : '-';
  $('overPop').textContent = player ? num(player.population) : '-';
  $('overTech').textContent = player ? num(player.techLevel) : '-';
  overlay.hidden = false;
  setPlayDisabled(true);
}

// ==================== 开局选时代 ====================

const START_ERAS = [
  { key: 'savage', label: '蒙昧' },
  { key: 'agrarian', label: '农业' },
  { key: 'ancient', label: '古典' },
  { key: 'medieval', label: '中世纪' },
  { key: 'industrial', label: '工业' },
  { key: 'information', label: '信息' },
  { key: 'interstellar', label: '星际' },
];

function openEraPicker() {
  const list = $('eraPickerList');
  list.innerHTML = '';
  for (const era of START_ERAS) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = era.label;
    btn.addEventListener('click', () => startNewGame(era.key));
    list.appendChild(btn);
  }
  $('eraPicker').hidden = false;
}

function closeEraPicker() {
  $('eraPicker').hidden = true;
}

async function startNewGame(key) {
  closeEraPicker();
  $('gameOverOverlay').hidden = true;
  await run(async () => {
    const result = await post('/api/world/new-game-with-era', { startEra: key });
    if (result && result.error) {
      showError('开局失败：' + result.error);
      return;
    }
    showNotice(result && result.result ? result.result : '已重开一局。', 6000);
  });
  if (!$('techPanel').hidden) {
    await loadTechTree();
  }
}

// ==================== 五种资源点（左栏） ====================

// 与后端 Civilization.POINT_* 一一对应；顺序即展示顺序
const POINT_LABELS = { food: '农业', production: '工业', military: '军事', culture: '文化', science: '科学' };
const POINT_ORDER = ['food', 'production', 'military', 'culture', 'science'];

/**
 * 左栏「五种资源点」区块是运行时插进侧栏的（不能改 index.html），
 * 插在第一个 side-block（我的国家）之后。
 */
function ensurePointsBlock() {
  let block = $('pointsBlock');
  if (block) return block;
  const body = document.querySelector('#sidePanel .sidebar-body') || document.querySelector('.sidebar .sidebar-body');
  if (!body) return null;
  block = document.createElement('section');
  block.className = 'side-block';
  block.id = 'pointsBlock';
  const title = document.createElement('h2');
  title.textContent = '五种资源点';
  const line = document.createElement('p');
  line.className = 'note';
  line.id = 'pointsLine';
  line.textContent = '-';
  block.appendChild(title);
  block.appendChild(line);
  const first = body.querySelector('.side-block');
  if (first && first.nextSibling) {
    body.insertBefore(block, first.nextSibling);
  } else {
    body.appendChild(block);
  }
  return block;
}

/** 渲染「农业 250 / 工业 80 / 军事 150 / 文化 60 / 科学 300」。 */
function renderPoints(player) {
  if (!ensurePointsBlock()) return;
  const line = $('pointsLine');
  if (!line) return;
  const points = player && player.points ? player.points : null;
  if (!points) {
    line.textContent = '-';
    return;
  }
  line.textContent = POINT_ORDER
    .map((key) => `${POINT_LABELS[key]} ${num(points[key] || 0)}`)
    .join(' / ');
}

/** 顶栏原来那格是「科技点」，现在显示五种资源点之和（标签也顺手改掉）。 */
function renderTotalPoints(player) {
  const box = $('myTechPoints');
  if (!box) return;
  const points = player && player.points ? player.points : null;
  if (!points) {
    box.textContent = '-';
    return;
  }
  let total = 0;
  for (const key of POINT_ORDER) total += Number(points[key]) || 0;
  box.textContent = num(total);
  const label = box.parentElement ? box.parentElement.firstChild : null;
  // 3 === Node.TEXT_NODE：标签是插在 <b> 前面的那段文字
  if (label && label.nodeType === 3) {
    label.textContent = '资源点 ';
  }
}

// ==================== 世界混乱度（左栏进度条） ====================

// 闪烁动画的 keyframes 无法进 styles.css（约束），运行时注入一次
function ensureChaosStyle() {
  if (document.getElementById('chaosStyle')) return;
  const style = document.createElement('style');
  style.id = 'chaosStyle';
  style.textContent = '@keyframes chaosBlink { from { opacity: 1; } to { opacity: 0.35; } }';
  document.head.appendChild(style);
}

/** 左栏「混乱度」区块是运行时插进侧栏的（不能改 index.html），插在五种资源点之后。 */
function ensureChaosBlock() {
  ensureChaosStyle();
  let block = $('chaosBlock');
  if (block) return block;
  const body = document.querySelector('#sidePanel .sidebar-body') || document.querySelector('.sidebar .sidebar-body');
  if (!body) return null;
  block = document.createElement('section');
  block.className = 'side-block';
  block.id = 'chaosBlock';
  const title = document.createElement('h2');
  title.textContent = '混乱度';
  const track = document.createElement('div');
  track.className = 'pillar-track';
  track.style.height = '10px';
  const fill = document.createElement('div');
  fill.className = 'pillar-fill';
  fill.id = 'chaosFill';
  fill.style.width = '0%';
  track.appendChild(fill);
  const line = document.createElement('p');
  line.className = 'note';
  line.id = 'chaosLine';
  line.textContent = '-';
  block.appendChild(title);
  block.appendChild(track);
  block.appendChild(line);
  const pointsBlock = $('pointsBlock');
  if (pointsBlock && pointsBlock.nextSibling) {
    body.insertBefore(block, pointsBlock.nextSibling);
  } else {
    body.appendChild(block);
  }
  return block;
}

/** 0~20 绿 / 20~50 黄 / 50~80 橙 / 80~100 红。 */
function chaosColor(level) {
  if (level < 20) return '#3fb950';
  if (level < 50) return '#ffd166';
  if (level < 80) return '#ff9f43';
  return '#ff2d2d';
}

function chaosTierLabel(level) {
  if (level < 20) return '稳定';
  if (level < 50) return '警觉';
  if (level < 80) return '恐慌';
  return '崩坏';
}

function renderChaos(data) {
  if (!ensureChaosBlock()) return;
  const fill = $('chaosFill');
  const line = $('chaosLine');
  if (!fill || !line) return;
  const level = data && typeof data.chaosLevel === 'number'
    ? Math.max(0, Math.min(100, data.chaosLevel)) : 0;
  const color = chaosColor(level);
  fill.style.width = level + '%';
  fill.style.background = color;
  fill.style.animation = level > 50 ? 'chaosBlink 0.9s infinite alternate' : '';
  line.textContent = `${level} / 100 · ${chaosTierLabel(level)}`;
}

/** 混乱度一变就在右上角弹一条小提示：「混乱度 +15（核打击）」。 */
function showChaosToast(change) {
  if (!change) return;
  showEventToast({ year: lastWorldYear, type: 'AI_EVENT', description: '混乱度 ' + change });
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
let techTreeCache = null;

function techNodeNames(branches) {
  const names = new Map();
  for (const nodes of Object.values(branches || {})) {
    for (const node of nodes || []) {
      names.set(node.id, node.name);
    }
  }
  return names;
}

/** 节点的实际消耗（按资源类型）：后端给 actualCosts（同组已选过别的节点时每种 ×2）；缺失时退回 costs。 */
function nodeCosts(node) {
  const costs = node.actualCosts || node.costs;
  return costs && typeof costs === 'object' ? costs : {};
}

/** 实际消耗之和，用于排序 / 兜底展示。 */
function nodeCost(node) {
  if (typeof node.totalCost === 'number') return node.totalCost;
  let total = 0;
  for (const value of Object.values(nodeCosts(node))) total += Number(value) || 0;
  return total;
}

/** 消耗文案：「农业 100、科学 50」；空表返回「免费」。 */
function costDetail(costs) {
  const safe = costs || {};
  const entries = POINT_ORDER
    .filter((key) => Number(safe[key]) > 0)
    .map((key) => `${POINT_LABELS[key]} ${num(safe[key])}`);
  // 兜底：出现不在标准类型里的键也显示出来
  for (const [key, value] of Object.entries(safe)) {
    if (!POINT_ORDER.includes(key) && Number(value) > 0) entries.push(`${key} ${num(value)}`);
  }
  return entries.length ? entries.join('、') : '免费';
}

/** 该文明是否每种资源点都够这组消耗。 */
function canAfford(costs, points) {
  const safe = costs || {};
  return POINT_ORDER.every((key) => (Number(points[key]) || 0) >= (Number(safe[key]) || 0));
}

/** 第一种不够的资源类型名，用于提示；都够时返回 null。 */
function lackingType(costs, points) {
  const safe = costs || {};
  for (const key of POINT_ORDER) {
    const need = Number(safe[key]) || 0;
    if (need > 0 && (Number(points[key]) || 0) < need) return POINT_LABELS[key];
  }
  return null;
}

/** 花费文案：被同组惩罚时加一句说明。 */
function costText(node) {
  const detail = costDetail(nodeCosts(node));
  return node.penalized ? `花费 ${detail}（同组已选，×2）` : `花费 ${detail}`;
}

/** 节点的悬停提示：名称 / 花费 / 前置 / 当前状态（够不够资源点）。 */
function techTooltip(node, state, names, points) {
  const prereqName = node.prerequisite
    ? (names.get(node.prerequisite) || node.prerequisite)
    : '无（树根）';
  const costs = nodeCosts(node);
  const lines = [node.name, `花费：${costDetail(costs)}`, `前置：${prereqName}`];
  if (node.penalized) {
    lines.push('同组惩罚：该组已选过别的节点，每种资源 ×2');
  }
  if (state === 'unlocked') {
    lines.push('状态：已解锁');
  } else if (state === 'successor') {
    lines.push(`状态：需要先解锁 ${prereqName}`);
  } else if (canAfford(costs, points)) {
    lines.push(`状态：可研究（你有 ${costDetail(points)}）`);
  } else {
    lines.push(`状态：资源点不够（需要 ${costDetail(costs)}，当前 ${costDetail(points)}）`);
  }
  return lines.join('\n');
}

/**
 * 科技迷雾：第一遍标记 已解锁 / 研究中 / 可研究 为可见；
 * 第二遍把"可见节点的直接子节点"也标为可见（successor，暗色，让玩家看到下一步）。
 * 其余节点不可见（只在父节点下方留一条淡虚线）。
 */
function computeTechView(branches, unlockedIds) {
  const allNodes = [];
  for (const nodes of Object.values(branches || {})) {
    for (const node of nodes || []) allNodes.push(node);
  }
  const isAvailable = (node) =>
    !unlockedIds.has(node.id) &&
    (!node.prerequisite || unlockedIds.has(node.prerequisite));

  const baseVisible = new Set();
  const visible = new Map(); // id -> 'unlocked' | 'available' | 'successor'
  for (const node of allNodes) {
    if (unlockedIds.has(node.id)) {
      baseVisible.add(node.id);
      visible.set(node.id, 'unlocked');
    } else if (isAvailable(node)) {
      baseVisible.add(node.id);
      visible.set(node.id, 'available');
    }
  }
  // 第二遍只认第一遍的可见集（不再向外扩散），迷雾只揭开"下一步"
  for (const node of allNodes) {
    if (visible.has(node.id)) continue;
    if (node.prerequisite && baseVisible.has(node.prerequisite)) {
      visible.set(node.id, 'successor');
    }
  }
  return { allNodes, visible };
}

// 树形布局常量（px）
const TECH_NODE_W = 118;
const TECH_NODE_H = 46;
const TECH_NODE_GAP = 16;
const TECH_LEVEL_H = 92;

/**
 * 递归布局：叶子节点各占一个水平槽位，父节点 x = 子节点 x 的平均值，y = 深度 × 层高。
 * 整棵树都参与布局（含迷雾节点），这样树的形状是完整的，玩家能看到"后面还有多少东西"。
 */
function buildTechLayout(allNodes) {
  const childrenOf = new Map();
  let root = null;
  for (const node of allNodes) {
    const parent = node.prerequisite || null;
    if (!parent) root = node;
    if (!childrenOf.has(parent)) childrenOf.set(parent, []);
    childrenOf.get(parent).push(node);
  }
  if (!root) return null;
  const kids = (id) => (childrenOf.get(id) || []);

  let cursor = 0;
  let maxDepth = 0;
  const place = (node, depth) => {
    if (depth > maxDepth) maxDepth = depth;
    const ch = kids(node.id);
    if (ch.length === 0) {
      node.slot = cursor++;
    } else {
      for (const c of ch) place(c, depth + 1);
      node.slot = ch.reduce((sum, c) => sum + c.slot, 0) / ch.length;
    }
    node.depth = depth;
  };
  place(root, 0);

  const step = TECH_NODE_W + TECH_NODE_GAP;
  return {
    root,
    childrenOf,
    width: Math.max(step, cursor * step),
    height: (maxDepth + 1) * TECH_LEVEL_H,
    step,
  };
}

/** 渲染成一棵树：SVG 画连线（曲线），绝对定位的按钮当节点。 */
function renderTechTree(tree) {
  const branches = (tree && tree.branches) || {};
  const unlockedIds = new Set((tree && tree.playerUnlocked) || []);
  const points = (tree && tree.playerPoints) || {};
  const playerEra = (tree && tree.playerEra) || '';
  const names = techNodeNames(branches);
  const view = computeTechView(branches, unlockedIds);

  const host = document.querySelector('.tech-branches');
  if (!host) return;
  // 隐藏旧的"四列"容器，改用单棵树
  host.querySelectorAll('.tech-branch').forEach((el) => { el.style.display = 'none'; });
  const oldWrap = host.querySelector('.tech-tree-wrap');
  if (oldWrap) oldWrap.remove();

  const wrap = document.createElement('div');
  wrap.className = 'tech-tree-wrap';
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('class', 'tech-tree-svg');
  const nodesLayer = document.createElement('div');
  nodesLayer.className = 'tech-tree-nodes';
  wrap.appendChild(svg);
  wrap.appendChild(nodesLayer);
  host.appendChild(wrap);

  const layout = view.allNodes.length ? buildTechLayout(view.allNodes) : null;
  if (!layout) {
    const empty = document.createElement('p');
    empty.className = 'note';
    empty.textContent = '暂无数据';
    nodesLayer.appendChild(empty);
    return;
  }

  const step = layout.step;
  const centerX = (node) => node.slot * step + TECH_NODE_W / 2;
  const topY = (node) => node.depth * TECH_LEVEL_H;
  const bottomY = (node) => node.depth * TECH_LEVEL_H + TECH_NODE_H;

  wrap.style.width = layout.width + 'px';
  wrap.style.height = layout.height + 'px';
  svg.setAttribute('width', layout.width);
  svg.setAttribute('height', layout.height);
  svg.setAttribute('viewBox', `0 0 ${layout.width} ${layout.height}`);

  // 连线：父节点底部 → 子节点顶部（贝塞尔曲线）。整棵树都画；
  // 已解锁 = 绿实线，可见但未解锁 = 灰虚线，迷雾（子节点不可见）= 更淡的虚线。
  for (const node of view.allNodes) {
    const children = layout.childrenOf.get(node.id) || [];
    for (const child of children) {
      const childState = view.visible.get(child.id);
      const path = document.createElementNS(SVG_NS, 'path');
      path.setAttribute('class',
        childState === 'unlocked' ? 'tech-edge unlocked'
          : childState ? 'tech-edge' : 'tech-edge fog');
      const x1 = centerX(node);
      const y1 = bottomY(node);
      const x2 = centerX(child);
      const y2 = topY(child);
      const my = (y1 + y2) / 2;
      path.setAttribute('d', `M ${x1} ${y1} C ${x1} ${my}, ${x2} ${my}, ${x2} ${y2}`);
      svg.appendChild(path);
    }
  }

  // 节点：可见 → 正常按钮；不可见 → 暗色"谜题方块"（显示轮廓，不显示内容，不可点）
  for (const node of view.allNodes) {
    const state = view.visible.get(node.id);
    const left = (node.slot * step) + 'px';
    const top = topY(node) + 'px';
    if (state) {
      const affordable = canAfford(nodeCosts(node), points);
      const cls = state === 'available' && !affordable ? 'costly' : state;
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'tech-node tech-tree-node ' + cls;
      el.dataset.techId = node.id;
      el.title = techTooltip(node, state, names, points);
      el.style.left = left;
      el.style.top = top;
      if (state === 'unlocked') {
        // 已解锁：绿色，不显示成本
        el.innerHTML = `<span class="tech-name">${node.name}</span>`;
      } else if (state === 'successor') {
        const prereqName = node.prerequisite ? (names.get(node.prerequisite) || node.prerequisite) : '前置';
        el.innerHTML = `<span class="tech-name">${node.name}</span>` +
          `<span class="tech-cost">需要先解锁 ${prereqName}</span>`;
      } else if (affordable) {
        // 够点：显示花费；被同组惩罚时附一句说明 + 琥珀色边框
        el.innerHTML = `<span class="tech-name">${node.name}</span>` +
          `<span class="tech-cost">${costText(node)}</span>`;
        if (node.penalized) {
          el.style.borderColor = '#b8860b';
        }
      } else {
        // 可研究但资源点不够：暗蓝
        el.innerHTML = `<span class="tech-name">${node.name}</span>` +
          `<span class="tech-cost">需要 ${costDetail(nodeCosts(node))}` +
          `${node.penalized ? '，同组 ×2' : ''}</span>`;
        el.style.borderColor = '#2b4a7a';
        el.style.background = '#0d1b30';
        el.style.color = '#8fa3d8';
      }
      el.addEventListener('click', () => onTechNodeClick(node, state, names, points));
      nodesLayer.appendChild(el);
    } else {
      const el = document.createElement('div');
      el.className = 'tech-node tech-tree-node hidden';
      el.title = '未探明的科技';
      el.style.left = left;
      el.style.top = top;
      el.innerHTML = '<span class="tech-name">???</span>';
      nodesLayer.appendChild(el);
    }
  }

  $('techSummary').textContent =
    `已解锁 ${unlockedIds.size}/${view.allNodes.length} · 可见 ${view.visible.size}`
    + ` · ${costDetail(points)}`
    + (playerEra ? ` · 我的时代 ${playerEra}` : '');
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
    // no-store：点完科技立刻重拉，避免浏览器 / 代理拿到缓存的旧树
    const tree = await api('/api/world/tech-tree', { cache: 'no-store' });
    techTreeCache = tree;
    renderTechTree(tree);
  } catch (error) {
    showError('科技树加载失败：' + error.message);
  }
}

async function onTechNodeClick(node, state, names, points) {
  if (state === 'unlocked') {
    showNotice(`【${node.name}】已解锁`);
    return;
  }
  if (state === 'successor') {
    const prereqName = node.prerequisite ? (names.get(node.prerequisite) || node.prerequisite) : '前置';
    showNotice(`需要先解锁【${prereqName}】`, 4000);
    return;
  }
  const costs = nodeCosts(node);
  const missing = lackingType(costs, points);
  if (missing) {
    showNotice(`${missing}点不够（需要 ${costDetail(costs)}）`, 3000);
    return;
  }
  if (!confirm(`花费 ${costDetail(costs)} 研究【${node.name}】？${node.penalized ? '（同组已选，×2）' : ''}`)) return;
  try {
    // 直接研究专用接口：点节点是明确动作，不需要 AI 翻译，也不再依赖 AI 返回 target
    const result = await post('/api/world/research', { techId: node.id });
    const message = result && result.result
      ? result.result
      : '研究失败：' + ((result && result.error) || '未知错误');
    $('commandResult').textContent = message;
    showNotice(message, 6000);
  } catch (error) {
    showError('研究请求失败：' + error.message);
  } finally {
    // 命令一返回就立刻重拉（不等下一次 refresh 周期）：科技树 + 世界状态并行拉，一次往返拿全。
    // 放在 finally 里，命令成功或失败都保证刷新一次，免得界面停在旧状态。
    await Promise.all([loadTechTree(), refresh().catch(() => {})]);
  }
}

// ==================== 历史事件（底部可折叠） ====================

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

// ==================== 世界地图（主地图 + 缩略图取景框） ====================

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

// 区域邻接表（与后端 RegionMap 一致）：判断"是否与异主区域接壤"用
const REGION_NEIGHBORS = {
  1: [2, 6, 7, 8],
  2: [1, 3, 4, 19],
  3: [2, 4, 6, 19],
  4: [2, 3, 5, 6],
  5: [4, 6, 11, 13, 17],
  6: [1, 3, 4, 5, 7],
  7: [1, 6, 8, 10, 11, 17],
  8: [1, 7, 10, 13],
  9: [6, 7, 10, 12],
  10: [7, 8, 9, 11, 12],
  11: [5, 7, 10, 12],
  12: [9, 10, 11, 13, 14],
  13: [5, 8, 12, 14, 15],
  14: [12, 13, 15, 16],
  15: [13, 14, 16, 17],
  16: [14, 15],
  17: [5, 7, 12, 18, 20],
  18: [17, 19, 20],
  19: [2, 3, 18, 21],
  20: [17, 18, 21],
  21: [19, 20, 22, 23],
  22: [21, 23, 24],
  23: [21, 22, 24],
  24: [22, 23],
  25: [2, 26, 30],
  26: [25, 30],
  27: [2, 25, 30],
  28: [24, 30],
  29: [6, 11, 17],
  30: [25, 26, 27, 28],
};

// 自己的文明高亮色（到底是哪个文明由 playerCivId 决定，见文件顶部）
const PLAYER_COLOR = '#ff2d2d';

// 颜色收敛方案
const FOG_COLOR = '#0a0d14';         // 未探索（更黑）
const UNOWNED_FILL = '#2a3040';      // 无主（深灰蓝）
const UNMAPPED_FILL = '#252b38';     // 不属于任何区域的国家

// 地形中文名（/api/world/regions 的 terrain 字段 → 展示名）
const TERRAIN_LABELS = {
  plains: '平原',
  mountain: '山地',
  desert: '沙漠',
  forest: '森林',
  tundra: '冻原',
  jungle: '热带',
};

// 文明配色：自己的文明红色，其他文明蓝色系
function colorForCiv(civId) {
  // 只有「自己当前控制的文明」是红的；别人（含多人在线时的其他玩家）一律蓝色系
  if (playerCivId != null && civId === playerCivId) {
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
let mapSvgSource = null;

// 底图原始 viewBox（世界坐标范围），主地图与缩略图共用同一套坐标
let baseView = { x: 0, y: 0, w: 1000, h: 500 };
const MIN_SCALE = 1;
const MAX_SCALE = 12;
const INITIAL_SCALE = 2;

// 主地图取景：以「世界坐标里的中心点 + 缩放倍数」描述，viewBox 由二者算出
let mainView = { scale: INITIAL_SCALE, cx: baseView.x + baseView.w / 2, cy: baseView.y + baseView.h / 2 };

/** 拉 world-map.svg 并缓存内容，同时记录底图 viewBox。 */
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
      baseView = parseViewBox(source.getAttribute('viewBox'));
      mainView = { scale: INITIAL_SCALE, cx: baseView.x + baseView.w / 2, cy: baseView.y + baseView.h / 2 };
      return true;
    })().catch((error) => {
      mapSvgPromise = null;
      throw error;
    });
  }
  return mapSvgPromise;
}

/** 解析 viewBox 字符串为 {x,y,w,h}，异常时回退 0 0 1000 500。 */
function parseViewBox(text) {
  const parts = (text || '').split(/[\s,]+/).map(Number);
  if (parts.length === 4 && parts.every((n) => Number.isFinite(n)) && parts[2] > 0 && parts[3] > 0) {
    return { x: parts[0], y: parts[1], w: parts[2], h: parts[3] };
  }
  return { x: 0, y: 0, w: 1000, h: 500 };
}

/** 把底图内容内联进指定 svg，并按区域归属上色（未探索 → 黑雾，无主 → 灰，有主 → 文明色）。 */
function renderMapToSvg(svg, regions) {
  if (!mapSvgSource) return;
  svg.setAttribute('viewBox', `${baseView.x} ${baseView.y} ${baseView.w} ${baseView.h}`);
  svg.removeAttribute('width');
  svg.removeAttribute('height');
  svg.innerHTML = mapSvgSource.innerHTML;

  const byId = new Map((Array.isArray(regions) ? regions : []).filter(Boolean).map((r) => [r.id, r]));

  // 纯色块：海洋露出的就是画布底色（#0d1b2a），区域一律不透明，颜色所见即所得
  for (const element of svg.querySelectorAll('[id]')) {
    const regionId = COUNTRY_REGION.get(element.id);
    if (regionId === undefined) {
      element.setAttribute('fill', UNMAPPED_FILL);
      element.setAttribute('fill-opacity', '1');
      continue;
    }
    const region = byId.get(regionId);
    element.setAttribute('fill', regionFill(region));
    element.setAttribute('fill-opacity', '1');
    ensureTitle(element).textContent = regionHoverText(region, regionId);
  }
}

/** 确保元素有 <title> 子元素（原生悬停提示）。 */
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

/** 区域悬停提示（原生 title） */
function regionHoverText(region, regionId) {
  if (region && region.explored === false) return '未探索';
  const regionName = region && region.name ? region.name : `区域 ${regionId}`;
  const terrainName = region && region.terrain ? (TERRAIN_LABELS[region.terrain] || region.terrain) : null;
  const ownerName = region && region.ownerCivName ? region.ownerCivName : '无主';
  return terrainName ? `${regionName} · ${terrainName} · ${ownerName}` : `${regionName} · ${ownerName}`;
}

/** 渲染主地图：色块底图 + 可点击标记 + 应用当前取景。 */
function renderMainMap(regions) {
  const mainMap = $('mainMap');
  renderMapToSvg(mainMap, regions);
  // fxLayer 被 innerHTML 清掉了，补回来（动画层必须在最上面）
  if (!$('fxLayer')) {
    const fx = document.createElementNS(SVG_NS, 'g');
    fx.id = 'fxLayer';
    mainMap.appendChild(fx);
  }
  const byId = new Map((Array.isArray(regions) ? regions : []).filter(Boolean).map((r) => [r.id, r]));
  for (const element of mainMap.querySelectorAll('[id]')) {
    const regionId = COUNTRY_REGION.get(element.id);
    if (regionId !== undefined) {
      element.classList.add('clickable');
      element.dataset.regionId = regionId;
      styleRegionBorder(element, byId.get(regionId), byId);
    }
  }
  updateMainMapView();
}

/**
 * 按归属设置区域边界（线宽用屏幕像素，不随缩放变粗）：
 * - 玩家领土：红 #ff4444，2px
 * - 与异主区域接壤（含邻区未探索）：深色 #0a0f18，2.5px
 * - 同主邻居：细线 rgba(255,255,255,0.15)，1px
 * - 未探索：不动，用 CSS 默认细边
 */
function styleRegionBorder(el, region, byId) {
  if (!region || region.explored === false) return;
  el.style.vectorEffect = 'non-scaling-stroke';
  const ownerId = region.ownerCivId || 0;
  if (playerCivId != null && ownerId === playerCivId) {
    el.style.stroke = '#ff4444';
    el.style.strokeWidth = '2';
  } else if (hasForeignNeighbor(region, byId)) {
    el.style.stroke = '#0a0f18';
    el.style.strokeWidth = '2.5';
  } else {
    el.style.stroke = 'rgba(255,255,255,0.15)';
    el.style.strokeWidth = '1';
  }
}

/** 是否有归属不同的邻区；邻居未探索（黑雾里归属未知）按"异主"处理，让已知世界边缘的边界也醒目。 */
function hasForeignNeighbor(region, byId) {
  const myOwner = region.ownerCivId || 0;
  for (const nid of REGION_NEIGHBORS[region.id] || []) {
    const n = byId.get(nid);
    if (!n) continue;
    const nOwner = n.explored === false ? -1 : (n.ownerCivId || 0);
    if (nOwner !== myOwner) return true;
  }
  return false;
}

/** 渲染缩略图：整幅世界 + 一个跟随主地图的取景框（每次重绘后重建，避免被 innerHTML 清掉）。 */
function renderMiniMap(regions) {
  const miniMap = $('miniMap');
  renderMapToSvg(miniMap, regions);
  const rect = document.createElementNS(SVG_NS, 'rect');
  rect.setAttribute('id', 'miniViewport');
  rect.setAttribute('class', 'mini-viewport');
  miniMap.appendChild(rect);
  updateMainMapView();
}

/** 按当前取景更新主地图 viewBox，并同步缩略图取景框。 */
function updateMainMapView() {
  const mainMap = $('mainMap');
  const w = baseView.w / mainView.scale;
  const h = baseView.h / mainView.scale;
  const vx = mainView.cx - w / 2;
  const vy = mainView.cy - h / 2;
  mainMap.setAttribute('viewBox', `${vx} ${vy} ${w} ${h}`);
  updateMiniViewport(vx, vy, w, h);
}

/** 取景框 = 主地图当前 viewBox，直接画在缩略图（同一套世界坐标）里。 */
function updateMiniViewport(vx, vy, w, h) {
  const rect = $('miniViewport');
  if (!rect) return;
  rect.setAttribute('x', vx);
  rect.setAttribute('y', vy);
  rect.setAttribute('width', w);
  rect.setAttribute('height', h);
}

/** 把主地图中心夹在世界范围内，避免平移到图外。 */
function clampCenter(cx, cy) {
  const w = baseView.w / mainView.scale;
  const h = baseView.h / mainView.scale;
  const minX = baseView.x + w / 2;
  const maxX = baseView.x + baseView.w - w / 2;
  const minY = baseView.y + h / 2;
  const maxY = baseView.y + baseView.h - h / 2;
  return {
    cx: Math.max(minX, Math.min(maxX, cx)),
    cy: Math.max(minY, Math.min(maxY, cy)),
  };
}

/** 把主地图取景中心移到世界坐标 (cx, cy)。 */
function centerMainAt(cx, cy) {
  const c = clampCenter(cx, cy);
  mainView.cx = c.cx;
  mainView.cy = c.cy;
  updateMainMapView();
}

/** 缩略图点击：把主地图移到点击处在世界里的位置。 */
function onMiniMapClick(event) {
  const miniMap = $('miniMap');
  const ctm = miniMap.getScreenCTM();
  if (!ctm) return;
  const point = miniMap.createSVGPoint();
  point.x = event.clientX;
  point.y = event.clientY;
  const world = point.matrixTransform(ctm.inverse());
  centerMainAt(world.x, world.y);
}

/** 初始化主地图 + 缩略图交互（滚轮缩放 / 拖拽平移 / 点击区域 / 缩略图定位）。 */
function initMapEvents() {
  const mainMap = $('mainMap');
  let dragging = false;
  let dragMoved = false;
  let lastX = 0;
  let lastY = 0;

  mainMap.addEventListener('mousedown', (event) => {
    dragging = true;
    dragMoved = false;
    lastX = event.clientX;
    lastY = event.clientY;
    event.preventDefault();
  });

  window.addEventListener('mousemove', (event) => {
    if (!dragging) return;
    const dx = event.clientX - lastX;
    const dy = event.clientY - lastY;
    if (Math.abs(dx) + Math.abs(dy) > 2) {
      dragMoved = true;
    }
    const rect = mainMap.getBoundingClientRect();
    const w = baseView.w / mainView.scale;
    const h = baseView.h / mainView.scale;
    const c = clampCenter(mainView.cx - (dx / rect.width) * w, mainView.cy - (dy / rect.height) * h);
    mainView.cx = c.cx;
    mainView.cy = c.cy;
    lastX = event.clientX;
    lastY = event.clientY;
    updateMainMapView();
  });

  window.addEventListener('mouseup', () => {
    if (!dragging) return;
    dragging = false;
  });

  // 点击区域 → 详情（拖动过后不算点击）
  mainMap.addEventListener('click', (event) => {
    if (dragMoved) return;
    const target = event.target.closest('[data-region-id]');
    if (target && target.dataset.regionId) {
      onRegionClick(target.dataset.regionId);
    }
  });

  // 滚轮缩放：以鼠标位置为中心
  mainMap.addEventListener('wheel', (event) => {
    event.preventDefault();
    const rect = mainMap.getBoundingClientRect();
    const ratioX = (event.clientX - rect.left) / rect.width;
    const ratioY = (event.clientY - rect.top) / rect.height;

    const w = baseView.w / mainView.scale;
    const h = baseView.h / mainView.scale;
    const worldX = (mainView.cx - w / 2) + ratioX * w;
    const worldY = (mainView.cy - h / 2) + ratioY * h;

    const factor = event.deltaY > 0 ? 0.88 : 1.12;
    const newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, mainView.scale * factor));
    if (newScale === mainView.scale) return;

    const nw = baseView.w / newScale;
    const nh = baseView.h / newScale;
    const c = clampCenter(worldX + (0.5 - ratioX) * nw, worldY + (0.5 - ratioY) * nh);
    mainView.scale = newScale;
    mainView.cx = c.cx;
    mainView.cy = c.cy;
    updateMainMapView();
  }, { passive: false });

  // 缩略图定位
  $('miniMap').addEventListener('click', onMiniMapClick);
}

// ==================== 区域详情 ====================

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
    $('civColor').style.backgroundColor = colorForCiv(detail.ownerCivId);
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

  // 邻接区域（点击跳转）
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

// ==================== 外交提示条 ====================

/**
 * 有 AI 外交待回应时，在输入框下方显示提示条：
 * 「有人在等你回应：文明-3请求结盟」+ [同意] [拒绝]。
 * 点按钮 = 往输入框里发「同意」/「拒绝」（见 bind 里的按钮绑定）。
 */
function renderDiplomacy(diplomacy) {
  const bar = $('diplomacyBar');
  if (!bar) return;
  if (diplomacy && diplomacy.pending) {
    const text = $('diplomacyText');
    if (text) {
      text.textContent = '有人在等你回应：' + (diplomacy.prompt || diplomacy.civName || '');
    }
    bar.hidden = false;
  } else {
    bar.hidden = true;
  }
}

// ==================== 主刷新逻辑 ====================

/** /api/world 的地址：带上 sessionId，后端据此返回「当前控制的文明」的四柱 / 概览。 */
function worldUrl() {
  return sessionId ? `/api/world?sessionId=${encodeURIComponent(sessionId)}` : '/api/world';
}

async function refresh() {
  const [state, events, regions, diplomacy, chaos] = await Promise.all([
    api(worldUrl(), { cache: 'no-store' }),
    api('/api/world/events', { cache: 'no-store' }),
    api('/api/world/regions', { cache: 'no-store' }),
    // 待回应的 AI 外交：后端没有时返回 { pending: false }，读失败当没有处理
    api('/api/diplomacy', { cache: 'no-store' }).catch(() => null),
    // 世界混乱度（左栏进度条）
    api('/api/chaos', { cache: 'no-store' }).catch(() => null),
  ]);

  // 「我控制的文明」以后端 /api/world 为准：切换文明后会跟着变，地图红色标记靠它（不是写死的 1）
  playerCivId = state && state.player && state.player.id > 0 ? state.player.id : null;

  renderDiplomacy(diplomacy);
  renderChaos(chaos);

  // 数字滚动
  renderWorldWithRolling(state);
  // 事件弹窗
  renderEventsWithToast(events);
  // 存储当前区域状态（战斗动画找攻击方领土用）
  _prevRegions = Array.isArray(regions) ? regions.map(r => ({ ...r })) : [];
  lastRegions = _prevRegions;

  // 检测时代晋升动画
  detectEraUpgrade(state);

  try {
    await loadMapSvg();
    mapErrorShown = false;
    renderMiniMap(regions);
    renderMainMap(regions);
  } catch (error) {
    if (!mapErrorShown) {
      mapErrorShown = true;
      showError('世界地图加载失败：' + error.message);
    }
  }

  // 地图重绘完成后，播放指令返回的战斗动画
  if (_pendingCombatEvents) {
    playCombatEvents(_pendingCombatEvents);
    _pendingCombatEvents = null;
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

// ==================== 动画系统 ====================

function toggleSidebar() {
  $('sidePanel').classList.toggle('collapsed');
}

function toggleEvents() {
  const panel = $('eventsPanel');
  const toggle = $('toggleEvents');
  const isOpen = panel.classList.contains('open');
  panel.classList.toggle('open');
  toggle.setAttribute('aria-expanded', !isOpen ? 'true' : 'false');
}

// ==================== 指挥输入时自动暂停世界 ====================
// 像《群星》：你操作时世界基本等你。玩家在指挥框里打字（focus）时把世界暂停，
// 提交（或失焦）后恢复运行。只记录「是不是这次输入导致的暂停」，
// 免得把玩家手动点的暂停也一并恢复掉。
let _pausedForInput = false;

async function setInputPause(paused) {
  if (paused) {
    if (_pausedForInput) return;
    _pausedForInput = true;
    try {
      await post('/api/world/pause');
    } catch (e) {
      _pausedForInput = false; // 失败回退，下次输入重试
    }
  } else {
    if (!_pausedForInput) return;
    _pausedForInput = false;
    try {
      await post('/api/world/resume');
    } catch (e) {
      _pausedForInput = true;
    }
  }
}

function bind() {
  $('toggleSidebar').addEventListener('click', toggleSidebar);
  $('sidebarCollapseBtn').addEventListener('click', toggleSidebar);
  $('toggleEvents').addEventListener('click', toggleEvents);
  $('closeEventsPanel').addEventListener('click', toggleEvents);
  $('closeRegionDetail').addEventListener('click', closeRegionDetail);

  $('advance').addEventListener('click', () => run(advanceYears));

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

  // AI 参与度：普通 / 高（改动即生效）
  $('aiLevel').addEventListener('change', (event) => run(() => setAiLevel(event.target.value)));

  // 新游戏：弹出开局时代选择；结算画面的「再来一局」也走这里
  $('newGame').addEventListener('click', openEraPicker);
  $('closeEraPicker').addEventListener('click', closeEraPicker);
  $('eraPicker').addEventListener('click', (event) => {
    if (event.target === $('eraPicker')) closeEraPicker();
  });
  // 「再来一局」：先把结算遮罩收起来，再打开时代选择。
  // 两个遮罩同为 .tech-overlay（z-index 都是 50），而 #gameOverOverlay 在 DOM 里更靠后，
  // 不移除的话会把 #eraPicker 整个盖住，看起来像"点了没反应"。
  $('playAgain').addEventListener('click', () => {
    $('gameOverOverlay').hidden = true;
    openEraPicker();
  });

  // 科技树
  $('openTechTree').addEventListener('click', () => run(openTechTree));
  $('closeTechTree').addEventListener('click', closeTechTree);
  $('techPanel').addEventListener('click', (event) => {
    if (event.target === $('techPanel')) closeTechTree();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    if (!$('techPanel').hidden) {
      closeTechTree();
    } else if (!$('eraPicker').hidden) {
      closeEraPicker();
    }
  });

  // 玩家指挥
  const sendCommand = () => run(async () => {
    const text = $('commandText').value.trim();
    if (!text) {
      $('commandResult').textContent = '请先输入一条指令。';
      return;
    }
    // 后端按 sessionId 定位「你当前控制的文明」，没有就先登录拿一个
    if (!sessionId) await ensureLoggedIn();
    const button = $('sendCommand');
    button.disabled = true;
    $('commandResult').textContent = '正在解析指令……';
    try {
      const result = await post('/api/command', { text, sessionId });
      if (result && result.result) {
        $('commandResult').textContent = result.result;
        // 捕获战斗事件，等 refresh 重绘完地图后再播放动画
        if (Array.isArray(result.combatEvents) && result.combatEvents.length > 0) {
          _pendingCombatEvents = result.combatEvents;
        }
        // 混乱度变了 → 右上角弹小提示
        if (result.chaosChange) {
          showChaosToast(result.chaosChange);
        }
      } else if (!handleIfSessionExpired(result)) {
        $('commandResult').textContent = '指令失败：' + ((result && result.error) || '未知错误');
      }
    } finally {
      button.disabled = false;
      // 提交后恢复世界运行（输入时暂停的那次）
      setInputPause(false);
    }
  });

  $('sendCommand').addEventListener('click', sendCommand);
  $('commandText').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') sendCommand();
  });
  // 打字时暂停、失焦时恢复：世界基本等你操作完再走
  $('commandText').addEventListener('focus', () => setInputPause(true));
  $('commandText').addEventListener('blur', () => setInputPause(false));
  $('commandText').addEventListener('input', () => {
    if (document.activeElement === $('commandText')) setInputPause(true);
  });

  // 外交提示条：点 [同意]/[拒绝] = 在输入框填上对应词并直接发送，走同一条 /api/command 链路
  const replyDiplomacy = (word) => {
    $('commandText').value = word;
    $('sendCommand').click();
  };
  $('diplomacyAgree').addEventListener('click', () => replyDiplomacy('同意'));
  $('diplomacyReject').addEventListener('click', () => replyDiplomacy('拒绝'));

  // 地图交互
  initMapEvents();
  initMapTooltip();
}

/** 自定义悬停提示：跟随鼠标，显示 区域名 · 地形 · 归属。 */
function initMapTooltip() {
  const tooltip = $('mapTooltip');
  const mainMap = $('mainMap');
  if (!tooltip || !mainMap) return;

  mainMap.addEventListener('mousemove', (event) => {
    const target = event.target.closest('[data-region-id]');
    if (!target) {
      tooltip.hidden = true;
      return;
    }
    const regionId = Number(target.dataset.regionId);
    const region = (lastRegions || []).find((r) => r && r.id === regionId);

    if (!region || region.explored === false) {
      tooltip.innerHTML = '<div class="tooltip-name">未探索</div>';
    } else {
      const terrainName = region.terrain ? (TERRAIN_LABELS[region.terrain] || region.terrain) : '未知';
      const ownerName = region.ownerCivName || '无主';
      tooltip.innerHTML =
        `<div class="tooltip-name">${region.name || '区域 ' + regionId}</div>` +
        `<div class="tooltip-owner">地形：${terrainName} · 归属：${ownerName}</div>`;
    }

    tooltip.hidden = false;
    // 跟随鼠标，越界时翻到左侧/上方
    const pad = 14;
    const rect = tooltip.getBoundingClientRect();
    let x = event.clientX + pad;
    let y = event.clientY + pad;
    if (x + rect.width > window.innerWidth) x = event.clientX - rect.width - pad;
    if (y + rect.height > window.innerHeight) y = event.clientY - rect.height - pad;
    tooltip.style.left = x + 'px';
    tooltip.style.top = y + 'px';
  });

  mainMap.addEventListener('mouseleave', () => {
    tooltip.hidden = true;
  });
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

// AI 参与度（普通 / 高）：读当前档位、改动时写回后端
async function loadAiLevel() {
  try {
    const result = await api('/api/world/ai-level', { cache: 'no-store' });
    if (result && result.level) $('aiLevel').value = result.level;
  } catch (error) {
    // 读不到就保持默认显示，不打扰玩家
  }
}

async function setAiLevel(level) {
  const result = await post('/api/world/ai-level', { level });
  if (result && result.error) {
    showError('设置 AI 参与度失败：' + result.error);
    return;
  }
  if (result && result.level) $('aiLevel').value = result.level;
  showNotice(result && result.level === 'high' ? 'AI 参与度：高（每 10 年一次，参与国家更多）' : 'AI 参与度：普通', 4000);
}

bind();
startAutoRefresh();
loadAiLevel();
run(() => Promise.resolve());

// ==================== 动画系统 ====================

// 区域中心坐标缓存（SVG 世界坐标，基于 viewBox 0 0 1000 500）
const REGION_CENTERS = {};
const SVG_VIEW_W = 1000;
const SVG_VIEW_H = 500;

// 根据区域 ID 计算中心点
function getRegionCenter(regionId) {
  if (REGION_CENTERS[regionId]) return REGION_CENTERS[regionId];

  // 遍历地图元素找到该区域的路径
  const mainMap = $('mainMap');
  const elements = mainMap.querySelectorAll('[data-region-id]');
  for (const el of elements) {
    if (Number(el.dataset.regionId) === Number(regionId)) {
      const bbox = el.getBBox();
      if (bbox && bbox.width > 0) {
        REGION_CENTERS[regionId] = {
          x: bbox.x + bbox.width / 2,
          y: bbox.y + bbox.height / 2
        };
        return REGION_CENTERS[regionId];
      }
    }
  }
  return null;
}

// 存储上一次状态用于变化检测
let _prevRegions = [];
let _prevEra = null;
let _prevEvents = [];
let _seenEventIds = new Set();
let _toastQueue = []; // 待显示的事件弹窗队列
let _pendingCombatEvents = null; // 指令返回的战斗事件，等地图重绘后播放动画

// ========== 动画 1：攻击箭头 ==========
function animateAttackArrow(fromRegionId, toRegionId, delayMs) {
  const fxLayer = $('fxLayer');
  const from = getRegionCenter(fromRegionId);
  const to = getRegionCenter(toRegionId);
  if (!from || !to) return;

  const sx = from.x, sy = from.y, ex = to.x, ey = to.y;

  // 计算控制点（贝塞尔曲线）
  const mx = (sx + ex) / 2;
  const my = (sy + ey) / 2;
  const dx = ex - sx, dy = ey - sy;
  const len = Math.sqrt(dx * dx + dy * dy);
  const offset = Math.min(len * 0.25, 80);
  // 法线方向偏移
  const nx = -dy / len * offset;
  const ny = dx / len * offset;
  const cx = mx + nx;
  const cy = my + ny;

  // 创建曲线 path
  const path = document.createElementNS(SVG_NS, 'path');
  path.setAttribute('class', 'attack-arrow');
  path.setAttribute('d', `M ${sx} ${sy} Q ${cx} ${cy} ${ex} ${ey}`);
  path.setAttribute('stroke-dasharray', '12 6');
  path.setAttribute('stroke-dashoffset', '0');

  // 箭头头部
  const angle = Math.atan2(ey - cy, ex - cx);
  const arrowSize = 12;
  const ax1 = ex - arrowSize * Math.cos(angle - 0.4);
  const ay1 = ey - arrowSize * Math.sin(angle - 0.4);
  const ax2 = ex - arrowSize * Math.cos(angle + 0.4);
  const ay2 = ey - arrowSize * Math.sin(angle + 0.4);
  const arrow = document.createElementNS(SVG_NS, 'polygon');
  arrow.setAttribute('points', `${ex},${ey} ${ax1},${ay1} ${ax2},${ay2}`);
  arrow.setAttribute('fill', '#ff2d2d');

  fxLayer.appendChild(path);
  fxLayer.appendChild(arrow);

  // 起点掩码：曲线先隐藏
  const totalLen = path.getTotalLength();
  path.style.strokeDasharray = `${totalLen} ${totalLen}`;
  path.style.strokeDashoffset = totalLen;

  setTimeout(() => {
    path.classList.add('animating');
    // 从起点"生长"动画 (500ms)
    path.style.transition = 'stroke-dashoffset 0.5s ease-out';
    path.style.strokeDashoffset = '0';
    // 箭头跟随
    arrow.style.transition = 'opacity 0.5s ease-out';
    arrow.style.opacity = '1';
  }, delayMs);

  // 停留 800ms 后淡出
  setTimeout(() => {
    path.style.transition = 'opacity 0.3s ease-out';
    path.style.opacity = '0';
    arrow.style.transition = 'opacity 0.3s ease-out';
    arrow.style.opacity = '0';
    setTimeout(() => {
      path.remove();
      arrow.remove();
    }, 300);
  }, delayMs + 500 + 800);
}

// ========== 动画 2：核弹爆炸 ==========
function animateNukeExplosion(regionId) {
  const mainMap = $('mainMap');
  const elements = mainMap.querySelectorAll('[data-region-id]');
  let targetEl = null;
  for (const el of elements) {
    if (Number(el.dataset.regionId) === Number(regionId)) {
      targetEl = el;
      break;
    }
  }
  if (!targetEl) return;

  // 恢复原始颜色变量
  const originalFill = targetEl.getAttribute('fill') || '#2a2d35';
  targetEl.style.setProperty('--region-color', originalFill);

  // 闪白 → 橙红 → 深灰（通过 class 触发 CSS 动画）
  targetEl.classList.add('nuke-flash');
  setTimeout(() => {
    targetEl.classList.remove('nuke-flash');
    targetEl.setAttribute('fill', originalFill);
  }, 1000);

  // 扩散圆
  const center = getRegionCenter(regionId);
  if (center) {
    const fxLayer = $('fxLayer');
    const circle = document.createElementNS(SVG_NS, 'circle');
    circle.setAttribute('cx', center.x);
    circle.setAttribute('cy', center.y);
    circle.setAttribute('r', '0');
    circle.setAttribute('class', 'nuke-ring animating');
    fxLayer.appendChild(circle);
    setTimeout(() => circle.remove(), 600);
  }
}

function showNukePopup(count) {
  const popup = $('nukePopup');
  popup.textContent = `☢ ${count} 个国家化为焦土`;
  popup.classList.add('animating');
  setTimeout(() => popup.classList.remove('animating'), 3000);
}

// ========== 动画 4：数字滚动 ==========
const _rollingValues = {};

function animateValue(elementId, newValue) {
  const el = $(elementId);
  if (!el) return;

  const target = Number(newValue) || 0;
  const prevStr = el.textContent.replace(/,/g, '');
  const prev = Number(prevStr) || 0;

  if (prev === target) return;

  _rollingValues[elementId] = { from: prev, to: target, start: null, duration: 300 };
  el.classList.add('rolling');

  if (!_rollingValues._rafActive) {
    _rollingValues._rafActive = true;
    requestAnimationFrame(tickRolling);
  }
}

function tickRolling(timestamp) {
  let hasActive = false;
  for (const id in _rollingValues) {
    if (id === '_rafActive') continue;
    const r = _rollingValues[id];
    if (!r.start) r.start = timestamp;
    const progress = Math.min(1, (timestamp - r.start) / r.duration);
    const ease = progress < 0.5 ? 2 * progress * progress : 1 - Math.pow(-2 * progress + 2, 2) / 2; // easeInOut
    const current = Math.round(r.from + (r.to - r.from) * ease);
    const el = $(id);
    if (el) el.textContent = num(current);
    if (progress < 1) {
      hasActive = true;
    } else {
      if (el) {
        el.textContent = num(r.to);
        el.classList.remove('rolling');
      }
      delete _rollingValues[id];
    }
  }
  if (hasActive) {
    requestAnimationFrame(tickRolling);
  } else {
    _rollingValues._rafActive = false;
  }
}

// ========== 动画 5：事件弹窗 ==========
function showEventToast(event) {
  const container = $('eventToasts');
  const toast = document.createElement('div');
  toast.className = 'event-toast';
  toast.innerHTML =
    `<span class="toast-year">Y${event.year}</span>` +
    `<span class="toast-type">${RARITY_LABELS[event.type] || event.type}</span>` +
    `<div class="toast-desc">${event.description}</div>`;
  container.appendChild(toast);

  // 限制最多 5 条
  const toasts = container.querySelectorAll('.event-toast:not(.out)');
  if (toasts.length > 5) {
    toasts[0].classList.add('out');
    setTimeout(() => toasts[0].remove(), 200);
  }

  // 3 秒后滑出
  setTimeout(() => {
    toast.classList.add('out');
    setTimeout(() => toast.remove(), 200);
  }, 3000);
}

// ========== 动画 6：时代晋升 ==========
function animateEraUpgrade(newEra) {
  const flash = $('eraFlash');
  const flashText = document.createElement('div');
  flashText.className = 'era-flash-text animating';
  flashText.textContent = `进入 ${newEra} 时代`;
  document.body.appendChild(flashText);

  flash.classList.add('animating');
  setTimeout(() => flash.classList.remove('animating'), 1800);
  setTimeout(() => flashText.remove(), 1800);
}

// ========== 辅助函数 ==========
// 辅助：获取攻击方的区域中心
function getAttackerCenter(civId, regions) {
  if (!Array.isArray(regions)) return null;
  const owned = regions.find(r => r && r.ownerCivId === civId);
  if (owned) return getRegionCenter(owned.id);
  return null;
}

// 辅助：根据拥有者 ID 找到区域 ID
function getRegionIdByOwner(civId, regions) {
  if (!Array.isArray(regions)) return null;
  const owned = regions.find(r => r && r.ownerCivId === civId);
  return owned ? owned.id : null;
}

// ========== 重写 renderWorld 以支持数字滚动 ==========
function renderWorldWithRolling(state) {
  renderWorld(state);

  const world = state.world;
  const player = state.player;

  // 数字滚动动画
  if (world) {
    if (world.population !== undefined) animateValue('population', world.population);
    if (world.techTotal !== undefined) animateValue('techTotal', world.techTotal);
  }
  if (player) {
    if (player.techLevel !== undefined) animateValue('myTech', player.techLevel);
    if (player.points) {
      let total = 0;
      for (const key of POINT_ORDER) total += Number(player.points[key]) || 0;
      animateValue('myTechPoints', total);
    }
  }
}

// ========== 重写 renderEvents 以支持事件弹窗 ==========
function renderEventsWithToast(events) {
  renderEvents(events);

  if (!Array.isArray(events)) return;

  // 检测新的 rarity >= 80 事件
  for (const event of events) {
    if (!event) continue;
    const eid = `${event.year}-${event.type}-${event.description}`;
    if (event.rarity >= 80 && !_seenEventIds.has(eid)) {
      _seenEventIds.add(eid);
      showEventToast(event);
    }
  }
}

// 播放后端 combatEvents 返回的战斗动画
// 事件格式：{attackerCivId, defenderCivId, regionId, type: 'attack'|'nuke'}
function playCombatEvents(events) {
  if (!Array.isArray(events) || events.length === 0) return;

  const regions = lastRegions;
  let delay = 0;
  const nukeRegions = [];

  for (const ev of events) {
    if (!ev) continue;
    if (ev.type === 'nuke') {
      // regionId=0 表示无领土可画，跳过
      if (ev.regionId) nukeRegions.push(ev.regionId);
    } else if (ev.type === 'attack') {
      // 从攻击方领土画箭头到目标区域（排除目标本身，避免零长度箭头）
      const fromRid = getAttackerRegionId(ev.attackerCivId, ev.regionId, regions);
      if (fromRid && ev.regionId) {
        animateAttackArrow(fromRid, ev.regionId, delay);
        delay += 200;
      }
    }
  }

  // 播放核弹爆炸
  if (nukeRegions.length > 0) {
    for (const rid of nukeRegions) {
      animateNukeExplosion(rid);
    }
    showNukePopup(nukeRegions.length);
  }
}

// 找攻击方的一块领土，优先选不是目标区域的（刚打完归属可能已变，防止箭头从目标指向目标）
function getAttackerRegionId(civId, excludeRegionId, regions) {
  if (!Array.isArray(regions) || !civId) return null;
  const owned = regions.find(r => r && r.ownerCivId === civId && r.id !== excludeRegionId);
  if (owned) return owned.id;
  const any = regions.find(r => r && r.ownerCivId === civId);
  return any ? any.id : null;
}

// 检测时代晋升
function detectEraUpgrade(state) {
  if (!state || !state.world || !state.world.era) return;
  if (_prevEra && _prevEra !== state.world.era) {
    animateEraUpgrade(state.world.era);
  }
  _prevEra = state.world.era;
}

// ========== 初始化时加载之前的状态 ==========
async function initAnimationState() {
  try {
    const state = await api(worldUrl(), { cache: 'no-store' });
    const events = await api('/api/world/events', { cache: 'no-store' });
    const regions = await api('/api/world/regions', { cache: 'no-store' });

    if (state && state.world) _prevEra = state.world.era;
    if (Array.isArray(regions)) _prevRegions = regions.map(r => ({ ...r }));
    if (Array.isArray(events)) {
      events.forEach(e => {
        if (e) _seenEventIds.add(`${e.year}-${e.type}-${e.description}`);
      });
    }
  } catch (e) {
    // 忽略初始化错误
  }
}

// 启动时初始化状态
initAnimationState();

// ==================== 玩家登录与同步 ====================

// 同步状态
let _syncState = null;
let _syncInterval = null;

/** 后端统一用这句话表示「认不出你的 sessionId」（WorldController.SESSION_EXPIRED_MESSAGE）。 */
const SESSION_EXPIRED_MARKER = '登录已失效';
/** 防止每秒一次的同步把「重新登录」弹爆。 */
let _reloginInFlight = false;

/** 响应里带「登录已失效」就清会话、重新加入；返回 true 表示已接管这次错误提示。 */
function handleIfSessionExpired(result) {
  const msg = (result && (result.error || result.message)) || '';
  if (typeof msg === 'string' && msg.includes(SESSION_EXPIRED_MARKER)) {
    handleSessionExpired();
    return true;
  }
  return false;
}

/**
 * sessionId 失效：后端重启会清空内存里的玩家表，localStorage 里的旧 id 就认不出了。
 * 处理方式：清掉本地会话 → 重新弹输入名字 → 加入游戏（重新拿一个 sessionId 存回 localStorage）。
 */
async function handleSessionExpired() {
  if (_reloginInFlight) return;
  _reloginInFlight = true;
  localStorage.removeItem('planet_session_id');
  sessionId = null;
  playerCivId = null;
  _syncState = null;
  showError('登录已失效，请重新输入名字加入');
  try {
    await ensureLoggedIn();   // 里面会 prompt 名字并重新 join，成功后写回 localStorage
  } finally {
    _reloginInFlight = false;
  }
}

async function ensureLoggedIn() {
  if (sessionId) return;

  // 弹出登录框
  const name = prompt('请输入你的名字：') || '匿名';
  playerName = name;
  localStorage.setItem('planet_player_name', name);

  try {
    const result = await api('/api/world/player/join', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name })
    });
    if (result && result.sessionId) {
      sessionId = result.sessionId;
      // 存 localStorage：刷新页面后前端还认得自己（后端按它还原「我控制的文明」）
      localStorage.setItem('planet_session_id', sessionId);
      playerCivId = result.civId;
      if (playerCivId > 0) {
        // 分到了独立且有效的文明，直接用
        showNotice(`欢迎 ${name}！你控制${result.civName || ('文明 ' + playerCivId)}`, 4000);
      } else {
        // 没分到独立文明（比如世界里只有 1 个文明，已被别人控制）：不硬塞给别人，让玩家自己选
        showNoCivNotice(result.aliveCivCount);
      }
    }
  } catch (e) {
    showError('加入游戏失败：' + e.message);
  }
}

async function syncWorld() {
  if (!sessionId) return;

  try {
    const result = await api(`/api/world/sync?sessionId=${encodeURIComponent(sessionId)}`);
    if (!result) return;

    // 后端认不出这个 sessionId（多半是后端重启过）→ 清掉本地会话、重新加入，别继续拿着废 id 操作
    if (result.sessionValid === false) {
      handleSessionExpired();
      return;
    }

    // 检测变化
    const needsFullRefresh =
      !_syncState ||
      _syncState.year !== result.year ||
      _syncState.gameOver !== result.gameOver ||
      JSON.stringify(_syncState.regionOwners) !== JSON.stringify(result.regionOwners);

    if (needsFullRefresh) {
      await refresh();
    }

    // 检测投票
    const voteStatus = await api('/api/world/vote/status');
    if (voteStatus) {
      showVoteBar(voteStatus);
    } else {
      hideVoteBar();
    }

    _syncState = result;
  } catch (e) {
    // 忽略同步错误
  }
}

function showVoteBar(status) {
  let bar = $('voteBar');
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'voteBar';
    bar.className = 'vote-bar';
    document.body.appendChild(bar);
  }
  bar.innerHTML = `
    <div class="vote-info">
      <strong>投票进行中</strong>：
      ${status.type === 'skip_era' ? '跳过时代' : '调整速度'} →
      发起人: ${status.proposerId}
      | 当前 ${status.currentVotes} 票 | 需要 ${status.neededVotes} 票
      | 剩余 ${status.remainingSeconds} 秒
    </div>
    <button id="voteAgree">同意</button>
  `;
  bar.style.display = 'flex';
  $('voteAgree')?.addEventListener('click', async () => {
    // sessionId 统一走 query 参数（后端从 @RequestParam 读；放 body 里后端根本读不到）
    const result = await api(`/api/world/vote/agree?sessionId=${encodeURIComponent(sessionId || '')}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    });
    if (result && result.ok) {
      showNotice('已同意投票', 2000);
    } else if (!handleIfSessionExpired(result)) {
      showError((result && result.message) || '同意投票失败');
    }
    syncWorld();
  });
}

function hideVoteBar() {
  const bar = $('voteBar');
  if (bar) bar.style.display = 'none';
}

function startSync() {
  if (_syncInterval) return;
  _syncInterval = setInterval(syncWorld, SYNC_INTERVAL_MS);
}

async function initPlayer() {
  await ensureLoggedIn();
  await initAnimationState();
  // syncWorld 内部的 refresh() 会用 /api/world 的 player 同步 playerCivId，刷新后也认得出自己
  await syncWorld();
  startSync();
}

// 修改 skipEra 按钮：改为发起投票
$('skipEra').addEventListener('click', async () => {
  if (!sessionId) {
    await ensureLoggedIn();
  }
  const options = await api('/api/world/eras');
  if (!options || !options.targets || options.targets.length === 0) {
    showError('没有可跳的目标时代');
    return;
  }
  const choice = prompt(`当前时代：${options.currentEra}\n\n跳到哪个时代？\n` +
    options.targets.map((t, i) => `${i + 1}. ${t.name}`).join('\n') + '\n\n输入序号：', '1');
  if (!choice) return;
  const idx = parseInt(choice.trim()) - 1;
  if (isNaN(idx) || idx < 0 || idx >= options.targets.length) {
    showError('无效的序号');
    return;
  }
  const target = options.targets[idx];
  const result = await api(`/api/world/vote/propose?sessionId=${encodeURIComponent(sessionId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'skip_era', targetEraIndex: target.index })
  });
  if (result && result.ok) {
    showNotice('投票已发起，等待其他玩家同意', 3000);
    syncWorld();
  } else {
    showError(result && result.message || '发起投票失败');
  }
});

// 添加切换文明按钮
function addSwitchCivButton() {
  const sideBlocks = document.querySelectorAll('.sidebar .side-block');
  const sideBlock = sideBlocks[sideBlocks.length - 1];
  if (!sideBlock) return;
  const btn = document.createElement('button');
  btn.id = 'switchCiv';
  btn.textContent = '切换文明';
  btn.style.marginTop = '8px';
  btn.addEventListener('click', async () => {
    if (!sessionId) await ensureLoggedIn();
    // 列「所有存活文明」并标出归属。原来只列在线玩家，问题有二：
    //   1) 没分到独立文明的玩家 civId=0，选中后提交 civId=0 → 后端报「请提供 civId」→ 切换失败；
    //   2) 无主 / 新诞生的文明根本选不到，「等新文明诞生后切换」形同虚设。
    const [state, players] = await Promise.all([
      api(worldUrl(), { cache: 'no-store' }),
      api('/api/world/player/list').catch(() => null),
    ]);
    const civs = ((state && state.civilizations) || []).filter((c) => c && c.population > 0);
    if (civs.length === 0) {
      showError('当前没有存活的文明');
      return;
    }
    const ownerOf = new Map();
    (players || []).forEach((p) => {
      if (p && p.civId > 0 && !ownerOf.has(p.civId)) ownerOf.set(p.civId, p.name);
    });
    const choices = civs
      .map((c, i) => `${i + 1}. ${c.name}（${ownerOf.get(c.id) ? ownerOf.get(c.id) + ' 控制' : '无主'}）`)
      .join('\n');
    const choice = prompt(`可选文明：\n${choices}\n\n输入序号切换：`);
    if (!choice) return;
    const idx = parseInt(choice.trim()) - 1;
    if (isNaN(idx) || idx < 0 || idx >= civs.length) return;
    const civ = civs[idx];
    await switchToCiv(civ.id, civ.name);
  });
  sideBlock.appendChild(btn);
}

/** 切换到自己控制的文明；成功返回 true。供「切换文明」按钮与加入提示共用。 */
async function switchToCiv(civId, label) {
  if (!sessionId) await ensureLoggedIn();
  const result = await api(`/api/world/player/choose-civ?sessionId=${encodeURIComponent(sessionId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ civId })
  });
  if (result && result.ok) {
    playerCivId = civId;
    showNotice(`已切换到 ${label || ('文明 ' + civId)}`, 2000);
    // 切换后立刻刷新：终局遮罩 / 玩家面板都要按「新控制的文明」重新渲染
    await refresh();
    return true;
  }
  if (!handleIfSessionExpired(result)) {
    showError((result && result.message) || '切换文明失败');
  }
  return false;
}

/** 当前世界里第一个存活文明（用于「和朋友共用一个文明」）。 */
async function firstAliveCiv() {
  const state = await api(worldUrl(), { cache: 'no-store' });
  const civs = (state && state.civilizations) || [];
  return civs.find((c) => c && c.population > 0) || null;
}

/**
 * 没分到独立文明时的提示：不硬塞给别人，给玩家两个选项
 *   a) 和朋友共用一个文明（切到当前第一个存活文明）
 *   b) 等新文明诞生后，自己点「切换文明」
 */
function showNoCivNotice(aliveCivCount) {
  const n = typeof aliveCivCount === 'number' ? aliveCivCount : '?';
  const old = $('joinChoice');
  if (old) old.remove();

  const box = document.createElement('div');
  box.id = 'joinChoice';
  box.className = 'vote-bar';                 // 复用投票条的样式，不新增 CSS
  box.style.flexDirection = 'column';
  box.style.alignItems = 'flex-start';
  box.innerHTML = `
    <div class="vote-info">
      <strong>当前只有 ${n} 个文明</strong>，暂时没有可分给你的独立文明。你可以：
      <br>a) 和朋友共用一个文明
      <br>b) 等新文明诞生后，点「切换文明」再选
    </div>
    <div style="display:flex;gap:8px;margin-top:8px;">
      <button id="joinShareCiv">a) 共用一个文明</button>
      <button id="joinWaitCiv">b) 等新文明诞生</button>
    </div>`;
  document.body.appendChild(box);

  $('joinShareCiv')?.addEventListener('click', async () => {
    const civ = await firstAliveCiv();
    if (!civ) {
      showError('当前没有可加入的文明');
      return;
    }
    if (await switchToCiv(civ.id, civ.name)) box.remove();
  });
  $('joinWaitCiv')?.addEventListener('click', () => box.remove());
}

// 页面加载完成后初始化
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => {
    addSwitchCivButton();
    initPlayer();
  });
} else {
  addSwitchCivButton();
  initPlayer();
}
