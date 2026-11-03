package com.ekeditor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Synthetic fixtures, so CI never needs to contain a player's real saved games. */
class EkBackupTest {
    private fun save(gold: Int): ByteArray = Base64.getEncoder().encode(
        (
            "format: test\n" +
            "version: 1.3.1219\n" +
            "gamedata: {\n" +
            "\tplayer: {\n" +
            "\t\tname: Test Warrior\n" +
            "\t\tgold: $gold\n" +
            "\t\tcharacter: {\n" +
            "\t\t\t\tcharacterClass: warrior\n" +
            "\t\t\t\textraTraitPoints: 2\n" +
            "\t\t\t\tbonusPoints: 3\n" +
            "\t\t}\n" +
            "\t}\n" +
            "\tbackpack: {\n" +
            "\t\titemList: [ 0, 0, 0, 0 ]\n" +
            "\t\tstackUnits: [ 0, 0, 0, 0 ]\n" +
            "\t}\n" +
            "}\n"
        ).toByteArray(Charsets.UTF_8)
    )

    private fun fixture(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            mapOf(
                "saves/global.sav" to "Synthetic global data".toByteArray(),
                "saves/0/game.sav" to save(11),
                "saves/0/auto.sav" to save(22),
                "saves/0/game.sav.nfo" to "Synthetic metadata".toByteArray()
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun entries(zipBytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    @Test fun changesOneSaveWithoutTouchingOtherEntries() {
        val original = fixture()
        val info = EkBackup.inspect(original)
        assertEquals(2, info.size)
        val edited = EkBackup.apply(original, EkBackup.EditPlan(
            targetPaths = setOf("saves/0/game.sav"), gold = 9999999,
            traitPoints = 10, skillPoints = 20,
            items = listOf(EkBackup.ItemAdd(554, 1), EkBackup.ItemAdd(5070, 999))
        ))
        val after = EkBackup.inspect(edited)
        assertEquals(9999999L, after.first { it.path.endsWith("game.sav") }.gold)
        assertEquals(10, after.first { it.path.endsWith("game.sav") }.traitPoints)
        assertEquals(20, after.first { it.path.endsWith("game.sav") }.skillPoints)
        assertEquals(22L, after.first { it.path.endsWith("auto.sav") }.gold)
        assertEquals(1, after.first { it.path.endsWith("game.sav") }.inventory[554])
        assertEquals(999, after.first { it.path.endsWith("game.sav") }.inventory[5070])
        val orig = entries(original)
        val out = entries(edited)
        assertEquals(orig.keys, out.keys)
        orig.filterKeys { it != "saves/0/game.sav" }.forEach { (name, bytes) ->
            assertTrue("Unexpected modification of $name", bytes.contentEquals(out.getValue(name)))
        }
    }

    @Test fun modifiesAllSavesAndPreservesSidecars() {
        val original = fixture()
        val edited = EkBackup.apply(original, EkBackup.EditPlan(
            targetPaths = EkBackup.inspect(original).map { it.path }.toSet(), gold = 12345
        ))
        assertTrue(EkBackup.inspect(edited).all { it.gold == 12345L })
        assertTrue(entries(original).getValue("saves/0/game.sav.nfo")
            .contentEquals(entries(edited).getValue("saves/0/game.sav.nfo")))
    }

    @Test fun consecutiveButtonsPreservePreviousChangesAndUpdatePreview() {
        val original = fixture()
        val selected = setOf("saves/0/game.sav")
        val afterGold = EkBackup.apply(original, EkBackup.EditPlan(selected, gold = 500))
        val afterTraits = EkBackup.apply(afterGold, EkBackup.EditPlan(selected, traitPoints = 99))
        val afterPotion = EkBackup.apply(afterTraits, EkBackup.EditPlan(selected, items = listOf(EkBackup.ItemAdd(5070, 777))))
        val selectedInfo = EkBackup.inspect(afterPotion).single { it.path in selected }
        assertEquals(500L, selectedInfo.gold)
        assertEquals(99, selectedInfo.traitPoints)
        assertEquals(777, selectedInfo.inventory[5070])
        assertEquals(11L, EkBackup.inspect(original).single { it.path in selected }.gold)
        assertEquals(22L, EkBackup.inspect(afterPotion).single { it.path.endsWith("auto.sav") }.gold)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesInvalidItemCounts() {
        EkBackup.apply(fixture(), EkBackup.EditPlan(
            targetPaths = setOf("saves/0/game.sav"),
            items = listOf(EkBackup.ItemAdd(5070, 1000))
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesNonZipFiles() { EkBackup.inspect("not a backup".toByteArray()) }
    @Test fun repeatedPointsAddAndResetAcrossAllSaves() {
        val initial = fixture()
        val paths = EkBackup.inspect(initial).map { it.path }.toSet()
        val first = EkBackup.apply(initial, EkBackup.EditPlan(paths, traitDelta = 5, skillDelta = 2))
        val second = EkBackup.apply(first, EkBackup.EditPlan(paths, traitDelta = 5, skillDelta = 2))
        assertTrue(EkBackup.inspect(second).all { it.traitPoints == 12 && it.skillPoints == 7 })
        val reset = EkBackup.apply(second, EkBackup.EditPlan(paths, resetTraits = true))
        assertTrue(EkBackup.inspect(reset).all { it.traitPoints == 0 && it.skillPoints == 7 })
    }

    private fun companionSave(gold: Int): ByteArray = Base64.getEncoder().encode(
        ("format: test\n" +
         "version: 1.3.1219\n" +
         "gamedata: {\n\tplayer: {\n\t\tname: Warrior\n\t\tgold: $gold\n" +
         "\t\tcharacter: {\n\t\t\t\tcharacterClass: warrior\n\t\t\t\textraTraitPoints: 2\n\t\t\t\tbonusPoints: 3\n\t\t}\n\t}\n" +
         "\tbackpack: {\n\t\titemList: [ 0, 0, 0, 0 ]\n\t\tstackUnits: [ 0, 0, 0, 0 ]\n\t}\n" +
         "}\n" +
         "\tparty: {\n\t\tactiveCompanion: grisenda\n\t\tcompanions: [\n\t\t\t{\n\t\t\t\tname: Grissenda\n\t\t\t\tspawn_id: grisenda\n" +
         "\t\t\t\tsheet: {\n\t\t\t\t\ttraits: {\n\t\t\t\t\t\textraTraitPoints: 4\n\t\t\t\t\t}\n\t\t\t\t\tbonusPoints: 2\n\t\t\t\t}\n\t\t\t}\n\t\t]\n\t}\n"
        ).toByteArray(Charsets.UTF_8)
    )

    @Test fun companionDetectionAndRepeatedPoints() {
        val initial = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("saves/0/game.sav"))
                zip.write(companionSave(100))
                zip.closeEntry()
            }
        }.toByteArray()
        val target = setOf("saves/0/game.sav")
        assertEquals("Grissenda", EkBackup.inspect(initial).single().companion?.name)
        val once = EkBackup.apply(initial, EkBackup.EditPlan(target, companionTraitDelta = 7, companionSkillDelta = 5))
        val twice = EkBackup.apply(once, EkBackup.EditPlan(target, companionTraitDelta = 7, companionSkillDelta = 5))
        assertEquals(18, EkBackup.inspect(twice).single().companion?.traitPoints)
        assertEquals(12, EkBackup.inspect(twice).single().companion?.skillPoints)
        val reset = EkBackup.apply(twice, EkBackup.EditPlan(target, resetCompanionTraits = true))
        assertEquals(0, EkBackup.inspect(reset).single().companion?.traitPoints)
        assertEquals(2, EkBackup.inspect(reset).single().traitPoints)
    }

    @Test fun noCompanionDoesNotCreateOne() {
        val paths = EkBackup.inspect(fixture()).map { it.path }.toSet()
        assertTrue(EkBackup.inspect(fixture()).all { it.companion == null })
        val changed = EkBackup.apply(fixture(), EkBackup.EditPlan(paths, companionSkillDelta = 1))
        assertTrue(EkBackup.inspect(changed).all { it.companion == null })
    }

}
