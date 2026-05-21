/**
 * 在第三方库初始化之前拦截 console，过滤掉已知的广告/噪音日志。
 * 必须在 main.tsx 最顶部导入。
 */

const NOISE_PATTERNS = [
    // i18next 广告
    /i18next is made possible by our own product/,
    /locize\.com/,
    // RxDB Dexie 存储广告
    /RxDB Open Core RxStorage/,
    /free Dexie\.js based RxStorage/,
    /premium plugins/,
    /setPremiumFlag/,
    /rxdb\.info\/premium/,
    /rxdb\.info\/rx-storage-dexie/,
    // aria-hidden 浏览器警告（来自 MUI Modal）
    /Blocked aria-hidden on an element/,
];

function isSuppressed(...args) {
    const msg = args.map(a => (typeof a === 'string' ? a : '')).join(' ');
    return NOISE_PATTERNS.some(p => p.test(msg));
}

(['log', 'info', 'warn'] ).forEach(level => {
    const orig = console[level].bind(console);
    console[level] = (...args) => {
        if (!isSuppressed(...args)) orig(...args);
    };
});
