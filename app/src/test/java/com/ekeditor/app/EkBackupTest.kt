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
            "\t\t\t\tspentPoints: [ 6, 0, 0, 0, 0, 0 ]\n" +
            "\t\t\t\tcached_level: 1\n" +
            "\t\t\t\tbonusPoints: 3\n" +
            "\t\t\t\tskillSet: {\n" +
            "\t\t\t\t\tcharacterSkills: [\n" +
            "\t\t\t\t\t\t{\n\t\t\t\t\t\t\tlevel: 1\n\t\t\t\t\t\t\tskillID: dungeoneering\n\t\t\t\t\t\t}\n" +
            "\t\t\t\t\t]\n" +
            "\t\t\t\t}\n" +
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
        val reset = EkBackup.apply(second, EkBackup.EditPlan(paths, resetTraits = true, resetSkills = true))
        assertTrue(EkBackup.inspect(reset).all { it.traitPoints == 2 && it.freeTraitPoints == 0 && it.skillPoints == 0 })
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
        // The old fixture has no companion level/spending data:
        // only bonus points can be tested here. Reset logic is tested below
        // using a complete fixture to avoid inventing missing available points.
        assertEquals(null, EkBackup.inspect(twice).single().companion?.freeTraitPoints)
        assertEquals(null, EkBackup.inspect(twice).single().companion?.freeSkillPoints)
    }

    @Test fun changesAttributesInAllFourSaveEntriesAndKeepsIndependentValues() {
        val original = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                listOf("auto.sav", "auto2.sav", "game.sav", "game2.sav").forEachIndexed { index, filename ->
                    zip.putNextEntry(ZipEntry("saves/0/$filename"))
                    zip.write(save(40 + index))
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("saves/global.sav"))
                zip.write("global stays intact".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val before = EkBackup.inspect(original)
        assertEquals(4, before.size)
        val target = before.map { it.path }.toSet()
        val first = EkBackup.apply(original, EkBackup.EditPlan(target, traitDelta = 17))
        val second = EkBackup.apply(first, EkBackup.EditPlan(target, traitDelta = 3))
        val after = EkBackup.inspect(second)
        assertEquals(target, after.map { it.path }.toSet())
        assertTrue(after.all { it.traitPoints == 22 })
        assertTrue(after.all { it.skillPoints == 3 })
        assertEquals(before.map { it.gold }, after.map { it.gold })
        assertTrue(entries(original).getValue("saves/global.sav")
            .contentEquals(entries(second).getValue("saves/global.sav")))
        for (info in after) {
            val raw = String(Base64.getDecoder().decode(entries(second).getValue(info.path)))
            assertTrue(raw.contains("\t\t\t\textraTraitPoints: 22"))
        }
    }

    @Test fun inventorySlotsKeepRealOrderAfterApplyingToAllSaves() {
        val original = fixture()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        val updated = EkBackup.apply(original, EkBackup.EditPlan(all,
            items = listOf(EkBackup.ItemAdd(5070, 8))))
        for (save in EkBackup.inspect(updated)) {
            assertEquals(4, save.inventorySlots.size)
            assertEquals(5070, save.inventorySlots[0].id)
            assertEquals(8, save.inventorySlots[0].count)
            assertEquals(3, save.inventorySlots.count { it.id == 0 })
        }
    }

    @Test fun noCompanionDoesNotCreateOne() {
        val paths = EkBackup.inspect(fixture()).map { it.path }.toSet()
        assertTrue(EkBackup.inspect(fixture()).all { it.companion == null })
        val changed = EkBackup.apply(fixture(), EkBackup.EditPlan(paths, companionSkillDelta = 1))
        assertTrue(EkBackup.inspect(changed).all { it.companion == null })
    }


    @Test fun blankAndInvalidNumberInputsReturnReadableErrors() {
        for (raw in listOf("", "   ", "abc", "999999999999999999999999", "-2")) {
            val failure = runCatching { EkBackup.parseNumberInput(raw, "Gold", 999_999_999) }.exceptionOrNull()
            assertTrue("Deveria rejeitar '$raw' sem travar", failure is IllegalArgumentException)
            assertTrue(failure?.message?.isNotBlank() == true)
        }
        assertTrue(runCatching { EkBackup.parseNumberInput("", "Habilidades", 999, 1) }.isFailure)
        assertTrue(runCatching { EkBackup.parseNumberInput("", "Atributos do companheiro", 999, 1) }.isFailure)
        assertEquals(0L, EkBackup.parseNumberInput("0", "Ouro", 999_999_999))
        assertEquals(10L, EkBackup.parseNumberInput(" 10 ", "Atributos", 999, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSimultaneousSkillResetAndAddition() {
        val paths = EkBackup.inspect(fixture()).map { it.path }.toSet()
        EkBackup.apply(fixture(), EkBackup.EditPlan(paths, skillDelta = 5, resetSkills = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCompanionSkillResetAndAdditionTogether() {
        val paths = EkBackup.inspect(fixture()).map { it.path }.toSet()
        EkBackup.apply(fixture(), EkBackup.EditPlan(paths, companionSkillDelta = 5, resetCompanionSkills = true))
    }

    @Test fun resetCompanionSkillsDoesNotCreateMissingCompanion() {
        val original = fixture()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        val changed = EkBackup.apply(original, EkBackup.EditPlan(all, resetCompanionSkills = true))
        assertTrue(EkBackup.inspect(changed).all { it.companion == null })
    }

    /** Realistic data shape for a level-16 cleric: 35 earned, 10 bonus, 45 spent => 0 free. */
    private fun level16Save(extra: Int = 10, spent: String = "10, 10, 3, 6, 6, 10"): ByteArray {
        val content = (
            "format: test\n" +
            "version: 1.3.1219\n" +
            "gamedata: {\n" +
            "\tplayer: {\n" +
            "\t\tname: Test Cleric\n" +
            "\t\tgold: 25\n" +
            "\t\tsheet: {\n" +
            "\t\t\ttraits: {\n" +
            "\t\t\t\tvalue: [ 4, 4, 2, 3, 3, 4 ]\n" +
            "\t\t\t\textraTraitPoints: $extra\n" +
            "\t\t\t\tspentPoints: [ $spent ]\n" +
            "\t\t\t}\n" +
            "\t\t\tstats: {\n" +
            "\t\t\t\tcharacterClass: CLERIC\n" +
            "\t\t\t\tcached_level: 16\n" +
            "\t\t\t}\n" +
            "\t\t\tskillSet: {\n" +
            "\t\t\t\tbonusPoints: 8\n" +
            "\t\t\t}\n" +
            "\t\t}\n" +
            "\t}\n" +
            "\tbackpack: {\n" +
            "\t\titemList: [ 0, 0, 0, 0 ]\n" +
            "\t\tstackUnits: [ 0, 0, 0, 0 ]\n" +
            "\t}\n" +
            "}\n"
        )
        return Base64.getEncoder().encode(content.toByteArray(Charsets.UTF_8))
    }

    private fun fourLevel16Saves(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for (filename in listOf("auto.sav", "auto2.sav", "game.sav", "game2.sav")) {
                zip.putNextEntry(ZipEntry("saves/0/$filename"))
                zip.write(level16Save())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("saves/global.sav"))
            zip.write("Other archive content".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    @Test fun level16ReadsAvailablePointsNotTheExtraBonus() {
        val all = EkBackup.inspect(fourLevel16Saves())
        assertEquals(4, all.size)
        assertTrue(all.all { it.characterLevel == 16 && it.spentTraitPoints == 45 })
        assertTrue(all.all { it.traitPoints == 10 && it.freeTraitPoints == 0 })
    }

    @Test fun adding20And10IncreasesRealBalanceAndResetsAllFourSaves() {
        val original = fourLevel16Saves()
        val target = EkBackup.inspect(original).map { it.path }.toSet()
        val edited = EkBackup.apply(original, EkBackup.EditPlan(target, traitDelta = 20))
        assertTrue(EkBackup.inspect(edited).all { it.freeTraitPoints == 20 && it.traitPoints == 30 })
        val twice = EkBackup.apply(edited, EkBackup.EditPlan(target, traitDelta = 10))
        assertTrue(EkBackup.inspect(twice).all { it.freeTraitPoints == 30 && it.traitPoints == 40 })
        val reset = EkBackup.apply(twice, EkBackup.EditPlan(target, resetTraits = true))
        assertTrue(EkBackup.inspect(reset).all { it.freeTraitPoints == 0 && it.traitPoints == 10 })
        assertTrue(EkBackup.inspect(reset).all { it.spentTraitPoints == 45 })
        assertTrue(entries(original).getValue("saves/global.sav")
            .contentEquals(entries(reset).getValue("saves/global.sav")))
        assertTrue(EkBackup.inspect(original).all { it.freeTraitPoints == 0 })
    }

    @Test fun existingUnusedPointsAreRecognizedBeforeAdding20() {
        val input = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("saves/0/auto.sav"))
                zip.write(level16Save(extra = 15))
                zip.closeEntry()
            }
        }.toByteArray()
        val before = EkBackup.inspect(input).single()
        assertEquals(5, before.freeTraitPoints)
        val changed = EkBackup.apply(input, EkBackup.EditPlan(setOf(before.path), traitDelta = 20))
        val after = EkBackup.inspect(changed).single()
        assertEquals(25, after.freeTraitPoints)
        assertEquals(35, after.traitPoints)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsafeResetFailsWithoutChangingAlreadyAllocatedTraits() {
        val input = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("saves/0/game.sav"))
                zip.write(level16Save(extra = 0, spent = "0, 0, 0, 0, 0, 0"))
                zip.closeEntry()
            }
        }.toByteArray()
        EkBackup.apply(input, EkBackup.EditPlan(setOf("saves/0/game.sav"), resetTraits = true))
    }

    @Test fun missingLevelAndSpentIsShownAsUnknownInsteadOfMisleadingBonus() {
        val input = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("saves/0/game.sav"))
                zip.write(companionSave(100))
                zip.closeEntry()
            }
        }.toByteArray()
        val result = EkBackup.inspect(input).single()
        assertEquals(null, result.freeTraitPoints)
        assertEquals(2, result.traitPoints)
    }


    /** Ten learned skills with a total cost of 39 for a level-16 character. */
    private fun clericWithLearnedSkills(): ByteArray {
        val original = String(Base64.getDecoder().decode(level16Save()), Charsets.UTF_8)
        val list = listOf(
            "heal_wounds" to 3, "crusader" to 3, "dungeoneering" to 2,
            "gossip" to 1, "nivarias_barrier" to 2, "intervention" to 2,
            "extra_recovery" to 2, "arbenos_might" to 3, "sacred_fire" to 3,
            "holy_shield" to 2
        ).joinToString("\n") { (id, rank) ->
            "\t\t\t\t\t{\n\t\t\t\t\t\tlevel: $rank\n\t\t\t\t\t\tskillID: $id\n\t\t\t\t\t}"
        }
        val modified = original.replace("\t\t\t\tbonusPoints: 8",
            "\t\t\t\tcharacterSkills: [\n$list\n\t\t\t\t]\n\t\t\t\tbonusPoints: 8")
        return Base64.getEncoder().encode(modified.toByteArray(Charsets.UTF_8))
    }

    private fun companionWithProgress(): ByteArray {
        val hero = String(Base64.getDecoder().decode(save(100)), Charsets.UTF_8)
        val abilities = listOf(
            "shield_expert" to 2, "dungeoneering" to 1, "cleave" to 2,
            "fury" to 1, "whirlwind" to 1, "two_handed_expert" to 3, "bash" to 2
        ).joinToString("\n") { (id, rank) ->
            "\t\t\t\t\t\t\t{\n\t\t\t\t\t\t\t\tlevel: $rank\n\t\t\t\t\t\t\t\tskillID: $id\n\t\t\t\t\t\t\t}"
        }
        val party = "\tparty: {\n" +
            "\t\tactiveCompanion: grissenda\n" +
            "\t\tcompanions: [\n" +
            "\t\t\t{\n\t\t\t\tspawn_id: grissenda\n\t\t\t\tname: Grissenda\n" +
            "\t\t\t\tsheet: {\n" +
            "\t\t\t\t\ttraits: {\n" +
            "\t\t\t\t\t\textraTraitPoints: 10\n" +
            "\t\t\t\t\t\tspentPoints: [ 10, 10, 3, 0, 0, 1 ]\n" +
            "\t\t\t\t\t}\n" +
            "\t\t\t\t\tstats: {\n\t\t\t\t\t\tcached_level: 14\n\t\t\t\t\t}\n" +
            "\t\t\t\t\tskillSet: {\n" +
            "\t\t\t\t\t\tcharacterSkills: [\n$abilities\n\t\t\t\t\t\t]\n" +
            "\t\t\t\t\t\tbonusPoints: 2\n" +
            "\t\t\t\t\t}\n" +
            "\t\t\t\t}\n" +
            "\t\t\t}\n" +
            "\t\t]\n" +
            "\t}\n"
        return Base64.getEncoder().encode((hero + party).toByteArray(Charsets.UTF_8))
    }

    private fun zipSave(entry: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("saves/0/auto.sav"))
            zip.write(entry)
            zip.closeEntry()
        }
    }.toByteArray()

    @Test fun level16SkillBalanceReadsRealFreePointsAndResetSafely() {
        val data = zipSave(clericWithLearnedSkills())
        val paths = setOf("saves/0/auto.sav")
        assertEquals(0, EkBackup.inspect(data).single().freeSkillPoints)
        assertEquals(39, EkBackup.inspect(data).single().spentSkillPoints)
        val added = EkBackup.apply(data, EkBackup.EditPlan(paths, skillDelta = 20))
        assertEquals(20, EkBackup.inspect(added).single().freeSkillPoints)
        val addedAgain = EkBackup.apply(added, EkBackup.EditPlan(paths, skillDelta = 10))
        assertEquals(30, EkBackup.inspect(addedAgain).single().freeSkillPoints)
        val reset = EkBackup.apply(addedAgain, EkBackup.EditPlan(paths, resetSkills = true))
        assertEquals(0, EkBackup.inspect(reset).single().freeSkillPoints)
        assertEquals(39, EkBackup.inspect(reset).single().spentSkillPoints)
    }

    @Test fun companionFreeAttributeAndSkillPointsUseIndependentLevelRules() {
        val data = zipSave(companionWithProgress())
        val paths = setOf("saves/0/auto.sav")
        val before = EkBackup.inspect(data).single().companion!!
        assertEquals(0, before.freeTraitPoints)
        assertEquals(0, before.freeSkillPoints)
        assertEquals(24, before.spentTraitPoints)
        assertEquals(16, before.spentSkillPoints)
        val changed = EkBackup.apply(data, EkBackup.EditPlan(paths,
            companionTraitDelta = 20, companionSkillDelta = 5))
        val after = EkBackup.inspect(changed).single().companion!!
        assertEquals(20, after.freeTraitPoints)
        assertEquals(5, after.freeSkillPoints)
        val reset = EkBackup.apply(changed, EkBackup.EditPlan(paths,
            resetCompanionTraits = true, resetCompanionSkills = true))
        val zero = EkBackup.inspect(reset).single().companion!!
        assertEquals(0, zero.freeTraitPoints)
        assertEquals(0, zero.freeSkillPoints)
        assertEquals(24, zero.spentTraitPoints)
        assertEquals(16, zero.spentSkillPoints)
    }

    private fun archiveWithShiftedItem(missingGameItem: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for (name in listOf("auto.sav", "game.sav")) {
                    val original = String(Base64.getDecoder().decode(save(11)), Charsets.UTF_8)
                    val ids = if (name == "auto.sav") "5070, 0, 0, 0"
                              else if (missingGameItem) "554, 0, 0, 0"
                              else "554, 5070, 0, 0"
                    val amounts = if (name == "auto.sav") "7, 0, 0, 0"
                                  else if (missingGameItem) "1, 0, 0, 0"
                                  else "1, 7, 0, 0"
                    val adjusted = original.replace("itemList: [ 0, 0, 0, 0 ]", "itemList: [ $ids ]")
                        .replace("stackUnits: [ 0, 0, 0, 0 ]", "stackUnits: [ $amounts ]")
                    zip.putNextEntry(ZipEntry("saves/0/$name"))
                    zip.write(Base64.getEncoder().encode(adjusted.toByteArray(Charsets.UTF_8)))
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("saves/global.sav"))
                zip.write("metadata untouched".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

    @Test fun inventoryGridChangesQuantityAndDeletesSameIdInDifferentSlots() {
        val original = archiveWithShiftedItem()
        val paths = EkBackup.inspect(original).map { it.path }.toSet()
        val changed = EkBackup.apply(original, EkBackup.EditPlan(paths,
            slotEdits = listOf(EkBackup.SlotEdit(0, 5070, 7, 12))))
        assertTrue(EkBackup.inspect(changed).all { it.inventory[5070] == 12 })
        assertEquals(1, EkBackup.inspect(changed).single { it.path.endsWith("game.sav") }.inventory[554])
        val deleted = EkBackup.apply(changed, EkBackup.EditPlan(paths,
            slotEdits = listOf(EkBackup.SlotEdit(0, 5070, 12, 0))))
        assertTrue(EkBackup.inspect(deleted).all { 5070 !in it.inventory })
        assertEquals(1, EkBackup.inspect(deleted).single { it.path.endsWith("game.sav") }.inventory[554])
        assertTrue(entries(original).getValue("saves/global.sav")
            .contentEquals(entries(deleted).getValue("saves/global.sav")))
    }

    @Test fun inventoryGridDeletionSkipsMissingItemInAnotherSaveWithoutRemovingOthers() {
        val data = archiveWithShiftedItem(missingGameItem = true)
        val paths = EkBackup.inspect(data).map { it.path }.toSet()
        val edited = EkBackup.apply(data, EkBackup.EditPlan(paths,
            slotEdits = listOf(EkBackup.SlotEdit(0, 5070, 7, 0))))
        val after = EkBackup.inspect(edited)
        assertTrue(after.all { 5070 !in it.inventory })
        assertEquals(1, after.single { it.path.endsWith("/game.sav") }.inventory[554])
    }


    @Test fun combinedPlayerCompanionEditsReachAllFourSaves() {
        val original = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for (name in listOf("auto.sav", "auto2.sav", "game.sav", "game2.sav")) {
                    zip.putNextEntry(ZipEntry("saves/0/$name"))
                    zip.write(companionWithProgress())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val paths = EkBackup.inspect(original).map { it.path }.toSet()
        assertEquals(4, paths.size)
        val modified = EkBackup.apply(original, EkBackup.EditPlan(paths,
            traitDelta = 20, skillDelta = 20, companionTraitDelta = 5, companionSkillDelta = 10))
        val infos = EkBackup.inspect(modified)
        assertEquals(paths, infos.map { it.path }.toSet())
        assertTrue(infos.all { it.freeTraitPoints == 20 && it.freeSkillPoints == 23 })
        assertTrue(infos.all { it.companion?.freeTraitPoints == 5 && it.companion?.freeSkillPoints == 10 })
        val reset = EkBackup.apply(modified, EkBackup.EditPlan(paths,
            resetTraits = true, resetSkills = true,
            resetCompanionTraits = true, resetCompanionSkills = true))
        val zeros = EkBackup.inspect(reset)
        assertTrue(zeros.all { it.freeTraitPoints == 0 && it.freeSkillPoints == 0 })
        assertTrue(zeros.all { it.companion?.freeTraitPoints == 0 && it.companion?.freeSkillPoints == 0 })
    }


    /** Regression test matching four real saves: level 14, 10 bonuses, 24 points spent. */
    @Test fun grissendaLevel14ResetThenPlusTenReallyGivesTenNotSeven() {
        val initial = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for (name in listOf("auto.sav", "auto2.sav", "game.sav", "game2.sav")) {
                    zip.putNextEntry(ZipEntry("saves/0/$name"))
                    zip.write(companionWithProgress())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val paths = EkBackup.inspect(initial).map { it.path }.toSet()
        assertTrue(EkBackup.inspect(initial).all { it.companion?.level == 14 })
        assertTrue(EkBackup.inspect(initial).all { it.companion?.freeTraitPoints == 0 })
        val first = EkBackup.apply(initial, EkBackup.EditPlan(paths, companionTraitDelta = 10))
        assertTrue(EkBackup.inspect(first).all { it.companion?.freeTraitPoints == 10 })
        val reset = EkBackup.apply(first, EkBackup.EditPlan(paths, resetCompanionTraits = true))
        assertTrue(EkBackup.inspect(reset).all { it.companion?.freeTraitPoints == 0 })
        val addedAgain = EkBackup.apply(reset, EkBackup.EditPlan(paths, companionTraitDelta = 10))
        assertTrue(EkBackup.inspect(addedAgain).all { it.companion?.freeTraitPoints == 10 })
        assertTrue(EkBackup.inspect(addedAgain).all { it.companion?.spentTraitPoints == 24 })
    }

    @Test fun resetOfOldV05CompanionSaveWithNegativeThreeFreePointsRecoversToZero() {
        val oldState = String(Base64.getDecoder().decode(companionWithProgress()), Charsets.UTF_8)
            .replace("extraTraitPoints: 10", "extraTraitPoints: 7")
        val source = zipSave(Base64.getEncoder().encode(oldState.toByteArray(Charsets.UTF_8)))
        val target = setOf("saves/0/auto.sav")
        assertEquals(-3, EkBackup.inspect(source).single().companion?.freeTraitPoints)
        val corrected = EkBackup.apply(source, EkBackup.EditPlan(target, resetCompanionTraits = true))
        assertEquals(0, EkBackup.inspect(corrected).single().companion?.freeTraitPoints)
        val withTen = EkBackup.apply(corrected, EkBackup.EditPlan(target, companionTraitDelta = 10))
        assertEquals(10, EkBackup.inspect(withTen).single().companion?.freeTraitPoints)
    }

    @Test fun clickingEmptyGridSlotAddsItemToSameCellOnAllSaves() {
        val original = archiveWithShiftedItem()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        val modified = EkBackup.apply(original, EkBackup.EditPlan(all,
            slotInserts = listOf(EkBackup.SlotInsert(2, 750, 1))))
        val after = EkBackup.inspect(modified)
        assertTrue(after.all { it.inventorySlots[2].id == 750 && it.inventorySlots[2].count == 1 })
        assertEquals(1, after.single { it.path.endsWith("game.sav") }.inventory[554])
        assertTrue(entries(original).getValue("saves/global.sav")
            .contentEquals(entries(modified).getValue("saves/global.sav")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun insertingIntoCellOccupiedInOneSaveIsBlockedForEveryone() {
        val original = archiveWithShiftedItem()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        EkBackup.apply(original, EkBackup.EditPlan(all,
            slotInserts = listOf(EkBackup.SlotInsert(1, 750, 3))))
    }

    @Test fun tappingOccupiedStackCanIncreaseQuantityOnAllSaves() {
        val original = archiveWithShiftedItem()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        val modified = EkBackup.apply(original, EkBackup.EditPlan(all,
            slotEdits = listOf(EkBackup.SlotEdit(0, 5070, 7, 5, addQuantity = true))))
        assertTrue(EkBackup.inspect(modified).all { it.inventory[5070] == 12 })
        val twice = EkBackup.apply(modified, EkBackup.EditPlan(all,
            slotEdits = listOf(EkBackup.SlotEdit(0, 5070, 12, 10, addQuantity = true))))
        assertTrue(EkBackup.inspect(twice).all { it.inventory[5070] == 22 })
        assertEquals(1, EkBackup.inspect(twice).single { it.path.endsWith("game.sav") }.inventory[554])
    }

    @Test(expected = IllegalArgumentException::class)
    fun raisingStackBeyond999IsBlockedBeforeAnyOutputIsReturned() {
        val original = archiveWithShiftedItem()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        EkBackup.apply(original, EkBackup.EditPlan(all,
            slotEdits = listOf(EkBackup.SlotEdit(0, 5070, 7, 999, addQuantity = true))))
    }


    private fun mixedFourSaveInventories(): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                val entries = listOf(
                    Triple("auto.sav", "5070, 0, 0, 0", "7, 0, 0, 0"),
                    Triple("auto2.sav", "554, 726, 3001, 4001", "1, 1, 1, 1"),
                    Triple("game.sav", "554, 5070, 0, 0", "1, 7, 0, 0"),
                    Triple("game2.sav", "554, 726, 0, 0", "1, 1, 0, 0")
                )
                for ((name, itemIds, counts) in entries) {
                    val plain = String(Base64.getDecoder().decode(save(200)), Charsets.UTF_8)
                        .replace("itemList: [ 0, 0, 0, 0 ]", "itemList: [ $itemIds ]")
                        .replace("stackUnits: [ 0, 0, 0, 0 ]", "stackUnits: [ $counts ]")
                    zip.putNextEntry(ZipEntry("saves/0/$name"))
                    zip.write(Base64.getEncoder().encode(plain.toByteArray(Charsets.UTF_8)))
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("saves/global.sav"))
                zip.write("metadata unchanged".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

    @Test fun regressionOneSaveFullWhilePreviewHasVacanciesIsDetected() {
        val backup = mixedFourSaveInventories()
        val all = EkBackup.inspect(backup)
        assertEquals(4, all.size)
        assertTrue(all.first { it.path.endsWith("/auto.sav") }.freeSlots > 0)
        assertEquals(0, all.first { it.path.endsWith("/auto2.sav") }.freeSlots)
        val result = runCatching {
            EkBackup.apply(backup, EkBackup.EditPlan(all.map { it.path }.toSet(),
                items = listOf(EkBackup.ItemAdd(750, 1))))
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("slots") == true ||
            result.exceptionOrNull()?.message?.contains("Inventory full") == true)
    }

    @Test fun fullInventoryRequiresExplicitReplacementAndPreservesOtherSlots() {
        val backup = mixedFourSaveInventories()
        val original = EkBackup.inspect(backup)
        val target = original.map { it.path }.toSet()
        val expectedByPath = original.associate { it.path to it.inventorySlots[0] }
        val updated = EkBackup.apply(backup, EkBackup.EditPlan(target,
            slotReplacements = listOf(EkBackup.SlotReplace(
                0, 5070, 15, expectedByPath))))
        val inspected = EkBackup.inspect(updated)
        assertEquals(4, inspected.size)
        for (info in inspected) {
            val before = original.single { it.path == info.path }
            assertEquals(EkBackup.InventorySlot(5070, 15), info.inventorySlots[0])
            assertEquals(before.inventorySlots.drop(1), info.inventorySlots.drop(1))
        }
        assertTrue(entries(backup).getValue("saves/global.sav")
            .contentEquals(entries(updated).getValue("saves/global.sav")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun replacementRefusesStaleSlotDataWithoutChangingBackup() {
        val backup = mixedFourSaveInventories()
        val originals = EkBackup.inspect(backup)
        val expected = originals.associate { it.path to it.inventorySlots[0] }.toMutableMap()
        expected["saves/0/auto2.sav"] = EkBackup.InventorySlot(999, 999)
        EkBackup.apply(backup, EkBackup.EditPlan(originals.map { it.path }.toSet(),
            slotReplacements = listOf(EkBackup.SlotReplace(0, 750, 4, expected))))
    }

    @Test fun gridDeleteMissingIdIsSafeForAllSavesEvenIfAnotherOneFull() {
        val backup = mixedFourSaveInventories()
        val paths = EkBackup.inspect(backup).map { it.path }.toSet()
        val edited = EkBackup.apply(backup, EkBackup.EditPlan(paths,
            slotEdits = listOf(EkBackup.SlotEdit(0, 5070, 7, 0))))
        val before = EkBackup.inspect(backup).associateBy { it.path }
        val after = EkBackup.inspect(edited)
        assertEquals(4, after.size)
        assertTrue(after.all { 5070 !in it.inventory })
        for (save in after) {
            val old = before[save.path]!!
            for ((id, count) in old.inventory) if (id != 5070) {
                assertEquals(count, save.inventory[id])
            }
        }
    }

    @Test fun gridSetQuantityAddsMissingIdToOtherSaveOnlyWhenRoomExists() {
        val input = archiveWithShiftedItem()
        val paths = EkBackup.inspect(input).map { it.path }.toSet()
        val edited = EkBackup.apply(input, EkBackup.EditPlan(paths,
            slotEdits = listOf(EkBackup.SlotEdit(0, 5070, 7, 12))))
        assertTrue(EkBackup.inspect(edited).all { it.inventory[5070] == 12 })
    }

    @Test fun gridSetQuantityWhenIdMissingInOneFullSaveShowsReason() {
        val input = mixedFourSaveInventories()
        val paths = EkBackup.inspect(input).map { it.path }.toSet()
        val failure = runCatching {
            EkBackup.apply(input, EkBackup.EditPlan(paths,
                slotEdits = listOf(EkBackup.SlotEdit(0, 5070, 7, 12))))
        }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message?.contains("auto2.sav") == true)
    }


    @Test fun tomeAndEquipmentAreUnitaryWhileMedipackIsStackable() {
        for (id in listOf(5013, 5035, 10001, 554, 750, 450)) {
            assertTrue("Item $id must allow only one unit per slot", !ItemRules.isStackable(id))
            assertEquals(1, ItemRules.maxPerSlot(id))
            assertTrue(runCatching { ItemRules.ensureSlotQuantity(id, 2) }.isFailure)
        }
        assertTrue(ItemRules.isStackable(5070))
        assertTrue(ItemRules.isStackable(5000))
        assertTrue(ItemRules.isStackable(6001))
        ItemRules.ensureSlotQuantity(5070, 999)
    }

    @Test fun addingThreeAncientTolassianTomesUsesThreeSeparateSlotsInEverySave() {
        val original = fixture()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        val edited = EkBackup.apply(original, EkBackup.EditPlan(
            all, items = listOf(EkBackup.ItemAdd(5013, 3))))
        for (save in EkBackup.inspect(edited)) {
            assertEquals(3, save.inventory[5013])
            assertEquals(3, save.inventorySlots.count { it.id == 5013 && it.count == 1 })
            assertEquals(1, save.inventorySlots.count { it.id == 0 })
        }
    }

    @Test fun tappingTomeAddsAnotherCopyInSeparateSlotAndCanDeleteOneCopy() {
        val original = fixture()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        val withOne = EkBackup.apply(original, EkBackup.EditPlan(
            all, slotInserts = listOf(EkBackup.SlotInsert(0, 5013, 1))))
        val added = EkBackup.apply(withOne, EkBackup.EditPlan(
            all, slotEdits = listOf(EkBackup.SlotEdit(
                0, 5013, 1, 2, addQuantity = true))))
        for (save in EkBackup.inspect(added)) {
            assertEquals(3, save.inventory[5013])
            assertTrue(save.inventorySlots.filter { it.id == 5013 }.all { it.count == 1 })
        }
        val deletedOne = EkBackup.apply(added, EkBackup.EditPlan(
            all, slotEdits = listOf(EkBackup.SlotEdit(
                0, 5013, 1, 0))))
        for (save in EkBackup.inspect(deletedOne)) {
            assertEquals(2, save.inventory[5013])
            assertEquals(2, save.inventorySlots.count { it.id == 5013 && it.count == 1 })
        }
    }

    @Test fun enteringMoreTomesThanFreeSlotsIsRejectedWithoutCorruption() {
        val original = fixture()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        assertTrue(runCatching {
            EkBackup.apply(original, EkBackup.EditPlan(all,
                items = listOf(EkBackup.ItemAdd(5035, 5))))
        }.isFailure)
        assertTrue(EkBackup.inspect(original).all { it.inventory.isEmpty() })
    }

    @Test fun nonStackableReplacementCannotSetTwoUnitsInSameSlot() {
        val original = fixture()
        val all = EkBackup.inspect(original)
        val expected = all.associate { it.path to it.inventorySlots[0] }
        assertTrue(runCatching {
            EkBackup.apply(original, EkBackup.EditPlan(all.map { it.path }.toSet(),
                slotReplacements = listOf(EkBackup.SlotReplace(0, 5013, 2, expected))))
        }.isFailure)
    }

    @Test fun medipacksRemainStackableWithinOneSlotAfterClickingAdd() {
        val original = fixture()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        val inserted = EkBackup.apply(original, EkBackup.EditPlan(
            all, slotInserts = listOf(EkBackup.SlotInsert(0, 5070, 10))))
        val increased = EkBackup.apply(inserted, EkBackup.EditPlan(
            all, slotEdits = listOf(EkBackup.SlotEdit(0, 5070, 10, 25, addQuantity = true))))
        assertTrue(EkBackup.inspect(increased).all {
            it.inventorySlots[0].id == 5070 && it.inventorySlots[0].count == 35
        })
    }


    @Test fun duplicateTomesCanBeDuplicatedAgainAndSecondCopyDeleted() {
        val original = fixture()
        val paths = EkBackup.inspect(original).map { it.path }.toSet()
        val first = EkBackup.apply(original, EkBackup.EditPlan(paths,
            slotInserts = listOf(EkBackup.SlotInsert(0, 5013, 1))))
        val two = EkBackup.apply(first, EkBackup.EditPlan(paths,
            slotEdits = listOf(EkBackup.SlotEdit(0, 5013, 1, 1,
                addQuantity = true, occurrence = 0))))
        assertTrue(EkBackup.inspect(two).all { it.inventory[5013] == 2 })
        val three = EkBackup.apply(two, EkBackup.EditPlan(paths,
            slotEdits = listOf(EkBackup.SlotEdit(1, 5013, 1, 1,
                addQuantity = true, occurrence = 1))))
        assertTrue(EkBackup.inspect(three).all { it.inventory[5013] == 3 })
        val deletedSecond = EkBackup.apply(three, EkBackup.EditPlan(paths,
            slotEdits = listOf(EkBackup.SlotEdit(1, 5013, 1, 0,
                occurrence = 1))))
        for (info in EkBackup.inspect(deletedSecond)) {
            assertEquals(2, info.inventory[5013])
            assertEquals(5013, info.inventorySlots[0].id)
            assertEquals(0, info.inventorySlots[1].id)
            assertEquals(5013, info.inventorySlots[2].id)
        }
        // Delete the next remaining copy (with updated occurrence index).
        val deletedAgain = EkBackup.apply(deletedSecond, EkBackup.EditPlan(paths,
            slotEdits = listOf(EkBackup.SlotEdit(2, 5013, 1, 0,
                occurrence = 1))))
        assertTrue(EkBackup.inspect(deletedAgain).all { it.inventory[5013] == 1 })
    }

    private fun duplicatedFourSaves(): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            val layouts = mapOf(
                "auto.sav" to ("5013, 554, 5013, 0" to "1, 1, 1, 0"),
                "auto2.sav" to ("554, 5013, 0, 5013" to "1, 1, 0, 1"),
                "game.sav" to ("5013, 5013, 554, 0" to "1, 1, 1, 0"),
                "game2.sav" to ("0, 5013, 5013, 554" to "0, 1, 1, 1")
            )
            for ((name, pair) in layouts) {
                val plain = String(Base64.getDecoder().decode(save(100)), Charsets.UTF_8)
                    .replace("itemList: [ 0, 0, 0, 0 ]", "itemList: [ ${pair.first} ]")
                    .replace("stackUnits: [ 0, 0, 0, 0 ]", "stackUnits: [ ${pair.second} ]")
                zip.putNextEntry(ZipEntry("saves/0/$name"))
                zip.write(Base64.getEncoder().encode(plain.toByteArray(Charsets.UTF_8)))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("saves/global.sav"))
            zip.write("preserve global".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    @Test fun secondIdenticalCopyDeletesCorrectOrdinalAcrossFourDifferentInventories() {
        val original = duplicatedFourSaves()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        val edited = EkBackup.apply(original, EkBackup.EditPlan(all,
            slotEdits = listOf(EkBackup.SlotEdit(2, 5013, 1, 0,
                occurrence = 1))))
        val before = EkBackup.inspect(original).associateBy { it.path }
        val after = EkBackup.inspect(edited)
        assertEquals(4, after.size)
        for (info in after) {
            val previous = before.getValue(info.path)
            val indices = previous.inventorySlots.indices.filter { previous.inventorySlots[it].id == 5013 }
            assertEquals(2, indices.size)
            assertEquals(5013, info.inventorySlots[indices[0]].id)
            assertEquals(0, info.inventorySlots[indices[1]].id)
            assertEquals(1, info.inventory[5013])
            assertEquals(1, info.inventory[554])
        }
        assertTrue(entries(original).getValue("saves/global.sav")
            .contentEquals(entries(edited).getValue("saves/global.sav")))
    }


    @Test fun repeatedStackableIdChangesOnlyChosenOrdinalInFourSaves() {
        val amounts = mapOf(
            "auto.sav" to ("5070, 554, 5070, 0" to "5, 1, 9, 0"),
            "auto2.sav" to ("554, 5070, 0, 5070" to "1, 7, 0, 12"),
            "game.sav" to ("5070, 5070, 554, 0" to "11, 20, 1, 0"),
            "game2.sav" to ("0, 5070, 5070, 554" to "0, 33, 44, 1")
        )
        val original = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, pair) in amounts) {
                    val plain = String(Base64.getDecoder().decode(save(100)), Charsets.UTF_8)
                        .replace("itemList: [ 0, 0, 0, 0 ]", "itemList: [ ${pair.first} ]")
                        .replace("stackUnits: [ 0, 0, 0, 0 ]", "stackUnits: [ ${pair.second} ]")
                    zip.putNextEntry(ZipEntry("saves/0/$name"))
                    zip.write(Base64.getEncoder().encode(plain.toByteArray(Charsets.UTF_8)))
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        val replaced = EkBackup.apply(original, EkBackup.EditPlan(all,
            slotEdits = listOf(EkBackup.SlotEdit(2, 5070, 9, 25, occurrence = 1))))
        for (info in EkBackup.inspect(replaced)) {
            val ids = info.inventorySlots.indices.filter { info.inventorySlots[it].id == 5070 }
            assertEquals(2, ids.size)
            assertEquals(25, info.inventorySlots[ids[1]].count)
            val expectedFirst = when (info.path.substringAfterLast('/')) {
                "auto.sav" -> 5
                "auto2.sav" -> 7
                "game.sav" -> 11
                else -> 33
            }
            assertEquals(expectedFirst, info.inventorySlots[ids[0]].count)
        }
        val more = EkBackup.apply(replaced, EkBackup.EditPlan(all,
            slotEdits = listOf(EkBackup.SlotEdit(2, 5070, 25, 3,
                addQuantity = true, occurrence = 1))))
        val removed = EkBackup.apply(more, EkBackup.EditPlan(all,
            slotEdits = listOf(EkBackup.SlotEdit(2, 5070, 28, 0, occurrence = 1))))
        assertTrue(EkBackup.inspect(removed).all { info ->
            info.inventorySlots.count { it.id == 5070 } == 1
        })
    }

    @Test fun unitaryDuplicateWorksWhenOtherSaveHasDifferentNumberOfCopies() {
        val original = duplicatedFourSaves()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        val first = EkBackup.apply(original, EkBackup.EditPlan(all,
            slotEdits = listOf(EkBackup.SlotEdit(2, 5013, 1, 1,
                addQuantity = true, occurrence = 1))))
        assertTrue(EkBackup.inspect(first).all { it.inventory[5013] == 3 })
        // The second copy exists in all saves, but occupies a different slot.
        val second = EkBackup.apply(first, EkBackup.EditPlan(all,
            slotEdits = listOf(EkBackup.SlotEdit(2, 5013, 1, 0,
                occurrence = 1))))
        assertTrue(EkBackup.inspect(second).all { it.inventory[5013] == 2 })
    }


    private fun cityVaultBackup(): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            val names = listOf("auto.sav", "auto2.sav", "game.sav", "game2.sav")
            names.forEachIndexed { saveIndex, name ->
                val basic = String(Base64.getDecoder().decode(save(100 + saveIndex)), Charsets.UTF_8)
                val firstVault = when (saveIndex) {
                    0 -> listOf(5013, 0, 5013, 0)
                    1 -> listOf(0, 5013, 0, 5013)
                    2 -> listOf(5013, 5013, 0, 0)
                    else -> listOf(554, 5013, 0, 5013)
                } + List(16) { 0 }
                val vaults = (1..4).joinToString("\n") { number ->
                    val ids = when (number) {
                        1 -> firstVault
                        2 -> (554..573).toList() // Second vault is completely full
                        3 -> listOf(5070) + List(19) { 0 }
                        else -> List(20) { 0 }
                    }
                    val quantities = ids.map { id ->
                        if (id == 0) 0 else if (id == 5070) 10 + saveIndex else 1
                    }
                    val id = if (number == 1) "vault" else "vault" + number
                    "\t\t{\n\t\t\tid: $id\n\t\t\titems: {\n" +
                        "\t\t\t\titemList: [ ${ids.joinToString(", ")} ]\n" +
                        "\t\t\t\tstackUnits: [ ${quantities.joinToString(", ")} ]\n" +
                        "\t\t\t}\n\t\t}"
                }
                val prefix = "\thasVault: true\n\thasVault2: true\n" +
                    "\thasVault3: true\n\thasVault4: ${saveIndex != 1}\n" +
                    "\tworldContainers: [\n$vaults\n\t]\n"
                val full = basic.replace("\tbackpack: {", prefix + "\tbackpack: {")
                zip.putNextEntry(ZipEntry("saves/0/$name"))
                zip.write(Base64.getEncoder().encode(full.toByteArray(Charsets.UTF_8)))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("saves/global.sav"))
            zip.write("keep this data unchanged".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    @Test fun townHallFourVaultsAreFoundSeparatelyInEverySave() {
        val data = cityVaultBackup()
        val all = EkBackup.inspectVaults(data)
        assertEquals(4, all.size)
        for ((_, vaults) in all) {
            assertEquals(listOf(1, 2, 3, 4), vaults.map { it.number })
            assertEquals(List(4) { 20 }, vaults.map { it.slots.size })
            assertEquals(20, vaults.single { it.number == 2 }.occupied)
            assertEquals(20, vaults.single { it.number == 4 }.free)
        }
        assertTrue(all.getValue("saves/0/auto2.sav").single { it.number == 4 }.unlocked.not())
        assertTrue(all.getValue("saves/0/game.sav").single { it.number == 4 }.unlocked)
    }

    @Test fun townHallUnlockUpdatesFlagsInFourSavesPreservingContents() {
        val original = cityVaultBackup()
        val paths = EkBackup.inspect(original).map { it.path }.toSet()
        val before = EkBackup.inspectVaults(original)
        val changed = EkBackup.apply(original, EkBackup.EditPlan(paths,
            vaultChange = VaultStorage.Change(4, VaultStorage.Action.UNLOCK)))
        val after = EkBackup.inspectVaults(changed)
        assertTrue(after.values.all { list -> list.single { it.number == 4 }.unlocked })
        for (path in paths) {
            assertEquals(before.getValue(path).map { it.slots }, after.getValue(path).map { it.slots })
        }
        assertEquals(EkBackup.inspect(original), EkBackup.inspect(changed))
        assertTrue(entries(original).getValue("saves/global.sav")
            .contentEquals(entries(changed).getValue("saves/global.sav")))
    }

    @Test fun townHallDuplicateTomesAndDeleteSecondOnlyAcrossFourSaves() {
        val original = cityVaultBackup()
        val paths = EkBackup.inspect(original).map { it.path }.toSet()
        val edited = EkBackup.apply(original, EkBackup.EditPlan(paths,
            vaultChange = VaultStorage.Change(1, VaultStorage.Action.ADD,
                index = 2, itemId = 5013, amount = 2, occurrence = 1)))
        for (list in EkBackup.inspectVaults(edited).values) {
            val vault1 = list.single { it.number == 1 }
            assertEquals(4, vault1.total(5013))
            assertTrue(vault1.slots.filter { it.id == 5013 }.all { it.count == 1 })
        }
        val deleted = EkBackup.apply(edited, EkBackup.EditPlan(paths,
            vaultChange = VaultStorage.Change(1, VaultStorage.Action.DELETE,
                index = 2, itemId = 5013, occurrence = 1)))
        for (list in EkBackup.inspectVaults(deleted).values) {
            val vault1 = list.single { it.number == 1 }
            assertEquals(3, vault1.total(5013))
            assertEquals(3, vault1.slots.filter { it.id == 5013 && it.count == 1 }.size)
        }
        assertTrue(EkBackup.inspectVaults(original).keys.all { path ->
            EkBackup.inspectVaults(original).getValue(path).single { it.number == 2 } ==
                EkBackup.inspectVaults(deleted).getValue(path).single { it.number == 2 }
        })
        assertEquals(EkBackup.inspect(original), EkBackup.inspect(deleted))
    }

    @Test fun townHallStackQuantitiesPreserveDifferencesBetweenSaves() {
        val original = cityVaultBackup()
        val paths = EkBackup.inspect(original).map { it.path }.toSet()
        val changed = EkBackup.apply(original, EkBackup.EditPlan(paths,
            vaultChange = VaultStorage.Change(3, VaultStorage.Action.ADD,
                index = 0, itemId = 5070, amount = 5, occurrence = 0)))
        val after = EkBackup.inspectVaults(changed)
        val amounts = listOf("auto.sav", "auto2.sav", "game.sav", "game2.sav").map {
            after.getValue("saves/0/$it").single { v -> v.number == 3 }.total(5070)
        }
        assertEquals(listOf(15, 16, 17, 18), amounts)
        val fixed = EkBackup.apply(changed, EkBackup.EditPlan(paths,
            vaultChange = VaultStorage.Change(3, VaultStorage.Action.SET,
                index = 0, itemId = 5070, amount = 80, occurrence = 0)))
        assertTrue(EkBackup.inspectVaults(fixed).values.all {
            it.single { v -> v.number == 3 }.total(5070) == 80
        })
    }

    @Test fun townHallVault2FullRequiresConfirmedReplacement() {
        val original = cityVaultBackup()
        val paths = EkBackup.inspect(original).map { it.path }.toSet()
        assertTrue(runCatching {
            EkBackup.apply(original, EkBackup.EditPlan(paths,
                vaultChange = VaultStorage.Change(2, VaultStorage.Action.INSERT,
                    index = 0, itemId = 5013, amount = 1)))
        }.isFailure)
        val expected = EkBackup.inspectVaults(original).mapValues { (_, vaults) ->
            vaults.single { it.number == 2 }.slots[0]
        }
        val edited = EkBackup.apply(original, EkBackup.EditPlan(paths,
            vaultChange = VaultStorage.Change(2, VaultStorage.Action.REPLACE,
                index = 0, itemId = 5013, amount = 1, expectedByPath = expected)))
        assertTrue(EkBackup.inspectVaults(edited).values.all { vaults ->
            val vault2 = vaults.single { it.number == 2 }
            vault2.slots[0].id == 5013 && vault2.slots[0].count == 1 && vault2.occupied == 20
        })
        assertEquals(EkBackup.inspect(original), EkBackup.inspect(edited))
    }

    @Test fun townHallReplacementCannotSilentlyOverwriteChangedSlot() {
        val original = cityVaultBackup()
        val paths = EkBackup.inspect(original).map { it.path }.toSet()
        val expected = EkBackup.inspectVaults(original).mapValues { (_, vaults) ->
            vaults.single { it.number == 2 }.slots[0]
        }.toMutableMap()
        expected["saves/0/auto2.sav"] = EkBackup.InventorySlot(1, 1)
        assertTrue(runCatching {
            EkBackup.apply(original, EkBackup.EditPlan(paths,
                vaultChange = VaultStorage.Change(2, VaultStorage.Action.REPLACE,
                    index = 0, itemId = 5013, amount = 1, expectedByPath = expected)))
        }.isFailure)
    }

    @Test fun townHallEditingLockedVaultIsRejectedBeforeUnlocking() {
        val original = cityVaultBackup()
        val paths = EkBackup.inspect(original).map { it.path }.toSet()
        assertTrue(runCatching {
            EkBackup.apply(original, EkBackup.EditPlan(paths,
                vaultChange = VaultStorage.Change(4, VaultStorage.Action.INSERT,
                    index = 0, itemId = 5070, amount = 5)))
        }.isFailure)
        val unlocked = EkBackup.apply(original, EkBackup.EditPlan(paths,
            vaultChange = VaultStorage.Change(4, VaultStorage.Action.UNLOCK)))
        val inserted = EkBackup.apply(unlocked, EkBackup.EditPlan(paths,
            vaultChange = VaultStorage.Change(4, VaultStorage.Action.INSERT,
                index = 0, itemId = 5070, amount = 5)))
        assertTrue(EkBackup.inspectVaults(inserted).values.all {
            it.single { v -> v.number == 4 }.total(5070) == 5
        })
    }


    /** Duas pastas de personagens diferentes: /0 com 4 saves, /2 com 3. */
    private fun twoCharactersBackup(): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for (name in listOf("auto.sav", "auto2.sav", "game.sav", "game2.sav")) {
                    val content = String(Base64.getDecoder().decode(save(5000)), Charsets.UTF_8)
                        .replace("name: Test Warrior", "name: Arthur da Cruz")
                    zip.putNextEntry(ZipEntry("saves/0/$name"))
                    zip.write(Base64.getEncoder().encode(content.toByteArray(Charsets.UTF_8)))
                    zip.closeEntry()
                }
                // Save inicial de personagem: sem extraTraitPoints e bonusPoints.
                for (name in listOf("auto.sav", "game.sav", "game2.sav")) {
                    val content = String(Base64.getDecoder().decode(level16Save()), Charsets.UTF_8)
                        .replace("name: Test Cleric", "name: Shep Sossar")
                        .replace("gold: 25", "gold: 200")
                        .replace("characterClass: CLERIC", "characterClass: WARRIOR")
                        .replace("cached_level: 16", "cached_level: 1")
                        .replace("spentPoints: [ 10, 10, 3, 6, 6, 10 ]",
                            "spentPoints: [ 0, 0, 0, 0, 1, 3 ]")
                        .replace("\t\t\t\textraTraitPoints: 10\n", "")
                        .replace("\t\t\t\tbonusPoints: 8\n", "")
                    zip.putNextEntry(ZipEntry("saves/2/$name"))
                    zip.write(Base64.getEncoder().encode(content.toByteArray(Charsets.UTF_8)))
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("saves/global.sav"))
                zip.write("game-wide state".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("saves/2/levels/I10_tutorial_tiles.txt"))
                zip.write("character level data".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

    @Test fun multipleCharactersFoundByFolderEvenWithNoSavesOne() {
        val archive = twoCharactersBackup()
        val inspected = EkBackup.inspect(archive)
        assertEquals(7, inspected.size)
        val characters = EkBackup.characters(inspected)
        assertEquals(2, characters.size)
        assertEquals(listOf(0, 2), characters.map { it.slot })
        assertEquals(listOf("Arthur da Cruz", "Shep Sossar"), characters.map { it.name })
        assertEquals(listOf(4, 3), characters.map { it.savePaths.size })
        assertTrue(characters[0].savePaths.all { it.startsWith("saves/0/") })
        assertTrue(characters[1].savePaths.all { it.startsWith("saves/2/") })
        assertTrue(EkBackup.inspectVaults(archive).getValue("saves/2/auto.sav").isEmpty())
        assertTrue(inspected.filter { it.characterSlot == 2 }.all {
            it.traitPoints == 0 && it.skillPoints == 0
        })
    }

    @Test fun editingSecondCharacterDoesNotModifyAnyFirstCharacterSave() {
        val original = twoCharactersBackup()
        val characters = EkBackup.characters(EkBackup.inspect(original))
        val selected = characters.single { it.slot == 2 }.savePaths
        val before = entries(original)
        val updated = EkBackup.apply(original, EkBackup.EditPlan(selected,
            gold = 9999, traitDelta = 20, skillDelta = 10))
        val after = entries(updated)
        assertEquals(before.keys, after.keys)
        for ((path, bytes) in before) if (path !in selected) {
            assertTrue("File $path must not be modified", bytes.contentEquals(after.getValue(path)))
        }
        assertEquals(3, selected.size)
        val readback = EkBackup.inspect(updated)
        assertTrue(readback.filter { it.characterSlot == 2 }.all {
            it.gold == 9999L && it.traitPoints == 20 && it.skillPoints == 10
        })
        assertTrue(readback.filter { it.characterSlot == 0 }.all {
            it.gold == 5000L && it.traitPoints == 2 && it.skillPoints == 3
        })
    }

    @Test fun switchingBetweenTwoCharactersPreservesChangesToBoth() {
        val original = twoCharactersBackup()
        val first = EkBackup.characters(EkBackup.inspect(original))
        val p2 = first.single { it.slot == 2 }.savePaths
        val p0 = first.single { it.slot == 0 }.savePaths
        val shepEdited = EkBackup.apply(original, EkBackup.EditPlan(p2, gold = 7777L))
        val bothEdited = EkBackup.apply(shepEdited, EkBackup.EditPlan(p0, gold = 8888L))
        val final = EkBackup.inspect(bothEdited)
        assertTrue(final.filter { it.characterSlot == 2 }.all { it.gold == 7777L })
        assertTrue(final.filter { it.characterSlot == 0 }.all { it.gold == 8888L })
        assertEquals("game-wide state", String(entries(bothEdited).getValue("saves/global.sav")))
        assertEquals("character level data",
            String(entries(bothEdited).getValue("saves/2/levels/I10_tutorial_tiles.txt")))
    }

    @Test fun mixingCharacterGroupsInOneEditIsRejected() {
        val original = twoCharactersBackup()
        val all = EkBackup.inspect(original).map { it.path }.toSet()
        val failure = runCatching {
            EkBackup.apply(original, EkBackup.EditPlan(all, gold = 9999L))
        }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message?.contains("one character") == true)
    }

    @Test fun newlyCreatedCharacterSupportsItemInsertionWithMissingBonusFields() {
        val original = twoCharactersBackup()
        val selected = EkBackup.characters(EkBackup.inspect(original))
            .single { it.slot == 2 }.savePaths
        val originalOthers = entries(original).filterKeys { it.startsWith("saves/0/") }
        val updated = EkBackup.apply(original, EkBackup.EditPlan(selected,
            items = listOf(EkBackup.ItemAdd(5013, 2))))
        val info = EkBackup.inspect(updated).filter { it.characterSlot == 2 }
        assertTrue(info.all { it.inventory[5013] == 2 })
        assertTrue(info.all { it.inventorySlots.count { slot ->
            slot.id == 5013 && slot.count == 1
        } == 2 })
        assertTrue(originalOthers.all { (key, bytes) ->
            bytes.contentEquals(entries(updated).getValue(key))
        })
    }

}
