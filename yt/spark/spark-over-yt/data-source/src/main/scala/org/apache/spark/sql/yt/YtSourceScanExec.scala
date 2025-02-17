package org.apache.spark.sql.yt

import org.apache.hadoop.fs.Path
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.catalog.BucketSpec
import org.apache.spark.sql.catalyst.expressions.{And, Ascending, Attribute, AttributeReference, BoundReference, Expression, PlanExpression, Predicate, SortOrder, UnsafeProjection}
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.catalyst.plans.physical.{HashPartitioning, Partitioning, UnknownPartitioning}
import org.apache.spark.sql.catalyst.{InternalRow, TableIdentifier}
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.datasources.parquet.{ParquetFileFormat => ParquetSource}
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.v2.{YtFilePartition, YtReaderOptions}
import org.apache.spark.sql.vectorized.{ColumnarBatch, YtFileFormat}
import org.apache.spark.util.collection.BitSet
import tech.ytsaurus.spyt.format.conf.YtTableSparkSettings
import tech.ytsaurus.spyt.fs.{YtDynamicPath, YtPath, YtStaticPath}

import java.util.concurrent.TimeUnit.NANOSECONDS
import scala.collection.mutable.HashMap

case class YtSourceScanExec(
                             @transient relation: HadoopFsRelation,
                             output: Seq[Attribute],
                             requiredSchema: StructType,
                             partitionFilters: Seq[Expression],
                             optionalBucketSet: Option[BitSet],
                             dataFilters: Seq[Expression],
                             override val tableIdentifier: Option[TableIdentifier])
  extends DataSourceScanExec {

  lazy val optimizedForScan: Boolean = relation.fileFormat match {
    case yf: YtFileFormat =>
      relation.location.asInstanceOf[InMemoryFileIndex]
        .allFiles().forall { fileStatus =>
        YtPath.fromPath(fileStatus.getPath) match {
          case _: YtDynamicPath => false
          case yp: YtStaticPath => yp.optimizedForScan
          case _ => false
        }
      }
    case _ => false
  }

  lazy val relationOptions: Map[String, String] = relation.options + (
    YtTableSparkSettings.OptimizedForScan.name -> optimizedForScan.toString
    )

  // Note that some vals referring the file-based relation are lazy intentionally
  // so that this plan can be canonicalized on executor side too. See SPARK-23731.
  override lazy val supportsColumnar: Boolean = {
    relation.fileFormat match {
      case yf: YtFileFormat => YtReaderOptions.supportBatch(
        StructType.fromAttributes(output), relationOptions, relation.sparkSession.sqlContext.conf
      )
      case f => f.supportBatch(relation.sparkSession, StructType.fromAttributes(output))
    }
  }

  private lazy val needsUnsafeRowConversion: Boolean = {
    relation.fileFormat match {
      case _: ParquetSource =>
        SparkSession.getActiveSession.get.sessionState.conf.parquetVectorizedReaderEnabled
      //case yt: YtFileFormat => yt.canReadBatch()
      case _ => false
    }
  }

  override def vectorTypes: Option[Seq[String]] =
    relation.fileFormat.vectorTypes(
      requiredSchema = requiredSchema,
      partitionSchema = relation.partitionSchema,
      relation.sparkSession.sessionState.conf)

  private lazy val driverMetrics: HashMap[String, Long] = HashMap.empty

  /**
   * Send the driver-side metrics. Before calling this function, selectedPartitions has
   * been initialized. See SPARK-26327 for more details.
   */
  private def sendDriverMetrics(): Unit = {
    driverMetrics.foreach(e => metrics(e._1).add(e._2))
    val executionId = sparkContext.getLocalProperty(SQLExecution.EXECUTION_ID_KEY)
    SQLMetrics.postDriverMetricUpdates(sparkContext, executionId,
      metrics.filter(e => driverMetrics.contains(e._1)).values.toSeq)
  }

  private def isDynamicPruningFilter(e: Expression): Boolean =
    e.find(_.isInstanceOf[PlanExpression[_]]).isDefined

  @transient private lazy val selectedPartitions: Array[PartitionDirectory] = {
    val optimizerMetadataTimeNs = relation.location.metadataOpsTimeNs.getOrElse(0L)
    val startTime = System.nanoTime()
    val ret =
      relation.location.listFiles(
        partitionFilters.filterNot(isDynamicPruningFilter), dataFilters)
    if (relation.partitionSchemaOption.isDefined) {
      driverMetrics("numPartitions") = ret.length
    }
    setFilesNumAndSizeMetric(ret, true)
    val timeTakenMs = NANOSECONDS.toMillis(
      (System.nanoTime() - startTime) + optimizerMetadataTimeNs)
    driverMetrics("metadataTime") = timeTakenMs
    ret
    }.toArray

  // We can only determine the actual partitions at runtime when a dynamic partition filter is
  // present. This is because such a filter relies on information that is only available at run
  // time (for instance the keys used in the other side of a join).
  @transient private lazy val dynamicallySelectedPartitions: Array[PartitionDirectory] = {
    val dynamicPartitionFilters = partitionFilters.filter(isDynamicPruningFilter)

    if (dynamicPartitionFilters.nonEmpty) {
      val startTime = System.nanoTime()
      // call the file index for the files matching all filters except dynamic partition filters
      val predicate = dynamicPartitionFilters.reduce(And)
      val partitionColumns = relation.partitionSchema
      val boundPredicate = Predicate.create(predicate.transform {
        case a: AttributeReference =>
          val index = partitionColumns.indexWhere(a.name == _.name)
          BoundReference(index, partitionColumns(index).dataType, nullable = true)
      }, Nil)
      val ret = selectedPartitions.filter(p => boundPredicate.eval(p.values))
      setFilesNumAndSizeMetric(ret, false)
      val timeTakenMs = (System.nanoTime() - startTime) / 1000 / 1000
      driverMetrics("pruningTime") = timeTakenMs
      ret
    } else {
      selectedPartitions
    }
  }

  /**
   * [[partitionFilters]] can contain subqueries whose results are available only at runtime so
   * accessing [[selectedPartitions]] should be guarded by this method during planning
   */
  private def hasPartitionsAvailableAtRunTime: Boolean = {
    partitionFilters.exists(ExecSubqueryExpression.hasSubquery)
  }

  private def toAttribute(colName: String): Option[Attribute] =
    output.find(_.name == colName)

  // exposed for testing
  lazy val bucketedScan: Boolean = {
    if (relation.sparkSession.sessionState.conf.bucketingEnabled && relation.bucketSpec.isDefined) {
      val spec = relation.bucketSpec.get
      val bucketColumns = spec.bucketColumnNames.flatMap(n => toAttribute(n))
      bucketColumns.size == spec.bucketColumnNames.size
    } else {
      false
    }
  }

  override lazy val (outputPartitioning, outputOrdering): (Partitioning, Seq[SortOrder]) = {
    if (bucketedScan) {
      // For bucketed columns:
      // -----------------------
      // `HashPartitioning` would be used only when:
      // 1. ALL the bucketing columns are being read from the table
      //
      // For sorted columns:
      // ---------------------
      // Sort ordering should be used when ALL these criteria's match:
      // 1. `HashPartitioning` is being used
      // 2. A prefix (or all) of the sort columns are being read from the table.
      //
      // Sort ordering would be over the prefix subset of `sort columns` being read
      // from the table.
      // eg.
      // Assume (col0, col2, col3) are the columns read from the table
      // If sort columns are (col0, col1), then sort ordering would be considered as (col0)
      // If sort columns are (col1, col0), then sort ordering would be empty as per rule #2
      // above
      val spec = relation.bucketSpec.get
      val bucketColumns = spec.bucketColumnNames.flatMap(n => toAttribute(n))
      val partitioning = HashPartitioning(bucketColumns, spec.numBuckets)
      val sortColumns =
        spec.sortColumnNames.map(x => toAttribute(x)).takeWhile(x => x.isDefined).map(_.get)
      val shouldCalculateSortOrder =
        conf.getConf(SQLConf.LEGACY_BUCKETED_TABLE_SCAN_OUTPUT_ORDERING) &&
          sortColumns.nonEmpty &&
          !hasPartitionsAvailableAtRunTime

      val sortOrder = if (shouldCalculateSortOrder) {
        // In case of bucketing, its possible to have multiple files belonging to the
        // same bucket in a given relation. Each of these files are locally sorted
        // but those files combined together are not globally sorted. Given that,
        // the RDD partition will not be sorted even if the relation has sort columns set
        // Current solution is to check if all the buckets have a single file in it

        val files = selectedPartitions.flatMap(partition => partition.files)
        val bucketToFilesGrouping =
          files.map(_.getPath.getName).groupBy(file => BucketingUtils.getBucketId(file))
        val singleFilePartitions = bucketToFilesGrouping.forall(p => p._2.length <= 1)

        if (singleFilePartitions) {
          // TODO Currently Spark does not support writing columns sorting in descending order
          // so using Ascending order. This can be fixed in future
          sortColumns.map(attribute => SortOrder(attribute, Ascending))
        } else {
          Nil
        }
      } else {
        Nil
      }
      (partitioning, sortOrder)
    } else {
      (UnknownPartitioning(0), Nil)
    }
  }

  @transient
  private lazy val pushedDownFilters = {
    val supportNestedPredicatePushdown = DataSourceUtils.supportNestedPredicatePushdown(relation)
    dataFilters.flatMap(DataSourceStrategy.translateFilter(_, supportNestedPredicatePushdown))
  }

  override lazy val metadata: Map[String, String] = {
    def seqToString(seq: Seq[Any]) = seq.mkString("[", ", ", "]")
    val location = relation.location
    val locationDesc =
      location.getClass.getSimpleName + seqToString(location.rootPaths)
    val metadata =
      Map(
        "Format" -> relation.fileFormat.toString,
        "ReadSchema" -> requiredSchema.catalogString,
        "Batched" -> supportsColumnar.toString,
        "PartitionFilters" -> seqToString(partitionFilters),
        "PushedFilters" -> seqToString(pushedDownFilters),
        "DataFilters" -> seqToString(dataFilters),
        "Location" -> locationDesc)

    val withSelectedBucketsCount = relation.bucketSpec.map { spec =>
      val numSelectedBuckets = optionalBucketSet.map { b =>
        b.cardinality()
      } getOrElse {
        spec.numBuckets
      }
      metadata + ("SelectedBucketsCount" ->
        s"$numSelectedBuckets out of ${spec.numBuckets}")
    } getOrElse {
      metadata
    }

    withSelectedBucketsCount
  }

  override def verboseStringWithOperatorId(): String = {
    val metadataStr = metadata.toSeq.sorted.filterNot {
      case (_, value) if (value.isEmpty || value.equals("[]")) => true
      case (key, _) if (key.equals("DataFilters") || key.equals("Format")) => true
      case (_, _) => false
    }.map {
      case (key, _) if (key.equals("Location")) =>
        val location = relation.location
        val numPaths = location.rootPaths.length
        val abbreviatedLoaction = if (numPaths <= 1) {
          location.rootPaths.mkString("[", ", ", "]")
        } else {
          "[" + location.rootPaths.head + s", ... ${numPaths - 1} entries]"
        }
        s"$key: ${location.getClass.getSimpleName} ${redact(abbreviatedLoaction)}"
      case (key, value) => s"$key: ${redact(value)}"
    }

    s"""
       |$formattedNodeName
       |${ExplainUtils.generateFieldString("Output", output)}
       |${metadataStr.mkString("\n")}
       |""".stripMargin
  }

  lazy val inputRDD: RDD[InternalRow] = {
    val readFile: (PartitionedFile) => Iterator[InternalRow] =
      relation.fileFormat.buildReaderWithPartitionValues(
        sparkSession = relation.sparkSession,
        dataSchema = relation.dataSchema,
        partitionSchema = relation.partitionSchema,
        requiredSchema = requiredSchema,
        filters = pushedDownFilters,
        options = relationOptions,
        hadoopConf = relation.sparkSession.sessionState.newHadoopConfWithOptions(relationOptions))

    val readRDD = if (bucketedScan) {
      createBucketedReadRDD(relation.bucketSpec.get, readFile, dynamicallySelectedPartitions,
        relation)
    } else {
      createNonBucketedReadRDD(readFile, dynamicallySelectedPartitions, relation)
    }
    sendDriverMetrics()
    readRDD
  }

  override def inputRDDs(): Seq[RDD[InternalRow]] = {
    inputRDD :: Nil
  }

  /** SQL metrics generated only for scans using dynamic partition pruning. */
  private lazy val staticMetrics = if (partitionFilters.filter(isDynamicPruningFilter).nonEmpty) {
    Map("staticFilesNum" -> SQLMetrics.createMetric(sparkContext, "static number of files read"),
      "staticFilesSize" -> SQLMetrics.createSizeMetric(sparkContext, "static size of files read"))
  } else {
    Map.empty[String, SQLMetric]
  }

  /** Helper for computing total number and size of files in selected partitions. */
  private def setFilesNumAndSizeMetric(
                                        partitions: Seq[PartitionDirectory],
                                        static: Boolean): Unit = {
    val filesNum = partitions.map(_.files.size.toLong).sum
    val filesSize = partitions.map(_.files.map(_.getLen).sum).sum
    if (!static || partitionFilters.filter(isDynamicPruningFilter).isEmpty) {
      driverMetrics("numFiles") = filesNum
      driverMetrics("filesSize") = filesSize
    } else {
      driverMetrics("staticFilesNum") = filesNum
      driverMetrics("staticFilesSize") = filesSize
    }
  }

  override lazy val metrics = Map(
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"),
    "numFiles" -> SQLMetrics.createMetric(sparkContext, "number of files read"),
    "metadataTime" -> SQLMetrics.createTimingMetric(sparkContext, "metadata time"),
    "filesSize" -> SQLMetrics.createSizeMetric(sparkContext, "size of files read"),
    "pruningTime" ->
      SQLMetrics.createTimingMetric(sparkContext, "dynamic partition pruning time")
  ) ++ {
    // Tracking scan time has overhead, we can't afford to do it for each row, and can only do
    // it for each batch.
    if (supportsColumnar) {
      Some("scanTime" -> SQLMetrics.createTimingMetric(sparkContext, "scan time"))
    } else {
      None
    }
  } ++ {
    if (relation.partitionSchemaOption.isDefined) {
      Some("numPartitions" -> SQLMetrics.createMetric(sparkContext, "number of partitions read"))
    } else {
      None
    }
  } ++ staticMetrics

  protected override def doExecute(): RDD[InternalRow] = {
    val numOutputRows = longMetric("numOutputRows")
    if (needsUnsafeRowConversion) {
      inputRDD.mapPartitionsWithIndexInternal { (index, iter) =>
        val toUnsafe = UnsafeProjection.create(schema)
        toUnsafe.initialize(index)
        iter.map { row =>
          numOutputRows += 1
          toUnsafe(row)
        }
      }
    } else {
      inputRDD.mapPartitionsInternal { iter =>
        iter.map { row =>
          numOutputRows += 1
          row
        }
      }
    }
  }

  protected override def doExecuteColumnar(): RDD[ColumnarBatch] = {
    val numOutputRows = longMetric("numOutputRows")
    val scanTime = longMetric("scanTime")
    inputRDD.asInstanceOf[RDD[ColumnarBatch]].mapPartitionsInternal { batches =>
      new Iterator[ColumnarBatch] {

        override def hasNext: Boolean = {
          // The `FileScanRDD` returns an iterator which scans the file during the `hasNext` call.
          val startNs = System.nanoTime()
          val res = batches.hasNext
          scanTime += NANOSECONDS.toMillis(System.nanoTime() - startNs)
          res
        }

        override def next(): ColumnarBatch = {
          val batch = batches.next()
          numOutputRows += batch.numRows()
          batch
        }
      }
    }
  }

  override val nodeNamePrefix: String = "File"

  /**
   * Create an RDD for bucketed reads.
   * The non-bucketed variant of this function is [[createNonBucketedReadRDD]].
   *
   * The algorithm is pretty simple: each RDD partition being returned should include all the files
   * with the same bucket id from all the given Hive partitions.
   *
   * @param bucketSpec the bucketing spec.
   * @param readFile a function to read each (part of a) file.
   * @param selectedPartitions Hive-style partition that are part of the read.
   * @param fsRelation [[HadoopFsRelation]] associated with the read.
   */
  private def createBucketedReadRDD(
                                     bucketSpec: BucketSpec,
                                     readFile: (PartitionedFile) => Iterator[InternalRow],
                                     selectedPartitions: Array[PartitionDirectory],
                                     fsRelation: HadoopFsRelation): RDD[InternalRow] = {
    logInfo(s"Planning with ${bucketSpec.numBuckets} buckets")
    val filesGroupedToBuckets =
      selectedPartitions.flatMap { p =>
        p.files.map { f =>
          PartitionedFileUtil.getPartitionedFile(f, f.getPath, p.values)
        }
      }.groupBy { f =>
        BucketingUtils
          .getBucketId(new Path(f.filePath).getName)
          .getOrElse(sys.error(s"Invalid bucket file ${f.filePath}"))
      }

    val prunedFilesGroupedToBuckets = if (optionalBucketSet.isDefined) {
      val bucketSet = optionalBucketSet.get
      filesGroupedToBuckets.filter {
        f => bucketSet.get(f._1)
      }
    } else {
      filesGroupedToBuckets
    }

    val filePartitions = Seq.tabulate(bucketSpec.numBuckets) { bucketId =>
      FilePartition(bucketId, prunedFilesGroupedToBuckets.getOrElse(bucketId, Array.empty))
    }

    new FileScanRDD(fsRelation.sparkSession, readFile, filePartitions)
  }

  /**
   * Create an RDD for non-bucketed reads.
   * The bucketed variant of this function is [[createBucketedReadRDD]].
   *
   * @param readFile a function to read each (part of a) file.
   * @param selectedPartitions Hive-style partition that are part of the read.
   * @param fsRelation [[HadoopFsRelation]] associated with the read.
   */
  private def createNonBucketedReadRDD(
      readFile: (PartitionedFile) => Iterator[InternalRow],
      selectedPartitions: Array[PartitionDirectory],
      fsRelation: HadoopFsRelation): RDD[InternalRow] = {
    val maxSplitBytes = YtFilePartition.maxSplitBytes(fsRelation.sparkSession, selectedPartitions,
      fsRelation.options.get("readParallelism").map(_.toInt))
    val files = selectedPartitions.flatMap { partition =>
      partition.files.flatMap { file =>
        // getPath() is very expensive so we only want to call it once in this block:
        val filePath = file.getPath
        val isSplitable = relation.fileFormat.isSplitable(
          relation.sparkSession, relationOptions, filePath)
        YtFilePartition.splitFiles(
          sparkSession = relation.sparkSession,
          file = file,
          filePath = filePath,
          isSplitable = isSplitable,
          maxSplitBytes = maxSplitBytes,
          partitionValues = partition.values
        )
      }
    }.sortBy(_.length)(implicitly[Ordering[Long]].reverse)

    val partitions =
      FilePartition.getFilePartitions(relation.sparkSession, files, maxSplitBytes)

    new FileScanRDD(fsRelation.sparkSession, readFile, partitions)
  }

  override def doCanonicalize(): FileSourceScanExec = {
    FileSourceScanExec(
      relation,
      output.map(QueryPlan.normalizeExpressions(_, output)),
      requiredSchema,
      QueryPlan.normalizePredicates(partitionFilters, output),
      optionalBucketSet,
      /*optionalNumCoalescedBuckets*/ None,
      QueryPlan.normalizePredicates(dataFilters, output),
      /*tableIdentifier*/ None)
  }
}
