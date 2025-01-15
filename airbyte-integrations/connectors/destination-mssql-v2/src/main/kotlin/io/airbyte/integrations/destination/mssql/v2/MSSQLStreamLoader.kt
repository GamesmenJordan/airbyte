/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.mssql.v2

import io.airbyte.cdk.load.command.DestinationStream
import io.airbyte.cdk.load.message.Batch
import io.airbyte.cdk.load.message.DestinationRecordAirbyteValue
import io.airbyte.cdk.load.message.SimpleBatch
import io.airbyte.cdk.load.write.StreamLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.sql.DataSource

private val log = KotlinLogging.logger {}

class MSSQLStreamLoader(
    private val dataSource: DataSource,
    override val stream: DestinationStream,
    private val sqlBuilder: MSSQLQueryBuilder,
) : StreamLoader {

    override suspend fun start() {
        ensureTableExists(dataSource)
    }

    override suspend fun processRecords(
        records: Iterator<DestinationRecordAirbyteValue>,
        totalSizeBytes: Long,
        endOfStream: Boolean
    ): Batch {
        dataSource.connection.use { connection ->
            val statement =
                connection.prepareStatement(sqlBuilder.getFinalTableInsertColumnHeader())
            records.forEach { record ->
                sqlBuilder.populateStatement(statement, record, sqlBuilder.finalTableSchema)
                statement.addBatch()
            }
            statement.executeLargeBatch()
            connection.commit()
        }
        return SimpleBatch(Batch.State.COMPLETE)
    }

    private fun ensureTableExists(dataSource: DataSource) {
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(sqlBuilder.createFinalSchemaIfNotExists())
                }
                connection.createStatement().use { statement ->
                    statement.executeUpdate(sqlBuilder.createFinalTableIfNotExists())
                }
            }
        } catch (ex: Exception) {
            log.error(ex) { ex.message }
            throw ex
        }
    }

}
