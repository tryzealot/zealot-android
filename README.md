# Zealot Android SDK

支持 Kotlin 和 Java 语言编写的 Android 应用。

## 安装

### JitPack

使用 [jitpack](https://jitpack.io) 安装，先需要添加 maven 仓库：

```groovy
allprojects {
  repositories {
    ...
    maven { url 'https://jitpack.io' }
  }
}
```

之后在主 app 项目的 `build.gradle` 添加 zealot：

```groovy
dependencies {
  implementation 'com.github.getzealot:zealot-android:master-SNAPSHOT'
}
```

### JCenter

> 还未发布暂时不可用

```groovy
implementation 'im.ews.zealot:zealot:0.1.0'
```

## 使用

在你的 `Application` 文件的 `onCreate` 方法添加启动代码：

```kotlin
// Kotlin
Zealot.create(getApplication())
      .setEndpoint("https://zealot.test")
      .setChannelKey("...")
      .setBuildType(BuildConfig.BUILD_TYPE)
      .launch()
```

```java
// Java
Zealot.create(getApplication())
      .setEndpoint("http://172.16.16.29:3000")
      .setChannelKey("...")
      .setBuildType(BuildConfig.BUILD_TYPE)
      .launch();
```

## 注意

使用 Zealot SDK 需要开启网络权限

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Author

icyleaf, icyleaf.cn@gmail.com

## License

Zealot is available under the MIT license. See the LICENSE file for more info.
