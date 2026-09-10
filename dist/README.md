# 测试版构建产物备份

本目录存放测试版（`feat/modern-mcef-port` 分支，现代 CEF 143 内核）的构建产物，供手动测试直接下载，无需本地构建。

| 文件 | 用途 |
|---|---|
| `mcphone-addon-browser-v1.0.2-modern.4.jar` | **测试用这个**（release 版） |
| `mcphone-addon-browser-v1.0.2-modern.4-dev.jar` | 开发版（含未混淆调试信息，测试无需下载） |

sha256 校验（v1.0.2-modern.4）：

```
80eaceede67faf7c3962d76abeedb533c8be5d3f787c459011c846ac24023021  mcphone-addon-browser-v1.0.2-modern.4.jar
da3d3099c6698b8458aa7923c4fb6295a2842cefdab57a919ab16805107bf7ca  mcphone-addon-browser-v1.0.2-modern.4-dev.jar
```

测试步骤、注意事项（必须移除旧 MCEF jar）、诊断方法见仓库根目录 [TESTING-MODERN-MCEF.md](../TESTING-MODERN-MCEF.md)。

注意：jar 本体不含 CEF natives（约百 MB），首次打开浏览器时自动下载并校验 sha256，解压到 `<游戏目录>/mcefmodern/<commit>/`。下载源优先自建镜像（`github.com/skyc10/mcef-resources` 的 release 资产），不可达时回退 CCBlueX host；均可在配置中覆盖。
