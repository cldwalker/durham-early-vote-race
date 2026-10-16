// Parses early-vote-counts.csv: a header row of years followed by one
// comma-delimited row per day. A year still in progress has empty or missing
// cells for the days that haven't happened yet; `dataDays` is how many days
// it has data for.
export function parseCounts(text) {
  const lines = text.trim().split(/\r?\n/).map(l => l.trim()).filter(Boolean);
  const names = lines[0].split(',').map(s => s.trim());
  const rows = lines.slice(1).map(line => line.split(',').map(s => s.trim()));
  const days = rows.length;

  const years = names.map((name, i) => {
    let dataDays = 0;
    rows.forEach((row, d) => { if ((row[i] ?? '') !== '') dataDays = d + 1; });
    let sum = 0;
    const cumulative = [0, ...rows.slice(0, dataDays).map(row => (sum += Number(row[i]) || 0))];
    return { name, cumulative, dataDays, complete: dataDays === days };
  });
  return { years, days };
}

// Parses voter-phrases.csv: one phrase per line. A line wrapped in double
// quotes is unquoted, and a literal \n marks a line break within a phrase.
export function parsePhrases(text) {
  return text.split(/\r?\n/)
    .map(l => l.trim())
    .filter(Boolean)
    .map(l => (/^".*"$/.test(l) ? l.slice(1, -1).replace(/""/g, '"') : l))
    .map(l => l.replace(/\\n/g, '\n'));
}
