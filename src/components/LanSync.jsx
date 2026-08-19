// 同步面板(V3):只展示状态与手动同步入口。
// 冲突弹窗 / 人工裁决 UI 已随 V1 合并机一并移除 —— 中央 reducer 是唯一裁决者。
import { Wifi, RefreshCw } from 'lucide-react';
import useLanSync from '../hooks/useLanSync';

export default function LanSync(props) {
    const sync = useLanSync(props);
    const { isElectron, open, setOpen, config, setConfig, saveConfig, status, statusMsg, statusColor, doSync, serverUrl, lastVersion } = sync;

    return (
        <div style={{ position: 'relative' }}>
            <button className="btn btn--ghost" onClick={() => setOpen(v => !v)} title="同步"
                style={{ color: status === 'success' ? '#4A9DA8' : status === 'error' ? 'var(--clr-red,#C0392B)' : undefined }}>
                <Wifi size={13} />
            </button>

            {open && (
                <div style={{ position: 'absolute', top: '100%', right: 0, zIndex: 300, width: 300, background: 'var(--clr-surface,#1e1e1e)', border: '1px solid var(--clr-border,#333)', borderRadius: 8, padding: 14, boxShadow: '0 8px 24px rgba(0,0,0,0.5)', display: 'flex', flexDirection: 'column', gap: 12 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <span style={{ fontFamily: 'var(--font-display)', fontSize: 11, letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--clr-text-dim)' }}>同步服务器</span>
                    </div>
                    <input type="text" placeholder="https://sync.hamhuo.top" value={config.serverUrl}
                        onChange={e => setConfig(c => ({ ...c, serverUrl: e.target.value }))}
                        onBlur={() => saveConfig(config)} style={inputStyle} />
                    <div style={{ fontSize: 11, lineHeight: 1.6, color: 'var(--clr-text-dim)' }}>
                        保存、完成或删除后立即同步；其他在线设备收到通知后自动拉取最新版本。
                    </div>
                    {statusMsg && <span style={{ fontSize: 10, color: statusColor, fontFamily: 'var(--font-mono)' }}>{statusMsg}</span>}
                    {lastVersion > 0 && (
                        <span style={{ fontSize: 10, color: 'var(--clr-text-dim)', fontFamily: 'var(--font-mono)' }}>
                            本机镜像版本 v{lastVersion}
                        </span>
                    )}
                    <button className="btn btn--primary" onClick={() => doSync(serverUrl)} disabled={status === 'syncing' || !serverUrl}
                        style={{ display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'center' }}>
                        <RefreshCw size={12} style={status === 'syncing' ? { animation: 'spin 1s linear infinite' } : {}} />
                        立即同步
                    </button>
                </div>
            )}
        </div>
    );
}

const inputStyle = {
    background: 'var(--clr-bg,#111)', border: '1px solid var(--clr-border,#333)',
    borderRadius: 4, color: 'var(--clr-text,#e0e0e0)', fontSize: 12,
    padding: '4px 8px', outline: 'none', width: '100%', fontFamily: 'var(--font-mono)',
};
