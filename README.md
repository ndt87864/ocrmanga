# OCR Manga

OCR Manga là một ứng dụng Android để nhận dạng và dịch văn bản trong ảnh manga/truyện tranh.

## Tính năng

- Nhận dạng văn bản từ ảnh (OCR)
- Dịch văn bản sang nhiều ngôn ngữ
- **Dịch màn hình trực tiếp** - Tính năng mới cho phép dịch văn bản trên màn hình theo thời gian thực
- Xem và quản lý bộ sưu tập ảnh
- Giao diện hiện đại với Jetpack Compose

## Yêu cầu hệ thống

- Android 9.0 (API level 28) trở lên
- Android Studio (để phát triển)
- JDK 8 trở lên

## Cách build dự án

### Sử dụng VS Code Tasks

1. Mở Command Palette (`Ctrl+Shift+P`)
2. Chọn `Tasks: Run Task`
3. Chọn một trong các task sau:
   - **Build OCR Manga Debug**: Build debug APK
   - **Build OCR Manga Release**: Build release APK
   - **Clean OCR Manga**: Clean project

### Sử dụng Terminal

```bash
# Build debug APK
.\gradlew.bat assembleDebug

# Build release APK
.\gradlew.bat assembleRelease

# Clean project
.\gradlew.bat clean

# Build và install trên thiết bị
.\gradlew.bat installDebug
```

set JAVA_HOME=C:\Program Files\Java\jdk-22
set PATH=%JAVA_HOME%\bin;%PATH%
echo %JAVA_HOME%
where java
where javac
java -version
javac -version
gradlew.bat assembleRelease
## Cấu trúc dự án

```
app/src/main/java/com/example/ocrmanga/
├── data/
│   ├── database/          # Database helper và entities
│   ├── models/            # Data models
│   └── repositories/      # Repository pattern
├── services/              # Background services
│   ├── ScreenCaptureService.kt      # Screen capture service
│   └── OverlayTranslationService.kt # Overlay translation service
├── ui/
│   ├── screens/           # Compose screens
│   │   ├── ScreenTranslationScreen.kt # Screen translation UI
│   │   └── ...
│   └── theme/             # Theme và styling
└── viewmodels/            # ViewModels
```

## File APK

Sau khi build thành công, APK sẽ được tạo tại:

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## Cấu hình

- **Package**: com.example.ocrmanga
- **App Name**: OCR Manga
- **Min SDK**: 28 (Android 9.0)
- **Target SDK**: 34
- **Compile SDK**: 35

## Thư viện sử dụng

- Jetpack Compose - UI framework
- Room - Database
- ML Kit - Text recognition và translation
- Coil - Image loading
- Navigation Compose - Navigation
- Coroutines - Async programming

## Ghi chú

Dự án sử dụng KSP (Kotlin Symbol Processing) thay vì KAPT để tương thích tốt hơn với các phiên bản Java mới.

## Tính năng Screen Translation

Ứng dụng hiện có tính năng dịch màn hình trực tiếp, cho phép dịch văn bản từ bất kỳ ứng dụng nào trên thiết bị:

1. Vào **Settings** > **Screen Translation** 
2. Cấp quyền overlay và screen capture
3. Nhấn **Bắt đầu Screen Translation**
4. Văn bản dịch sẽ hiển thị dưới dạng overlay trên màn hình

Chi tiết kỹ thuật xem tại: [SCREEN_TRANSLATION.md](SCREEN_TRANSLATION.md)
