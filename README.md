# QwenPaw Mobile App (Android)

专为 QwenPaw 打造的极简 Android 手机端容器。

## 特性
- **直连公网 WebUI**：直连 `https://paw.xdjj.asia/`，无需本地复杂代理；
- **全功能 Web 运行时**：开启 `DomStorage`（localStorage / sessionStorage）、数据库与第三方 Cookie 支持；
- **原生实时流式输出**：支持 ReadableStream 逐字流式打字效果；
- **文件与图片上传**：支持通过相册/文件管理器上传多媒体及文档；
- **极简极速**：纯原生组件，冷启动毫秒级，APK 仅约 2~3MB。

## 构建方式
通过 GitHub Actions 自动编译出包，每次 push 至 `main` 分支会自动生成 Release APK。
