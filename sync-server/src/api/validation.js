// JSON Schema 校验:加载 sync-v3 协议中的 command-batch schema(Ajv, 2020-12)。
import Ajv2020 from 'ajv/dist/2020.js';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const SCHEMA_URL = new URL('../../../sync-v3/protocol/v3/command-batch.schema.json', import.meta.url);

export async function loadBatchValidator() {
  const schema = JSON.parse(await readFile(fileURLToPath(SCHEMA_URL), 'utf8'));
  const ajv = new Ajv2020({ allErrors: true });
  const validate = ajv.compile(schema);

  // 返回 null 表示通过,否则返回人类可读的问题列表
  return (batch) => {
    if (validate(batch)) return null;
    return (validate.errors ?? []).map((e) => `${e.instancePath || '/'} ${e.message}`);
  };
}
