package uis.cipsi.rdd.opentsdb

import java.util
import java.util.Map.Entry

import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.spark.SparkContext
import org.apache.hadoop.conf.Configuration
import org.apache.spark.rdd.RDD
import scala.collection.mutable.ArrayBuffer
import java.util.Arrays
import java.nio.ByteBuffer
import org.apache.hadoop.hbase.client.Result
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import scala.Array.canBuildFrom

class SparkTSDBQuery(sMaster: String, zkQuorum: String, zkClientPort: String) extends Serializable {
  private val sparkMaster = sMaster
  private val zookeeperQuorum = zkQuorum
  private val zookeeperClientPort = zkClientPort
  private val format_data = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm")

  def generalConfig(zookeeperQuorum: String, zookeeperClientPort: String): Configuration = {
    //Create configuration
    val config = HBaseConfiguration.create()
    //config.addResource(System.getenv("HBASE_HOME") + "/conf/hbase-site.xml")      
    config.set("hbase.zookeeper.quorum", zookeeperQuorum)
    config.set("hbase.zookeeper.property.clientPort", zookeeperClientPort)
    config.set("hbase.mapreduce.scan.column.family", "t")
    config
  }

  //Prepares the configuration for querying the TSDB-UID table and extracting UIDs for metrics and tags
  def tsdbuidConfig(zookeeperQuorum: String, zookeeperClientPort: String, columnQ: Array[String]) = {
    val config = generalConfig(zookeeperQuorum, zookeeperClientPort)
    config.set("hbase.mapreduce.inputtable", "tsdb-uid")
    config.set(TSDBInputFormat.TSDB_UIDS, columnQ.mkString("|"))
    config
  }

  //Prepares the configuration for querying the TSDB table
  def tsdbConfig(zookeeperQuorum: String, zookeeperClientPort: String,
                 metricUID: Array[Byte], tagkv: Option[Array[Byte]] = None,
                 startdate: Option[String] = None, enddate: Option[String] = None) = {

    val config = generalConfig(zookeeperQuorum, zookeeperClientPort)
    config.set("hbase.mapreduce.inputtable", "tsdb")

    config.set(TSDBInputFormat.METRICS, bytes2hex(metricUID))
    if (tagkv != None) {
      config.set(TSDBInputFormat.TAGKV, bytes2hex(tagkv.get))
    }

    def getTime(date: String): String = {
      val MAX_TIMESPAN = 3600
      def getBaseTime(date: String): Int = {
        val timestamp = format_data.parse(date).getTime()
        val base_time = ((timestamp / 1000) - ((timestamp / 1000) % MAX_TIMESPAN)).toInt
        base_time
      }

      val baseTime = getBaseTime(date)
      println(s"BaseTime:($date)" + baseTime)
      val intByteArray: Array[Byte] = ByteBuffer.allocate(Integer.BYTES).putInt(baseTime).array()
      bytes2hex(intByteArray)
    }

    if (startdate != None) {
      println("StartDate: " + getTime(startdate.get))
      config.set(TSDBInputFormat.SCAN_TIMERANGE_START, getTime(startdate.get))
    }

    if (enddate != None) {
      println("EndDate: " + getTime(enddate.get))
      config.set(TSDBInputFormat.SCAN_TIMERANGE_END, getTime(enddate.get))
    }

    config
  }

  //Converts Bytes to Hex (see: https://gist.github.com/tmyymmt/3727124)
  def bytes2hex(bytes: Array[Byte]): String = {
    val sep = "\\x"
    val regex = sep + bytes.map("%02x".format(_).toUpperCase()).mkString(sep)
    regex
  }

  //Prepare for any or all tag values
  def prepareTagKeyValue(tagkv: String): String = {
    var rtn = ""
    val size = tagkv.length()
    val keyValueGroupSize = 24 //(6key+6value+(2*6)\x)        	

    for (i <- 0 until size by keyValueGroupSize) {
      rtn += tagkv.slice(i, i + keyValueGroupSize) + "|"
    }
    rtn = rtn.slice(0, rtn.length() - 1)

    rtn
  }

  def parseQualifier(arr: Array[Byte], startIdx: Int, endIdx: Int) = {
    val length = endIdx - startIdx
    var value = 0
    for (i <- startIdx until endIdx)
      value = value | (arr(i).toInt << ((length - 1 - i) * 8))
    value
  }

  def is4BytesQualifier(qualifierBytes: Array[Byte]): Boolean = {
    val firstByte = qualifierBytes(0)
    ((firstByte >> 4) & 15) == 15 // first 4 bytes = 1111
  }

  def parseValue(valueBytes: Array[Byte], index: Int, isFloat: Boolean, valueByteLength: Int): Float = {
    val bufferArr = ByteBuffer.wrap(Arrays.copyOfRange(valueBytes, index, index + valueByteLength))
    if (isFloat) {
      if (valueByteLength == 4) bufferArr.getFloat()
      else if (valueByteLength == 8) bufferArr.getDouble().toFloat
      else throw new IllegalArgumentException(s"Can't parse Value (isFloat:$isFloat, valueByteLength:$valueByteLength")
    } else {
      if (valueByteLength == 1) valueBytes(index).toFloat
      else if (valueByteLength == 2) bufferArr.getShort.toFloat
      else if (valueByteLength == 4) bufferArr.getInt.toFloat
      else if (valueByteLength == 8) bufferArr.getLong.toFloat
      else throw new IllegalArgumentException(s"Can't parse Value (isFloat:$isFloat, valueByteLength:$valueByteLength")
    }
  }

  def parseValues(qualifierBytes: Array[Byte], valueBytes: Array[Byte], basetime: Int, step: Int, getOffset: Int => Int,
                  result: ArrayBuffer[(Long, Float)]) = {

    var index = 0
    for (i <- 0 until qualifierBytes.length by step) {
      val qualifier = parseQualifier(qualifierBytes, i, step)

      val timeOffset = getOffset(qualifier)
      val isFloat = (((qualifier >> 3) & 1) == 1)
      val valueByteLength = (qualifier & 7) + 1

      val value = parseValue(valueBytes, index, isFloat, valueByteLength)
      result += ((basetime + timeOffset, value))

      index = index + valueByteLength
    }
  }

  def generateRDD(metricName: String, tagKeyValueMap: String, startdate: String, enddate: String, sc: SparkContext) = {
    println("Generating RDD...")

    val metric = metricName
    val tags = if (tagKeyValueMap.trim != "*") tagKeyValueMap.split(",").map(_.split("->")).map(l => (l(0).trim, l(1).trim)).toMap
    else Map[String, String]()

    val tsdbUID = sc.newAPIHadoopRDD(
      tsdbuidConfig(
        zookeeperQuorum,
        zookeeperClientPort,
        Array(metric, tags.map(_._1).mkString("|"), tags.map(_._2).mkString("|"))
      ),
      classOf[uis.cipsi.rdd.opentsdb.TSDBInputFormat],
      classOf[ImmutableBytesWritable],
      classOf[Result])

    println("tsdbUID: " + tsdbUID.count)

    val metricsUID = tsdbUID
      .map(l => l._2.getValue("id".getBytes(), "metrics".getBytes()))
      .filter(_ != null).collect //Since we will have only one metric uid

    println("MetricsUID: ")
    metricsUID.foreach(arr => println(arr.mkString(", ")))

    if (metricsUID.isEmpty) {
      println("Not Found: " + (if (metricsUID.length == 0) "Metric:" + metricName))
      System.exit(1)
    }

    val tagKUIDs = tsdbUID
      .map(l => (new String(l._1.copyBytes()), l._2.getValue("id".getBytes(), "tagk".getBytes())))
      .filter(_._2 != null).collect.toMap

    println("tagKUIDs: ")
    tagKUIDs.foreach(m => println(m._1 + " => " + m._2.mkString(", ")))

    val tagVUIDs = tsdbUID
      .map(l => (new String(l._1.copyBytes()), l._2.getValue("id".getBytes(), "tagv".getBytes())))
      .filter(_._2 != null).collect.toMap

    println("tagVUIDs: ")
    tagVUIDs.foreach(m => println(m._1 + " => " + m._2.mkString(", ")))

    val tagKKeys: Array[String] = tagKUIDs.keys.toArray
    val tagVKeys: Array[String] = tagVUIDs.keys.toArray

    // all tags must exist
    if (tags.size != tagKKeys.length  || tagKKeys.length != tagVKeys.length) {
      println("Can't find keys or values")
      // TODO try to print missing values
      System.exit(1)
    }

    val tagKV = tagKUIDs
      .map(k => (k._2, tagVUIDs(tags(k._1)))) // key(byte Array), value(byte Array)
      .map(l => (l._1 ++ l._2)) // key + value
      .toList
      .sorted(Ordering.by((_: Array[Byte]).toIterable))

    val tsdb: RDD[(ImmutableBytesWritable, Result)] = sc.newAPIHadoopRDD(
      tsdbConfig(
        zookeeperQuorum,
        zookeeperClientPort,
        metricsUID.last,
        if (tagKV.size != 0) Option(tagKV.flatten.toArray) else None,
        if (startdate != "*") Option(startdate) else None,
        if (enddate != "*") Option(enddate) else None
      ),
      classOf[uis.cipsi.rdd.opentsdb.TSDBInputFormat],
      classOf[org.apache.hadoop.hbase.io.ImmutableBytesWritable],
      classOf[org.apache.hadoop.hbase.client.Result])

    //Decoding retrieved data into RDD
    tsdb
      // columns from 3-7 (the base time)
      .map(kv => (Arrays.copyOfRange(kv._1.copyBytes(), 3, 7), kv._2.getFamilyMap("t".getBytes())))
      .map({
        kv =>
          val basetime: Int = ByteBuffer.wrap(kv._1).getInt
          val iterator: util.Iterator[Entry[Array[Byte], Array[Byte]]] = kv._2.entrySet().iterator()
          val row = new ArrayBuffer[(Long, Float)]

          while (iterator.hasNext()) {
            val next = iterator.next()

            val qualifierBytes: Array[Byte] = next.getKey()
            val valueBytes = next.getValue()

            // Column Quantifiers are stored as follows:
            // if num of bytes=2: 12 bits (delta timestamp value in sec) + 4 bits flag
            // if num of bytes=4: 4 bits flag(must be 1111) + 22 bits (delta timestamp value in ms) +
            //                       2 bits reserved + 4 bits flag
            // last 4 bits flag = (1 bit (int 0 | float 1) + 3 bits (length of value bytes - 1))

            // if num of bytes>4 & even: columns with compacted for the hour, with each qualifier having 2 bytes
            // TODO make sure that (qualifier only has 2 bytes)

            // if num of bytes is odd: Annotations or Other Objects

            if (qualifierBytes.length == 2) { // 2 bytes qualifier
              parseValues(qualifierBytes, valueBytes, basetime, 2, (x => x >> 4), row)

            } else if (qualifierBytes.length == 4 && is4BytesQualifier(qualifierBytes)) { // 4 bytes qualifier
              parseValues(qualifierBytes, valueBytes, basetime, 4, (x => (x << 4) >> 10), row)

            } else if (qualifierBytes.length % 2 == 0) { // compacted columns
              parseValues(qualifierBytes, valueBytes, basetime, 2, (x => x >> 4), row)

            } else {
              // TODO (Annotations or Other Objects)
              throw new Exception("Not Supported Yet")
            }
          }

          row
      }).flatMap(_.map(kv => (kv._1, kv._2)))

  }
}
