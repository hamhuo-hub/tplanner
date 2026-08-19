// 同步配置与 URL 规范化。
// V1 的三方合并 / LWW / 人工裁决已随 V3 全部移除 —— 客户端不再合并数据,
// 只把本地差异转成命令交给中央 reducer(见 src/syncV3/commandsFromData.js)。
export const DEFAULT_SERVER_URL = 'https://sync.hamhuo.top';
export const DEFAULT_CONFIG = { serverUrl: DEFAULT_SERVER_URL };

export function normalizeServerUrl(url) {
    const trimmed = (url || '').trim();
    if (!trimmed) return '';
    const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
    return withScheme.replace(/\/+$/, '');
}
