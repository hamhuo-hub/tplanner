#!/bin/bash
set -e

unset npm_config_prefix

export NVM_DIR="$HOME/.nvm"
if [ -s "$NVM_DIR/nvm.sh" ]; then
    . "$NVM_DIR/nvm.sh"
    nvm use 22
else
    echo "nvm not found, using system node ($(node --version))"
fi

# Use Chinese mirror to download Electron binary
export ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/"

npm run build
npx electron-builder build --linux
