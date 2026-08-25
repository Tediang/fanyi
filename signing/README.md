# Android 发布签名

`fanyi-release.jks` 是快译的正式发布签名文件，已通过根目录 `.gitignore` 排除，不能提交到公开仓库。

请把 JKS 与对应的 `keystore.properties` 分开进行离线备份。丢失签名文件或密码后，将无法为已安装用户提供可覆盖升级的 APK。
