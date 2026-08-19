// 旧数据导入 + Snapshot V1 引导(见 docs/sync-v3.md §17 第 2/6/7 步)。
// 用法:
//   TPLANNER_DB_PATH=/var/lib/tplanner-sync/state/tplanner.db \
//   LEGACY_DATA_DIR=/home/hamhuo/Documents/sync-server/data \
//   node scripts/import-legacy.mjs
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { openDatabase } from '../src/state/database.js';
import { parseLegacyData, importIntoDatabase } from '../src/state/v1Importer.js';
import { createInitialSnapshot } from '../src/materializer/materializer.js';
import { resolveServerInstanceId } from '../src/serverInstance.js';

const dbPath = process.env.TPLANNER_DB_PATH || '/var/lib/tplanner-sync/state/tplanner.db';
const dataDir = process.env.LEGACY_DATA_DIR || process.argv[2];

if (!dataDir) {
    console.error('usage: LEGACY_DATA_DIR=<旧数据目录> node scripts/import-legacy.mjs');
    process.exit(2);
}

const readJson = (name) => {
    try {
        return JSON.parse(readFileSync(join(dataDir, name), 'utf8'));
    } catch {
        return undefined;
    }
};

const legacy = {
    events: readJson('events.json') ?? [],
    journals: readJson('journals.json') ?? {},
    goals: readJson('goals.json') ?? [],
    insights: readJson('insights.json') ?? { entries: [], reports: {} },
};

const { entities, issues } = parseLegacyData(legacy);
if (issues.length > 0) {
    console.warn(`import issues (${issues.length}):`);
    for (const issue of issues) console.warn(`  - ${issue}`);
}

const db = openDatabase(dbPath);
const written = importIntoDatabase(db, entities);
const manifest = createInitialSnapshot(db, { serverInstanceId: resolveServerInstanceId(dbPath) });

console.log(JSON.stringify({
    imported: written,
    tasks: entities.filter((e) => e.entityType === 'task').length,
    journals: entities.filter((e) => e.entityType === 'journal').length,
    goals: entities.filter((e) => e.entityType === 'goal').length,
    insights: entities.filter((e) => e.entityType === 'insight').length,
    snapshotV1: manifest,
}, null, 2));
db.close();
