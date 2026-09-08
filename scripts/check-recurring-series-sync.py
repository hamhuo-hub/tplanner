#!/usr/bin/env python3
"""Run the small JVM recurrence/Wear checks after app and Wear debug compilation.

Uses the repository's cached Kotlin compiler and already-built production classes.
Does not invoke Gradle, create test source sets, download dependencies, or change app data.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--gradle-cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    args = parser.parse_args()
    if not args.java:
        parser.error("Java is unavailable; pass --java with the Android Studio JBR java executable")
    root = Path(__file__).resolve().parents[1]
    version = re.search(r'^kotlin\s*=\s*"([^"]+)"', (root / "gradle/libs.versions.toml").read_text(), re.M).group(1)

    def jar(group: str, artifact: str, selected_version: str | None = None) -> Path:
        candidates = [p for p in (args.gradle_cache / group / artifact).glob(f"{selected_version or '*'}/*/*.jar")
                      if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
        if not candidates:
            parser.error(f"Missing cached dependency {group}:{artifact}; resolve normal Android dependencies first")
        return max(candidates, key=lambda p: p.stat().st_mtime)

    compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", version),
                jar("org.jetbrains.kotlin", "kotlin-stdlib", version),
                jar("org.jetbrains.kotlin", "kotlin-script-runtime", version),
                jar("org.jetbrains.kotlin", "kotlin-reflect", version),
                jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
                jar("org.jetbrains", "annotations")]
    for group, artifact in [("org.jetbrains.intellij.deps", "trove4j"),
                            ("org.jetbrains.kotlin", "kotlin-daemon-embeddable")]:
        directory = args.gradle_cache / group / artifact
        if directory.exists():
            compiler.append(jar(group, artifact))

    def classes(module: str, expected: str) -> Path:
        candidates = [root / module / "build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
                      root / module / "build/tmp/kotlin-classes/debug"]
        for candidate in candidates:
            if (candidate / expected).is_file():
                return candidate
        parser.error(f"Missing compiled {module} classes; compile :app:assembleDebug and :wear:assembleDebug first")

    dependencies = [compiler[1], jar("org.json", "json"),
                    classes("app", "com/hamhuo/tplanner/syncv3/SyncV3CommandPlanner.class"),
                    classes("wear", "com/hamhuo/tplanner/WatchEventMarks.class")]
    sources = [root / "scripts/checks/RecurringSeriesSyncCheck.kt",
               root / "shared/src/main/kotlin/com/hamhuo/tplanner/WatchTaskSeries.kt",
               root / "wear/src/main/kotlin/com/hamhuo/tplanner/data/WatchTaskListProjection.kt"]
    classpath = os.pathsep.join(map(str, dependencies))
    with tempfile.TemporaryDirectory(prefix="tplanner-series-smoke-") as temporary:
        output = Path(temporary).resolve()
        assert output.parent == Path(tempfile.gettempdir()).resolve() and output.name.startswith("tplanner-series-smoke-")
        subprocess.run([args.java, "-cp", os.pathsep.join(map(str, compiler)),
                        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                        "-jvm-target", "17", "-classpath", classpath, "-d", str(output), *map(str, sources)], check=True)
        subprocess.run([args.java, "-cp", str(output) + os.pathsep + classpath,
                        "com.hamhuo.tplanner.RecurringSeriesSyncCheckKt"], check=True)


if __name__ == "__main__":
    main()
