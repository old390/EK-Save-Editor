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
        // O fixture antigo não contém nível/gastos do companheiro:
        // somente os bônus podem ser testados aqui. O zeramento é testado abaixo
        // em um fixture completo para não simular saldos inexistentes.
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
            val failure = runCatching { EkBackup.parseNumberInput(raw, "Ouro", 999_999_999) }.exceptionOrNull()
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


    /** Dez habilidades com custo total 39 para personagem de nível 16. */
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
        assertEquals(3, before.freeTraitPoints)
        assertEquals(0, before.freeSkillPoints)
        assertEquals(24, before.spentTraitPoints)
        assertEquals(16, before.spentSkillPoints)
        val changed = EkBackup.apply(data, EkBackup.EditPlan(paths,
            companionTraitDelta = 20, companionSkillDelta = 5))
        val after = EkBackup.inspect(changed).single().companion!!
        assertEquals(23, after.freeTraitPoints)
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

    @Test(expected = IllegalArgumentException::class)
    fun inventoryGridRefusesToDeleteMissingItemInOneSave() {
        val data = archiveWithShiftedItem(missingGameItem = true)
        EkBackup.apply(data, EkBackup.EditPlan(EkBackup.inspect(data).map { it.path }.toSet(),
            slotEdits = listOf(EkBackup.SlotEdit(0, 5070, 7, 0))))
    }

}
