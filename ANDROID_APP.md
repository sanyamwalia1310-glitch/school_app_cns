# Android App Wrapper

This project includes an Android Studio wrapper app in `android-app/`.

## Configure the live URL

Edit `android-app/gradle.properties` and replace:

```text
APP_BASE_URL=https://your-school-app.example.com/home
```

with your deployed Flask URL.

Use HTTPS for production.

## Build the APK

1. Open the repository root `school-management-app/` in Android Studio.
   Android Studio will detect the Gradle project and load the `mobileApp` module from `android-app/app`.
2. Let Gradle sync.
3. Build:
   `Build > Build Bundle(s) / APK(s) > Build APK(s)`
4. Install the generated APK on your phone.

## What the app does

- Opens your online Flask app in a native Android shell
- Supports swipe-to-refresh
- Supports back navigation inside the WebView
- Uses your hosted app URL from `BuildConfig.APP_BASE_URL`
