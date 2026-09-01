// 离线 shadow validation CLI(见 docs/sync-v3.md §9.1)。
//
// 用法:
//   node scripts/validate-journal.mjs [dbPath]
// 退出码:0 = 通过;1 = 断链/篡改/异常(只报告,绝不修复)。
import { openDatabase } from '../src/state/database.js';
import { JournalValidationError, validateJournalHead } from '../src/state/journalValidator.js';

const dbPath = process.argv[2]
  ?? process.env.TPLANNER_DB_PATH
  ?? '/var/lib/tplanner-sync/state/tplanner.db';

let db;
try {
  db = openDatabase(dbPath);
} catch (err) {
  console.error(`cannot open database ${dbPath}: ${err.message}`);
  process.exit(1);
}

try {
  const result = validateJournalHead(db);
  const { headState, ...printable } = result;
  console.log(JSON.stringify(printable, null, 2));
} catch (err) {
  if (err instanceof JournalValidationError) {
    console.error(`journal validation FAILED [${err.code}]: ${err.message}`);
  } else {
    console.error(`journal validation crashed: ${err.message}`);
  }
  process.exitCode = 1;
} finally {
  db.close();
}
