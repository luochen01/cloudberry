package controllers

import java.io.{File, FileInputStream}
import javax.inject.{Inject, Singleton}

import actor.RequestRouter
import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, ActorSystem, DeadLetter, OneForOneStrategy, PoisonPill, Props, Status, SupervisorStrategy, Terminated}
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import db.Migration_20160814
import edu.uci.ics.cloudberry.zion.actor.{BerryClient, DataStoreManager}
import edu.uci.ics.cloudberry.zion.actor.DataStoreManager._
import edu.uci.ics.cloudberry.zion.common.Config
import edu.uci.ics.cloudberry.zion.model.impl.{AsterixAQLConn, AsterixSQLPPConn}
import edu.uci.ics.cloudberry.zion.model.impl.{AQLGenerator, JSONParser, QueryPlanner, SQLPPGenerator}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsValue, Json, _}
import play.api.libs.streams.ActorFlow
import play.api.libs.ws.WSClient
import play.api.mvc._
import play.api.{Configuration, Environment}
import play.Logger

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Success, Failure}

@Singleton
class Application @Inject()(val wsClient: WSClient,
                            val configuration: Configuration,
                            val environment: Environment)
                           (implicit val system: ActorSystem,
                            implicit val materializer: Materializer
                           ) extends Controller {

  val config = new Config(configuration)
  val cities = Application.loadCity(environment.getFile(config.USCityDataPath))
  val (asterixConn, qlGenerator) =
    config.AsterixLang match {
      case "aql" => (new AsterixAQLConn(config.AsterixURL, wsClient), AQLGenerator)
      case "sqlpp" => (new AsterixSQLPPConn(config.AsterixURL, wsClient), SQLPPGenerator)
      case _ => throw new IllegalArgumentException(s"unknown asterixdb.lang option:${config.AsterixLang}")
    }

  Await.result(Migration_20160814.migration.up(asterixConn), 10.seconds)

  val manager = system.actorOf(DataStoreManager.props(Migration_20160814.berryMeta, asterixConn, qlGenerator, config))

  Logger.info("I'm initializing")

  val listener = system.actorOf(Props(classOf[Listener], this))
  system.eventStream.subscribe(listener, classOf[DeadLetter])

  def index = Action {
    Ok(views.html.index("Cloudberry"))
  }

  def debug = Action {
    Ok(views.html.debug("Debug"))
  }

  def dashboard = Action {
    Ok(views.html.dashboard("Dashboard"))
  }

  def ws = WebSocket.accept[JsValue, JsValue] { request =>
    ActorFlow.actorRef { out =>
      RequestRouter.props(BerryClient.props(new JSONParser(), manager, new QueryPlanner(), config, out), config)
    }
  }

  def tweet(id: String) = Action.async {
    val url = "https://api.twitter.com/1/statuses/oembed.json?id=" + id
    wsClient.url(url).get().map { response =>
      Ok(response.json)
    }
  }

  def berryQuery = Action(parse.json) { request =>
    implicit val timeout: Timeout = Timeout(config.UserTimeOut)
    val source = Source.single(request.body)

    val flow = Application.actorFlow[JsValue, JsValue]({ out =>
      BerryClient.props(new JSONParser(), manager, new QueryPlanner(), config, out)
    }, BerryClient.Done)
    // ??? do we need to convert it to string ??? will be more clear after we have the use case.
    val toStringFlow = Flow[JsValue].map(js => js.toString() + System.lineSeparator())
    Ok.chunked((source via flow) via toStringFlow)
  }

  def register = Action.async(parse.json) { request =>
    implicit val timeout: Timeout = Timeout(config.UserTimeOut)

    request.body.validate[Register] match {
      case registerRequest: JsSuccess[Register] =>
        val newTable = registerRequest.get
        val receipt = (manager ? newTable).mapTo[DataManagerResponse]

        receipt.map{ r =>
          if(r.isSuccess) Ok(r.message)
          else BadRequest(r.message)
        }.recover{ case e =>
          InternalServerError("Fail to get receipt from dataStore manager. " + e.toString)
        }

      case e: JsError =>
        Future{ BadRequest("Not a valid register Json POST: " + e.toString) }
    }
  }

  def deregister = Action.async(parse.json) { request =>
    implicit val timeout: Timeout = Timeout(config.UserTimeOut)

    request.body.validate[Deregister] match {
      case deregisterRequest: JsSuccess[Deregister] =>
        val dropTable = deregisterRequest.get
        val receipt = (manager ? dropTable).mapTo[DataManagerResponse]

        receipt.map{ r =>
          if(r.isSuccess) Ok(r.message)
          else BadRequest(r.message)
        }.recover{ case e =>
          InternalServerError("Fail to get receipt from dataStore manager. " + e.toString)
        }

      case e: JsError =>
        Future{ BadRequest("Not valid Json POST: " + e.toString) }
    }
  }

  def getCity(neLat: Double, swLat: Double, neLng: Double, swLng: Double) = Action {
    Ok(Application.findCity(neLat, swLat, neLng, swLng, cities))
  }

  class Listener extends Actor with ActorLogging {
    def receive = {
      case d: DeadLetter => Logger.info(d.toString)
    }
  }

}

object Application {
  val Features = "features"
  val Geometry = "geometry"
  val Type = "type"
  val Coordinates = "coordinates"
  val Polygon = "Polygon"
  val MultiPolygon = "MultiPolygon"
  val CentroidLatitude = "centroidLatitude"
  val CentroidLongitude = "centroidLongitude"

  val header = Json.parse("{\"type\": \"FeatureCollection\"}").as[JsObject]

  def loadCity(file: File): List[JsValue] = {
    val stream = new FileInputStream(file)
    val json = Json.parse(stream)
    stream.close()
    val features = (json \ Features).as[List[JsObject]]
    val newValues = features.map { thisValue =>
      (thisValue \ Geometry \ Type).as[String] match {
        case Polygon => {
          val coordinates = (thisValue \ Geometry \ Coordinates).as[JsArray].apply(0).as[List[List[Double]]]
          val (minLong, maxLong, minLat, maxLat) = coordinates.foldLeft(180.0, -180.0, 180.0, -180.0) {
            case (((minLong, maxLong, minLat, maxLat)), e) =>
              (math.min(minLong, e(0)), math.max(maxLong, e(0)), math.min(minLat, e(1)), math.max(minLat, e(1)))
          }
          val thisLong = (minLong + maxLong) / 2
          val thisLat = (minLat + maxLat) / 2
          thisValue + (CentroidLongitude -> Json.toJson(thisLong)) + (CentroidLatitude -> Json.toJson(thisLat))
        }
        case MultiPolygon => {
          val allCoordinates = (thisValue \ Geometry \ Coordinates).as[JsArray]
          val coordinatesBuilder = List.newBuilder[List[Double]]
          for (coordinate <- allCoordinates.value) {
            val rawCoordinate = coordinate.as[JsArray]
            val realCoordinate = rawCoordinate.apply(0).as[List[List[Double]]]
            realCoordinate.map(x => coordinatesBuilder += x)
          }
          val coordinates = coordinatesBuilder.result()
          val (minLong, maxLong, minLat, maxLat) = coordinates.foldLeft(180.0, -180.0, 180.0, -180.0) {
            case (((minLong, maxLong, minLat, maxLat)), e) =>
              (math.min(minLong, e(0)), math.max(maxLong, e(0)), math.min(minLat, e(1)), math.max(minLat, e(1)))
          }
          val thisLong = (minLong + maxLong) / 2
          val thisLat = (minLat + maxLat) / 2
          thisValue + (CentroidLongitude -> Json.toJson(thisLong)) + (CentroidLatitude -> Json.toJson(thisLat))
        }
        case _ => {
          throw new IllegalArgumentException("Unidentified geometry type in city.json");
        }
      }
    }
    newValues.sortWith((x, y) => (x \ CentroidLongitude).as[Double] < (y \ CentroidLongitude).as[Double])
  }

  /** Use binary search twice to find two breakpoints (startIndex and endIndex) to take out all cities whose longitude are in the range,
    * then scan those cities one by one for latitude.
    *
    * @param neLat  Latitude of the NorthEast point of the boundary
    * @param swLat  Latitude of the SouthWest point of the boundary
    * @param neLng  Latitude of the NorthEast point of the boundary
    * @param swLng  Latitude of the SouthWest point of the boundary
    * @param cities List of all cities
    * @return List of cities which centroids is in current boundary
    */
  def findCity(neLat: Double, swLat: Double, neLng: Double, swLng: Double, cities: List[JsValue]): JsValue = {
    val startIndex = binarySearch(cities, 0, cities.size, swLng)
    val endIndex = binarySearch(cities, 0, cities.size, neLng)

    if (startIndex == -1) {
      //no cities found
      Json.toJson(header)
    } else {
      val citiesWithinBoundary = cities.slice(startIndex, endIndex).filter { city =>
        (city \ CentroidLatitude).as[Double] <= neLat && (city \ CentroidLatitude).as[Double] >= swLat.toDouble
      }
      val response = header + (Features -> Json.toJson(citiesWithinBoundary))
      Json.toJson(response)
    }
  }

  /**
    * Use binary search to find the index in cities to insert the target Longitude
    *
    * @param targetLng the target Longitude
    * @return the index
    */
  def binarySearch(cities: List[JsValue], startIndex: Int, endIndex: Int, targetLng: Double): Int = {
    if (startIndex == endIndex) {
      startIndex
    } else {
      val thisIndex = (startIndex + endIndex) / 2
      val thisCity = cities(thisIndex)
      val centroidLongitude = (thisCity \ CentroidLongitude).as[Double]
      if (centroidLongitude > targetLng) {
        binarySearch(cities, startIndex, thisIndex, targetLng)
      } else if (centroidLongitude < targetLng) {
        binarySearch(cities, thisIndex + 1, endIndex, targetLng)
      } else {
        thisIndex
      }
    }
  }

  /**
    * Copy and revised based on [[play.api.libs.streams.ActorFlow.actorRef()]]
    * The difference is that the '''flowActor''' will not kill the proped actor when it receives the [[Status.Success]]
    * ,because our actor still need to generate multiple responses for one request.
    * To finish the stream, the working actor (generated by the `props` function) needs to provide the `onCompleteMessage`
    * to let the `deleteActor` know that the processing has finished.
    * Then it will finish the stream by sending the [[Status.Success]] to the downstream actor.
    */
  def actorFlow[In, Out](props: ActorRef => Props, onCompleteMessage: Any, bufferSize: Int = 16, overflowStrategy: OverflowStrategy = OverflowStrategy.dropNew)
                        (implicit factory: ActorRefFactory, mat: Materializer): Flow[In, Out, _] = {

    // The stream can be completed successfully by sending [[akka.actor.PoisonPill]]
    // or [[akka.actor.Status.Success]] to the outActor.
    val (outActor, publisher) = Source.actorRef[Out](bufferSize, overflowStrategy)
      .toMat(Sink.asPublisher(false))(Keep.both).run()

    Flow.fromSinkAndSource(
      Sink.actorRef(factory.actorOf(Props(new Actor {

        /**
          * Delegate actor to wrap the `onCompleteMessage` with [[Status.Success]] in order to finish the stream
          */
        val delegateActor: ActorRef = context.watch(context.actorOf(Props(new Actor {
          override def receive: Receive = {
            case `onCompleteMessage` => outActor ! Status.Success(())
            case Terminated(_) =>
              Logger.info("Child terminated, stopping")
              context.stop(self)
            case other => outActor ! other
          }
        }), "delegateActor"))

        val flowActor: ActorRef = context.watch(context.actorOf(props(delegateActor), "flowActor"))
        context.watch(outActor)

        def receive: Receive = {
          case Status.Failure(error) =>
            Logger.error("flowActor receive status.failure" + error)
            flowActor ! PoisonPill
            delegateActor ! PoisonPill
          case Terminated(_) =>
            Logger.info("Child terminated, stopping")
            context.stop(self)
          case other => flowActor ! other
        }

        override def supervisorStrategy = OneForOneStrategy() {
          case _ =>
            Logger.error("Stopping actor due to exception")
            SupervisorStrategy.Stop
        }
      })), akka.actor.Status.Success(())),
      Source.fromPublisher(publisher)
    )
  }
}
