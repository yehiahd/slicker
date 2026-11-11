package dev.slicker.plugins.databases

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.slicker.core.SlickerConnection
import dev.slicker.core.SlickerPlugin
import dev.slicker.core.SlickerResponder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Desktop plugin id "Databases". Exposes every SQLite file returned by Context.databaseList(). */
class DatabasesPlugin(context: Context) : SlickerPlugin {
    override val id = "Databases"

    private val appContext = context.applicationContext
    private var databases: Map<Int, File> = emptyMap()

    override fun onConnect(connection: SlickerConnection) {
        connection.receive("databaseList") { _, r -> r.success(databaseList()) }
        connection.receive("getTableData") { p, r -> withDb(p, r) { r.success(tableData(it, p)) } }
        connection.receive("getTableStructure") { p, r -> withDb(p, r) { r.success(tableStructure(it, p)) } }
        connection.receive("getTableInfo") { p, r -> withDb(p, r) { r.success(tableInfo(it, p)) } }
        connection.receive("execute") { p, r -> withDb(p, r) { r.success(execute(it, p.getString("value"))) } }
    }

    override fun onDisconnect() = Unit

    private fun databaseList(): JSONArray {
        val files = appContext.databaseList()
            .filterNot { name -> SIDE_FILE_SUFFIXES.any { name.endsWith(it) } }
            .sorted()
            .map { appContext.getDatabasePath(it) }
            .filter { it.isFile }
        databases = files.mapIndexed { index, file -> index + 1 to file }.toMap()

        val result = JSONArray()
        databases.forEach { (dbId, file) ->
            val tables = runCatching { open(file).use { tableNames(it) } }.getOrDefault(emptyList())
            result.put(
                JSONObject().put("id", dbId).put("name", file.name).put("tables", JSONArray(tables)),
            )
        }
        return result
    }

    private inline fun withDb(params: JSONObject, responder: SlickerResponder, block: (SQLiteDatabase) -> Unit) {
        val file = databases[params.optInt("databaseId")]
        if (file == null) {
            responder.error("Database ${params.optInt("databaseId")} not found", "DatabaseInvalid")
            return
        }
        open(file).use(block)
    }

    private fun open(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)

    private fun tableNames(db: SQLiteDatabase): List<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view') ORDER BY name", null)
            .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    private fun tableData(db: SQLiteDatabase, p: JSONObject): JSONObject {
        val table = quote(p.getString("table"))
        val start = p.optInt("start", 0)
        val count = p.optInt("count", 100)
        val order = p.optString("order").takeIf { it.isNotEmpty() && !p.isNull("order") }
        val orderBy = order?.let { " ORDER BY ${quote(it)} ${if (p.optBoolean("reverse")) "DESC" else "ASC"}" }.orEmpty()

        val total = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
        return db.rawQuery("SELECT * FROM $table$orderBy LIMIT $count OFFSET $start", null).use { c ->
            JSONObject()
                .put("columns", JSONArray(c.columnNames.toList()))
                .put("values", rows(c))
                .put("start", start)
                .put("count", c.count)
                .put("total", total)
        }
    }

    private fun tableStructure(db: SQLiteDatabase, p: JSONObject): JSONObject {
        val table = p.getString("table")
        val result = JSONObject()
        db.rawQuery("PRAGMA table_info(${quote(table)})", null).use { c ->
            result.put("structureColumns", JSONArray(c.columnNames.toList()))
            result.put("structureValues", rows(c))
        }
        val indexValues = JSONArray()
        db.rawQuery("PRAGMA index_list(${quote(table)})", null).use { indexes ->
            while (indexes.moveToNext()) {
                val name = indexes.getString(indexes.getColumnIndexOrThrow("name"))
                val unique = indexes.getInt(indexes.getColumnIndexOrThrow("unique")) == 1
                val columns = db.rawQuery("PRAGMA index_info(${quote(name)})", null).use { info ->
                    generateSequence { if (info.moveToNext()) info.getString(info.getColumnIndexOrThrow("name")) else null }
                        .joinToString(",")
                }
                indexValues.put(
                    JSONArray()
                        .put(stringValue(name))
                        .put(JSONObject().put("type", "boolean").put("value", unique))
                        .put(stringValue(columns)),
                )
            }
        }
        result.put("indexesColumns", JSONArray(listOf("index_name", "unique", "indexed_column_name")))
        result.put("indexesValues", indexValues)
        return result
    }

    private fun tableInfo(db: SQLiteDatabase, p: JSONObject): JSONObject {
        val definition = db.rawQuery("SELECT sql FROM sqlite_master WHERE name = ?", arrayOf(p.getString("table")))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        return JSONObject().put("definition", definition ?: "")
    }

    private fun execute(db: SQLiteDatabase, sql: String): JSONObject {
        return when (sql.trimStart().substringBefore(' ').lowercase()) {
            "select", "pragma", "explain", "with" -> db.rawQuery(sql, null).use { c ->
                JSONObject()
                    .put("type", "select")
                    .put("columns", JSONArray(c.columnNames.toList()))
                    .put("values", rows(c))
            }
            "insert" -> {
                db.execSQL(sql)
                JSONObject().put("type", "insert").put("insertedId", singleLong(db, "SELECT last_insert_rowid()"))
            }
            else -> {
                db.execSQL(sql)
                JSONObject().put("type", "update_delete").put("affectedCount", singleLong(db, "SELECT changes()"))
            }
        }
    }

    private fun singleLong(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }

    private fun rows(c: Cursor): JSONArray {
        val rows = JSONArray()
        while (c.moveToNext()) {
            val row = JSONArray()
            for (i in 0 until c.columnCount) row.put(value(c, i))
            rows.put(row)
        }
        return rows
    }

    private fun value(c: Cursor, i: Int): JSONObject = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject().put("type", "null")
        Cursor.FIELD_TYPE_INTEGER -> JSONObject().put("type", "integer").put("value", c.getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> JSONObject().put("type", "float").put("value", c.getDouble(i))
        Cursor.FIELD_TYPE_BLOB -> JSONObject().put("type", "blob").put("value", String(c.getBlob(i), Charsets.UTF_8))
        else -> stringValue(c.getString(i))
    }

    private fun stringValue(s: String): JSONObject = JSONObject().put("type", "string").put("value", s)

    private fun quote(identifier: String) = "`" + identifier.replace("`", "``") + "`"

    private companion object {
        val SIDE_FILE_SUFFIXES = listOf("-journal", "-wal", "-shm")
    }
}
