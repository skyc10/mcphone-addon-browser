# 测试版构建产物备份

本目录存放测试版（`feat/modern-mcef-port` 分支，现代 CEF 143 内核）的构建产物，供手动测试直接下载，无需本地构建。

| 文件 | 用途 |
|---|---|
| `mcphone-addon-browser-v1.0.2-modern.1.jar` | **测试用这个**（release 版） |
| `mcphone-addon-browser-v1.0.2-modern.1-dev.jar` | 开发版（含未混淆调试信息，测试无需下载） |

sha256 校验（v1.0.2-modern.1）：

```
362af826118e79c5873f9ac9fe4086e9ee25206410bdcedc5b103c6d67f954c7  mcphone-addon-browser-v1.0.2-modern.1.jar
1e9a290e4a2cd0c65696829402a544dc13813ddc8aaa720ddbbcdc3e80bb7d08  mcphone-addon-browser-v1.0.2-modern.1-dev.jar
```

测试步骤、注意事项（必须移除旧 MCEF jar）、诊断方法见仓库根目录 [TESTING-MODERN-MCEF.md](../TESTING-MODERN-MCEF.md)。

注意：jar 本体不含 CEF natives（约百 MB），首次打开浏览器时自动从 CCBlueX host 下载并校验 sha256，解压到 `<游戏目录>/mcefmodern/<commit>/`。host 可在配置中覆盖。
