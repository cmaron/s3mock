package io.findify.s3mock

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.alpakka.s3.scaladsl.S3Client
import better.files.File
import com.amazonaws.auth.{BasicAWSCredentials, DefaultAWSCredentialsProviderChain}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client, AmazonS3ClientBuilder}
import com.amazonaws.services.s3.model.S3Object
import com.amazonaws.services.s3.transfer.{TransferManager, TransferManagerBuilder}
import com.typesafe.config.{Config, ConfigFactory}
import io.findify.s3mock.provider.{FileProvider, InMemoryProvider}

import scala.collection.JavaConverters._
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.io.Source

/**
  * Created by shutty on 8/9/16.
  */
trait S3MockTest extends FlatSpec with Matchers with BeforeAndAfterAll {
  private val workDir = File.newTemporaryDirectory().pathAsString
  private val fileBasedPort = 8001
  private val fileSystem = ActorSystem.create("testfile", configFor("localhost", fileBasedPort))
  private val fileMat = ActorMaterializer()(fileSystem)
  private val fileBasedS3 = clientFor("localhost", fileBasedPort)
  private val fileBasedServer = new S3Mock(fileBasedPort, new FileProvider(workDir))
  private val fileBasedTransferManager: TransferManager = TransferManagerBuilder.standard().withS3Client(fileBasedS3).build()
  private val fileBasedAlpakkaClient = S3Client()(fileSystem, fileMat)

  private val inMemoryPort = 8002
  private val inMemorySystem = ActorSystem.create("testram", configFor("localhost", inMemoryPort))
  private val inMemoryMat = ActorMaterializer()(inMemorySystem)
  private val inMemoryS3 = clientFor("localhost", inMemoryPort)
  private val inMemoryServer = new S3Mock(inMemoryPort, new InMemoryProvider)
  private val inMemoryTransferManager: TransferManager = TransferManagerBuilder.standard().withS3Client(inMemoryS3).build()
  private val inMemoryBasedAlpakkaClient = S3Client()(inMemorySystem, inMemoryMat)

  case class Fixture(server: S3Mock, client: AmazonS3, tm: TransferManager, name: String, port: Int, alpakka: S3Client, system: ActorSystem, mat: Materializer)
  val fixtures = List(
    Fixture(fileBasedServer, fileBasedS3, fileBasedTransferManager, "file based S3Mock", fileBasedPort, fileBasedAlpakkaClient, fileSystem, fileMat),
    Fixture(inMemoryServer, inMemoryS3, inMemoryTransferManager, "in-memory S3Mock", inMemoryPort, inMemoryBasedAlpakkaClient, inMemorySystem, inMemoryMat)
  )

  def behaviour(fixture: => Fixture) : Unit

  for (fixture <- fixtures) {
    fixture.name should behave like behaviour(fixture)
  }

  override def beforeAll = {
    if (!File(workDir).exists) File(workDir).createDirectory()
    fileBasedServer.start
    inMemoryServer.start
    super.beforeAll
  }
  override def afterAll = {
    super.afterAll
    inMemoryServer.stop
    fileBasedServer.stop
    inMemoryTransferManager.shutdownNow()
    Await.result(fileSystem.terminate(), Duration.Inf)
    Await.result(inMemorySystem.terminate(), Duration.Inf)
    File(workDir).delete()
  }
  def getContent(s3Object: S3Object): String = Source.fromInputStream(s3Object.getObjectContent, "UTF-8").mkString

  def clientFor(host: String, port: Int): AmazonS3 = {
    val endpoint = new EndpointConfiguration(s"http://$host:$port", "us-east-1")
    AmazonS3ClientBuilder.standard()
      .withPathStyleAccessEnabled(true)
      .withCredentials(new DefaultAWSCredentialsProviderChain())
      .withEndpointConfiguration(endpoint)
      .build()
  }

  def configFor(host: String, port: Int): Config = {
    ConfigFactory.parseMap(Map(
      "akka.stream.alpakka.s3.proxy.host" -> host,
      "akka.stream.alpakka.s3.proxy.port" -> port,
      "akka.stream.alpakka.s3.proxy.secure" -> false,
      "akka.stream.alpakka.s3.path-style-access" -> true
    ).asJava)

  }

}

