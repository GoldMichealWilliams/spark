/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.streaming

import org.scalatest.BeforeAndAfterAll

import org.apache.spark.sql.catalyst.streaming.InternalOutputModes._
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.execution.streaming.state.StateStore
import org.apache.spark.sql.functions._

class DeduplicateSuite extends StateStoreMetricsTest with BeforeAndAfterAll {

  import testImplicits._

  override def afterAll(): Unit = {
    super.afterAll()
    StateStore.stop()
  }

  test("deduplicate with all columns") {
    val inputData = MemoryStream[String]
    val result = inputData.toDS().dropDuplicates()

    testStream(result, Append)(
      AddData(inputData, "a"),
      CheckLastBatch("a"),
      assertNumStateRows(total = 1, updated = 1),
      AddData(inputData, "a"),
      CheckLastBatch(),
      assertNumStateRows(total = 1, updated = 0),
      AddData(inputData, "b"),
      CheckLastBatch("b"),
      assertNumStateRows(total = 2, updated = 1)
    )
  }

  test("deduplicate with some columns") {
    val inputData = MemoryStream[(String, Int)]
    val result = inputData.toDS().dropDuplicates("_1")

    testStream(result, Append)(
      AddData(inputData, "a" -> 1),
      CheckLastBatch("a" -> 1),
      assertNumStateRows(total = 1, updated = 1),
      AddData(inputData, "a" -> 2), // Dropped
      CheckLastBatch(),
      assertNumStateRows(total = 1, updated = 0),
      AddData(inputData, "b" -> 1),
      CheckLastBatch("b" -> 1),
      assertNumStateRows(total = 2, updated = 1)
    )
  }

  test("multiple deduplicates") {
    val inputData = MemoryStream[(String, Int)]
    val result = inputData.toDS().dropDuplicates().dropDuplicates("_1")

    testStream(result, Append)(
      AddData(inputData, "a" -> 1),
      CheckLastBatch("a" -> 1),
      assertNumStateRows(total = Seq(1L, 1L), updated = Seq(1L, 1L)),

      AddData(inputData, "a" -> 2), // Dropped from the second `dropDuplicates`
      CheckLastBatch(),
      assertNumStateRows(total = Seq(1L, 2L), updated = Seq(0L, 1L)),

      AddData(inputData, "b" -> 1),
      CheckLastBatch("b" -> 1),
      assertNumStateRows(total = Seq(2L, 3L), updated = Seq(1L, 1L))
    )
  }

  test("deduplicate with watermark") {
    val inputData = MemoryStream[Int]
    val result = inputData.toDS()
      .withColumn("eventTime", $"value".cast("timestamp"))
      .withWatermark("eventTime", "10 seconds")
      .dropDuplicates()
      .select($"eventTime".cast("long").as[Long])

    testStream(result, Append)(
      AddData(inputData, (1 to 5).flatMap(_ => (10 to 15)): _*),
      CheckLastBatch(10 to 15: _*),
      assertNumStateRows(total = 6, updated = 6),

      AddData(inputData, 25), // Advance watermark to 15 seconds
      CheckLastBatch(25),
      assertNumStateRows(total = 7, updated = 1),

      AddData(inputData, 25), // Drop states less than watermark
      CheckLastBatch(),
      assertNumStateRows(total = 1, updated = 0),

      AddData(inputData, 10), // Should not emit anything as data less than watermark
      CheckLastBatch(),
      assertNumStateRows(total = 1, updated = 0),

      AddData(inputData, 45), // Advance watermark to 35 seconds
      CheckLastBatch(45),
      assertNumStateRows(total = 2, updated = 1),

      AddData(inputData, 45), // Drop states less than watermark
      CheckLastBatch(),
      assertNumStateRows(total = 1, updated = 0)
    )
  }

  test("deduplicate with aggregate - append mode") {
    val inputData = MemoryStream[Int]
    val windowedaggregate = inputData.toDS()
      .withColumn("eventTime", $"value".cast("timestamp"))
      .withWatermark("eventTime", "10 seconds")
      .dropDuplicates()
      .withWatermark("eventTime", "10 seconds")
      .groupBy(window($"eventTime", "5 seconds") as 'window)
      .agg(count("*") as 'count)
      .select($"window".getField("start").cast("long").as[Long], $"count".as[Long])

    testStream(windowedaggregate)(
      AddData(inputData, (1 to 5).flatMap(_ => (10 to 15)): _*),
      CheckLastBatch(),
      // states in aggregate in [10, 14), [15, 20) (2 windows)
      // states in deduplicate is 10 to 15
      assertNumStateRows(total = Seq(2L, 6L), updated = Seq(2L, 6L)),

      AddData(inputData, 25), // Advance watermark to 15 seconds
      CheckLastBatch(),
      // states in aggregate in [10, 14), [15, 20) and [25, 30) (3 windows)
      // states in deduplicate is 10 to 15 and 25
      assertNumStateRows(total = Seq(3L, 7L), updated = Seq(1L, 1L)),

      AddData(inputData, 25), // Emit items less than watermark and drop their state
      CheckLastBatch((10 -> 5)), // 5 items (10 to 14) after deduplicate
      // states in aggregate in [15, 20) and [25, 30) (2 windows, note aggregate uses the end of
      // window to evict items, so [15, 20) is still in the state store)
      // states in deduplicate is 25
      assertNumStateRows(total = Seq(2L, 1L), updated = Seq(0L, 0L)),

      AddData(inputData, 10), // Should not emit anything as data less than watermark
      CheckLastBatch(),
      assertNumStateRows(total = Seq(2L, 1L), updated = Seq(0L, 0L)),

      AddData(inputData, 40), // Advance watermark to 30 seconds
      CheckLastBatch(),
      // states in aggregate in [15, 20), [25, 30) and [40, 45)
      // states in deduplicate is 25 and 40,
      assertNumStateRows(total = Seq(3L, 2L), updated = Seq(1L, 1L)),

      AddData(inputData, 40), // Emit items less than watermark and drop their state
      CheckLastBatch((15 -> 1), (25 -> 1)),
      // states in aggregate in [40, 45)
      // states in deduplicate is 40,
      assertNumStateRows(total = Seq(1L, 1L), updated = Seq(0L, 0L))
    )
  }

  test("deduplicate with aggregate - update mode") {
    val inputData = MemoryStream[(String, Int)]
    val result = inputData.toDS()
      .select($"_1" as "str", $"_2" as "num")
      .dropDuplicates()
      .groupBy("str")
      .agg(sum("num"))
      .as[(String, Long)]

    testStream(result, Update)(
      AddData(inputData, "a" -> 1),
      CheckLastBatch("a" -> 1L),
      assertNumStateRows(total = Seq(1L, 1L), updated = Seq(1L, 1L)),
      AddData(inputData, "a" -> 1), // Dropped
      CheckLastBatch(),
      assertNumStateRows(total = Seq(1L, 1L), updated = Seq(0L, 0L)),
      AddData(inputData, "a" -> 2),
      CheckLastBatch("a" -> 3L),
      assertNumStateRows(total = Seq(1L, 2L), updated = Seq(1L, 1L)),
      AddData(inputData, "b" -> 1),
      CheckLastBatch("b" -> 1L),
      assertNumStateRows(total = Seq(2L, 3L), updated = Seq(1L, 1L))
    )
  }

  test("deduplicate with aggregate - complete mode") {
    val inputData = MemoryStream[(String, Int)]
    val result = inputData.toDS()
      .select($"_1" as "str", $"_2" as "num")
      .dropDuplicates()
      .groupBy("str")
      .agg(sum("num"))
      .as[(String, Long)]

    testStream(result, Complete)(
      AddData(inputData, "a" -> 1),
      CheckLastBatch("a" -> 1L),
      assertNumStateRows(total = Seq(1L, 1L), updated = Seq(1L, 1L)),
      AddData(inputData, "a" -> 1), // Dropped
      CheckLastBatch("a" -> 1L),
      assertNumStateRows(total = Seq(1L, 1L), updated = Seq(0L, 0L)),
      AddData(inputData, "a" -> 2),
      CheckLastBatch("a" -> 3L),
      assertNumStateRows(total = Seq(1L, 2L), updated = Seq(1L, 1L)),
      AddData(inputData, "b" -> 1),
      CheckLastBatch("a" -> 3L, "b" -> 1L),
      assertNumStateRows(total = Seq(2L, 3L), updated = Seq(1L, 1L))
    )
  }

  test("deduplicate with file sink") {
    withTempDir { output =>
      withTempDir { checkpointDir =>
        val outputPath = output.getAbsolutePath
        val inputData = MemoryStream[String]
        val result = inputData.toDS().dropDuplicates()
        val q = result.writeStream
          .format("parquet")
          .outputMode(Append)
          .option("checkpointLocation", checkpointDir.getPath)
          .start(outputPath)
        try {
          inputData.addData("a")
          q.processAllAvailable()
          checkDataset(spark.read.parquet(outputPath).as[String], "a")

          inputData.addData("a") // Dropped
          q.processAllAvailable()
          checkDataset(spark.read.parquet(outputPath).as[String], "a")

          inputData.addData("b")
          q.processAllAvailable()
          checkDataset(spark.read.parquet(outputPath).as[String], "a", "b")
        } finally {
          q.stop()
        }
      }
    }
  }

  test("SPARK-19841: watermarkPredicate should filter based on keys") {
    val input = MemoryStream[(Int, Int)]
    val df = input.toDS.toDF("time", "id")
      .withColumn("time", $"time".cast("timestamp"))
      .withWatermark("time", "1 second")
      .dropDuplicates("id", "time") // Change the column positions
      .select($"id")
    testStream(df)(
      AddData(input, 1 -> 1, 1 -> 1, 1 -> 2),
      CheckLastBatch(1, 2),
      AddData(input, 1 -> 1, 2 -> 3, 2 -> 4),
      CheckLastBatch(3, 4),
      AddData(input, 1 -> 0, 1 -> 1, 3 -> 5, 3 -> 6), // Drop (1 -> 0, 1 -> 1) due to watermark
      CheckLastBatch(5, 6),
      AddData(input, 1 -> 0, 4 -> 7), // Drop (1 -> 0) due to watermark
      CheckLastBatch(7)
    )
  }

  test("SPARK-21546: dropDuplicates should ignore watermark when it's not a key") {
    val input = MemoryStream[(Int, Int)]
    val df = input.toDS.toDF("id", "time")
      .withColumn("time", $"time".cast("timestamp"))
      .withWatermark("time", "1 second")
      .dropDuplicates("id")
      .select($"id", $"time".cast("long"))
    testStream(df)(
      AddData(input, 1 -> 1, 1 -> 2, 2 -> 2),
      CheckLastBatch(1 -> 1, 2 -> 2)
    )
  }
}
