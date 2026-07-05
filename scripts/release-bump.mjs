// ── tPlanner Release Script (Node.js, cross‑platform) ──────────────────
import { readFileSync, writeFileSync } from 'fs';
import { execSync } from 'child_process';

export function release(bumpType) {
    if (!['patch', 'minor', 'major'].includes(bumpType)) {
        console.error('Usage: release("patch"|"minor"|"major")');
        process.exit(1);
    }

    const branch = execSync('git branch --show-current', { encoding: 'utf8' }).trim();

    // ── 检查 working tree 是否干净 ──────────────────────────────────────
    const status = execSync('git status --porcelain', { encoding: 'utf8' });
    if (status.trim()) {
        console.error('❌ Working tree not clean. Please commit or stash changes first.');
        console.error('   Uncommitted files:');
        console.error(status.split('\n').filter(Boolean).map(l => '   ' + l).join('\n'));
        process.exit(1);
    }

    function bumpVer(v, t) {
        let [maj, min, pat] = v.split('-')[0].split('.').map(Number);
        if (t === 'patch') pat += 1;
        else if (t === 'minor') { min += 1; pat = 0; }
        else if (t === 'major') { maj += 1; min = 0; pat = 0; }
        return `${maj}.${min}.${pat}`;
    }

    function run(cmd) {
        try { execSync(cmd, { stdio: 'inherit' }); }
        catch (e) { console.error(`❌ Command failed: ${cmd}`); process.exit(1); }
    }

    // ── master / Electron ───────────────────────────────────────────────
    if (branch === 'master') {
        console.log(`[master] npm version ${bumpType} ...`);
        run(`npm version ${bumpType}`);

    // ── mobile_andorid / Android ────────────────────────────────────────
    } else if (branch === 'mobile_andorid') {
        const f = 'app/build.gradle.kts';
        let c = readFileSync(f, 'utf8');

        const cur = c.match(/versionName\s*=\s*"([^"]+)"/)?.[1];
        if (!cur) { console.error('❌ versionName not found in', f); process.exit(1); }

        const ver = bumpVer(cur, bumpType);
        console.log(`[mobile_andorid] ${cur} → ${ver}`);

        c = c.replace(/versionName\s*=\s*"[^"]+"/, `versionName = "${ver}"`);
        c = c.replace(/versionCode\s*=\s*(\d+)/, (_, n) => `versionCode = ${Number(n) + 1}`);
        writeFileSync(f, c);

        run(`git add "${f}"`);
        run(`git commit -m "${ver}"`);
        run(`git tag v${ver}`);
        console.log(`✓ Tag v${ver}. git push --follow-tags to push.`);

    } else {
        console.error(`❌ Unknown branch '${branch}'. Only master / mobile_andorid supported.`);
        process.exit(1);
    }
}
