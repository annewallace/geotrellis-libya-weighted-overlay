/*
 * Copyright (c) 2016 Azavea.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.azavea.geotrellis.weighted

import geotrellis.raster.histogram._
import geotrellis.raster.io._
import geotrellis.raster.Tile
import geotrellis.spark._
import geotrellis.spark.etl.config._
import geotrellis.spark.etl.{Etl, OutputPlugin}
import geotrellis.spark.io._
import geotrellis.spark.io.accumulo._
import geotrellis.spark.io.cassandra._
import geotrellis.spark.io.file._
import geotrellis.spark.io.hadoop._
import geotrellis.spark.io.hbase._
import geotrellis.spark.io.s3._
import geotrellis.spark.SpatialKey
import geotrellis.spark.util.SparkUtils
import geotrellis.vector.ProjectedExtent

import org.apache.spark.SparkConf


object Ingest extends App {
  implicit val sc = SparkUtils.createSparkContext("GeoTrellis ETL SinglebandIngest", new SparkConf(true))

  try {
    Etl.ingest[ProjectedExtent, SpatialKey, Tile](args)

    val conf = EtlConf(args).head
    val output = conf.output
    val outputProfile = conf.outputProfile
    val backend = output.backend
    val outputPlugin =
      Etl.defaultModules.reduce(_ union _)
        .findSubclassOf[OutputPlugin[SpatialKey, Tile, TileLayerMetadata[SpatialKey]]]
        .find { _.suitableFor(output.backend.`type`.name) }
        .getOrElse(sys.error(s"Unable to find output module of type '${output.backend.`type`.name}'"))
    val attributeStore = outputPlugin.attributes(conf)
    val layerNames = attributeStore.layerIds.map(_.name).distinct
    val reader = (attributeStore, outputProfile) match {
      case (as: HadoopAttributeStore, _) => HadoopLayerReader(as)
      case (as: FileAttributeStore, _) => FileLayerReader(as)
      case (as: S3AttributeStore, _) => S3LayerReader(as)
      case (as: AccumuloAttributeStore, Some(opp: AccumuloProfile)) =>
        implicit val instance = opp.getInstance
        AccumuloLayerReader(as)
      case (as: CassandraAttributeStore, Some(opp: CassandraProfile)) =>
        implicit val instance = opp.getInstance
        CassandraLayerReader(as)
      case (as: HBaseAttributeStore, Some(opp: HBaseProfile)) =>
        implicit val instance = opp.getInstance
        HBaseLayerReader(as)
      case _ => throw new Exception
    }

    layerNames.foreach({ layerName =>
      val maxZoom = attributeStore.layerIds
        .filter(_.name == layerName)
        .map(_.zoom)
        .reduce(math.max)
      val layerId = LayerId(layerName, math.min(9, maxZoom))
      val histogram: StreamingHistogram =
        reader
          .read[SpatialKey, Tile, TileLayerMetadata[SpatialKey]](LayerId(layerName, 8))
          .mapPartitions({ partition =>
            Iterator(partition
              .map({ case (_, tile) => StreamingHistogram.fromTile(tile, 1<<9) })
              .reduce(_ + _)) },
            preservesPartitioning = true)
          .reduce(_ + _)

      attributeStore.write(
        LayerId(layerName, 0),
        "histogram",
        histogram.asInstanceOf[Histogram[Double]])
    })
  } finally {
    sc.stop()
  }
}
