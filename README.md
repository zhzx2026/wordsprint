# 刷单词 · 开发者测试通道

根目录 = 最近一次构建（不分分支，兼容老的更新源写法）。每条分支有独立坑位，手机「设置 → 更新源」按需填：

    https://raw.githubusercontent.com/zhzx2026/wordsprint/dev                            ← 最近一次（arena01a0b2c2）
    https://raw.githubusercontent.com/zhzx2026/wordsprint/dev/channels/arena01a0b2c2            ← 分支 arena01a0b2c2

同签名覆盖安装，进度不丢。正式版仍走 GitHub Releases。
撤销整个通道： git push origin --delete dev；某分支已合并可手动删掉它的 channels/<id>/ 目录。
