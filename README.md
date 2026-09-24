# KFT GCS

Kotlin Multiplatform ground control station for KFT's ArduPilot drones: Android tablets and desktop (Windows first) from one codebase. Survey planning comes first.

## Run it

Requirements: JDK 25 (Gradle downloads it automatically), Android SDK (compileSdk 37) for the Android app.

```bash
./gradlew :app:desktop:run              # desktop app  (Windows: gradlew.bat)
./gradlew :app:android:installDebug     # Android app on a connected device or emulator
./gradlew check                         # all tests
```

## Find your way around

- `CLAUDE.md`: architecture rules, module import rules, TX gateway rule, testing, pass workflow
- `docs/implementation/`: the implementation plan, one section per file
- `docs/learning-log.md`: what each development pass changed, and why
- `docs/spec/`: feature spec and pod interface contract
