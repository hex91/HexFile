# 第三方组件与许可证（THIRD_PARTY）

本项目（HexFile）为个人本地工具集，**零 AndroidX / 零 Gradle**，采用 Apache-2.0 许可（见 `LICENSE`）。
项目内 vendored（内置源码/二进制）了以下开源组件，特此致谢并标注其许可证：

| 组件 | 用途 | 许可证 | 来源 |
|------|------|--------|------|
| **Termux 终端引擎**（`src/com/termux/`） | 内置终端 / PTY（`libtermux.so` 由 NDK 编译，`native/jni/termux.c`） | Apache-2.0 | https://github.com/termux/termux-app |
| **NanoHTTPD 2.3.1**（`libs/nanohttpd-2.3.1.jar`） | 远程管理 HTTP 服务 | BSD-3-Clause（上游 nanohttpd/nanohttpd） | https://github.com/NanoHttpd/nanohttpd |
| **Nayuki QR Code generator**（`src/io/nayuki/qrcodegen/`） | 远程管理接入二维码生成 | MIT | https://github.com/nayuki/QR-Code-generator |
| **jadx ARSC 解析器**（`src/jadx/`） | Android 资源表（ARSC）解析/编辑 | Apache-2.0 | https://github.com/skylot/jadx |
| **smali / baksmali / dexlib2 2.5.2**（`libs/*.jar`） | smali 反编译 / DEX 编辑 | BSD-3-Clause | https://github.com/JesusFreke/smali |
| **sevenzipjbinding**（`libs/sevenzipjbinding.jar` + `native/lib7-Zip-JBinding.so`） | RAR4/RAR5/7z 解压原生引擎 | GNU LGPL-2.1（带静态链接例外） | https://github.com/borisbrodski/sevenzipjbinding |
| **zstd-jni 1.5.5-11**（`libs/zstd-jni-*.jar` + `native/libzstd-jni-1.5.5-11.so`） | Zstandard 压缩 | BSD-2-Clause | https://github.com/luben/zstd-jni |
| **Apache Commons Compress 1.22**（`libs/commons-compress-1.22.jar`） | 归档格式支持 | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |
| **zip4j 2.11.5**（`libs/zip4j-2.11.5.jar`） | AES/ZipCrypto 加密 ZIP | Apache-2.0 | https://github.com/srikanth-lingala/zip4j |
| **Guava 27.1-android** 及其依赖（`libs/guava-*.jar`、`failureaccess`、`jsr305`） | 通用工具 | Apache-2.0 / BSD | https://github.com/google/guava |
| **ANTLR runtime 3.5.2**（`libs/antlr-runtime-3.5.2.jar`） | 解析支持 | BSD-3-Clause | https://www.antlr.org/ |
| **SLF4J 1.7.36**（`libs/slf4j-api-1.7.36.jar`） | 日志门面（sevenzipjbinding 依赖） | MIT | https://www.slf4j.org/ |
| **XZ for Java 1.9**（`libs/xz-1.9.jar`） | .xz 解压（commons-compress 依赖） | Public Domain | https://github.com/tukaani-project/xz-java |

> 说明：`sevenzipjbinding` 以 LGPL-2.1 发布并附带静态链接例外，本项目以动态加载其原生库（`.so`）的方式使用，符合其许可要求。
> 内置重签名密钥 `assets/hexfile.p12`（alias `hexfile`，密码 `android`）仅用于 App 内部「重打包/重签名」工具功能，非发布签名密钥。
> 发布签名密钥库 `hexfile.keystore`（PKCS12，条目别名 `hex91`）**不随仓库分发**，由 CI 通过仓库 Secrets（`HEXFILE_KEYSTORE_BASE64` / `HEXFILE_KEYSTORE_PASS`）注入。
