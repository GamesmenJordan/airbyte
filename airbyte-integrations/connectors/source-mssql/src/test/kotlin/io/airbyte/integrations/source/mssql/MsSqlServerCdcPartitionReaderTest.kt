package io.airbyte.integrations.source.mssql

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.airbyte.cdk.command.OpaqueStateValue
import io.airbyte.cdk.jdbc.JdbcConnectionFactory
import io.airbyte.cdk.read.Stream
import io.airbyte.cdk.read.cdc.*
import io.airbyte.cdk.testcontainers.TestContainerFactory
import io.airbyte.cdk.util.Jsons
import org.apache.kafka.connect.source.SourceRecord
import java.sql.Connection
import java.sql.Statement
import java.time.Instant
import kotlin.random.Random
import kotlin.random.nextInt
import io.airbyte.cdk.ssh.TunnelSession
import io.debezium.connector.sqlserver.Lsn
import io.debezium.connector.sqlserver.SqlServerConnector
import io.debezium.connector.sqlserver.TxLogPosition
import io.github.oshai.kotlinlogging.KotlinLogging
import org.testcontainers.containers.MSSQLServerContainer
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class MsSqlServerCdcPartitionReaderTest :
        AbstractCdcPartitionReaderTest<TxLogPosition, MsSqlServercontainerWithCdc>(
            namespace = "test",
        ) {

        override fun createContainer(): MsSqlServercontainerWithCdc {
            val retVal = MsSqlServerContainerFactory.exclusive(MsSqlServerImage.SQLSERVER_2022, MsSqlServerContainerFactory.WithCdcAgent) as MsSqlServercontainerWithCdc
            retVal.config.schemas = arrayOf("test")
            return retVal
        }

        companion object {
            const val DOCKER_IMAGE_NAME = "mcr.microsoft.com/mssql/server:2022-latest"
            init {
                TestContainerFactory.register(DOCKER_IMAGE_NAME, ::MSSQLServerContainer)
            }
        }

        override fun MsSqlServercontainerWithCdc.createStream() {
            withStatement { it.execute("CREATE SCHEMA test") }
            withStatement { it.execute("CREATE TABLE test.tbl (id INT PRIMARY KEY IDENTITY, v INT)") }
            withStatement {it.execute(MsSqlServercontainerWithCdc.ENABLE_CDC_SQL_FMT.format("test", "tbl", "RANDOM_ROLE", "cdc_test_tbl"))}
            withWaitUntilMaxLsnAvailable(config)

        }

        override fun MsSqlServercontainerWithCdc.insert12345() {
            for (i in 1..5) {
                withStatement { it.execute("INSERT INTO test.tbl (v) VALUES ($i)") }
            }
            waitForCdcNewRecords(5)
        }

        override fun MsSqlServercontainerWithCdc.update135() {
            withStatement { it.execute("UPDATE test.tbl SET v = 6 WHERE id = 1") }
            withStatement { it.execute("UPDATE test.tbl SET v = 7 WHERE id = 3") }
            withStatement { it.execute("UPDATE test.tbl SET v = 8 WHERE id = 5") }
            waitForCdcNewRecords(3)
        }

        override fun MsSqlServercontainerWithCdc.delete24() {
            withStatement { it.execute("DELETE FROM test.tbl WHERE id = 2") }
            withStatement { it.execute("DELETE FROM test.tbl WHERE id = 4") }
            waitForCdcNewRecords(2)
        }

        private fun <X> MsSqlServercontainerWithCdc.withStatement(fn: (Statement) -> X): X {
            JdbcConnectionFactory(MsSqlServerSourceConfigurationFactory().make(config)).get().use { connection: Connection ->
                connection.createStatement().use {
                    val rs = it.executeQuery("select DB_NAME()")
                    rs.next()
                    log.info{"SGX dbName=${rs.getString(1)}"}
                }
                connection.createStatement().use { return fn(it) }
            }
        }

        private var lastRecordCount = 0
        private fun MsSqlServercontainerWithCdc.waitForCdcNewRecords(numNewRecords: Int) {
            var currentRecordCount: Int = lastRecordCount
            var retries = 0
            JdbcConnectionFactory(MsSqlServerSourceConfigurationFactory().make(config)).get().use { connection: Connection ->
                connection.createStatement().use {
                    do {
                        Thread.sleep(1000)
                        log.info{"SGX waiting to see ${lastRecordCount + numNewRecords} CDC records. Currently seeing only $currentRecordCount on retry $retries"}
                        val rs = it.executeQuery("SELECT count(*) FROM cdc.cdc_test_tbl_ct")
                        rs.next()
                        currentRecordCount = rs.getInt(1)
                    } while (currentRecordCount < lastRecordCount + numNewRecords && retries++ < MsSqlServercontainerWithCdc.MAX_RETRIES)
                }
            }
            if (currentRecordCount < lastRecordCount + numNewRecords) {
                throw RuntimeException("Failed to wait for new records")
            } else {
                lastRecordCount = currentRecordCount

                return
            }
        }

    override fun getCdcOperations(): DebeziumOperations<TxLogPosition> {
        return object: AbstractCdcPartitionReaderDebeziumOperationsForTest<TxLogPosition>(stream) {
            override fun position(offset: DebeziumOffset): TxLogPosition {
                return TxLogPosition.valueOf(Lsn.valueOf(offset.wrapped.values.first()["commit_lsn"].asText()), Lsn.valueOf(offset.wrapped.values.first()["change_lsn"].asText()))
            }

            override fun position(recordValue: DebeziumRecordValue): TxLogPosition? {
                val commitLsn: String =
                    recordValue.source["commit_lsn"]?.takeIf { it.isTextual }?.asText() ?: return null
                val changeLsn: String? =
                    recordValue.source["change_lsn"]?.takeIf { it.isTextual }?.asText()
                return TxLogPosition.valueOf(Lsn.valueOf(commitLsn), Lsn.valueOf(changeLsn))
            }

            override fun position(sourceRecord: SourceRecord): TxLogPosition? {
                val commitLsn: String = sourceRecord.sourceOffset()[("commit_lsn")]?.toString() ?: return null
                val changeLsn: String? = sourceRecord.sourceOffset()[("change_lsn")]?.toString()
                return TxLogPosition.valueOf(Lsn.valueOf(commitLsn), Lsn.valueOf(changeLsn))
            }

            override fun synthesize(streams: List<Stream>): DebeziumInput {
                val config = MsSqlServerSourceConfigurationFactory().make(container.config)
                val retVal = MsSqlServerDebeziumOperations(JdbcConnectionFactory(config), config).synthesize(streams)
                log.info {"SGX synthesize $retVal"}
                return retVal
            }

            override fun deserialize(opaqueStateValue: OpaqueStateValue, streams: List<Stream>): DebeziumInput {
                return super.deserialize(opaqueStateValue, streams).let {
                    DebeziumInput(synthesize(streams).properties + ("snapshot.mode" to "when_needed"), it.state, it.isSynthetic)
                }
            }
        }
    }
    }

