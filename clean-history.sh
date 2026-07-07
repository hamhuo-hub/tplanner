#!/bin/bash
# Replace API keys in all text files
find . -type f \( -name '*.kt' -o -name '*.properties' -o -name '*.kts' \) -exec sed -i 's/sk-f96de0cfd34d4f4c95ee2e004c4c800f/YOUR_DEEPSEEK_API_KEY/g' {} +
find . -type f \( -name '*.kt' -o -name '*.properties' -o -name '*.kts' \) -exec sed -i 's/d7201bb04a81eb33da8d7f8e7d6ed523/YOUR_AMAP_API_KEY/g' {} +
