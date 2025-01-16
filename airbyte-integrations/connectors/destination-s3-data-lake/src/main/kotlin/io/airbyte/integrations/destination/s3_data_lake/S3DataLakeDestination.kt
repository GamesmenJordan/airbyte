/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.s3_data_lake

import io.airbyte.cdk.AirbyteDestinationRunner

object S3DataLakeDestination {
    @JvmStatic
    fun main(args: Array<String>) {
        AirbyteDestinationRunner.run(*args)
    }
}
