// Apply from app/build.gradle.kts; distribute this matching archive with each APK.
tasks.register<Zip>("correspondingSource") {
    archiveFileName.set("RetroMobileBench-corresponding-source-fixed-suite-v1.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(rootProject.projectDir) {
        include("native/**", "feasibility/**", "third_party/**", "scripts/**", "docs/**", "app/src/**", "app/*.kts", "app/web-corpus-lock.json", "gradle/**", "*.kts", "gradlew*", "gradle.properties", "README.md", ".gitmodules")
        exclude("**/.git", "**/.git/**", "**/build/**", "**/__pycache__/**")
    }
}
