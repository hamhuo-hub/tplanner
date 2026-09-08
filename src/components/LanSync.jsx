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
                style={{ color: status === 'success' ? 'var(--tp-semantic-color-success)' : status === 'error' ? 'var(--clr-red)' : undefined }}>
                <Wifi size={13} />
            </button>

            {open && (
                <div className="tp-popover" style={{ position: 'absolute', top: '100%', right: 0, zIndex: 300, width: 300, padding: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <span style={{ fontFamily: 'var(--font-display)', fontSize: 'var(--tp-profile-meta-font-size)', letterSpacing: '0.1em', textTransform: 'uppercase', color: 'var(--clr-text-dim)' }}>同步服务器</span>
                    </div>
                    <input className="tp-field" type="text" aria-label="同步服务器地址" placeholder="https://sync.hamhuo.top" value={config.serverUrl}
                        onChange={e => setConfig(c => ({ ...c, serverUrl: e.target.value }))}
                        onBlur={() => saveConfig(config)} />
                    <div style={{ fontSize: 'var(--tp-profile-meta-font-size)', lineHeight: 1.6, color: 'var(--clr-text-dim)' }}>
                        保存、完成或删除后立即同步；其他在线设备收到通知后自动拉取最新版本。
                    </div>
                    {statusMsg && <span style={{ fontSize: 'var(--tp-profile-meta-font-size)', color: statusColor, fontFamily: 'var(--font-body)' }}>{statusMsg}</span>}
                    {lastVersion > 0 && (
                        <span style={{ fontSize: 'var(--tp-profile-meta-font-size)', color: 'var(--clr-text-dim)', fontFamily: 'var(--font-body)' }}>
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
