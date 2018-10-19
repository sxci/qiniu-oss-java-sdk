# qiniu-oss-java-sdk

```
git clone git@github.com:sxci/qiniu-oss-java-sdk.git
cd qiniu-oss-java-sdk
./gradlew clean
./gradlew jar
ls build/libs/
```


部署：
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

运行测试：
测试代码在 `QiniuOSSTest.java` 文件中。
在 `QiniuOSSTest.java` 文件中选择初始化类型。在 `setUp` 方法中选择运行 `initPublic()` 或 `initPrivate()`，在对应的方法中设置好 `ak`、`sk`。
其中选择 `initPublic()` 表示使用七牛公网服务，选择 `initPrivate()` 表示运行一特定私有存储部署。
测试命令：`./gradlew test -i --tests com.aliyun.oss.QiniuOSSTest.testQiniu` 。
执行命令后 `setUp` 方法会自动运行，进而运行初始化方法；实际测试代码在 `testQiniu` 方法中；测试结束 `tearDown` 方法也会自动运行。


`./gradlew help` 可查看 gradle 支持的一些命令、用法。
