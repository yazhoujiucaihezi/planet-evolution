// 星球演化 · 前端逻辑（第一阶段：只做真实 API 数据展示与手动推进）
const MAJOR_THRESHOLD = 60;
const MAX_EVENTS_SHOWN = 200;
const RARITY_LABELS = {
  CIVILIZATION_BIRTH: '文明诞生',
  DISASTER: '灾害',
  WAR: '战争',
  TECH_BREAKTHROUGH: '科技突破',
  EXTINCTION: '文明灭亡',
  AI_NARRATIVE: 'AI叙事',
};

const $ = (id) => document.getElementById(id);

function showError(message) {
  const box = $('error');
  box.textContent = message;
  box.hidden = !message;
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
  // 默认只渲染存活文明（population > 0），灭亡文明不再出现在列表中
  const aliveList = civilizations.filter((c) => c.population > 0);
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
  const aliveFromList = civilizations.filter((c) => c.population > 0).length;
  const alive = Number.isFinite(aliveCivilizations) ? aliveCivilizations : aliveFromList;
  const extinct = Number.isFinite(extinctCivilizations)
    ? extinctCivilizations
    : civilizations.length - aliveFromList;
  $('civCount').textContent = `存活 ${alive} / 累计 ${alive + extinct}`;
}

function renderEvents(events) {
  const list = $('events');
  list.innerHTML = '';
  // 空值防御：非数组按空数组处理，并过滤掉 null / undefined 元素
  const items = (Array.isArray(events) ? events : []).filter(Boolean).filter(e => e.rarity >= 60);
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

async function refresh() {
  const [state, events] = await Promise.all([api('/api/world'), api('/api/world/events')]);
  renderWorld(state);
  renderEvents(events);
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

function bind() {
  $('advance').addEventListener('click', () => run(async () => {
    const total = Math.min(10000, Math.max(1, Number($('years').value) || 1));
    $('years').value = total;
    const btn = $('advance');
    const batch = 200;
    let done = 0;
    btn.disabled = true;
    btn.textContent = `推进中 0/${total} 年`;
    try {
      while (done < total) {
        const chunk = Math.min(batch, total - done);
        const result = await post('/api/world/advance', { years: chunk });
        done += chunk;
        if (result.year !== undefined) {
          $('year').textContent = num(result.year);
        }
        btn.textContent = `推进中 ${done}/${total} 年`;
        await refresh();
      }
    } finally {
      btn.disabled = false;
      btn.textContent = '推进';
    }
  }));

  $('runToMajor').addEventListener('click', () => run(async () => {
    const result = await post('/api/world/run-to-major', { maxYears: 5000 });
    if (!result.found) {
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
}

bind();
run(() => Promise.resolve());
