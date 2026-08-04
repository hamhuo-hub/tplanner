import { useState } from 'react'
import { ArrowRight, CalendarDays, Eye, EyeOff, LockKeyhole } from 'lucide-react'
import './LoginScreen.css'

function LoginScreen({ onLogin }) {
    const [account, setAccount] = useState('')
    const [password, setPassword] = useState('')
    const [showPassword, setShowPassword] = useState(false)
    const [remember, setRemember] = useState(true)
    const [isSubmitting, setIsSubmitting] = useState(false)
    const [error, setError] = useState('')

    const handleSubmit = async (event) => {
        event.preventDefault()

        if (!account.trim() || !password.trim()) {
            setError('请输入账号和密码')
            return
        }

        setError('')
        setIsSubmitting(true)
        try {
            const authenticated = await onLogin?.({ account, password, remember })
            if (!authenticated) setError('账号或密码错误，请重新输入')
        } catch (requestError) {
            setError(requestError?.message || '暂时无法连接认证服务，请稍后重试')
        } finally {
            setIsSubmitting(false)
        }
    }

    return (
        <main className="login-shell">
            <section className="login-visual" aria-label="建筑背景">
                <div className="login-window-controls" aria-hidden="true">
                    <span className="login-window-dot login-window-dot--red" />
                    <span className="login-window-dot login-window-dot--yellow" />
                    <span className="login-window-dot login-window-dot--green" />
                </div>

                <div className="login-visual-shade" />
                <div className="login-brand">
                    <span className="login-brand-mark"><CalendarDays size={20} strokeWidth={1.8} /></span>
                    <span>tPlanner</span>
                </div>

                <div className="login-visual-copy">
                    <p className="login-eyebrow">YOUR PRIVATE TIME SPACE</p>
                    <h1>让每一段时间，<br />都有自己的秩序。</h1>
                    <p>安静地规划，专注地生活。</p>
                </div>

                <p className="login-image-credit">PRIVATE WORKSPACE · 2026</p>
            </section>

            <section className="login-panel">
                <div className="login-card">
                    <header className="login-card-header">
                        <span className="login-lock"><LockKeyhole size={20} strokeWidth={1.8} /></span>
                        <p>安全访问</p>
                    </header>

                    <div className="login-card-copy">
                        <p className="login-card-kicker">WELCOME BACK</p>
                        <h2>欢迎回来</h2>
                        <p>登录以继续进入你的个人日程空间。</p>
                    </div>

                    <form className="login-form" onSubmit={handleSubmit} noValidate>
                        <label className="login-field">
                            <span>账号</span>
                            <input
                                type="text"
                                value={account}
                                onChange={(event) => setAccount(event.target.value)}
                                placeholder="请输入邮箱或用户名"
                                autoComplete="username"
                                autoFocus
                            />
                        </label>

                        <label className="login-field">
                            <span>密码</span>
                            <span className="login-password-wrap">
                                <input
                                    type={showPassword ? 'text' : 'password'}
                                    value={password}
                                    onChange={(event) => setPassword(event.target.value)}
                                    placeholder="请输入密码"
                                    autoComplete="current-password"
                                />
                                <button
                                    type="button"
                                    className="login-password-toggle"
                                    onClick={() => setShowPassword((visible) => !visible)}
                                    aria-label={showPassword ? '隐藏密码' : '显示密码'}
                                >
                                    {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                                </button>
                            </span>
                        </label>

                        <div className="login-form-meta">
                            <label className="login-remember">
                                <input
                                    type="checkbox"
                                    checked={remember}
                                    onChange={(event) => setRemember(event.target.checked)}
                                />
                                <span>保持登录</span>
                            </label>
                            <button type="button" className="login-text-button">忘记密码？</button>
                        </div>

                        {error && <p className="login-error" role="alert">{error}</p>}

                        <button type="submit" className="login-submit" disabled={isSubmitting}>
                            <span>{isSubmitting ? '正在验证…' : '进入日程'}</span>
                            <ArrowRight size={18} />
                        </button>
                    </form>

                    <footer className="login-card-footer">
                        <span className="login-status-dot" />
                        你的数据保存在自己的工作空间中
                    </footer>
                </div>

                <p className="login-panel-footer">tPlanner for macOS · Build 4.0</p>
            </section>
        </main>
    )
}

export default LoginScreen
