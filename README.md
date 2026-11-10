# ePPT Android 離線閱讀器

將根目錄的 `ePPT.pptx` 在 GitHub Actions 轉為投影片圖片並編譯成 Android APK。藍色圖示，橫向全螢幕、左右滑動翻頁、離線使用。

請先透過 GitHub 的 Add file → Upload files 上傳 `ePPT.pptx` 至 Repository 根目錄。完成後到 Actions → Build ePPT APK → Run workflow。編譯成功後從 Artifacts 下載 `ePPT-apk`，解壓取得 `app-debug.apk`，可重新命名 `ePPT.apk`。