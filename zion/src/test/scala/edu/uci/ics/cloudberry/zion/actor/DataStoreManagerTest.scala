package edu.uci.ics.cloudberry.zion.actor

import java.util.concurrent.Executors

import akka.actor.{ActorRef, ActorRefFactory, Props}
import akka.testkit.TestProbe
import edu.uci.ics.cloudberry.zion.actor.DataStoreManager._
import edu.uci.ics.cloudberry.zion.common.Config
import edu.uci.ics.cloudberry.zion.model.datastore.{IDataConn, IQLGenerator, IQLGeneratorFactory}
import edu.uci.ics.cloudberry.zion.model.impl.{AQLGenerator, DataSetInfo, UnresolvedSchema}
import edu.uci.ics.cloudberry.zion.model.schema._
import edu.uci.ics.cloudberry.zion.model.util.MockConnClient
import org.joda.time.DateTime
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{JsArray, JsSuccess, Json}

import scala.concurrent.{ExecutionContext, Future}

class DataStoreManagerTest extends TestkitExample with SpecificationLike with MockConnClient {

  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

  sequential

  import edu.uci.ics.cloudberry.zion.model.impl.TestQuery._
  import org.mockito.Mockito._

  import scala.concurrent.duration._

  "DataManager" should {
    val sender = new TestProbe(system)
    val view = new TestProbe(system)
    val base = new TestProbe(system)
    val meta = new TestProbe(system)
    val metaDataSet = "metaDataSet"

    def testActorMaker(agentType: AgentType.Value,
                       context: ActorRefFactory,
                       actorName: String,
                       dbName: String,
                       dbSchema: Schema,
                       qLGenerator: IQLGenerator,
                       conn: IDataConn,
                       appConfig: Config
                      )(implicit ec: ExecutionContext): ActorRef = {
      import AgentType._
      agentType match {
        case Meta => meta.ref
        case Origin => base.ref
        case View => view.ref
      }
    }

    "load the meta info when preStart" in {
      val mockParserFactory = mock[IQLGeneratorFactory]
      val mockConn = mock[IDataConn]

      val initialInfo = JsArray(Seq(DataSetInfo.write(sourceInfo)))
      val dataManager = system.actorOf(Props(new DataStoreManager(metaDataSet, mockConn, mockParserFactory, Config.Default, testActorMaker)))
      sender.send(dataManager, DataStoreManager.AreYouReady)
      val metaQuery = meta.receiveOne(5 seconds)
      metaQuery.asInstanceOf[Query].dataset must_== metaDataSet
      meta.reply(initialInfo)
      sender.expectMsg(true)
    }
    "answer the meta info" in {
      val mockParserFactory = mock[IQLGeneratorFactory]
      val mockConn = mock[IDataConn]

      val initialInfo = JsArray(Seq(DataSetInfo.write(sourceInfo)))
      val dataManager = system.actorOf(Props(new DataStoreManager(metaDataSet, mockConn, mockParserFactory, Config.Default, testActorMaker)))
      val metaQuery = meta.receiveOne(5 seconds)
      metaQuery.asInstanceOf[Query].dataset must_== metaDataSet
      meta.reply(initialInfo)

      sender.send(dataManager, DataStoreManager.AskInfoAndViews(sourceInfo.name))
      val actual = sender.receiveOne(5 second)
      actual must_== Seq(sourceInfo)

      sender.send(dataManager, DataStoreManager.AskInfoAndViews("nobody"))
      sender.expectMsg(Seq.empty)
    }
    "forward the query to agent" in {
      val mockParserFactory = mock[IQLGeneratorFactory]
      val mockConn = mock[IDataConn]

      val initialInfo = JsArray(Seq(DataSetInfo.write(sourceInfo)))
      val dataManager = system.actorOf(Props(new DataStoreManager(metaDataSet, mockConn, mockParserFactory, Config.Default, testActorMaker)))
      meta.receiveOne(5 seconds)
      meta.reply(initialInfo)

      val query = Query(dataset = sourceInfo.name)
      sender.send(dataManager, query)
      base.expectMsg(query)
      ok
    }
    "update meta info if create view succeeds" in {
      val now = DateTime.now()
      val parser = new AQLGenerator
      val mockParserFactory = mock[IQLGeneratorFactory]
      when(mockParserFactory.apply()).thenReturn(parser)

      val mockConn = mock[IDataConn]
      when(mockConn.postControl(any[String])).thenReturn(Future(true))

      val viewStatJson = JsArray(Seq(Json.obj("min" -> "2015-01-01T00:00:00.000Z", "max" -> "2016-01-01T00:00:00.000Z", "count" -> 2000)))
      when(mockConn.postQuery(any[String])).thenReturn(Future(viewStatJson))

      val initialInfo = JsArray(Seq(DataSetInfo.write(sourceInfo)))
      val dataManager = system.actorOf(Props(new DataStoreManager(metaDataSet, mockConn, mockParserFactory, Config.Default, testActorMaker)))
      meta.receiveOne(5 seconds)
      meta.reply(initialInfo)

      sender.send(dataManager, DataStoreManager.AskInfoAndViews(sourceInfo.name))
      sender.expectMsg(Seq(sourceInfo))

      val createView = CreateView("zika", zikaCreateQuery)
      sender.send(dataManager, createView)
      sender.expectNoMsg(500 milli)
      val upsertRecord = meta.receiveOne(5 seconds)
      upsertRecord.asInstanceOf[UpsertRecord].dataset must_== metaDataSet
      sender.send(dataManager, DataStoreManager.AskInfoAndViews(sourceInfo.name))
      val response = sender.receiveOne(2000 milli).asInstanceOf[Seq[DataSetInfo]]
      response.size must_== 2
      response.head must_== sourceInfo
      val viewInfo = response.last
      viewInfo.name must_== createView.dataset
      viewInfo.createQueryOpt must_== Some(createView.query)
      viewInfo.schema must_== sourceInfo.schema
      viewInfo.dataInterval.getStart must_== TimeField.TimeFormat.parseDateTime((viewStatJson \\ "min").head.as[String])
      viewInfo.dataInterval.getEnd must_== TimeField.TimeFormat.parseDateTime((viewStatJson \\ "max").head.as[String])
      viewInfo.stats.cardinality must_== (viewStatJson \\ "count").head.as[Long]
      viewInfo.stats.lastModifyTime.getMillis must be_>=(now.getMillis)
      ok
    }
    "update meta stats if append view succeeds" in {
      val parser = new AQLGenerator
      val mockParserFactory = mock[IQLGeneratorFactory]
      when(mockParserFactory.apply()).thenReturn(parser)

      val now = DateTime.now()
      val mockConn = mock[IDataConn]
      val viewStatJson = JsArray(Seq(Json.obj("min" -> "2015-01-01T00:00:00.000Z", "max" -> "2016-01-01T00:00:00.000Z", "count" -> 2000)))
      when(mockConn.postQuery(any[String])).thenReturn(Future(viewStatJson))

      val initialInfo = Json.toJson(Seq(DataSetInfo.write(sourceInfo), DataSetInfo.write(zikaHalfYearViewInfo))).asInstanceOf[JsArray]
      val dataManager = system.actorOf(Props(new DataStoreManager(metaDataSet, mockConn, mockParserFactory, Config.Default, testActorMaker)))
      meta.receiveOne(3 seconds)
      meta.reply(initialInfo)

      sender.send(dataManager, DataStoreManager.AreYouReady)
      sender.expectMsg(true)
      sender.send(dataManager, DataStoreManager.AskInfoAndViews(sourceInfo.name))
      sender.expectMsg(Seq(sourceInfo, zikaHalfYearViewInfo))

      val appendView = AppendView(zikaHalfYearViewInfo.name, Query(sourceInfo.name))
      sender.send(dataManager, appendView)
      view.expectMsg(appendView)
      view.reply(true)
      sender.expectNoMsg(1 seconds)
      sender.send(dataManager, DataStoreManager.AskInfoAndViews(zikaHalfYearViewInfo.name))
      val newInfo = sender.receiveOne(1 second).asInstanceOf[Seq[DataSetInfo]].head
      newInfo.name must_== zikaHalfYearViewInfo.name
      newInfo.dataInterval.getEnd must_== TimeField.TimeFormat.parseDateTime((viewStatJson \\ "max").head.as[String])
      newInfo.stats.cardinality must_== (viewStatJson \\ "count").head.as[Long]
      newInfo.stats.lastModifyTime.getMillis must be_>=(now.getMillis)
    }
    "update meta info if receive drop request" in {
      ok
    }
    "use existing child to solve the query" in {
      ok
    }
  }

  "Register/Deregister Data model" should {
    val sender = new TestProbe(system)
    val view = new TestProbe(system)
    val base = new TestProbe(system)
    val meta = new TestProbe(system)
    val metaDataSet = "metaDataSet"

    def testActorMaker(agentType: AgentType.Value,
                       context: ActorRefFactory,
                       actorName: String,
                       dbName: String,
                       dbSchema: Schema,
                       qLGenerator: IQLGenerator,
                       conn: IDataConn,
                       appConfig: Config
                      )(implicit ec: ExecutionContext): ActorRef = {
      import AgentType._
      agentType match {
        case Meta => meta.ref
        case Origin => base.ref
        case View => view.ref
      }
    }

    val mockParserFactory = mock[IQLGeneratorFactory]
    val mockConn = mock[IDataConn]
    val initialInfo = JsArray(Seq(DataSetInfo.write(sourceInfo)))
    val dataManager = system.actorOf(Props(new DataStoreManager(metaDataSet, mockConn, mockParserFactory, Config.Default, testActorMaker)))
    meta.receiveOne(1 second)
    meta.reply(initialInfo)
    sender.send(dataManager, DataStoreManager.AreYouReady)
    sender.expectMsg(true)

    val field1 = TimeField("myTime")
    val field2 = TextField("myText")
    val field3 = StringField("myString")
    val field4 = NumberField("myNumber")
    val schema = UnresolvedSchema("testType", Seq(field1, field2), Seq(field3, field4), Seq("myString"), "myTime")
    val registerRequest = Register("test", schema)
    val deregisterRequest = Deregister("test")

    "parse json register/deregister request" in {
      val jsonRegisterRequest = Json.parse(
        """
          |{
          |  "dataset": "test",
          |  "schema": {
          |    "typeName": "testType",
          |    "dimension": [
          |      {"name":"myTime","isOptional":false,"datatype":"Time"},
          |      {"name":"myText","isOptional":false,"datatype":"Text"}
          |    ],
          |    "measurement": [
          |      {"name":"myString","isOptional":false,"datatype":"String"},
          |      {"name":"myNumber","isOptional":false,"datatype":"Number"}
          |    ],
          |    "primaryKey": ["myString"],
          |    "timeField": "myTime"
          |  }
          |}
        """.stripMargin)

      jsonRegisterRequest.validate[Register] match {
        case jsonResult: JsSuccess[Register] => jsonResult.get mustEqual(registerRequest)
        case _ => throw new IllegalArgumentException
      }

      val jsonDeregisterRequest = Json.parse(
        """
          |{
          |   "dataset": "test"
          |}
        """.stripMargin)

      jsonDeregisterRequest.validate[Deregister] match {
        case jsonResult: JsSuccess[Deregister] => jsonResult.get mustEqual(deregisterRequest)
        case _ => throw new IllegalArgumentException
      }
      ok
    }
    "respond success if register a correct data model and registered dataset can be successfully retrieved" in {
      sender.send(dataManager, registerRequest)
      sender.expectMsg(DataManagerResponse(true, "Register Finished: dataset " + registerRequest.dataset + " has successfully registered.\n"))
      sender.send(dataManager, AskInfoAndViews("test"))
      val infos = sender.receiveOne(1 second).asInstanceOf[List[DataSetInfo]]
      infos.map { dataset: DataSetInfo =>
        dataset.name must_==("test")
        val datasetSchema = Schema("testType", Seq(field1, field2), Seq(field3, field4), Seq(field3), field1)
        dataset.schema must_==(datasetSchema)
      }
      ok
    }
    "respond failure if register an existing data model" in {
      sender.send(dataManager, registerRequest)
      sender.expectMsg(DataManagerResponse(false, "Register Denied: dataset " + registerRequest.dataset + " already existed.\n"))
      ok
    }
    "respond failure if register a data model without time field" in {
      val schemaNoTimeField = UnresolvedSchema("typeNoTimeField", Seq(field1, field2), Seq(field3, field4), Seq("myString"), "")
      val registerRequestNoTimeField = Register("TableNoTimeField", schemaNoTimeField)
      sender.send(dataManager, registerRequestNoTimeField)
      sender.expectMsg(DataManagerResponse(false, "Register Denied. Field Parsing Error: " + "Time field is not specified for " + schemaNoTimeField.typeName + ".\n"))
      ok
    }
    "respond failure if register a data model where time field cannot be found in dimensions and measurements" in {
      val schemaFalseTimeField = UnresolvedSchema("typeFalseTimeField", Seq(field1, field2), Seq(field3, field4), Seq("myString"), "falseTimeField")
      val registerRequestFalseTimeField = Register("TableFalseTimeField", schemaFalseTimeField)
      sender.send(dataManager, registerRequestFalseTimeField)
      sender.expectMsg(DataManagerResponse(false, "Register Denied. Field Not Found Error: " + schemaFalseTimeField.timeField + " is not found in dimensions and measurements: not a valid field.\n"))
      ok
    }
    "respond failure if register a data model where time field is not a field type of timeField" in {
      val schemaNotATimeField = UnresolvedSchema("typeNotATimeField", Seq(field1, field2), Seq(field3, field4), Seq("myString"), "myNumber")
      val registerRequestNotATimeField = Register("TableNotATimeField", schemaNotATimeField)
      sender.send(dataManager, registerRequestNotATimeField)
      sender.expectMsg(DataManagerResponse(false, "Register Denied. Field Parsing Error: " + "Time field of " + schemaNotATimeField.typeName + "is not in TimeField format.\n"))
      ok
    }
    "respond success if deregister an existing data model" in {
      sender.send(dataManager, deregisterRequest)
      sender.expectMsg(DataManagerResponse(true, "Deregister Finished: dataset " + deregisterRequest.dataset + " has successfully removed.\n"))
      ok
    }
    "respond failure if deregister a non-existing data model" in {
      val anotherDeregisterRequest = Deregister("anotherTable")
      sender.send(dataManager, anotherDeregisterRequest)
      sender.expectMsg(DataManagerResponse(false, "Deregister Denied: dataset " + anotherDeregisterRequest.dataset + " does not exist in database.\n"))
      ok
    }
  }
}
