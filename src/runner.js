// SVG runner figures with jointed limbs. Poses are computed in JS each frame
// so stride rate and amplitude can follow each runner's current speed.

export const VIEWBOX = { x: -34, y: -58, w: 68, h: 98 };

export const LOOKS = [
  { shirt: '#2563eb', shorts: '#1e3a8a', skin: '#f0c08a', hair: '#4a2c17',
    shoe: '#111827', sole: '#f8fafc', band: '#facc15', ponytail: false },
  { shirt: '#db2777', shorts: '#3b0764', skin: '#9a5b34', hair: '#1c1310',
    shoe: '#f8fafc', sole: '#db2777', band: null, ponytail: true },
];

function shade(hex, amount) {
  const n = parseInt(hex.slice(1), 16);
  const ch = v => Math.round(v * (1 + amount));
  return `rgb(${ch(n >> 16)},${ch((n >> 8) & 255)},${ch(n & 255)})`;
}

function leg(id, c) {
  return `
  <g data-p="thigh${id}">
    <line y2="17" stroke="${c.skin}" stroke-width="6" stroke-linecap="round"/>
    <line y2="7" stroke="${c.shorts}" stroke-width="9" stroke-linecap="round"/>
    <g transform="translate(0 17)"><g data-p="shin${id}">
      <line y2="17" stroke="${c.skin}" stroke-width="5.2" stroke-linecap="round"/>
      <rect x="-3.5" y="15.5" width="10.5" height="5" rx="2.3" fill="${c.shoe}"/>
      <rect x="-3.5" y="19.2" width="10.5" height="1.6" rx="0.8" fill="${c.sole}"/>
    </g></g>
  </g>`;
}

function arm(id, c) {
  return `
  <g transform="translate(4 -25)"><g data-p="arm${id}">
    <line y2="12" stroke="${c.skin}" stroke-width="4.6" stroke-linecap="round"/>
    <line y2="5" stroke="${c.shirt}" stroke-width="6.5" stroke-linecap="round"/>
    <g transform="translate(0 12)"><g data-p="fore${id}">
      <line y2="10" stroke="${c.skin}" stroke-width="4.2" stroke-linecap="round"/>
      <circle cy="10.5" r="2.6" fill="${c.skin}"/>
    </g></g>
  </g></g>`;
}

function svgMarkup(look, bib) {
  const back = { ...look, skin: shade(look.skin, -0.18), shirt: shade(look.shirt, -0.2),
    shorts: shade(look.shorts, -0.2), shoe: look.shoe };
  const { x, y, w, h } = VIEWBOX;
  return `
<svg xmlns="http://www.w3.org/2000/svg" viewBox="${x} ${y} ${w} ${h}" aria-hidden="true">
  <ellipse cx="3" cy="38.6" rx="13" ry="2.4" fill="rgba(0,0,0,.28)" data-p="shadow"/>
  <g data-p="body">
    ${look.ponytail ? `
    <g transform="translate(-0.5 -44)"><g data-p="tail">
      <path d="M0 -1 C-6 -2 -10 3 -12 11 C-8 8 -5 7 0 4 Z" fill="${look.hair}"/>
    </g></g>` : ''}
    ${arm('B', back)}
    ${leg('B', back)}
    <line x1="0" y1="-3" x2="4" y2="-25" stroke="${look.shirt}" stroke-width="13" stroke-linecap="round"/>
    <g transform="translate(2 -14) rotate(10)">
      <rect x="-5" y="-3.6" width="10" height="7.2" rx="1" fill="#fff"/>
      <text y="1.6" font-size="4.3" font-weight="800" text-anchor="middle"
        font-family="system-ui, sans-serif" fill="#111827">${bib}</text>
    </g>
    ${leg('F', look)}
    <line x1="4" y1="-25" x2="5" y2="-31" stroke="${look.skin}" stroke-width="4.5" stroke-linecap="round"/>
    <circle cx="4.6" cy="-41.6" r="8.4" fill="${look.hair}"/>
    <circle cx="6.2" cy="-39.5" r="7.8" fill="${look.skin}"/>
    <circle cx="4.3" cy="-39" r="1.7" fill="${shade(look.skin, -0.15)}"/>
    <circle cx="10.4" cy="-40.6" r="1.05" fill="#1f2937"/>
    <path d="M9.6 -36.4 q1.6 1.1 3.1 -0.2" stroke="#7c2d12" stroke-width="0.8" fill="none" stroke-linecap="round"/>
    ${look.band ? `<path d="M-1.4 -42 Q6.2 -48.5 13.8 -42" stroke="${look.band}" stroke-width="2.6" fill="none"/>` : ''}
    ${look.ponytail ? `<circle cx="-0.5" cy="-44" r="1.9" fill="${look.shirt}"/>` : ''}
    ${arm('F', look)}
  </g>
</svg>`;
}

const JOINTS = ['thighF', 'shinF', 'thighB', 'shinB', 'armF', 'foreF', 'armB', 'foreB', 'tail'];

export function createRunner(look, bib) {
  const holder = document.createElement('div');
  holder.innerHTML = svgMarkup(look, bib).trim();
  const el = holder.firstElementChild;
  const parts = {};
  el.querySelectorAll('[data-p]').forEach(p => { parts[p.dataset.p] = p; });

  return {
    el,
    setPose(pose) {
      for (const j of JOINTS) {
        if (parts[j]) parts[j].setAttribute('transform', `rotate(${pose[j].toFixed(1)})`);
      }
      parts.body.setAttribute('transform', `translate(0 ${pose.bob.toFixed(2)})`);
    },
  };
}

// p: stride phase in radians. amp: 0 (standing) to 1 (full sprint).
// Positive angles swing a limb backwards for a runner facing right.
export function runPose(p, amp) {
  const s = Math.sin(p);
  const c = Math.cos(p);
  const fore = -(20 + 75 * amp);
  return {
    thighF: -34 * s * amp, shinF: amp * (6 + 75 * Math.max(0, c)),
    thighB: 34 * s * amp, shinB: amp * (6 + 75 * Math.max(0, -c)),
    armF: 38 * s * amp, foreF: fore,
    armB: -38 * s * amp, foreB: fore,
    bob: -2.5 * amp * Math.abs(s),
    tail: 15 + amp * (25 + 12 * Math.sin(2 * p)),
  };
}

// t: seconds since the celebration started. Jumping with both arms raised.
export function celebratePose(t) {
  const j = Math.abs(Math.sin(t * 5.5));
  const wave = 10 * Math.sin(t * 11);
  return {
    thighF: -25 * j, shinF: 50 * j,
    thighB: 10 * j, shinB: 45 * j,
    armF: 130 + wave, foreF: 12,
    armB: -130 - wave, foreB: -12,
    bob: -10 * j,
    tail: 20 + 30 * j,
  };
}
