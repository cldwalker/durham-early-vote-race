import confetti from 'canvas-confetti';
import countsCsv from '../early-vote-counts.csv';
import phrasesCsv from '../voter-phrases.csv';
import { parseCounts, parsePhrases } from './data.js';
import { monotoneCurve } from './curve.js';
import { LOOKS, VIEWBOX, createRunner, runPose, celebratePose } from './runner.js';

const RACE_MS = 10000;
const UNIT = 'ballots';
const COUNTDOWN = ['3', '2', '1', 'Go!'];
const BEAT_MS = 650;
// Fraction of the runner's width where the chest is; this is what crosses the lines
const ANCHOR = 0.65;
const ASPECT = VIEWBOX.w / VIEWBOX.h;
const DEFAULT_YEARS = ['2022', '2026'];
// Debug/screenshot aid: index.html?at=6.5 loads with the race already 6.5s in
const SKIP_TO = new URLSearchParams(location.search).get('at');

const $ = id => document.getElementById(id);
const numberFormat = new Intl.NumberFormat('en-US');
const fmt = n => numberFormat.format(Math.round(n));
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const escapeHtml = s => s.replace(/[&<>"']/g, ch => `&#${ch.charCodeAt(0)};`);

const { years, days } = parseCounts(countsCsv);
const phrases = parsePhrases(phrasesCsv);

const scene = $('scene');
const road = $('road');
const overlay = $('overlay');
const countdownEl = $('countdown');
const result = $('result');

$('subtitle').textContent = `${days} days of early voting. Pick two years and watch them race.`;
$('day-of').textContent = `of ${days}`;

// Indexes into `years` of the year running in each lane
const picked = DEFAULT_YEARS.map(y => years.findIndex(year => year.name === y));
if (picked.some(i => i < 0) || picked[0] === picked[1]) {
  picked.splice(0, 2, Math.max(0, years.length - 2), years.length - 1);
}

const pickers = picked.map((_, lane) => {
  const look = LOOKS[lane % LOOKS.length];
  const group = document.createElement('div');
  group.className = 'pick';
  group.style.setProperty('--c', look.shirt);
  group.setAttribute('role', 'group');
  group.setAttribute('aria-label', `Lane ${lane + 1} year`);
  const chips = years.map((year, i) => {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'chip';
    chip.textContent = year.name;
    if (!year.complete) {
      const tag = document.createElement('small');
      tag.textContent = 'in progress';
      chip.append(tag);
    }
    chip.addEventListener('click', () => pickYear(lane, i));
    group.append(chip);
    return chip;
  });
  return { group, chips };
});
const vs = document.createElement('span');
vs.className = 'vs';
vs.textContent = 'vs';
$('matchup').append(pickers[0].group, vs, pickers[1].group);

// Picking the year that's in the other lane swaps the two lanes
function pickYear(lane, i) {
  if (picked[lane] === i) return;
  const other = 1 - lane;
  if (picked[other] === i) picked[other] = picked[lane];
  picked[lane] = i;
  buildRace();
}

function renderPickers() {
  pickers.forEach(({ chips }, lane) => chips.forEach((chip, i) => {
    const mine = picked[lane] === i;
    const theirs = picked[1 - lane] === i;
    chip.setAttribute('aria-pressed', String(mine));
    chip.classList.toggle('taken', theirs);
    chip.title = theirs ? 'Swap lanes' : '';
  }));
}

let runners = [];
let best = 1;
let decided = true;
// Race time at which an in-progress year runs out of data, and that runner
let pauseMs = null;
let chaser = null;

function buildRace() {
  closeCheer();
  for (const el of road.querySelectorAll('.lane')) el.remove();
  $('cards').replaceChildren();
  runners = picked.map((yi, lane) => makeRunner(years[yi], lane));
  best = Math.max(...runners.map(r => r.total)) || 1;

  const ends = runners.filter(r => !r.complete).sort((a, b) => a.endK - b.endK);
  chaser = ends[0] || null;
  pauseMs = chaser ? (chaser.endK / days) * RACE_MS : null;

  const notes = [`Each runner advances by that year's daily ${UNIT}. ` +
    'Counts are smoothed between days so the runners move continuously.'];
  for (const r of ends) notes.push(`${r.name} is still in progress, with data through day ${r.endK}.`);
  $('note').textContent = notes.join(' ');

  renderPickers();
  mode = 'idle';
  reset();
  overlay.hidden = false;
  countdownEl.hidden = true;
  $('lead').textContent = 'Press start to begin';
  $('day').textContent = '–';
}

function makeRunner(year, slot) {
  const { name } = year;
  const look = LOOKS[slot % LOOKS.length];
  const fig = createRunner(look, escapeHtml(name));

  const lane = document.createElement('div');
  lane.className = 'lane';
  lane.style.setProperty('--c', look.shirt);
  lane.innerHTML = '<div class="bubble"><b></b><span>0</span></div><div class="runner"></div>';
  lane.querySelector('b').textContent = name;
  lane.querySelector('.runner').append(fig.el);
  road.append(lane);

  const card = document.createElement('div');
  card.className = 'card';
  card.style.setProperty('--c', look.shirt);
  card.innerHTML = `<div class="card-top"><span class="swatch"></span><span class="card-name"></span></div>
    <div class="card-total"><span class="num">0</span> <span class="card-unit">${UNIT}</span></div>
    <div class="bar"><i></i></div>`;
  card.querySelector('.card-name').textContent = name;
  if (!year.complete) {
    const tag = document.createElement('span');
    tag.className = 'card-tag';
    tag.textContent = `thru day ${year.dataDays}`;
    card.querySelector('.card-top').append(tag);
  }
  $('cards').append(card);

  const { cumulative, complete, dataDays } = year;
  return {
    name, look, fig, card, complete,
    curve: monotoneCurve(cumulative, { endAtRest: !complete }),
    endK: dataDays,
    total: cumulative[cumulative.length - 1],
    winner: false,
    wrap: lane.querySelector('.runner'),
    bubble: lane.querySelector('.bubble'),
    bubbleTotal: lane.querySelector('.bubble span'),
    cardTotal: card.querySelector('.num'),
    bar: card.querySelector('.bar i'),
    phase: slot * 1.7,
    x: 0, v: 0, cum: 0, glide: 0, celebrateAt: null, shown: '',
  };
}

// Track geometry in px, recomputed on resize
let G = {};
function layout() {
  const W = road.clientWidth;
  const rh = Math.round(clamp(W * 0.11, 62, 120));
  const rw = rh * ASPECT;
  const start = Math.max(W * 0.07, rw * ANCHOR + 8);
  const finish = W - Math.max(W * 0.12, rw * 1.5);
  G = { W, rh, rw, start, finish, span: finish - start };
  scene.style.setProperty('--runner-h', `${rh}px`);
  scene.style.setProperty('--runner-w', `${rw}px`);
  for (const id of ['start-line', 'start-sign']) $(id).style.left = `${start}px`;
  for (const id of ['finish-line', 'finish-sign']) $(id).style.left = `${finish}px`;
}

let mode = 'idle'; // idle | countdown | racing | paused | finished
let countStart = 0;
let raceStart = 0;
let pausedAt = 0;
let pausedOnce = false;
let finishedAt = 0;
let shownBeat = -1;
let finalLead = '';
let last = performance.now();

function reset() {
  for (const r of runners) {
    Object.assign(r, { v: 0, cum: 0, glide: 0, celebrateAt: null, winner: r.total === best });
    r.bubble.classList.remove('won');
  }
  // A race against an in-progress year is only decided if that year is
  // already strictly ahead
  const winners = runners.filter(r => r.winner);
  decided = runners.every(r => r.complete || (r.winner && winners.length === 1));
  pausedOnce = false;
  $('finish-sign').classList.remove('won');
  result.hidden = true;
}

function startCountdown() {
  reset();
  overlay.hidden = true;
  mode = 'countdown';
  countStart = performance.now();
  shownBeat = -1;
}

function showBeat(text) {
  countdownEl.hidden = false;
  countdownEl.textContent = text;
  countdownEl.classList.remove('pop');
  void countdownEl.offsetWidth; // restart the CSS animation
  countdownEl.classList.add('pop');
}

function celebrate() {
  const rect = scene.getBoundingClientRect();
  const origin = {
    x: (rect.left + G.finish) / innerWidth,
    y: (rect.top + road.offsetTop + road.offsetHeight / 2) / innerHeight,
  };
  const winners = runners.filter(r => r.winner);
  const colors = [...winners.map(r => r.look.shirt), '#facc15', '#ffffff', '#22c55e'];
  const opts = { colors, disableForReducedMotion: true, zIndex: 50 };
  confetti({ ...opts, particleCount: 140, spread: 80, startVelocity: 40, origin });
  setTimeout(() => {
    confetti({ ...opts, particleCount: 80, angle: 60, spread: 60, origin: { x: 0, y: 0.9 } });
    confetti({ ...opts, particleCount: 80, angle: 120, spread: 60, origin: { x: 1, y: 0.9 } });
  }, 350);
  setTimeout(() => confetti({ ...opts, particleCount: 100, spread: 120, origin }), 900);
}

function finish(now) {
  mode = 'finished';
  finishedAt = now;
  result.hidden = false;

  const ranked = [...runners].sort((a, b) => b.total - a.total);
  const winners = ranked.filter(r => r.winner);
  if (!decided) {
    const leader = ranked.find(r => r !== chaser);
    const needed = leader.total - chaser.total + 1;
    $('result-title').textContent = `🏁 ${leader.name} finished with ${fmt(leader.total)} ${UNIT}`;
    $('result-sub').textContent = `${chaser.name} has ${fmt(chaser.total)} through day ${chaser.endK} ` +
      `of ${days} and needs ${fmt(needed)} more to win. It's up to us!`;
    finalLead = `${chaser.name} needs ${fmt(needed)} more ${UNIT} to win`;
    return;
  }

  $('finish-sign').classList.add('won');
  if (winners.length > 1) {
    $('result-title').textContent = "🤝 It's a tie!";
    $('result-sub').textContent = `${winners.map(r => r.name).join(' and ')} both finished with ${fmt(best)} ${UNIT}.`;
    finalLead = "It's a tie!";
  } else {
    const [w, second] = ranked;
    $('result-title').textContent = `🏆 ${w.name} wins!`;
    finalLead = `${w.name} wins!`;
    $('result-sub').textContent = second
      ? `${fmt(w.total)} to ${fmt(second.total)}, a margin of ${fmt(w.total - second.total)} ${UNIT}.`
      : `${fmt(w.total)} ${UNIT}.`;
  }
  celebrate();
}

function updateRunner(r, k, elapsed, dt, now) {
  if (elapsed < RACE_MS) {
    // An in-progress year stops where its data ends
    const { value, slope } = r.curve(Math.min(k, r.endK));
    r.cum = value;
    r.x = G.start + (value / best) * G.span;
    r.v = k < r.endK ? (slope * days) / (RACE_MS / 1000) / best * G.span : 0;
  } else {
    // Coast to a stop after the clock runs out
    r.cum = r.total;
    r.glide += r.v * dt;
    r.v *= Math.exp(-2 * dt);
    r.x = G.start + (r.total / best) * G.span + r.glide;
    if (!r.winner) r.x = Math.min(r.x, G.finish - 4);
  }

  const refSpeed = G.span / (RACE_MS / 1000);
  const norm = r.v / refSpeed;
  if (r.winner && decided && mode === 'finished' && (norm < 0.15 || now - finishedAt > 700)) {
    if (r.celebrateAt === null) {
      r.celebrateAt = now;
      r.bubble.classList.add('won');
    }
    r.fig.setPose(celebratePose((now - r.celebrateAt) / 1000));
  } else {
    r.phase += 2 * Math.PI * (0.9 + 1.5 * Math.min(norm, 1.6)) * dt;
    r.fig.setPose(runPose(r.phase, clamp(norm * 1.5, 0, 1)));
  }
}

function renderRunner(r) {
  r.wrap.style.transform = `translateX(${(r.x - G.rw * ANCHOR).toFixed(1)}px)`;

  const text = fmt(r.cum);
  if (text !== r.shown) {
    r.shown = text;
    r.bubbleTotal.textContent = text;
    r.cardTotal.textContent = text;
  }
  r.bar.style.width = `${(r.cum / best) * 100}%`;

  // Keep the label above the runner's head but inside the track
  const bw = r.bubble.offsetWidth;
  const left = clamp(r.x - bw / 2, 4, G.W - bw - 4);
  r.bubble.style.transform = `translateX(${left.toFixed(1)}px)`;
  r.bubble.style.setProperty('--tip', `${clamp(r.x - left, 10, bw - 10).toFixed(1)}px`);
}

function renderBoard(k) {
  $('day').textContent = mode === 'idle' || mode === 'countdown'
    ? '–'
    : String(mode === 'finished' ? days : clamp(Math.ceil(k), 1, days));

  const ranked = [...runners].sort((a, b) => b.cum - a.cum);
  runners.forEach(r => r.card.classList.toggle('leading', mode !== 'idle' && r === ranked[0] && r.cum > 0));
  if (mode === 'idle') return;
  const gap = ranked.length > 1 ? ranked[0].cum - ranked[1].cum : 0;
  const lead = $('lead');
  if (mode === 'countdown') lead.textContent = 'On your marks…';
  else if (mode === 'finished') lead.textContent = finalLead;
  else if (Math.round(gap) === 0) lead.textContent = 'Neck and neck!';
  else lead.textContent = `${ranked[0].name} leads by ${fmt(gap)} ${UNIT}`;
}

function step(now, dt) {
  if (mode === 'paused') return;
  if (mode === 'countdown') {
    const beat = Math.floor((now - countStart) / BEAT_MS);
    if (beat !== shownBeat && beat < COUNTDOWN.length) {
      shownBeat = beat;
      showBeat(COUNTDOWN[beat]);
    }
    if (beat >= COUNTDOWN.length - 1) {
      mode = 'racing';
      raceStart = countStart + (COUNTDOWN.length - 1) * BEAT_MS;
    }
  }

  let elapsed = mode === 'racing' || mode === 'finished' ? now - raceStart : 0;
  // Freeze on the exact moment an in-progress year runs out of data
  const pauseNow = mode === 'racing' && pauseMs !== null && !pausedOnce && elapsed >= pauseMs;
  if (pauseNow) {
    elapsed = pauseMs;
    raceStart = now - pauseMs;
  }
  if (mode === 'racing' && !pauseNow && elapsed >= RACE_MS) finish(now);

  const k = (Math.min(elapsed, RACE_MS) / RACE_MS) * days;
  for (const r of runners) {
    updateRunner(r, k, elapsed, dt, now);
    renderRunner(r);
  }
  renderBoard(k);
  if (pauseNow) pause(now);
}

function pause(now) {
  mode = 'paused';
  pausedOnce = true;
  pausedAt = now;
  $('lead').textContent = `${chaser.name} is waiting on you…`;
  openCheer();
}

function resume() {
  if (mode !== 'paused') return;
  const now = performance.now();
  raceStart += now - pausedAt;
  last = now;
  mode = 'racing';
}

// Encouragement modal shown when the race pauses for an in-progress year
const cheer = $('cheer');
const cheerMsg = $('cheer-msg');
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
let phraseIndex = -1;

function showPhrase() {
  if (!phrases.length) return;
  let i = Math.floor(Math.random() * phrases.length);
  if (phrases.length > 1 && i === phraseIndex) i = (i + 1 + Math.floor(Math.random() * (phrases.length - 1))) % phrases.length;
  phraseIndex = i;
  cheerMsg.textContent = phrases[i];
  cheerMsg.classList.remove('swap');
  void cheerMsg.offsetWidth; // restart the CSS animation
  cheerMsg.classList.add('swap');
}

function openCheer() {
  const other = runners.find(r => r !== chaser);
  $('cheer-title').textContent = `Will ${chaser.name} beat out ${other.name}? It's up to us!`;
  phraseIndex = -1;
  showPhrase();
  cheer.classList.remove('closing');
  cheer.showModal();
  $('cheer-next').focus();
}

function closeCheer() {
  if (!cheer.open || cheer.classList.contains('closing')) return;
  if (reducedMotion.matches) return cheer.close();
  cheer.classList.add('closing');
}

cheer.addEventListener('animationend', e => {
  if (e.target === cheer && cheer.classList.contains('closing')) {
    cheer.classList.remove('closing');
    cheer.close();
  }
});
cheer.addEventListener('close', resume);
cheer.addEventListener('cancel', e => { e.preventDefault(); closeCheer(); });
// Clicks outside the card land on the dialog element itself (its backdrop)
cheer.addEventListener('click', e => { if (e.target === cheer) closeCheer(); });
$('cheer-close').addEventListener('click', closeCheer);
$('cheer-done').addEventListener('click', closeCheer);
$('cheer-next').addEventListener('click', showPhrase);

function frame(now) {
  step(now, Math.min(0.05, (now - last) / 1000));
  last = now;
  requestAnimationFrame(frame);
}

function skipAhead(seconds) {
  overlay.hidden = true;
  mode = 'racing';
  raceStart = last - seconds * 1000;
  for (let t = raceStart; t < last; t += 1000 / 60) step(t, 1 / 60);
}

$('start-btn').addEventListener('click', startCountdown);
$('again-btn').addEventListener('click', () => {
  startCountdown();
  scene.scrollIntoView({ behavior: 'smooth', block: 'center' });
});
new ResizeObserver(layout).observe(road);
layout();
buildRace();
if (SKIP_TO !== null) skipAhead(Number(SKIP_TO) || 0);
requestAnimationFrame(frame);
