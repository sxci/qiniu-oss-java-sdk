# qiniu-oss-java-sdk

执行 `./gradlew clean` 清理编译的缓存；

执行 `./gradlew jar` 编译并生成 jar 文件，保存在 `./build/libs/` 下；

将生成的 jar 文件放入目标项目中。

初始化
```
// qiniuOSSClient 初始化之后，可一直使用
QiniuOSSClient qiniuOSSClient;


Zone zone = Zone.zone0(); // 以实际区域为准
String accessKey = "ak";
String secretKey = "sk";
Configuration config = new Configuration();
qiniuOSSClient = new QiniuOSSClient(accessKey, secretKey, config);



// qiniuOSSClient.deleteObject(bucket, key)

```