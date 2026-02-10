# Soundboard

Android soundboard app with HTTP server.

## Deploy & Launch

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"

./gradlew installDebug && adb shell am start -n com.soundboard/.MainActivity
```
