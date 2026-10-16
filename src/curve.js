// Monotone cubic (Fritsch–Carlson) interpolation through cumulative totals at
// integer day boundaries. Totals stay exact at each day boundary, never run
// backwards, and speed changes smoothly instead of jumping once per day.
// With `endAtRest`, the curve eases to a stop at its last point.
export function monotoneCurve(ys, { endAtRest = false } = {}) {
  const n = ys.length;
  const d = [];
  for (let k = 0; k < n - 1; k++) d[k] = ys[k + 1] - ys[k];

  const m = new Array(n).fill(0);
  // m[0] stays 0 so runners accelerate away from the start line
  m[n - 1] = endAtRest ? 0 : d[n - 2] || 0;
  for (let k = 1; k < n - 1; k++) {
    m[k] = d[k - 1] * d[k] <= 0 ? 0 : (d[k - 1] + d[k]) / 2;
  }
  for (let k = 0; k < n - 1; k++) {
    if (d[k] === 0) {
      m[k] = 0;
      m[k + 1] = 0;
      continue;
    }
    const a = m[k] / d[k];
    const b = m[k + 1] / d[k];
    const s = a * a + b * b;
    if (s > 9) {
      const tau = 3 / Math.sqrt(s);
      m[k] = tau * a * d[k];
      m[k + 1] = tau * b * d[k];
    }
  }

  // Returns the interpolated total and its slope (per day) at fractional day x
  return x => {
    if (x <= 0) return { value: ys[0], slope: m[0] };
    if (x >= n - 1) return { value: ys[n - 1], slope: m[n - 1] };
    const i = Math.floor(x);
    const h = x - i;
    const h2 = h * h;
    const h3 = h2 * h;
    const [y0, y1, m0, m1] = [ys[i], ys[i + 1], m[i], m[i + 1]];
    return {
      value: (2 * h3 - 3 * h2 + 1) * y0 + (h3 - 2 * h2 + h) * m0 +
        (-2 * h3 + 3 * h2) * y1 + (h3 - h2) * m1,
      slope: (6 * h2 - 6 * h) * y0 + (3 * h2 - 4 * h + 1) * m0 +
        (-6 * h2 + 6 * h) * y1 + (3 * h2 - 2 * h) * m1,
    };
  };
}
