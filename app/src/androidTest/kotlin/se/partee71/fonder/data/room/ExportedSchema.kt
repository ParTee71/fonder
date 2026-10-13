package se.partee71.fonder.data.room

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals

/**
 * `CREATE TABLE`/`CREATE INDEX`-satserna för en **frusen** schemaversion, lästa ur den incheckade
 * `schemas/…/<version>.json` (monterad som androidTest-asset) — så ett migreringstest kan bygga den
 * gamla databasen med rå SQL utan att skriva av alla tabeller en gång till (skill `room-migrations`:
 * aldrig `MigrationTestHelper`, aldrig den nuvarande entiteten). Delad mellan migreringstesterna
 * från och med 15→16; en kopia per test vore just den dubbelkod `cpdCheck` fäller.
 */
internal fun exportedSchemaStatements(version: Int): List<String> {
    val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    val json = testAssets.open("se.partee71.fonder.data.room.AppDatabase/$version.json").bufferedReader().use { it.readText() }
    val database = JSONObject(json).getJSONObject("database")
    assertEquals(version, database.getInt("version"))
    val entities = database.getJSONArray("entities")
    return (0 until entities.length()).flatMap { i ->
        val entity = entities.getJSONObject(i)
        val indices = entity.optJSONArray("indices")
        val statements = listOf(entity.getString("createSql")) +
            (0 until (indices?.length() ?: 0)).map { j -> indices!!.getJSONObject(j).getString("createSql") }
        statements.map { it.replace("\${TABLE_NAME}", entity.getString("tableName")) }
    }
}
