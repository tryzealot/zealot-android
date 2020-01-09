# Zealot Android SDK

## Installation

> 还未发布暂时不可用

```groovy
implementation 'im.ews.zealot:zealot:0.1.0'
```

## Usage

In your `Application` class add this line to your `onCreate` method:

```kotlin
Zealot.create(this)
    .setEndpoint("https://zealot.test")
    .setChannelKey("xxx")
    .launch()
```

## Note

You need internet permission:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Author

icyleaf, icyleaf.cn@gmail.com

## License

Zealot is available under the MIT license. See the LICENSE file for more info.
