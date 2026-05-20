#!/bin/bash
# tPlanner Sync Server 安装脚本
# 适用于树莓派 3B / Debian / Ubuntu
# 使用方法: sudo bash install.sh

set -e

INSTALL_DIR="/home/hamhuo/Documents/sync-server"
SERVICE_NAME="tplanner-sync"
SERVICE_USER="tplanner"
PORT="${PORT:-37401}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

[ "$EUID" -ne 0 ] && error "请以 root 运行: sudo bash install.sh"

# ── 1. 检查 / 安装 Node.js ────────────────────────────────────────────────────
info "检查 Node.js..."
if ! command -v node &>/dev/null; then
    warn "未找到 Node.js，正在安装..."
    apt-get update -qq
    # 树莓派 OS (Debian bookworm/bullseye) 自带 nodejs，也可用 NodeSource
    if apt-get install -y nodejs 2>/dev/null; then
        info "Node.js $(node -v) 安装完成"
    else
        error "Node.js 安装失败，请手动安装: https://nodejs.org"
    fi
else
    info "Node.js $(node -v) 已存在"
fi

# 检查版本 >= 18
NODE_MAJOR=$(node -e "process.stdout.write(process.version.slice(1).split('.')[0])")
if [ "$NODE_MAJOR" -lt 18 ]; then
    warn "Node.js 版本 $NODE_MAJOR 较旧，建议使用 18+。继续安装..."
fi

# ── 2. 创建系统用户 ───────────────────────────────────────────────────────────
info "创建系统用户 $SERVICE_USER..."
if ! id "$SERVICE_USER" &>/dev/null; then
    useradd -r -s /bin/false -d "$INSTALL_DIR" "$SERVICE_USER"
    info "用户 $SERVICE_USER 已创建"
else
    info "用户 $SERVICE_USER 已存在"
fi

# ── 3. 复制服务器文件 ──────────────────────────────────────────────────────────
info "安装到 $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR/data"
cp "$(dirname "$0")/server.js" "$INSTALL_DIR/server.js"
chmod 644 "$INSTALL_DIR/server.js"
chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
info "文件已复制"

# ── 4. 安装 systemd 服务 ──────────────────────────────────────────────────────
info "安装 systemd 服务..."
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=tPlanner Sync Server
After=network.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=${INSTALL_DIR}
ExecStart=/usr/bin/node ${INSTALL_DIR}/server.js
Restart=on-failure
RestartSec=5
Environment=PORT=${PORT}
Environment=DATA_DIR=${INSTALL_DIR}/data
User=${SERVICE_USER}
Group=${SERVICE_USER}
NoNewPrivileges=true
PrivateTmp=true
StandardOutput=journal
StandardError=journal
SyslogIdentifier=tplanner-sync

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl restart "$SERVICE_NAME"

# ── 5. 验证启动 ───────────────────────────────────────────────────────────────
sleep 2
if systemctl is-active --quiet "$SERVICE_NAME"; then
    info "服务启动成功 ✓"
else
    error "服务启动失败，查看日志: journalctl -u $SERVICE_NAME -n 30"
fi

# ── 6. 显示本机 IP ────────────────────────────────────────────────────────────
echo ""
info "=============================="
info " tPlanner 同步服务器已就绪"
info "=============================="
info "端口: $PORT"
info "数据目录: $INSTALL_DIR/data"
echo ""
info "本机局域网地址:"
hostname -I | tr ' ' '\n' | grep -v '^$' | grep -v '^::' | while read -r ip; do
    echo -e "  ${GREEN}http://${ip}:${PORT}/tplanner/events${NC}"
done
echo ""
info "常用命令:"
echo "  查看状态:  sudo systemctl status $SERVICE_NAME"
echo "  查看日志:  sudo journalctl -u $SERVICE_NAME -f"
echo "  重启服务:  sudo systemctl restart $SERVICE_NAME"
echo "  健康检查:  curl http://localhost:$PORT/health"
echo ""
