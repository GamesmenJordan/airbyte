#
# Copyright (c) 2023 Airbyte, Inc., all rights reserved.
#
from dataclasses import dataclass
from typing import Any, Mapping

from airbyte_cdk.sources.declarative.retrievers.simple_retriever import SimpleRetriever
from airbyte_cdk.sources.declarative.stream_slicers.stream_slicer import StreamSlicer
from source_google_sheets.components import RangePartitionRouter


@dataclass
class SheetsDataRetriever(SimpleRetriever):
    def __post_init__(self, parameters: Mapping[str, Any]) -> None:
        super().__post_init__(parameters)
        self.stream_slicer: StreamSlicer = RangePartitionRouter(
            parameters={"batch_size":self.config.get("batch_size", 200), "row_count":  parameters.get("row_count", 0) }
        )

