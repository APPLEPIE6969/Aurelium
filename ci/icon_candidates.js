// Extract iconCandidates()/TEXTURE_OVERRIDES from app.js by evaluating the real
// file, so the Python probe can never drift from the shipped rules.
const fs = require('fs');
const src = fs.readFileSync('src/main/resources/web/app.js', 'utf8');

// Take only the top of the file: constants + the resolver, no DOM code.
const end = src.indexOf('function itemIconTag');
const head = src.slice(0, end);

const sandbox = {};
const fn = new Function(head + '\nreturn { iconCandidates };');
const { iconCandidates } = fn();

const mats = fs.readFileSync(process.argv[2], 'utf8').split(/\s+/).filter(Boolean);
const out = {};
for (const m of mats) {
    out[m] = iconCandidates(m);
}
fs.writeFileSync(process.argv[3], JSON.stringify(out));
console.log(`iconCandidates evaluated for ${mats.length} materials`);
