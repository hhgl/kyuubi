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

package yaooqinn.kyuubi.server

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.hadoop.hive.cli.OptionsProcessor
import org.apache.spark.{KyuubiConf, SparkConf, SparkUtils}

import yaooqinn.kyuubi.Logging
import yaooqinn.kyuubi.ha.HighAvailabilityUtils
import yaooqinn.kyuubi.service.CompositeService
import yaooqinn.kyuubi.utils.VersionUtils

/**
 * Main entrance of Kyuubi Server
 */
private[kyuubi] class KyuubiServer private(name: String)
  extends CompositeService(name) with Logging {

  private[this] var _beService: BackendService = _
  def beService: BackendService = _beService
  private[this] var _feService: FrontendService = _
  def feService: FrontendService = _feService

  private[this] val started = new AtomicBoolean(false)

  def this() = this(classOf[KyuubiServer].getSimpleName)

  override def init(conf: SparkConf): Unit = synchronized {
    this.conf = conf
    _beService = new BackendService()
    _feService = new FrontendService(_beService)
    addService(_beService)
    addService(_feService)
    super.init(conf)
    SparkUtils.addShutdownHook {
      () => this.stop()
    }
  }

  override def start(): Unit = {
    super.start()
    started.set(true)
  }

  override def stop(): Unit = {
    if (started.getAndSet(false)) {
      super.stop()
    }
  }
}

object KyuubiServer extends Logging {

  def main(args: Array[String]): Unit = {
    SparkUtils.initDaemon(logger)
    val op = new OptionsProcessor()
    if (!op.process_stage1(args)) {
      System.exit(1)
    }

    val conf = new SparkConf(loadDefaults = true)
    setupCommonConfig(conf)

    try {
      val server = new KyuubiServer()
      server.init(conf)
      server.start()
      info(server.getName + " started!")
      if (HighAvailabilityUtils.isSupportDynamicServiceDiscovery(conf)) {
        info(s"HA mode: start to add this ${server.getName} instance to Zookeeper...")
        HighAvailabilityUtils.addServerInstanceToZooKeeper(server)
      }
    } catch {
      case e: Exception =>
        error("Error starting Kyuubi Server", e)
        System.exit(-1)
    }
  }

  /**
   *
   * @param conf the default [[SparkConf]]
   */
  private[this] def setupCommonConfig(conf: SparkConf): Unit = {
    if (!conf.getBoolean("spark.driver.userClassPathFirst", defaultValue = false)) {
      error("SET spark.driver.userClassPathFirst to true")
      System.exit(-1)
    }
    // overwrite later for each SparkC
    conf.set("spark.app.name", classOf[KyuubiServer].getSimpleName)
    // avoid max port retries reached
    conf.set("spark.ui.port", "0")
    conf.set("spark.driver.allowMultipleContexts", "true")
    conf.set("spark.sql.catalogImplementation", "hive")

    // When use User ClassPath First, will cause ClassNotFound exception,
    // see https://github.com/apache/spark/pull/20145
    if (!VersionUtils.isSpark23OrLater()) {
      conf.set("spark.sql.hive.metastore.jars",
        sys.env("SPARK_HOME") + File.separator + "jars" + File.separator + "*")
    }
    // For the server itself the deploy mode could be either client or cluster,
    // but for the later [[SparkContext]] must be set to client mode
    conf.set("spark.submit.deployMode", "client")

    // Set missing Kyuubi configs to SparkConf
    KyuubiConf.getAllDefaults.foreach(kv => conf.setIfMissing(kv._1, kv._2))
  }
}