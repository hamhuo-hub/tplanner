#!/usr/bin/env python3
"""Run the production recurrence domain on the JVM, without Gradle or an Android test framework.

Uses the Kotlin compiler and org.json already present in the local Gradle cache. The Android-only
Store is excluded; its data classes and date filter are extracted verbatim for this small harness.
No dependencies are downloaded and no production or Gradle configuration is changed.
"""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
VERSION = re.search(r'kotlin\s*=\s*"([^"]+)"', (ROOT / "gradle/libs.versions.toml").read_text())[1]


def jar(group, artifact, version=None):
    folder = CACHE / group / artifact
    pattern = f"{version}/*/{artifact}-{version}.jar" if version else f"*/*/{artifact}-*.jar"
    candidates = sorted(p for p in folder.glob(pattern) if not p.name.endswith(("-sources.jar", "-javadoc.jar")))
    if not candidates:
        raise SystemExit(f"Required cached dependency missing: {group}:{artifact}:{version or '*'}")
    return str(candidates[-1])


HARNESS = r'''
package com.hamhuo.tplanner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject

private var checks = 0
private fun verify(value: Boolean, message: String) { check(value) { message }; checks++ }
private fun task(id: String = "root", start: String = "2026-01-31T01:00:00Z", frequency: String = "daily", count: Int = 3) = ScheduleItem(
    id, "Title", "task", Instant.parse(start), Instant.parse(start).plusSeconds(3600), false,
    listOf(CheckItem("c1", "First", true)), 0, "Note", 0L, 1L,
    extras = if (frequency.isEmpty()) emptyMap() else mapOf("recurrenceType" to frequency, "recurrenceCount" to count),
)
private fun List<ScheduleItem>.applyPlan(plan: List<ScheduleItem>): List<ScheduleItem> =
    (associateBy { it.id } + plan.associateBy { it.id }).values.toList()
private fun List<ScheduleItem>.at(index: Int) = single { recurringSeriesMetadata(it)?.occurrenceIndex == index && it.deletedAt == 0L }
private fun ScheduleItem.rule(frequency: String, count: Int) = copy(extras = extras + mapOf("recurrenceType" to frequency, "recurrenceCount" to count))

fun main() {
    val root = task()
    val created = createRecurringTaskInstances(root)
    verify(created.size == 3 && created.map { it.id }.distinct().size == 3, "new series creates exactly count instances")
    verify(created.all { recurringSeriesMetadata(it)?.seriesId == root.id }, "stable canonical identity on every instance")
    verify(created.at(0).checklist.single().completed && !created.at(1).checklist.single().completed, "future checklist progress resets")
    verify(created.at(2).start == root.start.plusSeconds(2 * 86400), "daily schedule")
    val plain = task("existing", frequency = "")
    val converted = listOf(plain).applyPlan(planRecurringTaskChange(listOf(plain), plain, plain.rule("weekly", 4), 2L))
    verify(converted.size == 4 && converted.at(0).id == plain.id, "existing task converts without replacing first id")
    verify(converted.at(3).start == plain.start.plusSeconds(21 * 86400), "weekly schedule")

    val before = created.at(1)
    val latest = created.map { if (it.id == created.at(2).id) it.copy(completed = true, note = "remote note", checklist = listOf(CheckItem("c1", "First", true))) else it }
    val renamed = latest.applyPlan(planRecurringTaskChange(latest, before, before.copy(title = "Shared title"), 3L))
    verify(renamed.all { it.title == "Shared title" }, "edit non-first instance changes series title")
    verify(renamed.at(2).completed && renamed.at(2).checklist.single().completed, "latest sibling completion retained")
    verify(renamed.at(2).note == "remote note", "unchanged note never overwritten from stale editor")
    verify(renamed.map { it.start } == latest.map { it.start }, "title-only edit never reschedules")
    val progressed = renamed.applyPlan(planRecurringTaskChange(renamed, renamed.at(0), renamed.at(0).copy(completed = true), 4L))
    verify(progressed.at(0).completed && !progressed.at(1).completed, "completion only affects selected occurrence")
    val ownRemoteProgress = created.map { if (it.id == before.id) it.copy(completed = true) else it }
    val ownMerged = ownRemoteProgress.applyPlan(planRecurringTaskChange(ownRemoteProgress, before, before.copy(note = "local note"), 4L))
    verify(ownMerged.at(1).completed, "stale own copy does not reset completion during an unrelated action")
    val singleCheck = created.applyPlan(planRecurringTaskChange(created, before, before.copy(checklist = listOf(CheckItem("c1", "First", true))), 4L))
    verify(singleCheck.at(1).checklist.single().completed && !singleCheck.at(2).checklist.single().completed, "checklist-only completion does not propagate")
    val checkBefore = progressed.at(1)
    val checklistEdit = progressed.applyPlan(planRecurringTaskChange(progressed, checkBefore,
        checkBefore.copy(checklist = listOf(CheckItem("c1", "Renamed item", false))), 5L))
    verify(checklistEdit.all { it.checklist.single().text == "Renamed item" }, "checklist title shared")
    verify(checklistEdit.at(0).checklist.single().completed && !checklistEdit.at(1).checklist.single().completed, "checklist progress independent")
    val onlyCheck = checklistEdit.applyPlan(planRecurringTaskChange(checklistEdit, checklistEdit.at(1),
        checklistEdit.at(1).copy(checklist = listOf(CheckItem("c1", "Renamed item", true))), 6L))
    verify(onlyCheck.at(1).checklist.single().completed && onlyCheck.at(2).checklist.single().completed, "single checklist progress preserves siblings")

    val monthly = createRecurringTaskInstances(task("monthly", frequency = "monthly"))
    verify(monthly.at(1).start == Instant.parse("2026-02-28T01:00:00Z") && monthly.at(2).start == Instant.parse("2026-03-31T01:00:00Z"), "month end derives each date from original anchor")
    val selected = monthly.at(1)
    val changedFrequency = monthly.applyPlan(planRecurringTaskChange(monthly, selected, selected.rule("daily", 3), 7L))
    verify(changedFrequency.at(1).start == selected.start && changedFrequency.at(0).start == selected.start.minusSeconds(86400), "frequency change aligns selected occurrence")
    val withHistory = monthly.map { if (it.id == monthly.at(0).id) it.copy(completed = true) else it }
    val moved = withHistory.applyPlan(planRecurringTaskChange(withHistory, selected, selected.copy(start = selected.start.plusSeconds(3600), end = selected.end.plusSeconds(3600)), 8L))
    verify(moved.at(0).start == monthly.at(0).start && moved.at(1).start == selected.start.plusSeconds(3600), "schedule edits preserve other completed history")
    val ownHistory = moved.at(0)
    val explicitlyMoved = moved.applyPlan(planRecurringTaskChange(moved, ownHistory, ownHistory.copy(start = ownHistory.start.plusSeconds(600)), 9L))
    verify(explicitlyMoved.at(0).start == ownHistory.start.plusSeconds(600), "completed occurrence can explicitly edit its own schedule")
    val dstSource = task("dst", "2026-03-07T14:00:00Z").copy(extras = root.extras + ("timezone" to "America/New_York"))
    val dst = createRecurringTaskInstances(dstSource)
    verify(dst.at(1).start == Instant.parse("2026-03-08T13:00:00Z"), "daily recurrence retains wall time across DST")

    val five = createRecurringTaskInstances(task("five", count = 5)).map { if (recurringSeriesMetadata(it)?.occurrenceIndex == 4) it.copy(completed = true) else it }
    val shrunk = five.applyPlan(planRecurringTaskChange(five, five.at(0), five.at(0).rule("daily", 2), 10L))
    verify(shrunk.count { it.deletedAt != 0L } == 2 && shrunk.at(4).completed, "shrink preserves completed history")
    val tombstones = shrunk.filter { it.deletedAt != 0L }.map { it.id }.toSet()
    verify(shrunk.filter { it.deletedAt == 0L }.all { recurringSeriesMetadata(it)!!.retiredOccurrenceIds.containsAll(tombstones) }, "shrink publishes retired IDs on every live member")
    val purged = shrunk.filter { it.deletedAt == 0L }
    val afterPurge = purged.applyPlan(planRecurringTaskChange(purged, purged.at(0), purged.at(0).rule("daily", 5), 11L))
    verify(afterPurge.count { it.deletedAt == 0L } == 5 && afterPurge.none { it.id in tombstones }, "extension after tombstone purge cannot reuse retired IDs")
    val extended = shrunk.applyPlan(planRecurringTaskChange(shrunk, shrunk.at(0), shrunk.at(0).rule("daily", 5), 11L))
    verify(extended.count { it.deletedAt == 0L } == 5 && extended.filter { it.id in tombstones }.all { it.deletedAt != 0L }, "extension never revives tombstones")
    verify(extended.at(2).id !in tombstones, "replacement has new id")
    val again = extended.applyPlan(planRecurringTaskChange(extended, extended.at(0), extended.at(0), 12L))
    verify(again.map { it.id }.toSet() == extended.map { it.id }.toSet(), "repeat save idempotent")
    val deleted = created.applyPlan(planRecurringTaskChange(created, created.at(1), created.at(1).copy(deletedAt = 20L), 20L))
    val afterDelete = deleted.applyPlan(planRecurringTaskChange(deleted, deleted.at(0), deleted.at(0).copy(note = "changed"), 21L))
    verify(afterDelete.size == 3 && afterDelete.count { it.deletedAt != 0L } == 1, "unrelated edit does not recreate deleted instance")
    verify(afterDelete.filter { it.deletedAt == 0L }.all { created.at(1).id in recurringSeriesMetadata(it)!!.retiredOccurrenceIds }, "single deletion retires ID on remaining live members")
    val cancelled = extended.applyPlan(planRecurringTaskChange(extended, extended.at(1), withoutRecurringTaskSeries(extended.at(1)), 22L))
    verify(cancelled.filter { it.deletedAt == 0L }.map { it.id }.toSet() == setOf(extended.at(1).id, extended.at(4).id), "cancel preserves edited occurrence plus completed history")
    verify(cancelled.filter { it.deletedAt == 0L }.all { recurringSeriesMetadata(it) == null }, "cancel clears live series identity")
    val cancelRoot = created.applyPlan(planRecurringTaskChange(created, created.at(0), withoutRecurringTaskSeries(created.at(0)), 23L))
    val cancelPurged = cancelRoot.filter { it.deletedAt == 0L }
    val restartSource = cancelPurged.single()
    val restarted = cancelPurged.applyPlan(planRecurringTaskChange(cancelPurged, restartSource, restartSource.rule("daily", 3), 24L))
    verify(restarted.size == 3 && restarted.drop(1).none { next -> created.drop(1).any { it.id == next.id } }, "cancel then purge then re-enable allocates fresh occurrence IDs")
    verify(recurringSeriesMetadata(restartSource) == null && "_retiredRecurringOccurrenceIds" in restartSource.extras, "retirement ledger survives cancellation without grouping")

    val legacy = created.map { it.copy(extras = it.extras - "_syncV3Recurrence") }
    val recovered = recoverRecurringTaskSeries(legacy)
    verify(recovered.all { recurringSeriesMetadata(it)?.seriesId == root.id }, "recover exact old Android ids")
    verify(legacy.all { recurringSeriesMetadata(it) == null }, "recovery does not mutate input")
    val partial = legacy.map { if (it.id == root.id) created.at(0) else it }
    verify(recoverRecurringTaskSeries(partial).all { recurringSeriesMetadata(it) != null }, "recover partially migrated Android group")
    val unrelated = legacy + task("random-web-id", start = root.start.toString())
    verify(recurringSeriesMetadata(recoverRecurringTaskSeries(unrelated).last()) == null, "same-title random Web task stays independent")
    val unlinked = task("web-unlinked", count = 10)
    verify(planRecurringTaskChange(listOf(unlinked), unlinked, unlinked.copy(title = "only title"), 23L).size == 1, "unverifiable old Web recurrence does not expand on rename")
    verify(planRecurringTaskChange(listOf(unlinked), unlinked, unlinked, 24L).size == 1, "unverifiable old Web recurrence does not expand on no-op save")
    verify(planRecurringTaskChange(listOf(unlinked), unlinked, unlinked.rule("weekly", 4), 25L).size == 4, "explicit old Web rule change can create new series")
    verify(TaskView.Inbox.listItems(legacy).single().id == root.id, "Inbox folds series")
    verify(TaskView.Today.listItems(legacy, LocalDate.parse("2026-02-01")).single().id == created.at(1).id, "Today recovers using root outside date filter")
    verify(TaskView.Inbox.listItems(created.map { it.copy(completed = true) }).single().id == created.at(2).id, "all completed retains latest representative")
    val scoped = created.map { if (it.id == root.id) it.copy(listId = "elsewhere") else it.copy(listId = "target") }
    verify(TaskView.CustomList("target", "Target").listItems(scoped).single().id == created.at(1).id, "custom list filters before selecting representative")
    val extensionWire = JSONObject((created.at(1).extras["_syncV3Recurrence"] as JSONObject).toString()).put("futureField", "keep")
    val rich = created.map { if (it.id == created.at(1).id) it.copy(extras = it.extras + ("_syncV3Recurrence" to extensionWire)) else it }
    val richEdited = rich.applyPlan(planRecurringTaskChange(rich, rich.at(0), rich.at(0).copy(title = "wire"), 26L))
    verify((richEdited.at(1).extras["_syncV3Recurrence"] as JSONObject).optString("futureField") == "keep", "unknown wire field retained")
    val malformed = JSONObject(extensionWire.toString()).put("occurrenceIndex", 1.5)
    verify(recurringSeriesMetadata(before.copy(extras = before.extras + ("_syncV3Recurrence" to malformed))) == null, "fractional occurrence index rejected")
    malformed.put("occurrenceIndex", "1")
    verify(recurringSeriesMetadata(before.copy(extras = before.extras + ("_syncV3Recurrence" to malformed))) == null, "string occurrence index rejected")
    val collidingPlainId = task(root.id, frequency = "")
    verify(collapseRecurringTaskSeries(created.drop(1) + collidingPlainId).size == 2, "plain id cannot collide with series grouping key")
    println("Recurring task domain: $checks checks passed")
}
'''


def main():
    java = shutil.which("java")
    if not java:
        raise SystemExit("Java is required on PATH")
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", VERSION)
    compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", VERSION), stdlib,
                jar("org.jetbrains.kotlin", "kotlin-script-runtime", VERSION),
                jar("org.jetbrains.kotlin", "kotlin-reflect", VERSION),
                jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), jar("org.jetbrains", "annotations")]
    json_jar = jar("org.json", "json")
    model = (ROOT / "app/src/main/java/com/hamhuo/tplanner/data/ScheduleItemStore.kt").read_text(encoding="utf-8")
    classes = model[model.index("data class CheckItem"):model.index("/** Room-backed façade")]
    date_filter = model[model.index("fun List<ScheduleItem>.forDate"):]
    with tempfile.TemporaryDirectory(prefix="tplanner-recurrence-") as directory:
        temp = Path(directory)
        domain = temp / "Domain.kt"
        domain.write_text("package com.hamhuo.tplanner\nimport java.time.Instant\nimport java.time.LocalDate\n" + classes + date_filter, encoding="utf-8")
        harness = temp / "RecurrenceRegression.kt"
        harness.write_text(HARNESS, encoding="utf-8")
        output = temp / "checks.jar"
        sources = [domain, harness,
                   ROOT / "app/src/main/java/com/hamhuo/tplanner/data/AppTime.kt",
                   ROOT / "app/src/main/java/com/hamhuo/tplanner/data/TaskView.kt",
                   ROOT / "app/src/main/java/com/hamhuo/tplanner/actions/RecurringTaskFactory.kt",
                   ROOT / "app/src/main/java/com/hamhuo/tplanner/actions/RecurringTaskSeries.kt"]
        subprocess.run([java, "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", os.pathsep.join([stdlib, json_jar]),
                        "-d", str(output), *map(str, sources)], check=True, cwd=ROOT)
        subprocess.run([java, "-cp", os.pathsep.join([str(output), stdlib, json_jar]),
                        "com.hamhuo.tplanner.RecurrenceRegressionKt"], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
