OPTIVERSE X - FINAL APK PACKAGE

What is inside
  index.html            -> your website (updated: Compress/Advanced now use the phone's own video engine inside the app;
                           in a normal browser everything works exactly as before)
  android/              -> the Android app (WebView for the screen + native video engine)
  .github/workflows/    -> builds the APK on GitHub

STEPS (GitHub, in the browser)
 1. Open android/app/src/main/res/values/strings.xml and replace the site_url value with your GitHub Pages link
    (looks like https://NAME.github.io/REPO/ ).
 2. Upload these three things to the ROOT of your GitHub repository, replacing the old index.html:
      index.html   android   .github
    (Add file > Upload files > drag them in > Commit changes)
 3. Open the repository's "Actions" tab > "Build APK" > "Run workflow".
 4. When it shows a green tick, open that run > Artifacts > OPTIVERSE-APK > download > unzip > install the .apk on the phone.

Later updates: just replace index.html on GitHub. The app loads the new version on its next start (GitHub may take a few minutes to publish).
If the build fails (red cross): open the run, copy the red error text, and send it to Claude.
